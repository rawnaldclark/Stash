import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import worker, { handle } from "../index.js";
import { playerNext, playerOrigin, playerReturn, ticketKey, TICKET_SECONDS } from "../player.js";
import { forgetAllAccess, signSession } from "../session.js";
import { env, fakeCtx, fakeKV, freezeClock, get, hashFor, post, SECRET } from "./fakes.js";

const EMAIL = "supporter@example.com";
const PLAYER = "https://player.example.net";
const T0 = Date.UTC(2026, 9, 7, 12, 0, 0);
const NOW = T0 / 1000;

// ---------------------------------------------------------------------------------------------
// The player's verifier, ported line for line from stash-web player/worker/gate.ts (verifyTicket and the
// base64url helpers it uses), so a ticket minted here is checked exactly the way the player checks it.
// If gate.ts changes, change this copy with it.
// ---------------------------------------------------------------------------------------------
const AUDIENCE = "stash-player";
const MAX_TICKET_LIFETIME_SECONDS = 24 * 3600;
const venc = new TextEncoder();
const vdec = new TextDecoder();

function b64urlDecode(s) {
    if (!/^[A-Za-z0-9_-]*$/.test(s)) return null;
    try {
        const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4));
        const out = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    } catch {
        return null;
    }
}

const buf = (u) => u.buffer.slice(u.byteOffset, u.byteOffset + u.byteLength);

function parseJsonPart(part) {
    const bytes = b64urlDecode(part);
    if (!bytes) return null;
    try {
        return JSON.parse(vdec.decode(bytes));
    } catch {
        return null;
    }
}

async function verifyTicket(token, pubkeyJwk, nowSeconds) {
    if (!pubkeyJwk || token.length > 2048) return null;
    const parts = token.split(".");
    if (parts.length !== 2) return null;
    const [body, sig] = parts;
    const sigBytes = b64urlDecode(sig);
    if (!sigBytes || sigBytes.length !== 64) return null;
    let key;
    try {
        key = await crypto.subtle.importKey("jwk", JSON.parse(pubkeyJwk), { name: "Ed25519" }, false, ["verify"]);
    } catch {
        return null;
    }
    let ok = false;
    try {
        ok = await crypto.subtle.verify({ name: "Ed25519" }, key, buf(sigBytes), venc.encode(body));
    } catch {
        return null;
    }
    if (!ok) return null;
    const t = parseJsonPart(body);
    if (!t || typeof t.sub !== "string" || t.sub.length === 0 || t.sub.length > 200) return null;
    if (t.aud !== AUDIENCE) return null;
    if (typeof t.exp !== "number" || !Number.isFinite(t.exp)) return null;
    if (t.exp <= nowSeconds || t.exp > nowSeconds + MAX_TICKET_LIFETIME_SECONDS) return null;
    return { sub: t.sub, exp: t.exp, aud: t.aud };
}

// The mint snippet from stash-web DEPLOY.md section 4, as written there: a ticket from /player must be the
// same string, byte for byte (Ed25519 signatures are deterministic).
const b64url = (b) => btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
async function snippetMintTicket(sub, privJwk, now) {
    const body = b64url(venc.encode(JSON.stringify({ sub, exp: now + 120, aud: "stash-player" })));
    const key = await crypto.subtle.importKey("jwk", JSON.parse(privJwk), { name: "Ed25519" }, false, ["sign"]);
    const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key, venc.encode(body)));
    return `${body}.${b64url(sig)}`;
}

// ---------------------------------------------------------------------------------------------

/** A key pair in the shape the player's `npm run keys` prints (node:crypto's JWK export). */
async function keyPair() {
    const { publicKey, privateKey } = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
    const strip = ({ key_ops, ext, ...jwk }) => jwk; // node:crypto's export has neither
    return {
        pub: JSON.stringify(strip(await crypto.subtle.exportKey("jwk", publicKey))),
        priv: JSON.stringify(strip(await crypto.subtle.exportKey("jwk", privateKey))),
    };
}

const KEYS = await keyPair();
const OTHER = await keyPair();

