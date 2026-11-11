import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign, verify, createPublicKey } from "node:crypto";
import { buildConfig, normaliseBase, normaliseManifestUrl, normaliseIndexUrl, describeDefaultAddons } from "../src/config.js";

test("normaliseBase: https only, no trailing slash; rejects a query or fragment (the client would strip them)", () => {
    assert.equal(normaliseBase("https://stash-relay.example.workers.dev/"), "https://stash-relay.example.workers.dev");
    assert.equal(normaliseBase(" https://a.example/relay/ "), "https://a.example/relay");
    assert.equal(normaliseBase("http://a.example"), null);
    assert.equal(normaliseBase("https://a.example/?x=1"), null);
    assert.equal(normaliseBase("not a url"), null);
});

test("buildConfig: v=1, relays normalised and sorted by priority, updated_at strictly above the live one", () => {
    const live = 1788220800;
    const s = buildConfig({ relays: [{ base: "https://b.example/", priority: 2 }, { base: "https://a.example", priority: 1 }] }, live, live - 5000);
    assert.deepEqual(JSON.parse(s), { v: 1, relays: [{ base: "https://a.example", priority: 1 }, { base: "https://b.example", priority: 2 }], updated_at: live + 1 });
    assert.equal(JSON.parse(buildConfig({ relays: [] }, live, live + 100)).updated_at, live + 100); // a later clock wins
    assert.equal(JSON.parse(buildConfig({ relays: [] }, 0, 5)).updated_at, 5);                    // first publish
});

test("buildConfig: the kill switch is a valid empty list; the key rides along; bad input throws", () => {
    const s = buildConfig({ relays: [], relay_key: "a".repeat(64) }, 0, 1);
    assert.deepEqual(JSON.parse(s), { v: 1, relays: [], updated_at: 1, relay_key: "a".repeat(64) });
    assert.throws(() => buildConfig({ relays: [{ base: "http://a.example" }] }, 0, 1), /https/);
    assert.throws(() => buildConfig({ relays: [], relay_key: "short" }, 0, 1), /relay_key/);
});

test("a signature over the built bytes verifies the way the device does (SHA256withECDSA, DER, SPKI key)", () => {
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const bytes = Buffer.from(buildConfig({ relays: [] }, 0, 1));
    const sigB64 = sign("sha256", bytes, privateKey).toString("base64");
    const spkiB64 = publicKey.export({ type: "spki", format: "der" }).toString("base64"); // what LOSSLESS_CONFIG_PUBKEY holds
    const pub = createPublicKey({ key: Buffer.from(spkiB64, "base64"), type: "spki", format: "der" });
    assert.equal(verify("sha256", bytes, pub, Buffer.from(sigB64, "base64")), true);
    assert.equal(verify("sha256", Buffer.from(bytes.toString() + " "), pub, Buffer.from(sigB64, "base64")), false);
});

const TIDAL_V1 = "https://stash-addon-tidal.example.workers.dev/v1/tok-123/manifest.json";

test("default_addons: written with explicit booleans, in order; absent key leaves the bytes as before", () => {
    const base = { relays: [], relay_key: "a".repeat(64), tidal_relay_url: "https://relay.example", tidal_relay_token: "t" };
    const without = JSON.parse(buildConfig(base, 0, 1));
    assert.equal("default_addons" in without, false);
    const s = buildConfig({ ...base, default_addons: [{ manifest: TIDAL_V1, first_party: true }, { manifest: "https://b.example/manifest.json", enabled: false }] }, 0, 1);
    const cfg = JSON.parse(s);
    assert.deepEqual(cfg.default_addons, [
        { manifest: TIDAL_V1, enabled: true, first_party: true },
        { manifest: "https://b.example/manifest.json", enabled: false, first_party: false },
    ]);
    // The legacy keys released apps read are untouched.
    assert.equal(cfg.tidal_relay_url, "https://relay.example");
    assert.equal(cfg.relay_key, "a".repeat(64));
    assert.deepEqual(JSON.parse(buildConfig({ ...base, default_addons: [] }, 0, 1)).default_addons, []);
});

