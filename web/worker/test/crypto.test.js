import { test } from "node:test";
import assert from "node:assert/strict";
import { b64urlDecode, b64urlEncode, hashEmail, normalizeEmail, sameHex, sixDigitCode, validEmail } from "../crypto.js";

// The same vectors are in infra/tipjar-worker/test/access.test.js: the tip jar, its CSV import and
// this site must hash an email identically, or supporters can't sign in.
const PLAIN = "0f4225ea8ff6902c024297ea42f683109a2f5d1d2e0946690a095ce3ee68ff37";
const PEPPERED = "82f2e5dc9b8f0f6d69290575810eda34b58a779e96eaa29a554050b8024d09df";

test("hashEmail matches the tip jar: trim, lowercase, SHA-256; HMAC-SHA256 with a pepper", async () => {
    assert.equal(await hashEmail("  Supporter@Example.com "), PLAIN);
    assert.equal(await hashEmail("supporter@example.com", undefined), PLAIN);
    assert.equal(await hashEmail("SUPPORTER@EXAMPLE.COM", "test-pepper"), PEPPERED);
});

test("validEmail is loose but refuses junk", () => {
    for (const ok of ["a@b.co", "first.last+tag@sub.example.org", normalizeEmail("  X@Y.IO "), "o'brien@example.com"]) assert.ok(validEmail(ok), ok);
    for (const bad of ["", "a", "a@b", "@b.co", "a@@b.co", "a b@c.co", "a@b.co, c@d.co", `${"x".repeat(250)}@b.co`, "<a@b.co>", 'a"b@c.co', "a\nb@c.co", "a\u0000b@c.co", "a\u007fb@c.co", "a\tb@c.co"]) assert.ok(!validEmail(bad), JSON.stringify(bad));
});

test("sameHex compares equal-length hex only", () => {
    assert.ok(sameHex("abc123", "abc123"));
    assert.ok(!sameHex("abc123", "abc124"));
    assert.ok(!sameHex("abc", "abcd"));
    assert.ok(!sameHex(undefined, "abc"));
});

test("base64url round-trips and rejects other alphabets", () => {
    const bytes = Uint8Array.from([0, 250, 251, 252, 253, 254, 255, 62, 63]);
    assert.deepEqual(b64urlDecode(b64urlEncode(bytes)), bytes);
    assert.equal(b64urlEncode("{}"), "e30");
    assert.throws(() => b64urlDecode("a+b/"));
});

test("sixDigitCode is six digits, leading zeros kept, and spread out", () => {
    const seen = new Set();
    for (let i = 0; i < 2000; i++) {
        const c = sixDigitCode();
        assert.match(c, /^\d{6}$/);
        seen.add(c);
    }
    assert.ok(seen.size > 1990, "codes repeat far too often");
});
