import { test } from "node:test";
import assert from "node:assert/strict";
import { answerOf, base64, homeKeyOf, requestMessage, routeOf, sameText, sha256Hex, MOST_FORM } from "../src/frames.js";
import { verifiedTid } from "../src/entitlement.js";

const HOME = "abcdefghijklmnop";

test("only the relay's own addresses are routed", () => {
    assert.deepEqual(routeOf(`/home/${HOME}/connect`), { kind: "connect", home: HOME });
    assert.deepEqual(routeOf(`/home/${HOME}/answer/k3x9`), { kind: "answer", home: HOME, request: "k3x9" });
    assert.deepEqual(routeOf(`/h/${HOME}/rest/ping.view`), { kind: "phone", home: HOME, rest: "/rest/ping.view" });
    for (const junk of ["/h/short/rest/ping", `/h/${HOME}/`, `/h/${HOME}/admin`, `/h/${HOME}/rest/a/b`, `/home/${HOME}/connect/x`,
        `/home/${HOME}/answer/../x`, "/home/ABCDEFGHIJKLMNOP/connect", `/h/${HOME}/rest/`]) {
        assert.equal(routeOf(junk), null, junk);
    }
});

test("a phone's request becomes one message, with only what the desktop needs", () => {
    const headers = new Headers({ range: "bytes=10-", "cf-connecting-ip": "203.0.113.9", cookie: "x" });
    const { message } = requestMessage({ id: "a1", method: "GET", rest: "/rest/stream", search: "?id=s-1&u=me", headers });
    assert.deepEqual(message, { t: "req", id: "a1", method: "GET", url: "/rest/stream?id=s-1&u=me", range: "bytes=10-", ip: "203.0.113.9" });

    const form = new TextEncoder().encode("u=me&p=pw&id=s-1");
    const post = requestMessage({ id: "b", method: "POST", rest: "/rest/star", search: "",
        headers: new Headers({ "content-type": "application/x-www-form-urlencoded" }), body: form });
    assert.equal(atob(post.message.form), "u=me&p=pw&id=s-1");
    assert.equal(requestMessage({ id: "c", method: "DELETE", rest: "/rest/x", search: "", headers: new Headers() }).status, 405);
    const big = requestMessage({ id: "d", method: "POST", rest: "/rest/x", search: "",
        headers: new Headers({ "content-type": "application/x-www-form-urlencoded" }), body: new Uint8Array(MOST_FORM + 1) });
    assert.equal(big.status, 413);
    assert.equal(base64(new Uint8Array(70_000).fill(65)).length, 93_336);
});

test("the desktop's answer passes only a player's headers on", () => {
    const answer = answerOf(new Headers({
        "x-stash-status": "206",
        "x-stash-length": "1000",
        "x-stash-headers": JSON.stringify([["Content-Type", "audio/flac"], ["Content-Range", "bytes 0-999/5000"],
            ["Set-Cookie", "x=1"], ["Accept-Ranges", "bytes\r\nX: y"], ["bad"]]),
    }));
    assert.deepEqual(answer, { status: 206, headers: [["Content-Type", "audio/flac"], ["Content-Range", "bytes 0-999/5000"]], length: 1000 });
    assert.equal(answerOf(new Headers({ "x-stash-status": "99" })), null);
    assert.equal(answerOf(new Headers({ "x-stash-status": "200", "x-stash-headers": "{" })), null);
    assert.equal(answerOf(new Headers({ "x-stash-status": "200" })).length, null);
});

test("a home key is a bearer of 32 random bytes, compared whole", async () => {
    const key = "A".repeat(43);
    assert.equal(homeKeyOf(new Headers({ authorization: `Bearer ${key}` })), key);
    assert.equal(homeKeyOf(new Headers({ authorization: "Bearer short" })), null);
    assert.equal(homeKeyOf(new Headers()), null);
    assert.equal(await sha256Hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assert.ok(sameText("abc", "abc"));
    assert.ok(!sameText("abc", "abd") && !sameText("abc", "ab") && !sameText(null, "x"));
});

test("only a supporter token signed with the tip jar's key passes", async () => {
    const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
    const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
    const publicB64 = btoa(String.fromCharCode(...raw));
    const b64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    const mint = async (payload) => {
        const head = b64url(new TextEncoder().encode(JSON.stringify(payload)));
        const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, new TextEncoder().encode(head)));
        return `${head}.${b64url(sig)}`;
    };
    const tid = "0123456789abcdef0123456789abcdef";
    const now = 1_800_000_000;
    const good = await mint({ tier: "supporter", iat: now - 10, tid, v: 1 });
    assert.equal(await verifiedTid(good, publicB64, now), tid);
    assert.equal(await verifiedTid(good.slice(0, -2) + "AA", publicB64, now), null);
    assert.equal(await verifiedTid(await mint({ tier: "free", iat: now, tid, v: 1 }), publicB64, now), null);
    assert.equal(await verifiedTid(await mint({ tier: "supporter", iat: now + 3600, tid, v: 1 }), publicB64, now), null);
    assert.equal(await verifiedTid(good, "IbghbSB8gJcuHbpSEf8tRKfCwzBfB14eBPew3nVkQFQ=", now), null);
    assert.equal(await verifiedTid(null, publicB64, now), null);
});
