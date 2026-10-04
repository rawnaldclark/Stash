import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { buildBulk, findColumns, hashEmail, parseCsv } from "../scripts/import-kofi-csv.mjs";

const CSV = [
    "DateTime (UTC),From,Message,Item,Received,Given,Currency,TransactionType,TransactionId,Email",
    '2025-01-03 10:00,Cedric,"Just downloaded, love it",,10.00,,USD,Donation,t1,Cedric@Example.com',
    '2025-03-01 09:00,Cedric,"Again ""thanks""",,5.00,,USD,Donation,t2, cedric@example.com ',
    "2025-02-01 12:00,Shopper,,Sticker,4.00,,USD,Shop Order,t3,shop@example.com",
    '2025-04-01 12:00,Mia,"Two\nlines",,3.00,,EUR,Subscription,t4,mia@example.com',
    "2025-05-01 12:00,NoMail,,,3.00,,USD,Donation,t5,",
].join("\r\n");

test("parseCsv handles quotes, doubled quotes, commas and line breaks inside fields, CRLF and a BOM", () => {
    const rows = parseCsv("﻿" + CSV);
    assert.equal(rows.length, 6);
    assert.equal(rows[1][2], "Just downloaded, love it");
    assert.equal(rows[2][2], 'Again "thanks"');
    assert.equal(rows[4][2], "Two\nlines");
    assert.equal(rows[0][0], "DateTime (UTC)");
});

test("findColumns matches Ko-fi's headers loosely", () => {
    assert.deepEqual(findColumns(["DateTime (UTC)", "From", "TransactionType", "Email"]), { email: 3, type: 2, date: 0 });
    assert.deepEqual(findColumns(["Timestamp", "Type", "Buyer Email"]), { email: 2, type: 1, date: 0 });
    assert.equal(findColumns(["From", "Amount"]).email, -1);
});

test("buildBulk: one entry per supporter (donations and subscriptions only), first and last dates, plus the hash check", () => {
    const { entries, counts } = buildBulk(parseCsv(CSV));
    assert.deepEqual(counts, { rows: 5, used: 3, noEmail: 1, otherType: 1, supporters: 2 });
    const byKey = new Map(entries.map((e) => [e.key, e.value]));
    const cedric = JSON.parse(byKey.get(`access:${hashEmail("cedric@example.com")}`));
    assert.equal(cedric.source, "kofi");
    assert.equal(cedric.firstAt, "2025-01-03T10:00:00.000Z"); // Ko-fi's zone-less dates are UTC
    assert.equal(cedric.lastAt, "2025-03-01T09:00:00.000Z");
    assert.ok(byKey.has(`access:${hashEmail("mia@example.com")}`));
    assert.ok(!byKey.has(`access:${hashEmail("shop@example.com")}`));
    assert.equal(byKey.get("meta:hashcheck"), hashEmail("hashcheck@stashfm.app"));
    for (const e of entries) assert.equal(typeof e.value, "string"); // wrangler kv bulk put wants string values
});

test("--all-types includes every row with an email; no email column is an error that lists the columns", () => {
    assert.equal(buildBulk(parseCsv(CSV), { allTypes: true }).counts.supporters, 3);
    assert.throws(() => buildBulk(parseCsv("From,Amount\nA,1")), /No email column/);
});

test("with no header row, the error never prints the first row (it can be an email)", () => {
    assert.throws(
        () => buildBulk(parseCsv("2025-01-03,Jo,secret.person@example.com,5.00\n")),
        (err) => /No email column/.test(err.message) && !err.message.includes("secret.person") && !err.message.includes("Jo"),
    );
});

test("the pepper changes every hash", () => {
    const plain = buildBulk(parseCsv(CSV)).entries.map((e) => e.key);
    const peppered = buildBulk(parseCsv(CSV), { pepper: "test-pepper" }).entries.map((e) => e.key);
    assert.ok(peppered.includes(`access:${hashEmail("mia@example.com", "test-pepper")}`));
    assert.equal(plain.filter((k) => peppered.includes(k) && k !== "meta:hashcheck").length, 0);
});

test("by default the CLI writes to a new private temp folder, and never over an existing file", () => {
    const dir = mkdtempSync(join(tmpdir(), "stash-import-"));
    const csv = join(dir, "export.csv");
    writeFileSync(csv, CSV);
    const printed = execFileSync(process.execPath, ["scripts/import-kofi-csv.mjs", csv], { encoding: "utf8", env: { ...process.env, EMAIL_PEPPER: "test-pepper" } });
    const out = /Wrote \d+ entries to (.+)$/m.exec(printed)[1].trim();
    assert.ok(existsSync(out));
    assert.notEqual(dirname(out), tmpdir(), "a folder of its own, not the shared temp folder");
    if (process.platform !== "win32") assert.equal(statSync(out).mode & 0o777, 0o600);
    assert.match(printed, /--binding ACCESS_KV --remote/);
    // --out to a file that exists is refused rather than overwritten.
    assert.throws(() => execFileSync(process.execPath, ["scripts/import-kofi-csv.mjs", csv, "--out", out], { encoding: "utf8", stdio: "pipe", env: { ...process.env, EMAIL_PEPPER: "test-pepper" } }), /already exists/);
});

test("the CLI refuses to run without EMAIL_PEPPER (the website requires it, so plain hashes would never match)", () => {
    const dir = mkdtempSync(join(tmpdir(), "stash-import-"));
    const csv = join(dir, "export.csv");
    const out = join(dir, "bulk.json");
    writeFileSync(csv, CSV);
    assert.throws(
        () => execFileSync(process.execPath, ["scripts/import-kofi-csv.mjs", csv, "--out", out], { encoding: "utf8", stdio: "pipe", env: { ...process.env, EMAIL_PEPPER: "" } }),
        /EMAIL_PEPPER/,
    );
    assert.ok(!existsSync(out));
});

test("the CLI writes the bulk file and never prints an email", () => {
    const dir = mkdtempSync(join(tmpdir(), "stash-import-"));
    const csv = join(dir, "export.csv");
    const out = join(dir, "bulk.json");
    writeFileSync(csv, CSV);
    const printed = execFileSync(process.execPath, ["scripts/import-kofi-csv.mjs", csv, "--out", out], { encoding: "utf8", env: { ...process.env, EMAIL_PEPPER: "test-pepper" } });
    assert.match(printed, /Supporters \(unique\): {2}2/);
    assert.ok(!/@/.test(printed.replace(/hashcheck@stashfm\.app/g, "")), "an email was printed");
    const written = readFileSync(out, "utf8");
    assert.ok(!/example\.com/i.test(written), "the bulk file holds an email");
    assert.equal(JSON.parse(written).length, 3);
});
