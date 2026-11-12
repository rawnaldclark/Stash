#!/usr/bin/env node
/**
 * Builds, signs and checks the add-on index: the official list and the blocklist (add-ons phase 4, plan 1.3-1.4).
 * The index lives in the public registry repo as `index.json` + `index.json.sig`; the app finds it through
 * `addon_index` in lossless.json and verifies it with the lossless.json key (LOSSLESS_CONFIG_PUBKEY).
 *
 *   node scripts/publish-index.mjs check <source.json>
 *       Checks a source against every rule and prints what it would publish. No key, no network, writes nothing.
 *   node scripts/publish-index.mjs publish <key.pem> <source.json> <registry checkout> [--offline] [--url <index url>] [--pubkey <base64>]
 *       Builds the index, signs it, writes index.json and index.json.sig into the checkout, reads both back and
 *       verifies them. It NEVER commits, pushes or uploads: that is a separate step by hand.
 *   node scripts/publish-index.mjs verify [<key.pem>] [--pubkey <base64>] [--url <index url> | --dir <registry checkout>]
 *       Reads the live pair (or a checkout's) and reports. Checks the signature when a key is given.
 *
 * source.json: {"addons":[{"id","name","summary","icon"?,"manifest" or "default":true,"min_build"?,"platforms"?}],
 *               "blocked":[{"origin","id"?,"reason":"harmful"|"broken"|"impersonation"}]}
 * The rules are in ../src/index.js; a bad entry is refused, never repaired.
 *
 * `updated_at` is never hand-edited: it becomes max(now, newest known + 1), where "newest known" is the checkout's
 * current index and the live one (unless --offline), each counted only when its signature verifies. A copy that
 * does not verify stops the run rather than lowering, or inflating, the floor.
 * --pubkey is the app's public key (base64 SPKI DER, what LOSSLESS_CONFIG_PUBKEY holds). With it, every check is
 * made against exactly the key phones use; without it, against the public half of key.pem.
 * Only ids, origins and reasons are printed, never a manifest path. Keep key.pem OUTSIDE every repo.
 */
import { createPrivateKey, createPublicKey } from "node:crypto";
import { existsSync, readFileSync, realpathSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { normaliseIndexUrl } from "../src/config.js";
import { buildIndex, describeIndex, isCanonical, isP256, publicKeyBase64, readIndex, sigUrlOf, signIndex, verifyIndexSignature, INDEX_URL } from "../src/index.js";

const USAGE = [
    "usage:",
    "  check <source.json>",
    "  publish <key.pem> <source.json> <registry checkout> [--offline] [--url <index url>] [--pubkey <base64>]",
    "  verify [<key.pem>] [--pubkey <base64>] [--url <index url> | --dir <registry checkout>]",
].join("\n");
const FLAGS = { "--offline": false, "--url": true, "--pubkey": true, "--dir": true }; // true: takes a value

/** A failure with a message for the person at the keyboard; `code` 2 is a usage error. */
class Stop extends Error {
    constructor(message, code = 1) { super(message); this.code = code; }
}

function parseArgs(argv) {
    const positional = [];
    const flags = {};
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (!a.startsWith("--")) { positional.push(a); continue; }
        if (!(a in FLAGS)) throw new Stop(`unknown option ${a}\n${USAGE}`, 2);
        if (!FLAGS[a]) { flags[a.slice(2)] = true; continue; }
        const value = argv[++i];
        if (value === undefined || value.startsWith("--")) throw new Stop(`${a} needs a value\n${USAGE}`, 2);
        flags[a.slice(2)] = value;
    }
    return { positional, flags };
}

function loadPrivateKey(path) {
    let key;
    try { key = createPrivateKey(readFileSync(path)); } catch { throw new Stop("the key file could not be read as a private key (PEM)"); }
    if (!isP256(key)) throw new Stop("the key is not an EC P-256 (prime256v1) key: the app could not verify its signatures");
    return key;
}

/** The public key every check is made against, as base64 SPKI DER, and where it came from. */
function verifierFor(flags, privateKey) {
    if (flags.pubkey !== undefined) {
        let ok = false;
        try { ok = /^[A-Za-z0-9+/]+={0,2}$/.test(flags.pubkey) && isP256(createPublicKey({ key: Buffer.from(flags.pubkey, "base64"), type: "spki", format: "der" })); } catch { /* not a key */ }
        if (!ok) throw new Stop("--pubkey must be a P-256 public key as base64 SPKI DER (the text of LOSSLESS_CONFIG_PUBKEY)", 2);
        return { key: flags.pubkey, from: "the key given by --pubkey" };
    }
    return privateKey ? { key: publicKeyBase64(privateKey), from: "the public half of the key file" } : null;
}

