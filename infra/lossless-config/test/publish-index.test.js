// The publish-index command (scripts/publish-index.mjs): check, publish and verify, with throwaway keys in a
// temporary folder and a stand-in for the network. Nothing here reads a real key or reaches a real host.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { run } from "../scripts/publish-index.mjs";
import { buildConfig } from "../src/config.js";
import { buildIndex, isCanonical, publicKeyBase64, readIndex, signIndex, verifyIndexSignature, INDEX_URL } from "../src/index.js";

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "..", "scripts", "publish-index.mjs");
const ROOT = mkdtempSync(join(tmpdir(), "addon-index-test-"));
after(() => rmSync(ROOT, { recursive: true, force: true }));

const NOW = 1794355200;
const SOURCE = {
    addons: [
        { id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", default: true },
        { id: "example-radio", name: "Example Radio", summary: "Live stations", manifest: "https://addons.example.org/private-path/manifest.json" },
    ],
    blocked: [{ origin: "https://bad.example", id: "bad-addon", reason: "harmful" }],
};

let serial = 0;
/** A fresh folder under the test root. */
const folder = (name) => { const d = join(ROOT, `${name}-${++serial}`); mkdirSync(d); return d; };
/** A throwaway key of the given kind, written as PEM; returns { path, privateKey, spki }. */
function keyFile(kind = "p256") {
    const { privateKey } = kind === "p256" ? generateKeyPairSync("ec", { namedCurve: "prime256v1" })
        : kind === "p384" ? generateKeyPairSync("ec", { namedCurve: "secp384r1" })
            : generateKeyPairSync("rsa", { modulusLength: 2048 });
    const path = join(folder("key"), "throwaway.pem");
    writeFileSync(path, privateKey.export({ type: "pkcs8", format: "pem" }));
    return { path, privateKey, spki: publicKeyBase64(privateKey) };
}
const sourceFile = (source = SOURCE) => { const p = join(folder("source"), "index.source.json"); writeFileSync(p, typeof source === "string" ? source : JSON.stringify(source, null, 2)); return p; };
/** Runs the command in-process; returns { code, out, err } with the network replaced. */
async function cli(argv, { fetch = () => { throw new Error("the network was used"); }, now = NOW } = {}) {
    const out = [];
    const err = [];
    const code = await run(argv, { fetch, now: () => now, out: (l) => out.push(l), err: (l) => err.push(l) });
    return { code, out: out.join("\n"), err: err.join("\n") };
}
/** A stand-in host: serves `pair` ({ bytes, sig }) at INDEX_URL, or a 404 when there is none. */
const host = (pair, { status = 200, sigStatus = 200, url = INDEX_URL } = {}) => async (asked) => {
    if (!pair) return new Response("404: Not Found", { status: 404 });
    if (asked === url) return new Response(pair.bytes, { status });
    if (asked === `${url}.sig`) return new Response(pair.sig, { status: sigStatus });
    return new Response("404: Not Found", { status: 404 });
};
const signedPair = (key, source, updatedAt) => { const bytes = Buffer.from(buildIndex(source, updatedAt - 1, 1), "utf8"); return { bytes, sig: signIndex(bytes, key.privateKey) }; };
const pairIn = (dir) => ({ bytes: readFileSync(join(dir, "index.json")), sig: readFileSync(join(dir, "index.json.sig"), "utf8") });

// ---------------------------------------------------------------------------------------------------------------

test("check: a good source passes with no key and no network, and writes nothing", async () => {
    const src = sourceFile();
    const before = readdirSync(dirname(src));
    const r = await cli(["check", src]);
    assert.equal(r.code, 0, r.err);
    assert.match(r.out, /^ok: updated_at=\d+ addons=tidal \(default\), example-radio \(https:\/\/addons\.example\.org\) blocked=https:\/\/bad\.example \[bad-addon\] harmful \(\d+ bytes; updated_at is set at publish time\)$/);
    assert.equal(r.out.includes("private-path"), false, "a manifest path is never printed");
    assert.deepEqual(readdirSync(dirname(src)), before);
});

test("check: a source that breaks a rule, or is not a source, fails with the reason", async () => {
    const cases = [
        [{ addons: [{ ...SOURCE.addons[0], name: "" }] }, /the source breaks a rule: addons\[0\]\.name is required text/],
        [{ blocked: [{ origin: "https://bad.example", reason: "spam" }] }, /blocked\[0\]\.reason must be one of: harmful, broken, impersonation/],
        [{ addon: [] }, /unknown keys: addon/],
        ["{ not json", /the source file is not JSON/],
        [`${String.fromCodePoint(0xfeff)}{}`, /byte order mark/],
    ];
    for (const [source, message] of cases) {
        const r = await cli(["check", sourceFile(source)]);
        assert.equal(r.code, 1);
        assert.match(r.err, message);
        assert.equal(r.out, "");
    }
    const missing = await cli(["check", join(ROOT, "no-such-file.json")]);
    assert.equal(missing.code, 1);
    assert.match(missing.err, /could not be read/);
});

test("check and publish never print a token that slipped into a source", async () => {
    const token = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
    const src = sourceFile({ addons: [{ id: "tidal", name: "Tidal", summary: "Lossless", manifest: `https://addons.example.org/v1/${token}/manifest.json` }] });
    const key = keyFile();
    const dir = folder("registry");
    for (const r of [await cli(["check", src]), await cli(["publish", key.path, src, dir, "--offline"])]) {
        assert.equal(r.code, 1);
        assert.match(r.err, /looks like a token/);
        assert.equal(`${r.out}\n${r.err}`.includes(token), false);
    }
    assert.deepEqual(readdirSync(dir), []);
});

test("publish: writes index.json and index.json.sig, and they verify the way the device checks", async () => {
    const key = keyFile();
    const dir = folder("registry");
    const r = await cli(["publish", key.path, sourceFile(), dir, "--offline"]);
    assert.equal(r.code, 0, r.err);
    assert.deepEqual(readdirSync(dir).sort(), ["index.json", "index.json.sig"]);
    const { bytes, sig } = pairIn(dir);
    assert.equal(verifyIndexSignature(bytes, sig, key.spki), true);
    assert.equal(isCanonical(bytes), true);
    assert.match(sig, /^[A-Za-z0-9+/]+={0,2}$/, "the .sig is one line of base64 with no line break");
    const index = readIndex(bytes);
    assert.equal(index.updated_at, NOW);
    assert.deepEqual(index.addons.map((a) => a.id), ["tidal", "example-radio"]);
    assert.deepEqual(index.blocked, SOURCE.blocked);
    assert.equal(bytes.toString("utf8"), buildIndex(SOURCE, 0, NOW));

    assert.match(r.out, /^checkout: no index yet$/m);
    assert.match(r.out, /^live: not checked \(--offline\)$/m);
    assert.match(r.out, new RegExp(`^wrote index\\.json \\(${bytes.length} bytes\\) and index\\.json\\.sig$`, "m"));
    assert.match(r.out, /^signature: verifies with the public half of the key file$/m);
    assert.equal(r.out.includes(`public key (must equal the app's LOSSLESS_CONFIG_PUBKEY): ${key.spki}`), true);
    assert.match(r.out, /Nothing was committed or pushed\./);
    // Ids, origins and reasons only: no manifest path, no key path, no key material.
    assert.equal(r.out.includes("private-path"), false);
    assert.equal(r.out.includes("throwaway.pem"), false);
    assert.equal(r.out.includes("PRIVATE KEY"), false);
    assert.equal(r.err, "");
});

test("publish: updated_at is strictly above the checkout's copy, even when the clock has not moved", async () => {
    const key = keyFile();
    const dir = folder("registry");
    const src = sourceFile();
    assert.equal((await cli(["publish", key.path, src, dir, "--offline"])).code, 0);
    const again = await cli(["publish", key.path, src, dir, "--offline"]);
    assert.equal(again.code, 0, again.err);
    assert.match(again.out, new RegExp(`^checkout: updated_at=${NOW} `, "m"));
    assert.equal(readIndex(pairIn(dir).bytes).updated_at, NOW + 1);
    // A clock that has gone backwards still cannot publish something older.
    assert.equal((await cli(["publish", key.path, src, dir, "--offline"], { now: NOW - 100000 })).code, 0);
    assert.equal(readIndex(pairIn(dir).bytes).updated_at, NOW + 2);
    // A later clock wins.
    assert.equal((await cli(["publish", key.path, src, dir, "--offline"], { now: NOW + 500 })).code, 0);
    assert.equal(readIndex(pairIn(dir).bytes).updated_at, NOW + 500);
    assert.equal(verifyIndexSignature(pairIn(dir).bytes, pairIn(dir).sig, key.spki), true);
});

test("publish: the live copy raises the floor too, and a 404 means nothing is live yet", async () => {
    const key = keyFile();
    const src = sourceFile();
    const first = folder("registry");
    const none = await cli(["publish", key.path, src, first], { fetch: host(null) });
    assert.equal(none.code, 0, none.err);
    assert.match(none.out, /^live: nothing published yet \(404\)$/m);
    assert.equal(readIndex(pairIn(first).bytes).updated_at, NOW);

    // A stale checkout (nothing in it), a live copy dated ahead of this clock.
    const stale = folder("registry");
    const ahead = await cli(["publish", key.path, src, stale], { fetch: host(signedPair(key, SOURCE, NOW + 7000)) });
    assert.equal(ahead.code, 0, ahead.err);
    assert.match(ahead.out, new RegExp(`^live: updated_at=${NOW + 7000} `, "m"));
    assert.equal(readIndex(pairIn(stale).bytes).updated_at, NOW + 7001);

    // --url reads the live copy from somewhere else.
    const moved = "https://index.example.org/addons/index.json";
    const elsewhere = folder("registry");
    const asked = [];
    const r = await cli(["publish", key.path, src, elsewhere, "--url", moved], { fetch: (u) => { asked.push(u); return host(signedPair(key, SOURCE, NOW + 9), { url: moved })(u); } });
    assert.equal(r.code, 0, r.err);
    assert.deepEqual(asked.sort(), [moved, `${moved}.sig`]);
    assert.equal(readIndex(pairIn(elsewhere).bytes).updated_at, NOW + 10);
});

test("publish: by default it asks only the index's public home, with plain GETs", async () => {
    const key = keyFile();
    const asked = [];
    const r = await cli(["publish", key.path, sourceFile(), folder("registry")], { fetch: (...args) => { asked.push(args); return host(null)(args[0]); } });
    assert.equal(r.code, 0, r.err);
    assert.deepEqual(asked.map((a) => a[0]).sort(), [INDEX_URL, `${INDEX_URL}.sig`]);
    assert.equal(asked.every((a) => a.length === 1), true, "no method, body or headers: a read");
});

test("publish: a live copy it cannot read or trust stops the run, and nothing is written", async () => {
    const key = keyFile();
    const other = keyFile();
    const src = sourceFile();
    const good = signedPair(key, SOURCE, NOW - 50);
    const cases = [
        ["the network fails", () => { throw new Error("getaddrinfo ENOTFOUND"); }, /could not read the live index \(getaddrinfo ENOTFOUND\)\. Check the connection, or pass --offline/],
        ["the host answers 500", host(good, { status: 500 }), /the index host answered 500 for the index and 200 for its \.sig/],
        ["the .sig is missing", host(good, { sigStatus: 404 }), /answered 200 for the index and 404 for its \.sig/],
        ["signed by another key", host(signedPair(other, SOURCE, NOW - 50)), /the live index is not signed by this key \(the public half of the key file\)\. Not publishing over it/],
        ["an unsigned copy dated far ahead", host({ bytes: Buffer.from(buildIndex(SOURCE, 9000000000000, 1)), sig: "AAAA" }), /the live index is not signed by this key/],
        ["a changed byte", host({ bytes: Buffer.from(good.bytes.toString().replace("bad.example", "bae.example")), sig: good.sig }), /the live index is not signed by this key/],
    ];
    for (const [why, fetch, message] of cases) {
        const dir = folder("registry");
        const r = await cli(["publish", key.path, src, dir], { fetch });
        assert.equal(r.code, 1, why);
        assert.match(r.err, message, why);
        assert.deepEqual(readdirSync(dir), [], `${why}: nothing written`);
    }
});

test("publish: a checkout copy it cannot trust stops the run and is left as it was", async () => {
    const key = keyFile();
    const other = keyFile();
    const src = sourceFile();

    const foreign = folder("registry");
    const theirs = signedPair(other, SOURCE, NOW - 50);
    writeFileSync(join(foreign, "index.json"), theirs.bytes);
    writeFileSync(join(foreign, "index.json.sig"), theirs.sig);
    const r1 = await cli(["publish", key.path, src, foreign, "--offline"]);
    assert.equal(r1.code, 1);
    assert.match(r1.err, /the checkout's index\.json is not signed by this key/);
    assert.equal(pairIn(foreign).bytes.equals(theirs.bytes), true);
    assert.equal(pairIn(foreign).sig, theirs.sig);

    for (const only of ["index.json", "index.json.sig"]) {
        const half = folder("registry");
        writeFileSync(join(half, only), only === "index.json" ? theirs.bytes : theirs.sig);
        const r = await cli(["publish", key.path, src, half, "--offline"]);
        assert.equal(r.code, 1, only);
        assert.match(r.err, /holds only one of index\.json and index\.json\.sig/);
        assert.deepEqual(readdirSync(half), [only]);
    }

    // Signed by this key, but not an index: a lossless.json dropped into the checkout by mistake.
    const config = folder("registry");
    const cfg = Buffer.from(buildConfig({ relays: [] }, 0, NOW), "utf8");
    writeFileSync(join(config, "index.json"), cfg);
    writeFileSync(join(config, "index.json.sig"), signIndex(cfg, key.privateKey));
    const r2 = await cli(["publish", key.path, src, config, "--offline"]);
    assert.equal(r2.code, 1);
    assert.match(r2.err, /the checkout's index\.json does not read as an index: type is not "stash-addon-index"/);
    assert.equal(readFileSync(join(config, "index.json")).equals(cfg), true);
});

test("publish: a bad source leaves the published pair untouched", async () => {
    const key = keyFile();
    const dir = folder("registry");
    assert.equal((await cli(["publish", key.path, sourceFile(), dir, "--offline"])).code, 0);
    const before = pairIn(dir);
    const bad = sourceFile({ ...SOURCE, blocked: [{ origin: "https://bad.example/", reason: "harmful" }] });
    const r = await cli(["publish", key.path, bad, dir, "--offline"]);
    assert.equal(r.code, 1);
    assert.match(r.err, /the source breaks a rule: blocked\[0\]\.origin must be written exactly as its origin .* Nothing was written\./);
    assert.equal(pairIn(dir).bytes.equals(before.bytes), true);
    assert.equal(pairIn(dir).sig, before.sig);
});

test("publish: only a P-256 private key signs", async () => {
    const src = sourceFile();
    for (const [kind, message] of [["p384", /not an EC P-256 \(prime256v1\) key/], ["rsa", /not an EC P-256 \(prime256v1\) key/]]) {
        const dir = folder("registry");
        const r = await cli(["publish", keyFile(kind).path, src, dir, "--offline"]);
        assert.equal(r.code, 1, kind);
        assert.match(r.err, message, kind);
        assert.deepEqual(readdirSync(dir), []);
    }
    const notAKey = join(folder("key"), "notes.txt");
    writeFileSync(notAKey, "this is not a key");
    const dir = folder("registry");
    for (const path of [notAKey, join(ROOT, "no-such-key.pem"), src]) {
        const r = await cli(["publish", path, src, dir, "--offline"]);
        assert.equal(r.code, 1);
        assert.match(r.err, /the key file could not be read as a private key \(PEM\)/);
    }
    // A public key is not a signing key either.
    const pub = join(folder("key"), "public.pem");
    writeFileSync(pub, generateKeyPairSync("ec", { namedCurve: "prime256v1" }).publicKey.export({ type: "spki", format: "pem" }));
    assert.equal((await cli(["publish", pub, src, dir, "--offline"])).code, 1);
    assert.deepEqual(readdirSync(dir), []);
});

test("publish --pubkey: checks against exactly the key the app holds, and stops when the key file is another key", async () => {
    const key = keyFile();
    const other = keyFile();
    const src = sourceFile();
    const dir = folder("registry");
    const ok = await cli(["publish", key.path, src, dir, "--offline", "--pubkey", key.spki]);
    assert.equal(ok.code, 0, ok.err);
    assert.match(ok.out, /^signature: verifies with the key given by --pubkey$/m);

    const wrong = folder("registry");
    const r = await cli(["publish", other.path, src, wrong, "--offline", "--pubkey", key.spki]);
    assert.equal(r.code, 1);
    assert.match(r.err, /the signature does not verify with the key given by --pubkey: this key file is not the key the app trusts\. Nothing was written\./);
    assert.deepEqual(readdirSync(wrong), []);

    const urlSafe = key.spki.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    for (const bad of ["not-base64!", "AAAA", publicKeyBase64(keyFile("p384").privateKey), `${key.spki.slice(0, 40)}\n${key.spki.slice(40)}`, `${urlSafe}-`]) {
        const r2 = await cli(["publish", key.path, src, folder("registry"), "--offline", "--pubkey", bad]);
        assert.equal(r2.code, 2, bad);
        assert.match(r2.err, /--pubkey must be a P-256 public key as base64 SPKI DER/);
    }
});

test("publish: the registry checkout must already exist; it is never created", async () => {
    const key = keyFile();
    const missing = join(ROOT, "no-such-checkout");
    const r = await cli(["publish", key.path, sourceFile(), missing, "--offline"]);
    assert.equal(r.code, 1);
    assert.match(r.err, /not a folder that exists/);
    assert.equal(existsSync(missing), false);
    const aFile = sourceFile();
    assert.equal((await cli(["publish", key.path, sourceFile(), aFile, "--offline"])).code, 1);
});

test("publish: a newest known index dated more than a day ahead is called out", async () => {
    const key = keyFile();
    const dir = folder("registry");
    const r = await cli(["publish", key.path, sourceFile(), dir], { fetch: host(signedPair(key, SOURCE, NOW + 3 * 86400)) });
    assert.equal(r.code, 0, r.err);
    assert.match(r.out, /^warning: the newest known index is dated more than a day ahead of this clock/m);
    assert.equal(readIndex(pairIn(dir).bytes).updated_at, NOW + 3 * 86400 + 1);
});

test("verify --dir: a checkout's pair, with and without a key", async () => {
    const key = keyFile();
    const other = keyFile();
    const dir = folder("registry");
    assert.equal((await cli(["publish", key.path, sourceFile(), dir, "--offline"])).code, 0);

    const withKey = await cli(["verify", key.path, "--dir", dir]);
    assert.equal(withKey.code, 0, withKey.err);
    assert.match(withKey.out, new RegExp(`^checkout: updated_at=${NOW} addons=tidal \\(default\\), example-radio \\(https://addons\\.example\\.org\\) blocked=https://bad\\.example \\[bad-addon\\] harmful bytes=\\d+ written_by_this_publisher=true signature=true$`));
    const withPub = await cli(["verify", "--dir", dir, "--pubkey", key.spki]);
    assert.equal(withPub.code, 0, withPub.err);
    assert.match(withPub.out, /signature=true$/);
    const noKey = await cli(["verify", "--dir", dir]);
    assert.equal(noKey.code, 0);
    assert.match(noKey.out, /signature=\(no key given\)$/);

    const wrongKey = await cli(["verify", other.path, "--dir", dir]);
    assert.equal(wrongKey.code, 1);
    assert.match(wrongKey.out, /signature=false$/);
    assert.match(wrongKey.err, /the signature does NOT verify/);

    // One changed byte.
    const bytes = readFileSync(join(dir, "index.json"));
    writeFileSync(join(dir, "index.json"), bytes.toString().replace("bad.example", "bae.example"));
    const tampered = await cli(["verify", key.path, "--dir", dir]);
    assert.equal(tampered.code, 1);
    assert.match(tampered.err, /the signature does NOT verify/);

    // Line endings converted by a tool: the signature no longer covers these bytes.
    writeFileSync(join(dir, "index.json"), bytes.toString().replace(/\n/g, "\r\n"));
    const crlf = await cli(["verify", key.path, "--dir", dir]);
    assert.equal(crlf.code, 1);
    assert.match(crlf.out, /written_by_this_publisher=false signature=false$/);

    const empty = await cli(["verify", key.path, "--dir", folder("registry")]);
    assert.equal(empty.code, 1);
    assert.match(empty.err, /holds no index\.json/);
});

test("verify: the live pair", async () => {
    const key = keyFile();
    const pair = signedPair(key, SOURCE, NOW);
    const nothing = await cli(["verify"], { fetch: host(null) });
    assert.equal(nothing.code, 0);
    assert.equal(nothing.out, `live: nothing published yet (404) at ${INDEX_URL}`);

    const ok = await cli(["verify", key.path], { fetch: host(pair) });
    assert.equal(ok.code, 0, ok.err);
    assert.match(ok.out, new RegExp(`^live: updated_at=${NOW} .* signature=true$`));
    assert.equal(ok.out.includes("private-path"), false);
    assert.match((await cli(["verify"], { fetch: host(pair) })).out, /signature=\(no key given\)$/);
    assert.equal((await cli(["verify", "--pubkey", key.spki], { fetch: host(pair) })).code, 0);
    assert.equal((await cli(["verify", "--pubkey", keyFile().spki], { fetch: host(pair) })).code, 1);

    const down = await cli(["verify", key.path], { fetch: () => { throw new Error("fetch failed"); } });
    assert.equal(down.code, 1);
    assert.match(down.err, /could not read the live index \(fetch failed\)/);

    // A signed lossless.json served at the index URL: the signature is good, and it is still not an index.
    const cfg = Buffer.from(buildConfig({ relays: [], relay_key: "k".repeat(32) }, 0, NOW), "utf8");
    const swapped = await cli(["verify", key.path], { fetch: host({ bytes: cfg, sig: signIndex(cfg, key.privateKey) }) });
    assert.equal(swapped.code, 1);
    assert.match(swapped.err, /live: not a readable index: type is not "stash-addon-index": this is not an add-on index/);

    const moved = "https://index.example.org/addons/index.json";
    assert.equal((await cli(["verify", key.path, "--url", moved], { fetch: host(pair, { url: moved }) })).code, 0);
});

test("usage errors exit 2 and touch nothing", async () => {
    const key = keyFile();
    const src = sourceFile();
    const dir = folder("registry");
    const bad = [
        [],
        ["help"],
        ["check"],
        ["check", src, src],
        ["check", src, "--offline"],
        ["publish"],
        ["publish", key.path, src],
        ["publish", key.path, src, dir, "extra"],
        ["publish", key.path, src, dir, "--push"],
        ["publish", key.path, src, dir, "--url"],
        ["publish", key.path, src, dir, "--url", "http://index.example.org/index.json"],
        ["publish", key.path, src, dir, "--url", "https://index.example.org/index.json?x=1"],
        ["publish", key.path, src, dir, "--dir", dir],
        ["verify", key.path, key.path],
        ["verify", "--offline"],
        ["verify", "--dir", dir, "--url", INDEX_URL],
        ["verify", "--pubkey"],
    ];
    for (const argv of bad) {
        const r = await cli(argv);
        assert.equal(r.code, 2, argv.join(" "));
        assert.match(r.err, /usage:|must be|needs a value/, argv.join(" "));
    }
    assert.deepEqual(readdirSync(dir), []);
});

test("the command itself, from a shell: check, publish and verify with a throwaway key", () => {
    const key = keyFile();
    const src = sourceFile();
    const dir = folder("registry");
    const sh = (...argv) => spawnSync(process.execPath, [SCRIPT, ...argv], { encoding: "utf8" });

    const usage = sh();
    assert.equal(usage.status, 2);
    assert.match(usage.stderr, /usage:/);

    const check = sh("check", src);
    assert.equal(check.status, 0, check.stderr);
    assert.match(check.stdout, /^ok: updated_at=\d+ addons=tidal \(default\)/);

    const publish = sh("publish", key.path, src, dir, "--offline");
    assert.equal(publish.status, 0, publish.stderr);
    const { bytes, sig } = pairIn(dir);
    assert.equal(verifyIndexSignature(bytes, sig, key.spki), true);
    const stamped = readIndex(bytes).updated_at;
    assert.ok(Math.abs(stamped - Date.now() / 1000) < 120, "updated_at is the real clock");

    const verify = sh("verify", key.path, "--dir", dir);
    assert.equal(verify.status, 0, verify.stderr);
    assert.match(verify.stdout, /signature=true/);

    const refused = sh("check", sourceFile({ blocked: [{ origin: "http://bad.example", reason: "harmful" }] }));
    assert.equal(refused.status, 1);
    assert.match(refused.stderr, /blocked\[0\]\.origin must be https/);
});
