import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign, verify, createPublicKey } from "node:crypto";
import { buildConfig, normaliseBase } from "../src/config.js";

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
