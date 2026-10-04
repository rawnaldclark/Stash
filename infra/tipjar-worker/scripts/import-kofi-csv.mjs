#!/usr/bin/env node
/**
 * Gives past Ko-fi supporters early access to stashfm.app.
 *
 * The webhook only sees donations from now on. This reads a Ko-fi CSV export and writes a
 * file for `wrangler kv bulk put`: one access:<hash> entry per supporter email, the same
 * entry the webhook writes (src/access.js). It never prints an email, and the file it writes
 * holds only hashes.
 *
 *   node scripts/import-kofi-csv.mjs <ko-fi-export.csv> [--out <file.json>] [--all-types]
 *   npx wrangler kv bulk put <file.json> --binding ACCESS_KV --remote
 *
 * Set the EMAIL_PEPPER environment variable to the Workers' EMAIL_PEPPER secret (the website requires
 * one); without it the script refuses to run, since entries hashed without the pepper would never match.
 *
 * The file goes in a new folder of its own in your temp folder, readable only by you (or wherever
 * --out says). It is never written over an existing file.
 *
 * Rows count when their type column says Donation, Subscription, Membership or Tip (shop
 * orders and commissions don't), or every row with an email when the export has no type
 * column or you pass --all-types. Column names are matched loosely, since Ko-fi's export has
 * changed over time: any header with "email" in it, "type" or "transaction type", and a
 * date or time column if there is one.
 *
 * Safe to re-run: each supporter's entry is rewritten with the same hash.
 */
import { createHash, createHmac } from "node:crypto";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const HASHCHECK_EMAIL = "hashcheck@stashfm.app"; // src/access.js
const SUPPORT_TYPES = /donation|subscription|membership|tip|coffee/i;

export const normalizeEmail = (email) => String(email ?? "").trim().toLowerCase();

/** The same hash as src/access.js hashEmail and web/worker/crypto.js. */
export function hashEmail(email, pepper) {
    const data = normalizeEmail(email);
    return pepper ? createHmac("sha256", pepper).update(data).digest("hex") : createHash("sha256").update(data).digest("hex");
}

/** RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks. Returns rows of strings. */
export function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = "";
    let quoted = false;
    const src = text.replace(/^﻿/, "");
    for (let i = 0; i < src.length; i++) {
        const ch = src[i];
        if (quoted) {
            if (ch === '"' && src[i + 1] === '"') { field += '"'; i++; }
            else if (ch === '"') quoted = false;
            else field += ch;
        } else if (ch === '"') quoted = true;
        else if (ch === ",") { row.push(field); field = ""; }
        else if (ch === "\n" || ch === "\r") {
            if (ch === "\r" && src[i + 1] === "\n") i++;
            row.push(field); field = "";
            if (row.some((f) => f !== "")) rows.push(row);
            row = [];
        } else field += ch;
    }
    row.push(field);
    if (row.some((f) => f !== "")) rows.push(row);
    return rows;
}

/** A CSV date. Ko-fi's "2025-01-03 10:00" style has no zone but is UTC, so it's read as UTC. */
export function parseDate(value) {
    const s = String(value ?? "").trim();
    const bare = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)$/.exec(s);
    return new Date(bare ? `${bare[1]}T${bare[2]}Z` : s);
}

/** Finds the columns this needs in a header row. */
export function findColumns(header) {
    const names = header.map((h) => h.trim().toLowerCase());
    const pick = (...tests) => {
        for (const test of tests) {
            const i = names.findIndex(test);
            if (i !== -1) return i;
        }
        return -1;
    };
    return {
        email: pick((n) => n === "email", (n) => n.includes("email")),
        type: pick((n) => n === "type", (n) => n === "transaction type" || n === "transactiontype", (n) => /\btype\b/.test(n)),
        date: pick((n) => n.startsWith("datetime"), (n) => n.includes("date") || n.includes("timestamp") || n.includes("time")),
    };
}

/**
 * The bulk file's entries, plus counts for the summary. Each supporter gets
 * { source: "kofi", firstAt, lastAt } from their earliest and latest rows (or now, with no
 * date column), and the file also carries meta:hashcheck (see src/access.js).
 */
