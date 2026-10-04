import { test } from "node:test";
import assert from "node:assert/strict";
import { hashEmail, monthKey, toUsdCents } from "../src/access.js";
import { hashEmail as scriptHash } from "../scripts/import-kofi-csv.mjs";

// The same vectors are in web/worker/test/crypto.test.js: the tip jar, the import script and
// the website must hash an email identically, or supporters can't sign in.
const PLAIN = "0f4225ea8ff6902c024297ea42f683109a2f5d1d2e0946690a095ce3ee68ff37";
const PEPPERED = "82f2e5dc9b8f0f6d69290575810eda34b58a779e96eaa29a554050b8024d09df";

test("hashEmail trims and lowercases, then SHA-256; with a pepper, HMAC-SHA256", async () => {
    assert.equal(await hashEmail("  Supporter@Example.com "), PLAIN);
    assert.equal(await hashEmail("supporter@example.com", ""), PLAIN);
    assert.equal(await hashEmail("SUPPORTER@example.com", "test-pepper"), PEPPERED);
});

test("the import script hashes exactly like the Worker", () => {
    assert.equal(scriptHash(" Supporter@Example.COM"), PLAIN);
    assert.equal(scriptHash("supporter@example.com", "test-pepper"), PEPPERED);
});

test("toUsdCents: USD as is, others by the table, junk is null", () => {
    assert.equal(toUsdCents("3.00", "USD"), 300);
    assert.equal(toUsdCents("3", undefined), 300);
    assert.equal(toUsdCents("2.50", "gbp"), 318);
    assert.equal(toUsdCents("1000", "JPY"), 670);
    assert.equal(toUsdCents("5", "XYZ"), null);
    assert.equal(toUsdCents("abc", "USD"), null);
    assert.equal(toUsdCents("-5", "USD"), null);
    assert.equal(toUsdCents("0", "USD"), null);
});

test("monthKey is the UTC month", () => {
    assert.equal(monthKey(new Date("2026-10-01T00:00:00Z")), "2026-10");
    assert.equal(monthKey(new Date("2026-09-30T23:59:59Z")), "2026-09");
});