function readSource(path) {
    let text;
    try { text = readFileSync(path, "utf8"); } catch { throw new Stop("the source file could not be read"); }
    if (text.charCodeAt(0) === 0xfeff) throw new Stop("the source file starts with a byte order mark: save it as UTF-8 without one");
    try { return JSON.parse(text); } catch { throw new Stop("the source file is not JSON"); }
}

function requireDir(dir) {
    if (!existsSync(dir) || !statSync(dir).isDirectory()) throw new Stop("the registry checkout is not a folder that exists (it is never created for you)");
}

/** A checkout's pair, or null when it holds neither file. One without the other stops the run. */
function readPair(dir) {
    const [json, sig] = [join(dir, "index.json"), join(dir, "index.json.sig")];
    if (!existsSync(json) && !existsSync(sig)) return null;
    if (!existsSync(json) || !existsSync(sig)) throw new Stop("the checkout holds only one of index.json and index.json.sig: restore the pair, or remove the stray file");
    return { bytes: readFileSync(json), sig: readFileSync(sig, "utf8") };
}

/** The live pair, or null when nothing is published there (404). Anything else that is not a 200 throws. */
async function fetchPair(url, fetchFn) {
    const [j, s] = await Promise.all([fetchFn(url), fetchFn(sigUrlOf(url))]);
    if (j.status === 404) return null;
    if (!j.ok || !s.ok) throw new Error(`the index host answered ${j.status} for the index and ${s.status} for its .sig`);
    return { bytes: Buffer.from(await j.arrayBuffer()), sig: await s.text() };
}

function indexUrl(flags) {
    if (flags.url === undefined) return INDEX_URL;
    const url = normaliseIndexUrl(flags.url);
    if (!url) throw new Stop("--url must be an https URL ending in .json, with no credentials, query or fragment", 2);
    return url;
}

/** A known copy's `updated_at`, counted only when it reads as an index and is signed by the key in use. */
function floorOf(pair, verifier, what) {
    if (!verifyIndexSignature(pair.bytes, pair.sig, verifier.key)) {
        throw new Stop(`${what} is not signed by this key (${verifier.from}). Not publishing over it: check the key, or remove that copy if it is known to be bad.`);
    }
    try { return readIndex(pair.bytes); } catch (e) { throw new Stop(`${what} does not read as an index: ${e.message}`); }
}

async function check(positional, flags, io) {
    if (positional.length !== 1 || Object.keys(flags).length) throw new Stop(USAGE, 2);
    let text;
    try { text = buildIndex(readSource(positional[0]), 0, io.now()); } catch (e) { if (e instanceof Stop) throw e; throw new Stop(`the source breaks a rule: ${e.message}`); }
    io.out(`ok: ${describeIndex(readIndex(text))} (${Buffer.byteLength(text)} bytes; updated_at is set at publish time)`);
    return 0;
}

