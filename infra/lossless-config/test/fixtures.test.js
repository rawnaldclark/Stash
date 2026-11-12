// Proves the committed add-on index fixtures (test/fixtures, made by scripts/make-index-fixtures.mjs) are what
// cases.json and their README say. The app's reader is tested against the same files, so a claim that is wrong here
// would be wrong there too.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createPublicKey } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { buildIndex, readIndex, isCanonical, isP256, verifyIndexSignature, INDEX_TYPE, MAX_ADDONS, MAX_BLOCKED, MAX_INDEX_BYTES, MAX_NAME, MAX_SUMMARY, BLOCK_REASONS, UNKNOWN_REASON } from "../src/index.js";

const DIR = join(dirname(fileURLToPath(import.meta.url)), "fixtures");
const file = (...p) => readFileSync(join(DIR, ...p));
const { public_key: keyFile, cases } = JSON.parse(file("cases.json").toString("utf8"));
const PUBKEY = file(keyFile).toString("utf8");
const byDir = Object.fromEntries(cases.map((c) => [c.dir, c]));
const bytesOf = (dir) => file(dir, "index.json");
const sigOf = (dir) => file(dir, "index.json.sig").toString("utf8");

test("the fixture key is a P-256 public key in the form the app's verifier takes (base64 SPKI DER, one line)", () => {
    assert.match(PUBKEY, /^[A-Za-z0-9+/]+={0,2}$/, "exactly the base64 text: no line break, no PEM armour");
    const key = createPublicKey({ key: Buffer.from(PUBKEY, "base64"), type: "spki", format: "der" });
    assert.equal(isP256(key), true);
    assert.equal(key.type, "public");
});

test("the folder holds exactly the listed cases, each an index.json and an index.json.sig, and no private key", () => {
    const dirs = readdirSync(DIR).filter((n) => statSync(join(DIR, n)).isDirectory()).sort();
    assert.deepEqual(dirs, cases.map((c) => c.dir).sort());
    assert.deepEqual(readdirSync(DIR).filter((n) => !dirs.includes(n)).sort(), [".gitattributes", "README.md", "cases.json", "pubkey.b64"]);
    for (const d of dirs) {
        assert.deepEqual(readdirSync(join(DIR, d)).sort(), ["index.json", "index.json.sig"], d);
        assert.equal(bytesOf(d).length, byDir[d].bytes, `${d}: the byte count in cases.json`);
        // What the publisher writes into a .sig: base64 of a DER signature, one line, no line break at all.
        assert.match(sigOf(d), /^[A-Za-z0-9+/]+={0,2}$/, `${d}: the .sig`);
    }
    const everything = [file("cases.json"), file("README.md"), file(keyFile), ...dirs.flatMap((d) => [bytesOf(d), file(d, "index.json.sig")])];
    for (const buf of everything) {
        assert.equal(buf.includes("PRIVATE KEY"), false);
        assert.equal(buf.includes("\r"), false, "no carriage return: the files are kept byte for byte (.gitattributes)");
    }
    assert.equal(file(".gitattributes").toString(), "* -text\n");
});

test("every case's signature is as cases.json says, checked the way the device checks it", () => {
    for (const c of cases) {
        assert.equal(verifyIndexSignature(bytesOf(c.dir), sigOf(c.dir), PUBKEY), c.signature === "valid", c.dir);
    }
    assert.deepEqual(cases.filter((c) => c.signature !== "valid").map((c) => c.dir).sort(), ["refuse-tampered", "refuse-wrong-signer"]);
});

