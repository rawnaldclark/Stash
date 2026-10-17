import { test } from "node:test";
import assert from "node:assert/strict";
import { verifyMint } from "../src/auth.js";

const H = {
    install: "0f7c3a2e-install",
    ts: "1700000000",
    auth: "fc6ad1b240aa21efc87e1088841bd3b2eac9bb5139d855de9dde6e0729bb0532",
};

test("accepts the client's HMAC vector", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["k-secret"]), true);
});

test("accepts the previous key during a rotation; blanks are skipped", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["new-key", "k-secret"]), true);
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["new-key", undefined]), false);
});

test("rejects a wrong key, a tampered track id, and a tampered install id", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["other"]), false);
    assert.equal(verifyMint(H, 43, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({ ...H, install: "someone-else-1" }, 42, 27, 1700000000, ["k-secret"]), false);
});

test("rejects a timestamp outside ±300 s in either direction", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000 + 300, ["k-secret"]), true);
    assert.equal(verifyMint(H, 42, 27, 1700000000 + 301, ["k-secret"]), false);
    assert.equal(verifyMint(H, 42, 27, 1700000000 - 301, ["k-secret"]), false);
});

test("rejects malformed or missing headers without throwing", () => {
    assert.equal(verifyMint({ install: "", ts: "x", auth: "zz" }, 42, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({}, 42, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({ ...H, auth: H.auth.slice(0, 63) }, 42, 27, 1700000000, ["k-secret"]), false);
});

test("the quota signature is <install>:quota:<ts> with the mint's key and encoding (fixed vector for the app)", async () => {
    const { verifyQuota, verifySigned } = await import("../src/auth.js");
    const Q = { ...H, auth: "c9f00efc855cdc0349a1bcb6b5cedcdf78ec50c76570e5c9c250db26bcf2df9d" };
    assert.equal(verifyQuota(Q, 1700000000, ["k-secret"]), true);
    assert.equal(verifyQuota(Q, 1700000000, ["other", "k-secret"]), true);
    assert.equal(verifyQuota(Q, 1700000301, ["k-secret"]), false);
    assert.equal(verifyQuota(H, 1700000000, ["k-secret"]), false, "a mint signature is not a quota signature");
    assert.equal(verifySigned(H, "42:27", 1700000000, ["k-secret"]), true, "verifyMint is verifySigned with <track>:<format>");
});