async function publish(positional, flags, io) {
    if (positional.length !== 3 || flags.dir !== undefined) throw new Stop(USAGE, 2);
    const [keyPath, sourcePath, dir] = positional;
    const url = indexUrl(flags);
    const source = readSource(sourcePath);
    requireDir(dir);
    const privateKey = loadPrivateKey(keyPath);
    const verifier = verifierFor(flags, privateKey);

    // The floor: the newest index a phone may already hold.
    let floor = 0;
    const local = readPair(dir);
    if (local) {
        const index = floorOf(local, verifier, "the checkout's index.json");
        floor = Math.max(floor, index.updated_at);
        io.out(`checkout: ${describeIndex(index)}`);
    } else io.out("checkout: no index yet");
    if (flags.offline) io.out("live: not checked (--offline)");
    else {
        let live;
        try { live = await fetchPair(url, io.fetch); } catch (e) {
            throw new Stop(`could not read the live index (${e.message}). Check the connection, or pass --offline to take the floor from the checkout alone.`);
        }
        if (live) {
            const index = floorOf(live, verifier, "the live index");
            floor = Math.max(floor, index.updated_at);
            io.out(`live: ${describeIndex(index)}`);
        } else io.out("live: nothing published yet (404)");
    }
    const now = io.now();
    if (floor > now + 86400) io.out(`warning: the newest known index is dated more than a day ahead of this clock (updated_at=${floor}). Check the clock.`);

    // Build and sign in memory, and check the result, before anything is written.
    let text;
    try { text = buildIndex(source, floor, now); } catch (e) { throw new Stop(`the source breaks a rule: ${e.message}. Nothing was written.`); }
    const bytes = Buffer.from(text, "utf8");
    const sig = signIndex(bytes, privateKey);
    if (!verifyIndexSignature(bytes, sig, verifier.key)) {
        throw new Stop(`the signature does not verify with ${verifier.from}: this key file is not the key the app trusts. Nothing was written.`);
    }
    if (!isCanonical(bytes)) throw new Stop("the built index does not read back as written. Nothing was written.");

    writeFileSync(join(dir, "index.json"), bytes);
    writeFileSync(join(dir, "index.json.sig"), sig);

    // Read both files back from disk: what will be committed is what was signed.
    const back = readPair(dir);
    if (!back || !back.bytes.equals(bytes) || back.sig !== sig) throw new Stop("the files on disk are not what was written. Do not push them.");
    const index = readIndex(back.bytes);
    if (!verifyIndexSignature(back.bytes, back.sig, verifier.key)) throw new Stop("the files on disk do not verify. Do not push them.");

    io.out(`wrote index.json (${bytes.length} bytes) and index.json.sig`);
    io.out(`index: ${describeIndex(index)}`);
    io.out(`signature: verifies with ${verifier.from}`);
    io.out(`public key (must equal the app's LOSSLESS_CONFIG_PUBKEY): ${verifier.key}`);
    io.out("Nothing was committed or pushed. Review the diff in the registry checkout, commit index.json and index.json.sig together, push, then run verify.");
    return 0;
}

async function verify(positional, flags, io) {
    if (positional.length > 1 || flags.offline || (flags.dir !== undefined && flags.url !== undefined)) throw new Stop(USAGE, 2);
    const verifier = verifierFor(flags, positional.length ? loadPrivateKey(positional[0]) : null);
    let pair;
    let what;
    if (flags.dir !== undefined) {
        requireDir(flags.dir);
        pair = readPair(flags.dir);
        what = "checkout";
        if (!pair) throw new Stop("the checkout holds no index.json");
    } else {
        const url = indexUrl(flags);
        what = "live";
        try { pair = await fetchPair(url, io.fetch); } catch (e) { throw new Stop(`could not read the live index (${e.message})`); }
        if (!pair) { io.out(`live: nothing published yet (404) at ${url}`); return 0; }
    }
    let index;
    try { index = readIndex(pair.bytes); } catch (e) { throw new Stop(`${what}: not a readable index: ${e.message}`); }
    const signature = verifier ? verifyIndexSignature(pair.bytes, pair.sig, verifier.key) : "(no key given)";
    io.out(`${what}: ${describeIndex(index)} bytes=${pair.bytes.length} written_by_this_publisher=${isCanonical(pair.bytes)} signature=${signature}`);
    if (signature === false) throw new Stop(`${what}: the signature does NOT verify with ${verifier.from}. Phones holding that key refuse this index.`);
    return 0;
}

/**
 * Runs one command. `io` is for tests: `fetch`, `now` (Unix seconds), and `out` / `err` (one line each).
 * Returns the exit code: 0 done, 1 refused or failed, 2 usage.
 */
export async function run(argv, io = {}) {
    const full = {
        fetch: io.fetch ?? ((url) => fetch(url, { signal: AbortSignal.timeout(20_000) })),
        now: io.now ?? (() => Math.floor(Date.now() / 1000)),
        out: io.out ?? ((line) => console.log(line)),
        err: io.err ?? ((line) => console.error(line)),
    };
    try {
        const [cmd, ...rest] = argv;
        const { positional, flags } = parseArgs(rest);
        switch (cmd) {
            case "check": return await check(positional, flags, full);
            case "publish": return await publish(positional, flags, full);
            case "verify": return await verify(positional, flags, full);
            default: throw new Stop(USAGE, 2);
        }
    } catch (e) {
        if (!(e instanceof Stop)) throw e;
        full.err(e.message);
        return e.code;
    }
}

const isMain = (() => {
    try { return !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url)); } catch { return false; }
})();
if (isMain) process.exitCode = await run(process.argv.slice(2));
