/**
 * Sessions load check (plan docs/superpowers/plans/2026-11-20-sessions-step1-server.md, task 20). Not run in CI.
 *
 *   node scripts/room-load.mjs           a session's stored room at its largest, worked out with src/room.js alone
 *   node scripts/room-load.mjs <origin>  that, then 54 sockets against `npm run dev` at <origin> (local only, e.g.
 *                                        http://127.0.0.1:8787): .dev.vars needs SESSIONS=1, and the local D1 its
 *                                        tables (npx wrangler d1 migrations apply stash-community --local)
 *
 * The live run: a station and its session, the host and 53 listeners, the host's 200-song album, a request from each
 * listener. Then round trips three ways: pings alone (a baseline); 20 songs played, the host taking 10 requests, every
 * listener reacting and pinging once a second at its own moment in it, as phones would; and all 53 at the same instant
 * (the worst case). It prints those, the frames each listener received, the largest, and the session's live directory
 * row. The plan's bar: a stored room past 1 MB, or a round trip over a second, means stop and tell the owner.
 */
import { cleanTrack } from "../src/validate.js";
import { step, EMOJI, MAX_PUBLIC_MEMBERS, PUBLIC_V, REACT_GAP_MS } from "../src/room.js";
import { MAX_FRAME } from "../src/listen-room.js";

// ── 1. The stored room at its largest ─────────────────────────────────────────

