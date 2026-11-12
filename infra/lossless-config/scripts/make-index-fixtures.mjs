#!/usr/bin/env node
/**
 * Writes the add-on index fixtures in ../test/fixtures: signed sample indexes for this folder's tests and for the
 * app's reader (AddonIndexFetcher), which must accept exactly the bytes this publisher emits and refuse the rest.
 *
 *   node scripts/make-index-fixtures.mjs [folder]
 *
 * Every run makes two NEW throwaway P-256 keys in memory and never writes a private key anywhere, so every
 * signature and pubkey.b64 change on each run. Run it only when the index format changes, commit the whole folder,
 * and tell whoever keeps a copy of these files. test/fixtures.test.js proves the files are what cases.json says.
 * With a folder, it writes there instead: every index.json and cases.json come out the same on every run, so a
 * run into a scratch folder shows what the script would write without touching the committed signatures.
 */
import { generateKeyPairSync } from "node:crypto";
import { mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { buildConfig } from "../src/config.js";
import { buildIndex, publicKeyBase64, signIndex, INDEX_TYPE, INDEX_URL, MAX_ADDONS, MAX_BLOCKED, MAX_INDEX_BYTES, MAX_NAME, MAX_SUMMARY } from "../src/index.js";

// The folder to write: test/fixtures, or another one given on the command line (to look at a run without re-keying
// the committed files).
const OUT = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), "..", "test", "fixtures");
const NOW = 1794355200; // 2026-11-11T00:00:00Z
const DAY = 86400;

const key = generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey;
const otherKey = generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey;

/** Text of exactly `n` UTF-16 units that starts with `base`. */
const fill = (base, n) => `${base}-${"0123456789".repeat(10)}`.slice(0, n);
// Two-, three- and four-byte UTF-8 characters, so a reader that re-encodes the text before verifying fails.
// Written as code points so this file stays ASCII: e-acute, e-grave, u-umlaut, an eighth note, a musical note emoji.
const NAME_60 = fill(`Sixty ${String.fromCodePoint(0xe9, 0xe8, 0xfc)} ${String.fromCodePoint(0x266a)} ${String.fromCodePoint(0x1f3b5)}`, MAX_NAME);
const SUMMARY_60 = fill("A summary of exactly sixty characters", MAX_SUMMARY);

