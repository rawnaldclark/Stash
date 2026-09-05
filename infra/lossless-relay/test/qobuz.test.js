import { test } from "node:test";
import assert from "node:assert/strict";
import { signGetFileUrl, classify, etspOf, extractCreds, mintFromQobuz, UA } from "../src/qobuz.js";

const SECRET = "abb21364945c0583309667d13ca3d93a";

test("signGetFileUrl reproduces the client's HAR vectors", () => {
    assert.equal(signGetFileUrl(1782781652, 2841459, 27, SECRET), "013c10042c5e15ca5f1d85610bdd62ad");
    assert.equal(signGetFileUrl(1782781565, 3144087, 6, SECRET), "ff083dedd464374d86affbb22daeae01");
});

test("etspOf parses the CDN URL's expiry the way the client does", () => {
    assert.equal(etspOf("https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1756760000&hmac=x"), 1756760000);
    assert.equal(etspOf("https://cdn.example/f.flac"), null);
    assert.equal(etspOf(null), null);
});

test("classify: a good body is ok, with sample_rate converted to Hz", () => {
    const r = classify(200, JSON.stringify({ url: "https://cdn/x?etsp=99", format_id: 7, bit_depth: 24, sampling_rate: 96 }));
    assert.deepEqual(r, { kind: "ok", url: "https://cdn/x?etsp=99", formatId: 7, bitDepth: 24, sampleRateHz: 96000, etsp: 99 });
    assert.equal(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 6, bit_depth: 16, sampling_rate: 44.1 })).sampleRateHz, 44100);
});

test("classify: dead-account signals are 401, USER_BLOCKED and UserUnauthenticated only", () => {
    assert.deepEqual(classify(401, "{}"), { kind: "dead", reason: "401" });
    assert.deepEqual(classify(403, '{"code":403,"message":"USER_BLOCKED"}'), { kind: "dead", reason: "USER_BLOCKED" });
    assert.equal(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 7, restrictions: [{ code: "UserUnauthenticated" }] })).kind, "dead");
});

test("classify: a preview reply is about the TRACK, not the account — 404, never dead", () => {
    // 2026-09-05: five of seven live accounts were retired overnight with dead_reason
    // 'preview' while every one of them still minted full FLAC when asked directly.
    // A 30 s sample / MP3 reply for a lossless request means this track is not
    // streamable in full for this account (region, licensing); the account is fine.
    assert.deepEqual(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 5, sample: false })), { kind: "locked" });
    assert.deepEqual(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 7, sample: true })), { kind: "locked" });
});

test("classify: a region lock is 'locked' (→ 404); everything else is transient", () => {
    assert.deepEqual(classify(200, JSON.stringify({ format_id: 7 })), { kind: "locked" });
    assert.deepEqual(classify(404, '{"status":"error","code":404,"message":"No result found"}'), { kind: "locked" });
    assert.deepEqual(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 0 })), { kind: "locked" });
    assert.equal(classify(403, '{"message":"geo"}').kind, "transient");
    assert.equal(classify(400, '{"message":"Invalid Request Signature parameter"}').kind, "transient");
    assert.equal(classify(500, "").kind, "transient");
    assert.equal(classify(200, "<html>").kind, "transient");
    assert.equal(classify(200, JSON.stringify({ url: "http://cdn/x", format_id: 7 })).reason, "plaintext_url");
});

test("extractCreds pulls the web pair out of a bundle", () => {
    assert.deepEqual(extractCreds('x;app_id:"712109809",app_secret:"abb21364945c0583309667d13ca3d93a";y'),
        { app_id: "712109809", app_secret: "abb21364945c0583309667d13ca3d93a" });
    assert.equal(extractCreds("nothing here"), null);
});

test("mintFromQobuz sends the signed request the way the app does", async () => {
    const calls = [];
    const fetchImpl = async (url, init) => {
        calls.push({ url: new URL(url), init });
        return new Response(JSON.stringify({ url: "https://cdn/x?etsp=5", format_id: 27, bit_depth: 24, sampling_rate: 192 }), { status: 200 });
    };
    const acct = { label: "a", token: "tok", app_id: "712109809", app_secret: SECRET };
    const r = await mintFromQobuz(fetchImpl, acct, 2841459, 27, 1782781652);
    assert.equal(r.kind, "ok");
    assert.equal(r.sampleRateHz, 192000);
    const { url, init } = calls[0];
    assert.equal(url.origin + url.pathname, "https://www.qobuz.com/api.json/0.2/track/getFileUrl");
    assert.equal(url.searchParams.get("track_id"), "2841459");
    assert.equal(url.searchParams.get("format_id"), "27");
    assert.equal(url.searchParams.get("app_id"), "712109809");
    assert.equal(url.searchParams.get("request_ts"), "1782781652");
    assert.equal(url.searchParams.get("request_sig"), "013c10042c5e15ca5f1d85610bdd62ad");
    assert.equal(url.searchParams.get("intent"), "stream");
    assert.equal(init.headers["X-User-Auth-Token"], "tok");
    assert.equal(init.headers["X-App-Id"], "712109809");
    assert.equal(init.headers["User-Agent"], UA);
    assert.ok(init.signal instanceof AbortSignal);
});

test("mintFromQobuz turns a thrown fetch into transient, never a throw", async () => {
    const acct = { label: "a", token: "t", app_id: "1", app_secret: "s" };
    const boom = async () => { throw Object.assign(new Error("t"), { name: "TimeoutError" }); };
    assert.deepEqual(await mintFromQobuz(boom, acct, 1, 6, 1), { kind: "transient", reason: "timeout" });
    const down = async () => { throw new TypeError("fetch failed"); };
    assert.equal((await mintFromQobuz(down, acct, 1, 6, 1)).reason, "network");
});
