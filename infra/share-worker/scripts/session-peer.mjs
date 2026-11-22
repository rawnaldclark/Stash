// A scripted second phone for trying Sessions and Listen Together on a real phone (Sessions plan step 2, task 2).
// Every frame is logged. Each run makes its own random Community key, so it's a new person every time.
//
//   node scripts/session-peer.mjs host <origin> [name] [vibe] [dj]   makes a station and goes live with it: two songs
//                                                                    in turn, each moved on when it ends; Ctrl-C ends it.
//                                                                    name, vibe line and DJ name: "Scripted ab12",
//                                                                    "Two songs, in turn" and "Script" if not given.
//                                                                    DJ=next|queue|pass answers each request that way
//                                                                    4 s after it arrives; DJ=auto turns Auto-accept on;
//                                                                    COHOST=1 makes the first listener a co-host;
//                                                                    AWAY=1 says "away" 5 s in; BOOT=<name> boots them.
//   node scripts/session-peer.mjs listen <origin> <stationId> [n]    tunes in to the station's session as n listeners
//                                                                    (1 if not given), each reacting every 10 s
//                                                                    With REQUEST=1 each also asks for a song 3 s in
//                                                                    (the first two for the test songs, the rest for
//                                                                    songs the host's phone may not have).
//   node scripts/session-peer.mjs private-host <origin>              a private Listen Together room (v2), guests add
//   node scripts/session-peer.mjs private-guest <origin> <code>      joins a private room (v2): Play next and a reaction
//
// <origin> is http://127.0.0.1:8811 for a `wrangler dev --var SESSIONS:1` on this PC, or https://stashfm.app. The songs
// are ones the test phone can play: change SONGS for another library.
import { randomBytes } from "node:crypto";

const [mode, origin, arg, countArg, djArg] = process.argv.slice(2);
if (!mode || !origin) { console.log("usage: see the top of scripts/session-peer.mjs"); process.exit(2); }
// With covers, so Live now's cards and the room have a picture (both hosts are on the Worker's COVER_HOSTS).
const SONGS = [
    { t: "Creep", a: "Radiohead", d: 235_000, yt: "XFkzRNyygfk", art: "https://i.ytimg.com/vi/XFkzRNyygfk/hqdefault.jpg" },
    { t: "Dreams", a: "The Cranberries", d: 255_000, yt: "Yam5uK6e-bQ", art: "https://i.scdn.co/image/ab67616d0000b273f6325f361d7803ad0d908451" },
];
const NAMES = ["Maya", "Sam", "Jo", "Ana", "Lee", "Ben", "Kai", "Rin", "Tess", "Noor"];
const EMOJI = ["❤️", "🔥", "😂", "😮", "👏", "🎶"];
const KEY_HEADER = "X-Stash-Community-Key";
const wsOrigin = origin.replace(/^http/, "ws");
const t0 = Date.now();
const log = (s) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(7)}s  ${s}`);
const newKey = () => randomBytes(32).toString("base64url"); // 43 characters, what the Worker takes as a key
const title = (t) => (t ? `${t.t} / ${t.a}${t.by ? ` (by ${t.by})` : ""}` : "-");

function brief(m) {
    switch (m.t) {
        case "welcome": return `welcome me ${m.memberId} host ${m.state.host} v ${m.state.v} track ${title(m.state.track)}` +
            (m.state.session ? ` session ${JSON.stringify(m.state.session)}` : ` guestsAdd ${m.state.guestsAdd}`);
        case "members": return `members ${m.members.map((x) => `${x.id}=${x.name ?? "?"}${x.v ? `:v${x.v}` : ""}`).join(", ")}`;
        case "prepare": return `prepare #${m.trackKey} ${title(m.track)}${m.why ? ` (${m.why})` : ""}`;
        case "timeline": return `timeline #${m.trackKey} ${m.playing ? "playing" : "paused"} at ${Math.round(m.positionMs / 1000)}s`;
        case "queue": return `queue (${m.queue.length}) ${m.queue.slice(0, 3).map(title).join(" | ")}`;
        case "reaction": return `reaction ${m.emoji} from ${m.from}`;
        default: return JSON.stringify(m).slice(0, 220);
    }
}

async function api(method, path, key, body) {
    const headers = { "content-type": "application/json", ...(key ? { [KEY_HEADER]: key } : {}) };
    const res = await fetch(origin + path, { method, headers, body: body && JSON.stringify(body) });
    const text = await res.text();
    if (!res.ok) { log(`${method} ${path} -> ${res.status} ${text}`); process.exit(1); }
    return text ? JSON.parse(text) : null;
}

/** One phone's socket: logs every frame, says ready to every prepare (as the app does once the song is in), pings. */
function phone(code, hello, name, quiet = false) {
    const ws = new WebSocket(`${wsOrigin}/v1/rooms/${code}/ws`);
    const p = { ws, name, onFrame: null, send: (m) => ws.send(JSON.stringify(m)) };
    ws.onopen = () => p.send(hello);
    ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.t !== "pong" && !(quiet && m.t === "reaction")) log(`${name} <- ${brief(m)}`);
        if (m.t === "prepare") p.send({ t: "status", status: "ready", trackKey: m.trackKey });
        if (m.t === "welcome" && m.state.trackKey) p.send({ t: "status", status: "ready", trackKey: m.state.trackKey });
        p.onFrame?.(m);
    };
    ws.onclose = (e) => { log(`${name} closed ${e.code} ${e.reason}`); if (!quiet) process.exit(0); };
    setInterval(() => { try { p.send({ t: "ping", c: Date.now() - t0 }); } catch { /* closed */ } }, 15_000);
    return p;
}

