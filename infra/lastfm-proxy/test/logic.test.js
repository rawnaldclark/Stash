import { test } from "node:test";
import assert from "node:assert/strict";
import {
    normalizeQuery,
    extractErrorCode,
    ALLOWED_METHODS,
    FORBIDDEN_PARAMS,
} from "../src/index.js";

test("normalizeQuery is order-independent and drops api_key/format", () => {
    const a = normalizeQuery(new URLSearchParams("method=tag.gettoptracks&tag=techno&api_key=K1&format=json"));
    const b = normalizeQuery(new URLSearchParams("tag=techno&method=tag.gettoptracks&api_key=K2"));
    assert.equal(a, b, "same logical query must collapse to one cache key regardless of key/order");
    assert.equal(a, "method=tag.gettoptracks&tag=techno");
});

test("normalizeQuery distinguishes different lookups", () => {
    assert.notEqual(
        normalizeQuery(new URLSearchParams("method=tag.gettoptracks&tag=techno")),
        normalizeQuery(new URLSearchParams("method=tag.gettoptracks&tag=house")),
    );
});

test("extractErrorCode reads numeric and string error codes", () => {
    assert.equal(extractErrorCode('{"error":29,"message":"Rate Limit Exceeded"}'), 29);
    assert.equal(extractErrorCode('{"error":"6","message":"not found"}'), 6);
    assert.equal(extractErrorCode('{"tracks":{"track":[]}}'), null);
    assert.equal(extractErrorCode("not json"), null);
});

test("allowlist covers the generic read methods and excludes signed/per-user", () => {
    for (const m of [
        "tag.gettoptracks", "tag.gettopartists", "artist.getsimilar",
        "artist.gettoptracks", "artist.gettoptags", "track.getsimilar",
        "track.gettoptags", "track.getinfo",
    ]) {
        assert.ok(ALLOWED_METHODS.has(m), `${m} should be allowed`);
    }
    for (const m of ["auth.getsession", "track.scrobble", "user.gettoptracks"]) {
        assert.ok(!ALLOWED_METHODS.has(m), `${m} must NOT be proxied`);
    }
});

test("forbidden params block signed/per-user requests", () => {
    for (const p of ["api_key", "api_sig", "sk", "user", "username"]) {
        assert.ok(FORBIDDEN_PARAMS.has(p), `${p} must be rejected`);
    }
});

test("artist.getinfo is allowlisted", () => {
    assert.ok(ALLOWED_METHODS.has("artist.getinfo"));
});