let clock;
beforeEach(() => {
    forgetAllAccess();
    clock?.restore();
    clock = freezeClock(T0);
});

async function signedIn({ email = EMAIL, listed = true, over = {} } = {}) {
    const h = await hashFor(email);
    const e = env({ ACCESS_KV: fakeKV(listed ? { [`access:${h}`]: { source: "kofi" } } : {}), PLAYER_URL: PLAYER, PLAYER_TICKET_PRIVATE_KEY: KEYS.priv, ...over });
    return { h, e, cookie: `stash_access=${await signSession(SECRET, h)}` };
}

const open = (e, cookie, path = "/player") => worker.fetch(get(path, cookie ? { Cookie: cookie } : {}), e, fakeCtx());

/** The ticket and the rest of a /player redirect. */
function parseRedirect(res) {
    const location = res.headers.get("location");
    const at = location.indexOf("#t=");
    assert.ok(at > 0, location);
    return { before: location.slice(0, at), ticket: location.slice(at + 3) };
}

test("signed in with early access: a 302 to the player's /auth with a ticket the player's own verifier accepts", async () => {
    const { e, cookie } = await signedIn();
    const res = await open(e, cookie);
    assert.equal(res.status, 302);
    const { before, ticket } = parseRedirect(res);
    assert.equal(before, `${PLAYER}/auth`);
    const t = await verifyTicket(ticket, KEYS.pub, NOW);
    assert.ok(t, "the player accepts it");
    assert.equal(t.aud, "stash-player");
    assert.equal(t.exp, NOW + 120);
    assert.equal(TICKET_SECONDS, 120);
    assert.ok(ticket.length < 2048);
    // Exactly {sub, exp, aud}, in that order, as the DEPLOY.md snippet writes it.
    const [body, sig] = ticket.split(".");
    assert.deepEqual(Object.keys(JSON.parse(atob(body.replace(/-/g, "+").replace(/_/g, "/")))), ["sub", "exp", "aud"]);
    assert.equal(b64urlDecode(sig).length, 64, "a raw 64-byte Ed25519 signature");
    assert.match(ticket, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/, "base64url, no padding");
    assert.equal(ticket, await snippetMintTicket(t.sub, KEYS.priv, NOW), "byte for byte the DEPLOY.md snippet's ticket");
});

test("the redirect is never cached and sends no Referer, and it carries the site's other security headers", async () => {
    const { e, cookie } = await signedIn();
    const res = await open(e, cookie);
    assert.equal(res.headers.get("cache-control"), "no-store");
    assert.equal(res.headers.get("referrer-policy"), "no-referrer");
    assert.equal(res.headers.get("vary"), "Cookie");
    assert.ok(res.headers.get("content-security-policy"));
    assert.equal(res.headers.get("x-content-type-options"), "nosniff");
    assert.equal(res.headers.get("set-cookie"), null);
    // Every other page keeps the site's usual policy.
    assert.equal((await open(e, cookie, "/")).headers.get("referrer-policy"), "strict-origin-when-cross-origin");
});

test("sub is opaque: not the email, not its access-list hash, stable per person and different between people", async () => {
    const one = await signedIn();
    const sub = async (s) => (await verifyTicket(parseRedirect(await open(s.e, s.cookie)).ticket, KEYS.pub, NOW)).sub;
    const a = await sub(one);
    assert.match(a, /^[0-9a-f]{64}$/);
    assert.notEqual(a, one.h);
    assert.ok(!a.includes(EMAIL) && !a.includes(one.h));
    assert.equal(await sub(one), a, "the same person gets the same sub");
    const two = await signedIn({ email: "someone-else@example.com" });
    assert.notEqual(await sub(two), a);
});

