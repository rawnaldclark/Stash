/**
 * The owner's Community moderation (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2):
 *
 *   npm run community -- list [n]                 newest and top posts, with hidden/removed flags
 *   npm run community -- remove <postId>          take a post down
 *   npm run community -- restore <postId>         undo a removal or a vote attack (drops its downvotes)
 *   npm run community -- block <postId>           block that post's phone and remove all its live posts
 *   npm run community -- unblock <posterIdPrefix> undo a block (list shows the first 8 characters)
 *
 * Runs on the live database through `wrangler d1 execute` with your own Cloudflare login. `d1 execute` takes
 * no bind parameters, so every argument is checked against a strict pattern before it goes into the SQL.
 */
import { execSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";
import { RECOUNT_SQL } from "../src/community.js";

const POST_ID = /^[A-Za-z0-9]{8}$/;
const PREFIX = /^[0-9a-f]{8,64}$/;
const USAGE = "usage: npm run community -- list [n] | remove <postId> | restore <postId> | block <postId> | unblock <posterIdPrefix>";

function postId(arg) {
    if (!POST_ID.test(arg ?? "")) throw new Error("expected a post id: 8 letters or digits");
    return arg;
}

/** The SQL for one command; statements are joined with ";\n". */
export function commandSql(command, arg, now = Date.now()) {
    switch (command) {
        case "list": {
            const n = Number.parseInt(arg ?? "20", 10);
            if (!(n >= 1 && n <= 200)) throw new Error("list takes a count from 1 to 200");
            const cols = `id, kind, title, poster_name, up, down, (up - down) <= -3 AS hidden, removed_at IS NOT NULL AS removed,
                ROUND((${now} - created_at) / 3600000.0, 1) AS age_h, substr(poster, 1, 8) AS poster`;
            return [`SELECT ${cols} FROM posts ORDER BY created_at DESC LIMIT ${n}`,
                `SELECT ${cols} FROM posts WHERE removed_at IS NULL ORDER BY (up - down) DESC LIMIT ${n}`].join(";\n");
        }
        case "remove":
            return `UPDATE posts SET removed_at = ${now} WHERE id = '${postId(arg)}'`;
        case "restore": {
            const id = postId(arg);
            return [`UPDATE posts SET removed_at = NULL WHERE id = '${id}'`,
                `DELETE FROM votes WHERE post_id = '${id}' AND value = -1`,
                RECOUNT_SQL.replaceAll("?1", `'${id}'`)].join(";\n");
        }
        case "block": {
            const id = postId(arg);
            return [`INSERT OR IGNORE INTO blocked (poster, at, note) SELECT poster, ${now}, 'post ${id}' FROM posts WHERE id = '${id}'`,
                `UPDATE posts SET removed_at = ${now} WHERE removed_at IS NULL AND poster = (SELECT poster FROM posts WHERE id = '${id}')`].join(";\n");
        }
        case "unblock": {
            if (!PREFIX.test(arg ?? "")) throw new Error("unblock takes the start of a poster id: 8 to 64 hex characters");
            // substr, not LIKE: D1 caps LIKE patterns at 50 bytes.
            return `DELETE FROM blocked WHERE substr(poster, 1, ${arg.length}) = '${arg}'`;
        }
        default:
            throw new Error(USAGE);
    }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
    const [command, arg] = process.argv.slice(2);
    try {
        const sql = commandSql(command, arg).replace(/\s+/g, " ");
        execSync(`npx wrangler d1 execute stash-community --remote --json --command ${JSON.stringify(sql)}`, {
            stdio: "inherit",
            cwd: dirname(dirname(fileURLToPath(import.meta.url))),
        });
    } catch (e) {
        console.error(e.message);
        process.exit(1);
    }
}