test("accept cases: read as an index, with the counts and updated_at cases.json gives", () => {
    const accept = cases.filter((c) => c.expect === "accept");
    assert.deepEqual(accept.map((c) => c.dir).sort(), ["valid", "valid-launch", "valid-older", "valid-unknown-fields", "valid-unknown-reason"]);
    for (const c of accept) {
        assert.equal(c.signature, "valid", c.dir);
        const index = readIndex(bytesOf(c.dir));
        assert.equal(index.type, INDEX_TYPE);
        assert.equal(index.updated_at, c.updated_at, c.dir);
        assert.equal(index.addons.length, c.addons, c.dir);
        assert.equal(index.blocked.length, c.blocked, c.dir);
        assert.equal(isCanonical(bytesOf(c.dir)), c.canonical, c.dir);
        if (c.blocked_reasons) assert.deepEqual(index.blocked.map((b) => b.reason), c.blocked_reasons, c.dir);
    }
    assert.deepEqual(accept.filter((c) => c.blocked_reasons).map((c) => c.dir), ["valid-unknown-reason"]);
});

test("valid-unknown-reason: the index is accepted, both add-ons are blocked, and the unknown reason reads as unknown", () => {
    const c = byDir["valid-unknown-reason"];
    assert.equal(c.expect, "accept");
    assert.equal(c.signature, "valid");
    assert.equal(verifyIndexSignature(bytesOf(c.dir), sigOf(c.dir), PUBKEY), true);
    const raw = JSON.parse(bytesOf(c.dir).toString("utf8"));
    assert.deepEqual(raw.blocked, [{ origin: "https://bad.example", reason: "harmful" }, { origin: "https://spam.example", reason: "spam" }]);
    assert.equal(BLOCK_REASONS.includes("spam"), false, "a word no reader knows");
    // A reader: the whole index applies, and the entry with the unknown word is a block like the other.
    const index = readIndex(bytesOf(c.dir));
    assert.deepEqual(index.blocked, [{ origin: "https://bad.example", reason: "harmful" }, { origin: "https://spam.example", reason: UNKNOWN_REASON }]);
    assert.equal(UNKNOWN_REASON, "unknown");
    assert.deepEqual(c.blocked_reasons, ["harmful", "unknown"]);
    assert.equal(JSON.stringify(index).includes("spam.example"), true, "the add-on is still named as blocked");
    assert.equal(JSON.stringify(index.blocked.map((b) => b.reason)).includes("spam"), false, "the word from the file is not passed on");
    // The publisher: never writes it, and refuses to build it.
    assert.throws(() => buildIndex(raw, 0, 1), /blocked\[1\]\.reason must be one of: harmful, broken, impersonation/);
    assert.equal(isCanonical(bytesOf(c.dir)), false);
    // Only the reason sets it apart: with a known word in its place, it is the publisher's own output.
    const known = `${JSON.stringify({ ...raw, blocked: [raw.blocked[0], { ...raw.blocked[1], reason: "broken" }] }, null, 2)}\n`;
    assert.equal(isCanonical(known), true);
});

test("canonical cases are byte for byte what the publisher emits for their content", () => {
    for (const c of cases.filter((x) => x.canonical)) {
        const bytes = bytesOf(c.dir);
        const again = buildIndex(JSON.parse(bytes.toString("utf8")), c.updated_at - 1, 1);
        assert.equal(Buffer.from(again, "utf8").equals(bytes), true, c.dir);
        assert.equal(bytes[bytes.length - 1], 0x0a, `${c.dir}: ends with one line feed`);
    }
});