test("the ticket expires after 2 minutes, and only fits the player's key and audience", async () => {
    const { e, cookie } = await signedIn();
    const { ticket } = parseRedirect(await open(e, cookie));
    assert.ok(await verifyTicket(ticket, KEYS.pub, NOW + 119));
    assert.equal(await verifyTicket(ticket, KEYS.pub, NOW + 120), null, "expired at exp");
    assert.equal(await verifyTicket(ticket, KEYS.pub, NOW + 3600), null);
    assert.equal(await verifyTicket(ticket, OTHER.pub, NOW), null, "another key pair's public half refuses it");
    // A ticket re-signed for another audience doesn't verify (the player checks aud after the signature).
    const [body] = ticket.split(".");
    const claims = JSON.parse(atob(body.replace(/-/g, "+").replace(/_/g, "/")));
    const key = await crypto.subtle.importKey("jwk", JSON.parse(KEYS.priv), { name: "Ed25519" }, false, ["sign"]);
    const wrong = b64url(venc.encode(JSON.stringify({ ...claims, aud: "someone-else" })));
    const forged = `${wrong}.${b64url(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key, venc.encode(wrong))))}`;
    assert.equal(await verifyTicket(forged, KEYS.pub, NOW), null);
    // Tickets are minted fresh each time: a minute later, a new exp.
    clock.advance(60_000);
    const later = parseRedirect(await open(e, cookie)).ticket;
    assert.equal((await verifyTicket(later, KEYS.pub, NOW + 60)).exp, NOW + 180);
});

test("not signed in: the early-access page, and after signing in, back to /player and on to the player", async () => {
    const { h, e } = await signedIn();
    for (const cookie of [null, "stash_access=abc.def"]) {
        const res = await open(e, cookie);
        assert.equal(res.status, 303);
        assert.equal(res.headers.get("location"), "/access?next=%2Fplayer");
        assert.equal(res.headers.get("cache-control"), "no-store");
    }
    // The sign-in page carries the way back in its form...
    const front = await handle(get("/access?next=%2Fplayer"), e);
    assert.match(await front.text(), /name="next" value="\/player"/);
    // ...to the "code sent" page...
    const ctx = fakeCtx();
    const sent = await handle(post("/access", { email: EMAIL, next: "/player" }), e, ctx);
    await ctx.drain();
    assert.match(await sent.text(), /name="next" value="\/player"/);
    const code = /is (\d{6})/.exec(e.EMAIL.sent[0].text)[1];
    // ...and a wrong code keeps it too...
    const wrong = await handle(post("/access/verify", { email: EMAIL, code: code === "000000" ? "111111" : "000000", next: "/player" }), e, fakeCtx());
    assert.equal(wrong.status, 400);
    assert.equal((await wrong.text()).match(/name="next" value="\/player"/g).length, 2);
    // ...and the right code goes back to /player.
    const verified = await handle(post("/access/verify", { email: EMAIL, code, next: "/player" }), e, fakeCtx());
    assert.equal(verified.status, 303);
    assert.equal(verified.headers.get("location"), "/player");
    const cookie = verified.headers.get("set-cookie").split(";")[0];
    const res = await open(e, cookie);
    assert.equal(res.status, 302);
    assert.ok(await verifyTicket(parseRedirect(res).ticket, KEYS.pub, NOW));
    assert.ok(h);
});

test("signing in from the home page still lands on /, and a signed-in visitor at /access?next=/player goes straight there", async () => {
    const { e, cookie } = await signedIn();
    const front = await handle(get("/access"), e);
    assert.match(await front.text(), /name="next" value=""/, "no way back when signing in didn't start at /player");
    const res = await handle(get("/access?next=%2Fplayer", { Cookie: cookie }), e);
    assert.equal(res.status, 303);
    assert.equal(res.headers.get("location"), "/player");
    assert.equal((await handle(get("/access", { Cookie: cookie }), e)).headers.get("location"), "/");
});

test("signed in but no longer on the list: the early-access page, as / shows it, and no ticket", async () => {
    const { e, cookie } = await signedIn({ listed: false });
    const res = await open(e, cookie);
    assert.equal(res.status, 303);
    assert.equal(res.headers.get("location"), "/");
    assert.equal(res.headers.get("cache-control"), "no-store");
    const page = await (await open(e, cookie, "/")).text();
    assert.match(page, /id="front"/);
});