test("default_addons: bad entries throw, and no message repeats the URL (it can carry a token)", () => {
    const bad = [
        ["not an array", { manifest: TIDAL_V1 }],
        ["http", [{ manifest: "http://a.example/manifest.json" }]],
        ["not a manifest URL", [{ manifest: "https://a.example/v1/tok-123/" }]],
        ["query", [{ manifest: "https://a.example/manifest.json?x=1" }]],
        ["fragment", [{ manifest: "https://a.example/manifest.json#x" }]],
        ["credentials", [{ manifest: "https://u:p@a.example/manifest.json" }]],
        ["whitespace", [{ manifest: "https://a.example/a b/manifest.json" }]],
        ["too long", [{ manifest: `https://a.example/${"x".repeat(2040)}/manifest.json` }]],
        ["string flag", [{ manifest: TIDAL_V1, enabled: "true" }]],
        ["number flag", [{ manifest: TIDAL_V1, first_party: 1 }]],
        ["unknown key", [{ manifest: TIDAL_V1, firstParty: true }]],
        ["duplicate", [{ manifest: TIDAL_V1 }, { manifest: TIDAL_V1 }]],
        ["too many", Array.from({ length: 17 }, (_, i) => ({ manifest: `https://a${i}.example/manifest.json` }))],
        ["missing manifest", [{ enabled: true }]],
        ["not an object", ["https://a.example/manifest.json"]],
    ];
    for (const [why, list] of bad) {
        assert.throws(() => buildConfig({ relays: [], default_addons: list }, 0, 1), (e) => {
            assert.match(e.message, /default_addons/, why);
            assert.equal(e.message.includes("tok-123"), false, `${why}: the message names the token`);
            return true;
        }, why);
    }
    // Sixteen is fine.
    const sixteen = Array.from({ length: 16 }, (_, i) => ({ manifest: `https://a${i}.example/manifest.json` }));
    assert.equal(JSON.parse(buildConfig({ relays: [], default_addons: sixteen }, 0, 1)).default_addons.length, 16);
});

test("normaliseManifestUrl and describeDefaultAddons: the printed form never has the path", () => {
    assert.equal(normaliseManifestUrl(` ${TIDAL_V1} `), TIDAL_V1);
    assert.equal(normaliseManifestUrl("https://a.example/manifest.json"), "https://a.example/manifest.json");
    assert.equal(normaliseManifestUrl("https://a.example/manifest.jsonx"), null);
    assert.equal(normaliseManifestUrl(42), null);
    const text = describeDefaultAddons([{ manifest: TIDAL_V1, enabled: true, first_party: true }, { manifest: "https://b.example/manifest.json", enabled: false, first_party: false }]);
    assert.equal(text, "https://stash-addon-tidal.example.workers.dev first_party, https://b.example (off)");
    assert.equal(text.includes("tok-123"), false);
    assert.equal(describeDefaultAddons(undefined), "(unset)");
    assert.equal(describeDefaultAddons([]), "[] (none)");
});

const INDEX_URL = "https://raw.githubusercontent.com/stash-addons/registry/main/index.json";

test("addon_index: written when given, after the other keys; absent key leaves the bytes as before", () => {
    const base = { relays: [], relay_key: "a".repeat(64), tidal_relay_url: "https://relay.example", tidal_relay_token: "t", default_addons: [{ manifest: TIDAL_V1, first_party: true }] };
    const without = buildConfig(base, 0, 1);
    assert.equal("addon_index" in JSON.parse(without), false);
    const withIt = buildConfig({ ...base, addon_index: ` ${INDEX_URL} ` }, 0, 1);
    const cfg = JSON.parse(withIt);
    assert.equal(cfg.addon_index, INDEX_URL);
    // Every other key is byte-for-byte what it was: the new key is only appended.
    assert.equal(withIt, `${without.slice(0, -1)},"addon_index":${JSON.stringify(INDEX_URL)}}`);
    assert.deepEqual(Object.keys(cfg).slice(-2), ["default_addons", "addon_index"]);
});

test("addon_index: anything but a public https .json URL throws", () => {
    for (const [why, url] of [
        ["http", "http://raw.githubusercontent.com/stash-addons/registry/main/index.json"],
        ["query", `${INDEX_URL}?x=1`],
        ["empty query", `${INDEX_URL}?`],
        ["fragment", `${INDEX_URL}#x`],
        ["credentials", "https://u:p@raw.githubusercontent.com/stash-addons/registry/main/index.json"],
        ["not .json", "https://raw.githubusercontent.com/stash-addons/registry/main/index"],
        ["the repo page", "https://github.com/stash-addons/registry/blob/main/index.json"],
        ["whitespace", "https://raw.githubusercontent.com/stash addons/index.json"],
        ["empty", ""],
        ["null", null],
        ["number", 1],
    ]) {
        assert.throws(() => buildConfig({ relays: [], addon_index: url }, 0, 1), /addon_index/, why);
    }
    assert.equal(normaliseIndexUrl("https://Index.Example:443/a/index.json"), "https://index.example/a/index.json");
    assert.equal(normaliseIndexUrl(`https://index.example/${"x".repeat(2040)}.json`), null);
});
