/**
 * The owner's Community moderation (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2):
 *
 *   npm run community -- list [n]                 newest and top posts, with hidden/vouched/removed flags, and blocked phones
 *   npm run community -- remove <postId>          take a post down
 *   npm run community -- restore <postId>         undo your removal or a vote attack (drops its downvotes and
 *                                                 vouches for it: votes can't hide it again)
 *   npm run community -- block <postId>           block that post's phone and remove all its live posts
 *   npm run community -- unblock <posterIdPrefix> undo a block (list shows the first 8 characters)
 *   npm run community -- drop-unheard-downvotes   once, when HEARD_REQUIRED goes on: delete every downvote whose voter
 *                                                 has no heard mark on that post, and recount every post (upvotes stay)
 *
 * Every command prints what it hit: an empty result means it matched nothing. For `block` and `restore`, read the
 * last result: the blocked row, or the post's votes, `removed` and `vouched`. Block's INSERT and restore's first
 * three statements have no RETURNING, so their results are always empty. `removed` says who took a post down
 * ('owner' or 'poster', or null while it's up), and `restore` never undoes a poster's own take-down: 'poster' there
 * means restore left it down. `unblock` leaves the phone's posts removed: `restore` brings back any of them within
 * a day, before the daily cleanup deletes them.
 *
 * Runs on the live database through `wrangler d1 execute` with your own Cloudflare login. `d1 execute` takes
 * no bind parameters, so every argument is checked against a strict pattern before it goes into the SQL.
 */
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";
import { HIDE_AT, RECOUNT_SQL, VOTES_PER_NETWORK } from "../src/community.js";

const POST_ID = /^[A-Za-z0-9]{8}$/;
const PREFIX = /^[0-9a-f]{8,64}$/;
const COUNT = /^\d{1,3}$/;
const USAGE = "usage: npm run community -- list [n] | remove <postId> | restore <postId> | block <postId> | unblock <posterIdPrefix> | drop-unheard-downvotes";

function postId(arg) {
    if (!POST_ID.test(arg ?? "")) throw new Error("expected a post id: 8 letters or digits");
    return arg;
}

/**
 * The SQL for one command; statements are joined with ";\n". The CLI passes all its arguments, so a second
 * argument lands in [now] and is refused there.
 */
export function commandSql(command, arg, now = Date.now()) {
    if (!Number.isSafeInteger(now)) throw new Error(USAGE);
    switch (command) {
        case "list": {
            const count = arg ?? "20";
            const n = COUNT.test(count) ? Number.parseInt(count, 10) : 0;
            if (!(n >= 1 && n <= 200)) throw new Error("list takes a count from 1 to 200");
            const cols = `id, kind, title, poster_name, up, down, ((up - down) <= ${HIDE_AT} AND vouched = 0) AS hidden, vouched, removed_by AS removed,
                ROUND((${now} - created_at) / 3600000.0, 1) AS age_h, substr(poster, 1, 8) AS poster`;
            return [`SELECT ${cols} FROM posts ORDER BY created_at DESC LIMIT ${n}`,
                `SELECT ${cols} FROM posts WHERE removed_at IS NULL ORDER BY (up - down) DESC LIMIT ${n}`,
                `SELECT substr(poster, 1, 8) AS poster, at, note FROM blocked ORDER BY at DESC LIMIT ${n}`].join(";\n");
        }
        case "remove":
            // Only a live post: relabelling a poster's take-down 'owner' would let restore bring it back.
            return `UPDATE posts SET removed_at = ${now}, removed_by = 'owner' WHERE id = '${postId(arg)}' AND removed_at IS NULL RETURNING id, title, poster_name`;
        case "restore": {
            const id = postId(arg);
            // The downvotes go, and the post is vouched for, only if it's up after the first UPDATE: a removal just
            // undone, or a post hidden by votes. A poster's own take-down stays down, unvouched.
            return [`UPDATE posts SET removed_at = NULL, removed_by = NULL WHERE id = '${id}' AND removed_by = 'owner'`,
                `DELETE FROM votes WHERE post_id = '${id}' AND value = -1 AND (SELECT removed_at FROM posts WHERE id = '${id}') IS NULL`,
                `UPDATE posts SET vouched = 1 WHERE id = '${id}' AND removed_at IS NULL`,
                RECOUNT_SQL.replaceAll("?1", `'${id}'`),
                `SELECT id, up, down, removed_by AS removed, vouched FROM posts WHERE id = '${id}'`].join(";\n");
        }
        case "block": {
            const id = postId(arg);
            return [`INSERT OR IGNORE INTO blocked (poster, at, note) SELECT poster, ${now}, 'post ${id}' FROM posts WHERE id = '${id}'`,
                `UPDATE posts SET removed_at = ${now}, removed_by = 'owner' WHERE removed_at IS NULL AND poster = (SELECT poster FROM posts WHERE id = '${id}') RETURNING id, title`,
                `SELECT substr(poster, 1, 8) AS poster, at, note FROM blocked WHERE poster = (SELECT poster FROM posts WHERE id = '${id}')`].join(";\n");
        }
        case "unblock": {
            if (!PREFIX.test(arg ?? "")) throw new Error("unblock takes the start of a poster id: 8 to 64 hex characters");
            // substr, not LIKE: D1 caps LIKE patterns at 50 bytes.
            return `DELETE FROM blocked WHERE substr(poster, 1, ${arg.length}) = '${arg}' RETURNING substr(poster, 1, 8) AS poster, note`;
        }
        case "drop-unheard-downvotes": {
            if (arg !== undefined) throw new Error("drop-unheard-downvotes takes no arguments");
            // Then every post is recounted by RECOUNT_SQL's rule (per network, at most VOTES_PER_NETWORK each way), so up
            // stays as it was and down is what's left. The first result counts what goes; the last shows what's left.
            const unheard = "value = -1 AND NOT EXISTS (SELECT 1 FROM heard h WHERE h.post_id = votes.post_id AND h.voter = votes.voter)";
            const counted = (value) => `(SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT post_id, COUNT(*) AS n FROM votes WHERE value = ${value} GROUP BY post_id, ip_hash) c WHERE c.post_id = posts.id)`;
            return [`SELECT COUNT(*) AS dropping, COUNT(DISTINCT voter) AS voters, COUNT(DISTINCT post_id) AS posts FROM votes WHERE ${unheard}`,
                `DELETE FROM votes WHERE ${unheard}`,
                `UPDATE posts SET up = ${counted(1)}, down = ${counted(-1)}`,
                `SELECT COUNT(*) AS downvotes_left, (SELECT COUNT(*) FROM posts WHERE removed_at IS NULL AND (up - down) <= 0) AS posts_at_or_below_0 FROM votes WHERE value = -1`].join(";\n");
        }
        default:
            throw new Error(USAGE);
    }
}

if (import.meta.main) {
    try {
        const sql = commandSql(...process.argv.slice(2));
        // It's collapsed onto one quoted line: a -- comment would swallow the rest, " would end the quotes, and cmd.exe expands %.
        if (/--|["%]/.test(sql)) throw new Error("the SQL must fit one quoted --command line: no --, \" or %");
        execSync(`npx wrangler d1 execute stash-community --remote --json --command ${JSON.stringify(sql.replace(/\s+/g, " "))}`, {
            stdio: "inherit",
            cwd: dirname(dirname(fileURLToPath(import.meta.url))),
        });
    } catch (e) {
        console.error(e.message);
        process.exit(1);
    }
}