/** The host's side of playing: each song is moved on to the other when it ends, as the host's phone does. */
function playInTurn(h, firstQv) {
    let next = 1, timer = null;
    h.onFrame = (m) => {
        if (m.t === "welcome") h.send({ t: "load", track: SONGS[0], positionMs: 0, queue: [SONGS[1]], why: "pick", qv: m.state.qv ?? firstQv });
        if (m.t === "timeline") clearTimeout(timer);
        if (m.t === "timeline" && m.playing) {
            const song = SONGS[(next + 1) % 2]; // the one playing now
            timer = setTimeout(() => {
                h.send({ t: "load", track: SONGS[next], positionMs: 0, queue: [SONGS[(next + 1) % 2]], why: "end" });
                next = (next + 1) % 2;
            }, Math.max(1_000, song.d - m.positionMs));
        }
        if (m.t === "ended") process.exit(0);
    };
    process.on("SIGINT", () => { log("ending the session"); h.send({ t: "end" }); setTimeout(() => process.exit(0), 500); });
}

if (mode === "host") {
    const key = newKey();
    const tag = randomBytes(2).toString("hex");
    const dj = djArg || "Script";
    const { id } = await api("POST", "/v1/community/stations", key, { name: arg || `Scripted ${tag}`, hostName: dj, vibe: countArg || "Two songs, in turn" });
    const room = await api("POST", "/v1/rooms", key, { public: true, stationId: id });
    log(`STATION ${id}  ROOM ${room.code}  link ${origin}/s/${id}`);
    // A session checks the host key on every hello, resumes too.
    const h = phone(room.code, { t: "hello", v: 3, name: dj, hostKey: room.hostKey }, "host");
    playInTurn(h);
    // The DJ's side, for trying requests, co-hosts, presence and booting from a real phone.
    const play = h.onFrame;
    const answered = new Set();
    let cohosted = false;
    h.onFrame = (m) => {
        play(m);
        if (m.t === "welcome") {
            if (process.env.DJ === "auto") h.send({ t: "autoAccept", on: true });
            if (process.env.AWAY) setTimeout(() => h.send({ t: "presence", state: "away" }), 5_000);
        }
        if (m.t === "requests" && ["next", "queue", "pass"].includes(process.env.DJ)) {
            for (const r of m.requests) {
                if (answered.has(r.id)) continue;
                answered.add(r.id);
                setTimeout(() => h.send({ t: "requestAction", id: r.id, action: process.env.DJ }), 4_000);
            }
        }
        if (m.t === "members") {
            const others = m.members.filter((x) => x.name !== dj);
            if (process.env.COHOST && !cohosted && others.length) { cohosted = true; h.send({ t: "cohost", memberId: others[0].id, on: true }); }
            const out = process.env.BOOT && others.find((x) => x.name === process.env.BOOT);
            if (out) h.send({ t: "boot", memberId: out.id });
        }
    };
} else if (mode === "listen") {
    if (!arg) { console.log("listen needs a station id"); process.exit(2); }
    const { live } = await api("GET", `/v1/community/stations/${arg}`);
    if (!live) { log("that station isn't on the air"); process.exit(1); }
    const n = Math.max(1, Math.min(50, Number(countArg ?? 1)));
    for (let i = 0; i < n; i++) {
        const l = phone(live.room, { t: "hello", v: 3, name: NAMES[i] ?? `Listener ${i + 1}`, communityKey: newKey() }, `listener${i + 1}`, n > 1);
        let e = i;
        setInterval(() => { try { l.send({ t: "react", emoji: EMOJI[e++ % EMOJI.length] }); } catch { /* closed */ } }, 10_000);
        l.onFrame = (m) => {
            if (m.t === "ended") { log(`listener${i + 1} ended: ${m.reason}`); if (n === 1) process.exit(0); }
            if (m.t === "welcome" && process.env.REQUEST) {
                const track = SONGS[i] ?? { t: `Request ${i + 1}`, a: NAMES[i] ?? "Listener", d: 200_000 };
                setTimeout(() => l.send({ t: "request", track }), 3_000 + i * 500);
            }
        };
    }
} else if (mode === "private-host") {
    const room = await api("POST", "/v1/rooms", null, { hostName: "Script" });
    log(`ROOM ${room.code}  invite ${room.url}`);
    playInTurn(phone(room.code, { t: "hello", v: 2, name: "Script", hostKey: room.hostKey, guestsAdd: true }, "host"), 0);
} else if (mode === "private-guest") {
    if (!arg) { console.log("private-guest needs a room code"); process.exit(2); }
    const g = phone(arg, { t: "hello", v: 2, name: "Script guest" }, "guest");
    g.onFrame = (m) => {
        if (m.t === "welcome") {
            g.send({ t: "add", tracks: [SONGS[1]], next: true });
            setTimeout(() => g.send({ t: "react", emoji: EMOJI[0] }), 2_000);
        }
        if (m.t === "ended") process.exit(0);
    };
} else {
    console.log(`unknown mode ${mode}`);
    process.exit(2);
}