/** mulberry32: a small seeded random source, so a run can be repeated. */
function seeded(seed) {
    return () => {
        seed = (seed + 0x6d2b79f5) | 0;
        let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

const CTL = String.fromCharCode(1); // 6 characters as JSON: the most one character can cost
let serial = 0;
/** A song as long as cleanTrack takes one (just under 4,000 characters as JSON), each one different. */
function longest() {
    const n = String(serial++).padStart(6, "0");
    return { t: CTL.repeat(494) + n, a: CTL.repeat(100), al: CTL.repeat(60) };
}
/** A song as the app sends one, about 250 characters as JSON. */
function ordinary(i) {
    return {
        t: `An Ordinary Song Title ${i}`, a: "An Artist With A Name", al: "An Album Title", d: 200_000, isrc: "GBBPW0100025",
        art: `https://i.scdn.co/image/${"a".repeat(40)}`,
    };
}
if (!cleanTrack(longest()) || !cleanTrack(ordinary(0))) throw new Error("the test songs must be ones a room takes");

const idOf = (prefix, n) => `${prefix}${String(n).padStart(7, "0")}`; // 8 characters, as randomCode makes them
const TOKEN = "t".repeat(22); // 16 random bytes, base64url

/** A full session as people would use it: 54 members, the host's 200-song album, 30 requests waiting, reactions. */
function fullSession() {
    let s = null;
    let now = 1_000_000;
    const apply = (event) => { s = step(s, event, now).state; };
    apply({ type: "create", code: "LOADTEST", hostName: "The Host", keyHash: "0".repeat(64), stationId: "STATION1" });
    apply({ type: "hello", msg: { t: "hello", v: PUBLIC_V, name: "The Host" }, hostKeyOk: true, newId: idOf("h", 0), newToken: TOKEN });
    for (let i = 1; i < MAX_PUBLIC_MEMBERS; i++) {
        apply({ type: "hello", msg: { t: "hello", v: PUBLIC_V, name: `Listener ${i}` }, hostKeyOk: false,
            listenerHash: String(i).padStart(64, "0"), newId: idOf("l", i), newToken: TOKEN });
    }
    apply({ type: "msg", from: idOf("h", 0), msg: { t: "load", track: ordinary(0), queue: Array.from({ length: 200 }, (_, i) => ordinary(i + 1)), qv: 0 } });
    for (let i = 1; i <= 30; i++) apply({ type: "msg", from: idOf("l", i), msg: { t: "request", track: ordinary(500 + i) }, newId: idOf("r", i) });
    for (let i = 1; i < MAX_PUBLIC_MEMBERS; i++) { now += 20; apply({ type: "msg", from: idOf("l", i), msg: { t: "react", emoji: EMOJI[0] } }); }
    return JSON.stringify(s).length;
}

/**
 * The largest a session's stored room gets: [steps] seeded random moves (joins, leaves, boots, co-hosts, the host's
 * loads and whole queues as big as a frame allows, adds, removals, requests and what's done with them, Auto-accept,
 * reactions, play and pause, alarms), with every song as long as a room takes. The size is JSON's; the room's own
 * storage (structured clone) keeps a control character in one byte, so the real thing is smaller.
 */
function largestStored({ steps = 20_000, seed = 20261121 } = {}) {
    const rand = seeded(seed);
    const chance = (p) => rand() < p;
    const pick = (a) => a[Math.floor(rand() * a.length)];
    const worst = { stored: 0, frame: 0, frameType: null };
    let s = null;
    let now = 1_000_000;
    let ids = 0;
    const apply = (event) => {
        const r = step(s, event, now);
        for (const o of r.out) {
            const n = JSON.stringify(o.msg).length;
            if (n > worst.frame) { worst.frame = n; worst.frameType = o.msg.t; }
        }
        const changed = r.state !== s;
        s = r.closed ? null : r.state;
        if (s && changed) worst.stored = Math.max(worst.stored, JSON.stringify(s).length);
    };
    const hello = (host) => {
        const id = idOf("m", ids++);
        apply({ type: "hello", msg: { t: "hello", v: PUBLIC_V, name: "N".repeat(40) }, hostKeyOk: host,
            listenerHash: host ? undefined : id.padEnd(64, "f"), newId: id, newToken: TOKEN });
    };
    const open = () => {
        s = null;
        apply({ type: "create", code: "LOADTEST", hostName: "H".repeat(40), keyHash: "0".repeat(64), stationId: "STATION1" });
        hello(true);
    };
    /** A whole queue from the host's phone: up to 200 songs, as many as its frame can carry. */
    const hostQueue = () => {
        const q = [];
        let size = 200;
        for (let i = Math.floor(rand() * 201); i > 0; i--) {
            const t = chance(0.5) ? longest() : ordinary(serial++);
            size += JSON.stringify(t).length + 1;
            if (size > MAX_FRAME - 5_000) break;
            q.push(t);
        }
        return q;
    };
    open();
    for (let i = 0; i < steps; i++) {
        if (!s) open();
        now += Math.floor(rand() * 3_000);
        const host = s.host;
        const members = s.members.filter((m) => m.leftAt === null);
        const listeners = members.filter((m) => m.id !== host);
        const staff = [host, ...listeners.filter((m) => s.pub.cohosts.includes(m.kh)).map((m) => m.id)];
        const send = (from, msg, extra = {}) => apply({ type: "msg", from, msg, ...extra });
        const x = rand();
        if (x < 0.10 && s.members.length < MAX_PUBLIC_MEMBERS) hello(false);
        else if (x < 0.13 && listeners.length) apply({ type: "close", from: pick(listeners).id });
        else if (x < 0.15 && listeners.length) send(host, { t: "boot", memberId: pick(listeners).id });
        else if (x < 0.20) {
            send(host, { t: "load", track: chance(0.5) ? longest() : ordinary(serial++), why: pick(["pick", "end", "skip"]), qv: s.qv,
                ...(chance(0.3) ? { queue: hostQueue() } : {}) });
        } else if (x < 0.24) send(host, { t: "queue", queue: hostQueue(), qv: s.qv });
        else if (x < 0.34) send(pick(staff), { t: "add", tracks: Array.from({ length: 1 + Math.floor(rand() * 5) }, longest), next: chance(0.5) });
        else if (x < 0.40 && s.queue.length) send(pick(staff), { t: "unqueue", track: pick(s.queue) });
        else if (x < 0.55 && listeners.length) send(pick(listeners).id, { t: "request", track: longest() }, { newId: idOf("r", serial++) });
        else if (x < 0.65 && s.suggestions.length) send(pick(staff), { t: "requestAction", id: pick(s.suggestions).id, action: pick(["next", "queue", "pass"]) });
        else if (x < 0.67) send(host, { t: "autoAccept", on: chance(0.5) });
        else if (x < 0.70 && listeners.length) send(host, { t: "cohost", memberId: pick(listeners).id, on: chance(0.7) });
        else if (x < 0.85 && members.length) send(pick(members).id, { t: "react", emoji: pick(EMOJI) });
        else if (x < 0.90) send(host, { t: pick(["pause", "play"]) });
        else if (x < 0.95) send(host, { t: "status", status: "ready", trackKey: s.trackKey });
        else apply({ type: "alarm" });
    }
    return worst;
}

// ── 2. 54 sockets against wrangler dev ────────────────────────────────────────

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const newKey = () => Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString("base64url"); // 43 characters

/** One phone's socket: counts what it receives, times its pings, and can wait for a frame. */
class Phone {
    constructor(url, hello) {
        this.frames = 0;
        this.chars = 0;
        this.biggest = 0;
        this.rtts = [];
        this.sentAt = new Map();
        this.waiters = [];
        this.closed = null;
        this.ws = new WebSocket(url);
        this.welcome = new Promise((resolve, reject) => {
            this.ws.onopen = () => this.ws.send(JSON.stringify({ t: "hello", v: PUBLIC_V, ...hello }));
            this.ws.onmessage = (e) => {
                const text = typeof e.data === "string" ? e.data : Buffer.from(e.data).toString();
                this.frames++;
                this.chars += text.length;
                this.biggest = Math.max(this.biggest, text.length);
                const m = JSON.parse(text);
                if (m.t === "welcome") { this.id = m.memberId; this.welcomeChars = text.length; resolve(m); }
                if (m.t === "pong" && this.sentAt.has(m.c)) { this.rtts.push(performance.now() - this.sentAt.get(m.c)); this.sentAt.delete(m.c); }
                if (m.t === "queue") this.qv = m.qv;
                if (m.t === "requests") this.requests = m.requests;
                this.waiters = this.waiters.filter((w) => (w.t === m.t ? (w.resolve(m), false) : true));
            };
            this.ws.onclose = (e) => { this.closed = e.code; reject(new Error(`closed ${e.code} ${e.reason}`)); };
            this.ws.onerror = () => {};
        });
        this.welcome.catch(() => {});
    }
    send(msg) { if (this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg)); }
    ping(c) { this.sentAt.set(c, performance.now()); this.send({ t: "ping", c }); }
    waitFor(t) { return new Promise((resolve) => this.waiters.push({ t, resolve })); }
}

const quantile = (sorted, q) => sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
const ms = (v) => `${Math.round(v)} ms`;

async function liveRun(origin) {
    const base = new URL(origin);
    if (!["127.0.0.1", "localhost", "[::1]"].includes(base.hostname)) throw new Error(`${origin}: local only, never the live site`);
    /** A call that must answer [want] with every one of [fields]: anything else (another server on the port) stops the run. */
    const call = async (path, { method = "GET", key, body, want = 200, fields = [] } = {}) => {
        const headers = { ...(key ? { "X-Stash-Community-Key": key } : {}), ...(body ? { "content-type": "application/json" } : {}) };
        const r = await fetch(new URL(path, base), { method, headers, body: body ? JSON.stringify(body) : undefined });
        const text = await r.text();
        const json = text ? JSON.parse(text) : null;
        if (r.status !== want || fields.some((f) => json?.[f] === undefined)) {
            throw new Error(`${method} ${path}: ${r.status} ${text.slice(0, 200)} (wanted ${want} with ${fields.join(", ") || "anything"})`);
        }
        return json;
    };
    const owner = newKey();
    const { id: station } = await call("/v1/community/stations", {
        method: "POST", key: owner, body: { name: "Load check", hostName: "Load" }, want: 201, fields: ["id"],
    });
    const { code, hostKey } = await call("/v1/rooms", {
        method: "POST", key: owner, body: { public: true, stationId: station }, want: 201, fields: ["code", "hostKey", "url"],
    });
    const url = `${base.origin.replace(/^http/, "ws")}/v1/rooms/${code}/ws`;
    const album = Array.from({ length: 200 }, (_, i) => ordinary(i));

    const host = new Phone(url, { hostKey, name: "Host" });
    await host.welcome;
    host.send({ t: "queue", queue: album, qv: 0 });
    const listeners = [];
    const joinStart = performance.now();
    for (let i = 1; i < MAX_PUBLIC_MEMBERS; i++) {
        const p = new Phone(url, { communityKey: newKey(), name: `Listener ${String(i).padStart(2, "0")}` });
        await p.welcome;
        listeners.push(p);
    }
    const joinMs = performance.now() - joinStart;
    const extra = new Phone(url, { communityKey: newKey(), name: "One too many" });
    const turnedAway = await extra.welcome.then(() => "let in", () => `refused (${extra.closed})`);
    for (const [i, p] of listeners.entries()) p.send({ t: "request", track: ordinary(1_000 + i) });

    let c = 0;
    const every = REACT_GAP_MS + 50;
    /** Round trips since the last call, sorted, and their phones' counts started again. */
    const takeRtts = () => listeners.flatMap((p) => p.rtts.splice(0)).sort((a, b) => a - b);
    const settle = () => sleep(1_500); // the last pongs of a phase

    // A: pings alone, a baseline for what the rest adds.
    for (let round = 0; round < 5; round++) { for (const p of listeners) p.ping(++c); await sleep(every); }
    await settle();
    const quiet = takeRtts();

    // B: the 20 songs, each listener reacting and pinging once a second at its own moment in it, as phones would.
    const timers = listeners.map((p, i) => setTimeout(() => {
        timers[i] = setInterval(() => { p.send({ t: "react", emoji: EMOJI[c % EMOJI.length] }); p.ping(++c); }, every);
    }, Math.floor((i / listeners.length) * every)));
    const hostPings = setInterval(() => host.ping(++c), every);
    const runStart = performance.now();
    try {
        for (let song = 1; song <= 20; song++) {
            const prepared = host.waitFor("prepare");
            host.send({ t: "load", track: { t: `Load Song ${song}`, a: "Load Artist", d: 180_000 }, why: "pick", queue: album.slice(song), qv: host.qv });
            const { trackKey } = await prepared;
            host.send({ t: "status", status: "ready", trackKey });
            if (song % 2 === 0 && host.requests?.length) host.send({ t: "requestAction", id: host.requests[0].id, action: "next" });
            await sleep(3_000);
        }
    } finally {
        for (const t of timers) { clearTimeout(t); clearInterval(t); }
        clearInterval(hostPings);
    }
    const runS = (performance.now() - runStart) / 1000;
    await settle();
    const spread = takeRtts();
    const hostMax = Math.max(...host.rtts);

    // C: the worst case, every listener reacting and pinging in the same instant, 5 times.
    for (let round = 0; round < 5; round++) {
        for (const p of listeners) { p.send({ t: "react", emoji: EMOJI[round % EMOJI.length] }); p.ping(++c); }
        await sleep(every);
    }
    await settle();
    const burst = takeRtts();
    const { sessions } = await call("/v1/community/live", { fields: ["sessions"] });
    const row = sessions.find((x) => x.room === code);
    const ended = Promise.all(listeners.map((p) => new Promise((resolve) => {
        if (p.closed !== null) resolve();
        else p.ws.addEventListener("close", () => resolve());
    })));
    host.send({ t: "end" });
    await Promise.race([ended, sleep(5_000)]);

    const frames = listeners.map((p) => p.frames).sort((a, b) => a - b);
    const lost = listeners.reduce((n, p) => n + p.sentAt.size, 0);
    const trips = (r) => `p50 ${ms(quantile(r, 0.5))}, p95 ${ms(quantile(r, 0.95))}, max ${ms(r.at(-1))} (${r.length})`;
    console.log(`\nLive run against ${base.origin}: 1 host + ${listeners.length} listeners (joined in ${ms(joinMs)}); a 55th: ${turnedAway}`);
    console.log(`  round trips, pings alone: ${trips(quiet)}`);
    console.log(`  20 songs over ${runS.toFixed(1)} s, every listener reacting and pinging each ${every} ms at its own moment:`);
    console.log(`    round trips ${trips(spread)}; the host's max ${ms(hostMax)}`);
    console.log(`  all 53 reacting and pinging in the same instant: ${trips(burst)}; ${lost} pings unanswered in all`);
    console.log(`  frames per listener: min ${frames[0]}, median ${quantile(frames, 0.5)}, max ${frames.at(-1)}`);
    console.log(`  largest frame: ${Math.max(host.biggest, ...listeners.map((p) => p.biggest)).toLocaleString()} characters;`
        + ` a late listener's welcome: ${listeners.at(-1).welcomeChars.toLocaleString()}`);
    console.log(`  live directory: ${row ? `${row.listeners} listeners, full ${row.full}, ${row.crowd.length} faces, "${row.track?.t}"` : "NO ROW"}`);
    console.log(`  after End: ${listeners.filter((p) => p.closed === 4000).length} of ${listeners.length} listeners closed 4000`);
    return { maxRtt: Math.max(spread.at(-1), burst.at(-1), hostMax) };
}

const full = fullSession();
console.log(`A full session's stored room (54 members, a 200-song album, 30 requests): ${full.toLocaleString()} characters as JSON`);
// SEED and STEPS (environment) search further: the default is what the PR reports.
const search = { seed: Number(process.env.SEED ?? 20261121), steps: Number(process.env.STEPS ?? 20_000) };
const worst = largestStored(search);
console.log(`The largest in ${search.steps.toLocaleString()} random moves (seed ${search.seed}) with the longest songs:`
    + ` ${worst.stored.toLocaleString()} characters (bar: 1,000,000); the largest frame ${worst.frame.toLocaleString()} (${worst.frameType})`);
if (process.argv[2]) {
    const { maxRtt } = await liveRun(process.argv[2]);
    if (worst.stored > 1_000_000 || maxRtt > 1_000) console.log("\nOVER THE PLAN'S BAR: stop and tell the owner.");
}
process.exit(0);