test("without a working key or PLAYER_URL: a plain 503 page that names no setting, for everyone, never a crash", async () => {
    const bad = [
        { PLAYER_TICKET_PRIVATE_KEY: undefined },
        { PLAYER_TICKET_PRIVATE_KEY: "" },
        { PLAYER_TICKET_PRIVATE_KEY: "not json" },
        { PLAYER_TICKET_PRIVATE_KEY: "null" },
        { PLAYER_TICKET_PRIVATE_KEY: KEYS.pub }, // the public half by mistake
        { PLAYER_TICKET_PRIVATE_KEY: JSON.stringify({ ...JSON.parse(KEYS.priv), d: "!!" }) },
        { PLAYER_TICKET_PRIVATE_KEY: JSON.stringify({ kty: "EC", crv: "P-256", d: "x", x: "y", y: "z" }) },
        { PLAYER_URL: undefined },
        { PLAYER_URL: "" },
    ];
    const errors = [];
    const realError = console.error;
    console.error = (...args) => errors.push(args.join(" "));
    try {
        for (const over of bad) {
            for (const signed of [true, false]) {
                const { e, cookie } = await signedIn({ over });
                const res = await open(e, signed ? cookie : null);
                assert.equal(res.status, 503, JSON.stringify(over));
                assert.equal(res.headers.get("location"), null);
                assert.equal(res.headers.get("cache-control"), "no-store");
                const page = await res.text();
                assert.match(page, /id="problem"/);
                assert.match(page, /isn&#39;t available|isn't available/);
                for (const leak of ["PLAYER_URL", "PLAYER_TICKET_PRIVATE_KEY", "JWK", "Ed25519", KEYS.priv.slice(0, 20)]) assert.ok(!page.includes(leak), leak);
            }
        }
    } finally {
        console.error = realError;
    }
    assert.ok(errors.every((line) => !line.includes(JSON.parse(KEYS.priv).d)), "the key never reaches the log");
    assert.ok(errors.some((line) => line.includes("PLAYER_TICKET_PRIVATE_KEY")), "the log (wrangler tail) says what's missing");
});

test("PLAYER_URL must be a plain https origin", async () => {
    for (const ok of ["https://player.example.net", "https://player.example.net/", " https://Player.Example.net ", "https://player.example.net:8443"]) {
        assert.ok(playerOrigin(ok), ok);
    }
    assert.equal(playerOrigin("https://Player.Example.net/"), "https://player.example.net");
    for (const bad of [
        "http://player.example.net",
        "player.example.net",
        "//player.example.net",
        "javascript:alert(1)",
        "data:text/html,hi",
        "https://user:pass@player.example.net",
        "https://player.example.net/app",
        "https://player.example.net/?x=1",
        "https://player.example.net/#x",
        "https://localhost",
        "https://player.localhost",
        "https://player",
        "ftp://player.example.net",
        "not a url",
    ]) {
        assert.equal(playerOrigin(bad), null, bad);
        const { e, cookie } = await signedIn({ over: { PLAYER_URL: bad } });
        const realError = console.error;
        console.error = () => {};
        try {
            assert.equal((await open(e, cookie)).status, 503, bad);
        } finally {
            console.error = realError;
        }
    }
    const { e, cookie } = await signedIn({ over: { PLAYER_URL: "https://player.example.net/" } });
    assert.equal(parseRedirect(await open(e, cookie)).before, "https://player.example.net/auth", "no double slash");
});

test("a shared song or mix comes along: /player?next=... lands there on the player, signed in or after signing in", async () => {
    const { e, cookie } = await signedIn();
    const res = await open(e, cookie, "/player?next=%2Ft%2Fabc123");
    const { before, ticket } = parseRedirect(res);
    assert.equal(before, `${PLAYER}/auth?next=%2Ft%2Fabc123`);
    assert.ok(await verifyTicket(ticket, KEYS.pub, NOW));
    // The player's own ?next= spelling (URLSearchParams.set) works too.
    assert.equal(parseRedirect(await open(e, cookie, "/player?next=%2Fplay%3Fq%3Da+b")).before, `${PLAYER}/auth?next=${encodeURIComponent("/play?q=a b")}`);
    // Signed out, it survives signing in.
    const out = await open(e, null, "/player?next=%2Fm%2Fmix1");
    assert.equal(out.headers.get("location"), `/access?next=${encodeURIComponent("/player?next=%2Fm%2Fmix1")}`);
    const front = await handle(get(out.headers.get("location")), e);
    assert.match(await front.text(), /name="next" value="\/player\?next=%2Fm%2Fmix1"/);
    const back = await handle(get(out.headers.get("location"), { Cookie: cookie }), e);
    assert.equal(back.headers.get("location"), "/player?next=%2Fm%2Fmix1");
});

test("next can't point anywhere else: other player paths are dropped, and the sign-in forms only ever go back to /player", async () => {
    const { e, cookie } = await signedIn();
    for (const next of ["//evil.example", "/\\evil.example", "https://evil.example/t/1", "/admin", "/tx", "/mix", "", "/t" + "x".repeat(3000)]) {
        assert.equal(playerNext(next), null, next);
        const res = await open(e, cookie, `/player?next=${encodeURIComponent(next)}`);
        assert.equal(parseRedirect(res).before, `${PLAYER}/auth`, next);
    }
    for (const ok of ["/t", "/t/abc", "/t?t=1", "/m/abc", "/play", "/play?q=x"]) assert.equal(playerNext(ok), ok);

    for (const value of [undefined, "", "/", "/admin", "//evil.example", "https://evil.example/player", "/player/", "/playerx", "/player#x", "/player?x=1", "/player?next=%2Fadmin", "/player?next=//evil.example", "/player?next=%2Ft&x=1"]) {
        assert.equal(playerReturn(value), null, String(value));
    }
    assert.equal(playerReturn("/player"), "/player");
    assert.equal(playerReturn("/player?next=/t/1"), "/player?next=%2Ft%2F1");

    // A forged next on the sign-in form lands on / instead.
    const ctx = fakeCtx();
    await handle(post("/access", { email: EMAIL }), e, ctx);
    await ctx.drain();
    const code = /is (\d{6})/.exec(e.EMAIL.sent[0].text)[1];
    const res = await handle(post("/access/verify", { email: EMAIL, code, next: "https://evil.example/" }), e, fakeCtx());
    assert.equal(res.status, 303);
    assert.equal(res.headers.get("location"), "/");
    const front = await handle(get("/access?next=https%3A%2F%2Fevil.example"), e);
    assert.match(await front.text(), /name="next" value=""/);
});

test("only GET (and HEAD) mint a ticket", async () => {
    const { e, cookie } = await signedIn();
    for (const method of ["POST", "PUT", "DELETE"]) {
        const res = await handle(new Request("https://stashfm.app/player", { method, headers: { Cookie: cookie, Origin: "https://stashfm.app" } }), e);
        assert.equal(res.status, 405, method);
        assert.equal(res.headers.get("allow"), "GET");
    }
});

test("the key check accepts the player's `npm run keys` format and nothing that can't sign", async () => {
    assert.ok(await ticketKey(KEYS.priv));
    const { publicKey, privateKey } = (await import("node:crypto")).generateKeyPairSync("ed25519");
    const printed = JSON.stringify(privateKey.export({ format: "jwk" })); // exactly what gen-keys.mjs prints
    assert.ok(await ticketKey(printed));
    const { e, cookie } = await signedIn({ over: { PLAYER_TICKET_PRIVATE_KEY: printed } });
    const { ticket } = parseRedirect(await open(e, cookie));
    assert.ok(await verifyTicket(ticket, JSON.stringify(publicKey.export({ format: "jwk" })), NOW));
    assert.equal(await ticketKey(JSON.stringify(publicKey.export({ format: "jwk" }))), null);
});

test("a removed visitor can't mint a ticket, even right after a cached yes", async () => {
    const { h, e, cookie } = await signedIn();
    assert.equal((await open(e, cookie)).status, 302, "listed: a ticket");
    await e.ACCESS_KV.delete(`access:${h}`);
    const res = await open(e, cookie);
    assert.notEqual(res.status, 302, "no ticket once removed");
    assert.ok(!(res.headers.get("location") ?? "").includes("#t="));
});