export function buildBulk(rows, { pepper, allTypes = false, now = new Date() } = {}) {
    const [header, ...data] = rows;
    if (!header) throw new Error("The CSV is empty.");
    const col = findColumns(header);
    // Only the count: with no header row, the first row is a supporter's data, maybe their email.
    if (col.email === -1) throw new Error(`No email column among the ${header.length} columns of the first row. Is the first row a header? Ko-fi's export has one with an "Email" column.`);
    const filterByType = !allTypes && col.type !== -1;
    const seen = new Map();
    const counts = { rows: data.length, used: 0, noEmail: 0, otherType: 0 };
    for (const row of data) {
        const email = normalizeEmail(row[col.email]);
        if (!email.includes("@")) { counts.noEmail++; continue; }
        if (filterByType && !SUPPORT_TYPES.test(row[col.type] ?? "")) { counts.otherType++; continue; }
        counts.used++;
        const when = col.date === -1 ? now : parseDate(row[col.date]);
        const at = Number.isNaN(when.getTime()) ? now.toISOString() : when.toISOString();
        const hash = hashEmail(email, pepper);
        const prev = seen.get(hash);
        seen.set(hash, prev ? { firstAt: at < prev.firstAt ? at : prev.firstAt, lastAt: at > prev.lastAt ? at : prev.lastAt } : { firstAt: at, lastAt: at });
    }
    const entries = [...seen].map(([hash, { firstAt, lastAt }]) => ({
        key: `access:${hash}`,
        value: JSON.stringify({ source: "kofi", firstAt, lastAt }),
    }));
    entries.push({ key: "meta:hashcheck", value: hashEmail(HASHCHECK_EMAIL, pepper) });
    return { entries, counts: { ...counts, supporters: seen.size }, filteredByType: filterByType, columns: col };
}

function main(argv) {
    const args = argv.slice(2);
    let csvPath;
    let outArg;
    let allTypes = false;
    for (let i = 0; i < args.length; i++) {
        if (args[i] === "--out") outArg = args[++i];
        else if (args[i] === "--all-types") allTypes = true;
        else if (!args[i].startsWith("--")) csvPath ??= args[i];
    }
    if (!csvPath) {
        console.error("Usage: node scripts/import-kofi-csv.mjs <ko-fi-export.csv> [--out <file.json>] [--all-types]");
        process.exit(2);
    }
    const out = resolve(outArg || join(mkdtempSync(join(tmpdir(), "stash-access-")), "stash-access-bulk.json"));
    const pepper = process.env.EMAIL_PEPPER || "";
    if (!pepper) {
        console.error("Set EMAIL_PEPPER to the Workers' value first. The website requires a pepper, so entries hashed without one would never match.");
        process.exit(2);
    }
    const { entries, counts, filteredByType } = buildBulk(parseCsv(readFileSync(csvPath, "utf8")), { pepper, allTypes });
    try {
        writeFileSync(out, JSON.stringify(entries, null, 1), { flag: "wx", mode: 0o600 });
    } catch (err) {
        if (err.code !== "EEXIST") throw err;
        console.error(`${out} already exists. Delete it, or pass another --out.`);
        process.exit(1);
    }
    console.log(`Rows read:            ${counts.rows}`);
    console.log(`Rows used:            ${counts.used}`);
    console.log(`Skipped, no email:    ${counts.noEmail}`);
    console.log(`Skipped, other type:  ${filteredByType ? counts.otherType : "n/a (no type filter)"}`);
    console.log(`Supporters (unique):  ${counts.supporters}`);
    console.log("Hashing:              HMAC-SHA256 with EMAIL_PEPPER");
    console.log(`Wrote ${entries.length} entries to ${out}`);
    console.log("");
    console.log("Next, from infra/tipjar-worker:");
    console.log(`  npx wrangler kv bulk put "${out}" --binding ACCESS_KV --remote`);
    console.log("Then delete that file and the CSV export.");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main(process.argv);
