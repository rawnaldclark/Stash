import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { b64urlDecode, b64urlEncode } from "../crypto.js";
import {
    clearedCookie,
    cookieValue,
    currentSession,
    forgetAllAccess,
    readSession,
    sessionCookie,
    sessionSecret,
    SESSION_SECONDS,
    signSession,
} from "../session.js";
import { env, fakeKV, freezeClock, get, SECRET } from "./fakes.js";

const H = "a".repeat(64);
const T0 = Date.UTC(2026, 9, 4, 12, 0, 0);

beforeEach(() => forgetAllAccess());

test("a signed cookie reads back as {h, exp}, 90 days out", async () => {
    const value = await signSession(SECRET, H, T0);
    const s = await readSession(SECRET, value, T0);
    assert.equal(s.h, H);
    assert.equal(s.exp, T0 / 1000 + 7_776_000);
    assert.equal(SESSION_SECONDS, 7_776_000);
    // The payload is {h, exp} and nothing else: no email.
    assert.deepEqual(Object.keys(JSON.parse(new TextDecoder().decode(b64urlDecode(value.split(".")[0])))), ["h", "exp"]);
});

test("an expired cookie is refused", async () => {
    const value = await signSession(SECRET, H, T0);
    assert.ok(await readSession(SECRET, value, T0 + (SESSION_SECONDS - 1) * 1000));
    assert.equal(await readSession(SECRET, value, T0 + SESSION_SECONDS * 1000), null);
});

test("a tampered cookie is refused: payload, signature, or another secret", async () => {
    const value = await signSession(SECRET, H, T0);
    const [payload, sig] = value.split(".");
    const forged = b64urlEncode(JSON.stringify({ h: "b".repeat(64), exp: T0 / 1000 + 999_999 }));
    assert.equal(await readSession(SECRET, `${forged}.${sig}`, T0), null);
    const flipped = sig.slice(0, -2) + (sig.at(-2) === "A" ? "B" : "A") + sig.at(-1);
    assert.equal(await readSession(SECRET, `${payload}.${flipped}`, T0), null);
    assert.equal(await readSession("another-secret-another-secret-0000", value, T0), null);
    for (const junk of ["", "x", "a.b.c", `${payload}.`, `.${sig}`, "%%%.%%%"]) assert.equal(await readSession(SECRET, junk, T0), null, junk);
});

test("a payload with a bad hash or exp is refused even when signed", async () => {
    const { hmacBytes } = await import("../crypto.js");
    for (const body of [{ h: "nothex", exp: T0 / 1000 + 60 }, { h: H, exp: "soon" }, { h: H }]) {
        const p = b64urlEncode(JSON.stringify(body));
        assert.equal(await readSession(SECRET, `${p}.${b64urlEncode(await hmacBytes(SECRET, p))}`, T0), null);
    }
});

test("SESSION_SECRET must be at least 32 characters, or nobody is signed in", () => {
    assert.equal(sessionSecret({}), null);
    assert.equal(sessionSecret({ SESSION_SECRET: "short" }), null);
    assert.equal(sessionSecret({ SESSION_SECRET: SECRET }), SECRET);
});

test("the cookie's attributes: HttpOnly, Secure, SameSite=Lax, Path=/, 90 days; sign-out clears it", () => {
    assert.equal(sessionCookie("v"), "stash_access=v; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=7776000");
    assert.equal(clearedCookie, "stash_access=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0");
});

test("cookieValue finds stash_access among other cookies", () => {
    assert.equal(cookieValue(get("/", { Cookie: "a=1; stash_access=abc.def; b=2" })), "abc.def");
    assert.equal(cookieValue(get("/", { Cookie: "stash_accessx=1" })), null);
    assert.equal(cookieValue(get("/")), null);
});

test("removing someone from the access list ends their session (after the few-minute cache)", async () => {
    const clock = freezeClock(T0);
    try {
        const e = env({ ACCESS_KV: fakeKV({ [`access:${H}`]: { source: "manual" } }) });
        const req = get("/", { Cookie: `stash_access=${await signSession(SECRET, H, T0)}` });
        assert.ok(await currentSession(req, e));
        await e.ACCESS_KV.delete(`access:${H}`);
        assert.ok(await currentSession(req, e), "cached for a few minutes");
        clock.advance(5 * 60 * 1000 + 1);
        assert.equal(await currentSession(req, e), null);
    } finally {
        clock.restore();
    }
});