const FULL = {
    addons: [
        { id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", default: true },
        {
            id: "example-radio", name: "Example Radio", summary: "Live stations from example.org",
            icon: "https://addons.example.org/radio/icon.png", manifest: "https://addons.example.org/radio/manifest.json",
            min_build: 160, platforms: ["android", "web"],
        },
        { id: "org.example.sixty", name: NAME_60, summary: SUMMARY_60, manifest: "https://sixty.example.org:8443/manifest.json", platforms: ["android"] },
    ],
    blocked: [
        { origin: "https://bad.example", id: "bad-addon", reason: "harmful" },
        { origin: "https://broken.example:8443", reason: "broken" },
        { origin: "https://fake-stash.example", id: "tidal", reason: "impersonation" },
    ],
};
const LAUNCH = { addons: [{ id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", default: true }], blocked: [] };

/** The publisher's own bytes for a source, stamped `updatedAt`. */
const built = (source, updatedAt) => Buffer.from(buildIndex(source, updatedAt - 1, 1), "utf8");
/** Bytes the publisher would refuse to write, in its spacing, for the must-refuse cases. */
const handMade = (obj) => Buffer.from(`${JSON.stringify(obj, null, 2)}\n`, "utf8");
const envelope = (lists, updatedAt = NOW) => ({ type: INDEX_TYPE, v: 1, updated_at: updatedAt, ...lists });

const valid = built(FULL, NOW);
const tampered = Buffer.from(valid);
const at = tampered.indexOf("https://bad.example");
tampered[at + "https://ba".length] = "e".charCodeAt(0); // bad.example -> bae.example: the block would move off its target

const longHost = (i) => `b${i}.${"x".repeat(60)}.${"y".repeat(60)}.${"z".repeat(60)}.example`;
const oversize = handMade(envelope({ addons: [], blocked: Array.from({ length: MAX_BLOCKED }, (_, i) => ({ origin: `https://${longHost(i)}`, reason: "harmful" })) }));
if (oversize.length <= MAX_INDEX_BYTES) throw new Error("the oversize case is not over the cap");

const config = Buffer.from(buildConfig({
    relays: [],
    relay_key: "example-relay-key-not-a-real-one-0123456789abcdef",
    tidal_relay_url: "https://relay.example.org",
    tidal_relay_token: "example-token",
    default_addons: [{ manifest: "https://addons.example.org/v1/example-token/manifest.json", first_party: true }],
    addon_index: INDEX_URL,
}, NOW + DAY - 1, 1), "utf8");

const cases = [
    {
        dir: "valid", expect: "accept", signature: "valid", canonical: true, bytes: valid,
        what: "A full index as the publisher writes it: a default entry, entries with a manifest, every optional field present in one entry and absent in another, a name and a summary at the 60-character cap, non-ASCII text, a port, and one block for each reason (with and without an id).",
    },
    {
        dir: "valid-older", expect: "accept", signature: "valid", canonical: true, bytes: built(FULL, NOW - DAY),
        what: "The same index published a day earlier. Valid by itself; a reader that has applied `valid` must refuse it (the rollback floor).",
    },
    {
        dir: "valid-launch", expect: "accept", signature: "valid", canonical: true, bytes: built(LAUNCH, NOW),
        what: "The shape of the first real index: one default entry and an empty blocklist.",
    },
    {
        dir: "valid-unknown-fields", expect: "accept", signature: "valid", canonical: false,
        bytes: handMade({
            ...envelope({
                addons: [{ ...LAUNCH.addons[0], category: "music" }],
                blocked: [{ origin: "https://bad.example", reason: "harmful", since: NOW - DAY }],
            }),
            notes: "A field from a later publisher.",
        }),
        what: "An index from a later publisher: an unknown field at the top, in an add-on and in a block. Unknown fields are ignored (plan 1.3), so a reader accepts it. This publisher never writes it, and refuses it as a source.",
    },
    {
        dir: "valid-unknown-reason", expect: "accept", signature: "valid", canonical: false, blocked_reasons: ["harmful", "unknown"],
        bytes: handMade(envelope({ addons: [], blocked: [{ origin: "https://bad.example", reason: "harmful" }, { origin: "https://spam.example", reason: "spam" }] })),
        what: "An index from a later publisher: two blocks, the second with the reason `spam`, a word no reader knows. A reader accepts the index and blocks both add-ons; the second one's reason reads as unknown and gets a generic fixed sentence. A newer list must never make an older reader drop its blocks. This publisher never writes it, and refuses it as a source.",
    },
    {
        dir: "refuse-lossless-json", expect: "refuse", refused_by: "type", signature: "valid", bytes: config,
        what: "A lossless.json signed with the same key, served as the index. The signature is good; it has no `type`.",
    },
    {
        dir: "refuse-wrong-type", expect: "refuse", refused_by: "type", signature: "valid",
        bytes: handMade({ ...envelope(LAUNCH), type: "stash-addon-list" }),
        what: "A well-formed index whose `type` is another word.",
    },
    {
        dir: "refuse-wrong-v", expect: "refuse", refused_by: "v", signature: "valid",
        bytes: handMade({ ...envelope(LAUNCH), v: 2 }),
        what: "A well-formed index whose `v` is 2.",
    },
    {
        dir: "refuse-wrong-signer", expect: "refuse", refused_by: "signature", signature: "invalid", bytes: valid, signer: otherKey,
        what: "The bytes of `valid`, signed with a different P-256 key (an unknown key). Only the signature is wrong.",
    },
    {
        dir: "refuse-tampered", expect: "refuse", refused_by: "signature", signature: "invalid", bytes: tampered, sigOf: valid,
        what: "The bytes of `valid` with one byte changed (bad.example became bae.example), with `valid`'s signature.",
    },
    {
        dir: "refuse-too-many-addons", expect: "refuse", refused_by: "rule", signature: "valid",
        bytes: handMade(envelope({ addons: Array.from({ length: MAX_ADDONS + 1 }, (_, i) => ({ id: `addon-${i}`, name: `Add-on ${i}`, summary: "One of too many", manifest: `https://addons.example.org/a${i}/manifest.json` })), blocked: [] })),
        what: `${MAX_ADDONS + 1} add-ons: one over the cap of ${MAX_ADDONS}. Each entry is fine by itself.`,
    },
    {
        dir: "refuse-too-many-blocked", expect: "refuse", refused_by: "rule", signature: "valid",
        bytes: handMade(envelope({ addons: [], blocked: Array.from({ length: MAX_BLOCKED + 1 }, (_, i) => ({ origin: `https://blocked-${i}.example`, reason: "broken" })) })),
        what: `${MAX_BLOCKED + 1} blocks: one over the cap of ${MAX_BLOCKED}. Each entry is fine by itself.`,
    },
    {
        dir: "refuse-oversize", expect: "refuse", refused_by: "size", signature: "valid", bytes: oversize,
        what: `A body over the ${MAX_INDEX_BYTES}-byte cap: ${MAX_BLOCKED} blocks (within the count cap) on long host names.`,
    },
];

// Start clean, but keep README.md: it is written by hand.
mkdirSync(OUT, { recursive: true });
for (const name of readdirSync(OUT)) if (name !== "README.md") rmSync(join(OUT, name), { recursive: true, force: true });
// Signatures cover exact bytes: git must never convert a line ending in this folder.
writeFileSync(join(OUT, ".gitattributes"), "* -text\n");
writeFileSync(join(OUT, "pubkey.b64"), publicKeyBase64(key));
const listed = [];
for (const c of cases) {
    const dir = join(OUT, c.dir);
    mkdirSync(dir);
    writeFileSync(join(dir, "index.json"), c.bytes);
    writeFileSync(join(dir, "index.json.sig"), signIndex(c.sigOf ?? c.bytes, c.signer ?? key));
    const entry = { dir: c.dir, expect: c.expect };
    if (c.refused_by) entry.refused_by = c.refused_by;
    entry.signature = c.signature;
    if (c.expect === "accept") {
        const index = JSON.parse(c.bytes.toString("utf8"));
        Object.assign(entry, { canonical: c.canonical, updated_at: index.updated_at, addons: index.addons.length, blocked: index.blocked.length });
        // Only where it is the point of the case: what a reader reports as each block's reason, in order.
        if (c.blocked_reasons) entry.blocked_reasons = c.blocked_reasons;
    }
    entry.bytes = c.bytes.length;
    entry.what = c.what;
    listed.push(entry);
}
writeFileSync(join(OUT, "cases.json"), `${JSON.stringify({ public_key: "pubkey.b64", cases: listed }, null, 2)}\n`);
console.log(`wrote ${cases.length} cases, cases.json, pubkey.b64 and .gitattributes to the fixtures folder (README.md is written by hand and was left alone)`);