test("valid: every optional field is present in one entry and absent in another; the caps and non-ASCII text are exercised", () => {
    const { addons, blocked } = readIndex(bytesOf("valid"));
    assert.deepEqual(addons[0], { id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", default: true });
    assert.deepEqual(Object.keys(addons[1]), ["id", "name", "summary", "icon", "manifest", "min_build", "platforms"]);
    assert.deepEqual(addons[1].platforms, ["android", "web"]);
    assert.equal(addons[1].min_build, 160);
    assert.deepEqual(Object.keys(addons[2]), ["id", "name", "summary", "manifest", "platforms"]);
    assert.equal(addons[2].name.length, MAX_NAME);
    assert.equal(addons[2].summary.length, MAX_SUMMARY);
    assert.equal([...addons[2].name].length, MAX_NAME - 1, "one character outside the BMP: 60 chars, 59 code points");
    assert.equal(/[^\x00-\x7f]/.test(addons[2].name), true);
    assert.equal(new URL(addons[2].manifest).port, "8443");
    assert.deepEqual(blocked.map((b) => b.reason), BLOCK_REASONS);
    assert.deepEqual(blocked.map((b) => "id" in b), [true, false, true]);
    assert.equal(blocked[1].origin, "https://broken.example:8443");
    // The bytes hold the text as UTF-8, not as \u escapes: a reader must verify the bytes as fetched.
    assert.equal(bytesOf("valid").includes(Buffer.from("\u{1F3B5}", "utf8")), true);
});

test("valid-older: the same index, a day older (for the rollback floor)", () => {
    const [newer, older] = [readIndex(bytesOf("valid")), readIndex(bytesOf("valid-older"))];
    assert.equal(older.updated_at, newer.updated_at - 86400);
    assert.deepEqual({ ...older, updated_at: 0 }, { ...newer, updated_at: 0 });
});

test("valid-launch: one default entry, nothing blocked", () => {
    const index = readIndex(bytesOf("valid-launch"));
    assert.deepEqual(index.addons, [{ id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", default: true }]);
    assert.deepEqual(index.blocked, []);
});

test("valid-unknown-fields: a reader ignores the unknown fields; the publisher refuses them in a source", () => {
    const raw = JSON.parse(bytesOf("valid-unknown-fields").toString("utf8"));
    assert.equal("notes" in raw && "category" in raw.addons[0] && "since" in raw.blocked[0], true);
    const index = readIndex(bytesOf("valid-unknown-fields"));
    assert.deepEqual(Object.keys(index), ["type", "v", "updated_at", "addons", "blocked"]);
    assert.deepEqual(index.addons, [{ id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", default: true }]);
    assert.deepEqual(index.blocked, [{ origin: "https://bad.example", reason: "harmful" }]);
    assert.throws(() => buildIndex(raw, 0, 1), /unknown keys: notes/);
});

test("refuse-lossless-json: a real lossless.json with a good signature, and not an index", () => {
    const c = byDir["refuse-lossless-json"];
    assert.equal(c.signature, "valid");
    const cfg = JSON.parse(bytesOf(c.dir).toString("utf8"));
    assert.deepEqual(Object.keys(cfg), ["v", "relays", "updated_at", "relay_key", "tidal_relay_url", "tidal_relay_token", "default_addons", "addon_index"]);
    assert.equal(cfg.v, 1);
    assert.equal("type" in cfg, false);
    // Newer than every valid case, so only `type` can be what refuses it.
    assert.ok(cfg.updated_at > byDir.valid.updated_at);
    assert.throws(() => readIndex(bytesOf(c.dir)), /this is not an add-on index/);
});

test("refuse-wrong-type and refuse-wrong-v: fine but for that one field", () => {
    for (const [dir, field, value, why] of [["refuse-wrong-type", "type", "stash-addon-list", /type is not/], ["refuse-wrong-v", "v", 2, /v is not 1/]]) {
        const raw = JSON.parse(bytesOf(dir).toString("utf8"));
        assert.equal(raw[field], value);
        assert.throws(() => readIndex(bytesOf(dir)), why);
        const fixed = Buffer.from(JSON.stringify({ ...raw, type: INDEX_TYPE, v: 1 }), "utf8");
        assert.doesNotThrow(() => readIndex(fixed), dir);
    }
});

test("refuse-wrong-signer: the bytes of `valid`, signed by another key", () => {
    assert.equal(bytesOf("refuse-wrong-signer").equals(bytesOf("valid")), true);
    assert.notEqual(sigOf("refuse-wrong-signer"), sigOf("valid"));
    assert.equal(verifyIndexSignature(bytesOf("refuse-wrong-signer"), sigOf("refuse-wrong-signer"), PUBKEY), false);
});

test("refuse-tampered: one byte of `valid` changed, under valid's own signature", () => {
    const [a, b] = [bytesOf("valid"), bytesOf("refuse-tampered")];
    assert.equal(a.length, b.length);
    const changed = [...a.keys()].filter((i) => a[i] !== b[i]);
    assert.equal(changed.length, 1);
    assert.equal(verifyIndexSignature(a, sigOf("refuse-tampered"), PUBKEY), true, "the signature is valid's");
    assert.equal(verifyIndexSignature(b, sigOf("refuse-tampered"), PUBKEY), false);
    // It still reads as an index: only the signature stands between it and a phone.
    assert.equal(readIndex(b).blocked[0].origin, "https://bae.example");
    assert.equal(readIndex(a).blocked[0].origin, "https://bad.example");
});

test("refuse-too-many-addons, refuse-too-many-blocked: one rule broken, the rest fine", () => {
    const addons = JSON.parse(bytesOf("refuse-too-many-addons").toString("utf8"));
    assert.equal(addons.addons.length, MAX_ADDONS + 1);
    assert.throws(() => readIndex(bytesOf("refuse-too-many-addons")), /addons holds at most 64/);
    assert.doesNotThrow(() => readIndex(JSON.stringify({ ...addons, addons: addons.addons.slice(1) })));

    const blocked = JSON.parse(bytesOf("refuse-too-many-blocked").toString("utf8"));
    assert.equal(blocked.blocked.length, MAX_BLOCKED + 1);
    assert.throws(() => readIndex(bytesOf("refuse-too-many-blocked")), /blocked holds at most 256/);
    assert.doesNotThrow(() => readIndex(JSON.stringify({ ...blocked, blocked: blocked.blocked.slice(1) })));

    for (const dir of ["refuse-too-many-addons", "refuse-too-many-blocked"]) {
        assert.ok(bytesOf(dir).length <= MAX_INDEX_BYTES, `${dir} is under the size cap, so the size is not what refuses it`);
    }
});

test("refuse-oversize: over the byte cap with every count within its cap", () => {
    const bytes = bytesOf("refuse-oversize");
    assert.ok(bytes.length > MAX_INDEX_BYTES);
    const raw = JSON.parse(bytes.toString("utf8"));
    assert.equal(raw.blocked.length, MAX_BLOCKED);
    assert.equal(raw.addons.length, 0);
    assert.throws(() => readIndex(bytes), /the app reads at most 65536/);
    assert.throws(() => buildIndex({ blocked: raw.blocked }, 0, 1), /the app reads at most 65536/);
});

test("every refuse case names what refuses it, and nothing but the signature cases has a bad signature", () => {
    for (const c of cases.filter((x) => x.expect === "refuse")) {
        assert.ok(["signature", "type", "v", "size", "rule"].includes(c.refused_by), c.dir);
        assert.equal(c.signature === "invalid", c.refused_by === "signature", c.dir);
        if (c.refused_by !== "signature") assert.throws(() => readIndex(bytesOf(c.dir)), Error, c.dir);
    }
});

test("nothing in the fixtures names a real host, a person or a local path", () => {
    const hosts = new Set();
    for (const c of cases) {
        for (const m of bytesOf(c.dir).toString("utf8").matchAll(/https:\/\/([^/"\s:]+)/g)) hosts.add(m[1]);
    }
    for (const h of hosts) {
        // raw.githubusercontent.com is the index's own public home (addon_index in the lossless.json case).
        assert.ok(h === "raw.githubusercontent.com" || /(^|\.)example(\.org)?$/.test(h), `unexpected host ${h}`);
    }
    for (const buf of [file("README.md"), file("cases.json"), ...cases.map((c) => bytesOf(c.dir))]) {
        assert.equal(/[A-Za-z]:\\|\/Users\/|@[a-z0-9-]+\.(com|net|org)\b|workers\.dev/i.test(buf.toString("utf8")), false);
    }
});
