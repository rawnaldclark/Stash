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
//                                                                    DECKS=1 opens the decks 3 s in, and each song is
//                                                                    then the front of the room's queue, as the app
//                                                                    plays it; TURN=<seconds> moves on that soon.
//   node scripts/session-peer.mjs listen <origin> <stationId> [n]    tunes in to the station's session as n listeners
//                                                                    (1 if not given), each reacting every 10 s
//                                                                    With REQUEST=1 each also asks for a song 3 s in
//                                                                    (the first two for the test songs, the rest for
//                                                                    songs the host's phone may not have).
//                                                                    With LIFE=1 they say phrases, send animated
//                                                                    reactions and save songs.
//                                                                    With DECKS=1 each joins the decks 2 s after they
//                                                                    open and readies a song each time theirs starts;
//                                                                    DECKS=once readies only the first, so they miss
//                                                                    turns after it.
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
/** A session's reactions: text faces by id (room.js FACES), the last five big. The six EMOJI are a private room's. */
const FACES = ["k:shrug", "k:lenny", "k:yay", "k:love", "k:cool", "k:look", "k:wow", "k:cry", "k:wave", "k:notes", "k:heart", "k:bear", "k:tableflip", "k:unflip", "k:dance", "k:rockon", "k:sparkle"];
const BIG = FACES.slice(12);
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
        case "decksState": return `decks ${m.open ? "open" : "closed"} djs ${m.djs.map((d) => d.id + (d.ready ? "+" : "-")).join(",") || "-"} line ${m.line.join(",") || "-"} now ${m.now} next ${m.next}`;
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
    const p = { ws, name, onFrame: null, picks: 0, send: (m) => ws.send(JSON.stringify(m)) };
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
    // DECKS: the room's queue as it last said, so the next song is its front (a DJ's pick, when one is waiting).
    const decks = !!process.env.DECKS;
    const turnMs = process.env.TURN ? Number(process.env.TURN) * 1000 : null;
    let queue = [], qv = 0, playing = SONGS[0];
    h.onFrame = (m) => {
        if (m.t === "welcome") h.send({ t: "load", track: SONGS[0], positionMs: 0, queue: [SONGS[1]], why: "pick", qv: m.state.qv ?? firstQv });
        if (m.t === "queue") { queue = m.queue; qv = m.qv ?? qv; }
        if (m.t === "prepare") playing = m.track;
        if (m.t === "timeline") clearTimeout(timer);
        if (m.t === "timeline" && m.playing) {
            const song = decks ? playing : SONGS[(next + 1) % 2]; // the one playing now
            timer = setTimeout(() => {
                if (decks && queue.length) {
                    const [track, ...rest] = queue;
                    // The host's own queue never runs dry: the two test songs keep following.
                    const own = rest.some((t) => !t.by || t.by === h.id) ? rest : [...rest, SONGS[next], SONGS[(next + 1) % 2]];
                    if (!track.by || track.by === h.id) next = (next + 1) % 2;
                    h.send({ t: "load", track, positionMs: 0, queue: own, why: "end", qv });
                    return;
                }
                h.send({ t: "load", track: SONGS[next], positionMs: 0, queue: [SONGS[(next + 1) % 2]], why: "end" });
                next = (next + 1) % 2;
            }, turnMs ?? Math.max(1_000, (song.d ?? 200_000) - m.positionMs));
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
            h.id = m.memberId;
            if (process.env.DECKS) setTimeout(() => h.send({ t: "decks", open: true }), 3_000);
            // LAST=<seconds>: calls the last song that long in. END=<seconds>: ends the session then.
            if (process.env.LAST) setTimeout(() => h.send({ t: "lastSong" }), Number(process.env.LAST) * 1000);
            if (process.env.END) setTimeout(() => h.send({ t: "end" }), Number(process.env.END) * 1000);
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
        // KEY=<43 characters>: the first listener's Community key, so its regular's count can be seeded for a test.
        const l = phone(live.room, { t: "hello", v: 3, name: NAMES[i] ?? `Listener ${i + 1}`, communityKey: i === 0 && process.env.KEY ? process.env.KEY : newKey() }, `listener${i + 1}`, n > 1);
        let e = i;
        setInterval(() => { try { l.send({ t: "react", emoji: FACES[e++ % 12] }); } catch { /* closed */ } }, 10_000);
        l.onFrame = (m) => {
            if (m.t === "ended") { log(`listener${i + 1} ended: ${m.reason}`); if (n === 1) process.exit(0); }
            // LIFE=1: each listener says a phrase 4 s in and every 12 s after (the first three all say "tune", so the
            // feed folds them), sends an animated reaction every 7 s, and saves each song once it starts.
            if (m.t === "welcome" && process.env.LIFE) {
                const phrases = ["tune", "tune", "tune", "chills", "who", "thanks", "again", "more", "hi", "louder"];
                let k = 0;
                setTimeout(() => l.send({ t: "say", phrase: phrases[i % phrases.length] }), 4_000);
                setInterval(() => { try { l.send({ t: "say", phrase: phrases[(i + ++k) % phrases.length] }); } catch { /* closed */ } }, 12_000 + i * 700);
                setInterval(() => { try { l.send({ t: "react", emoji: BIG[(i + k) % BIG.length] }); } catch { /* closed */ } }, 7_000 + i * 300);
            }
            if (process.env.LIFE && (m.t === "prepare" || (m.t === "welcome" && m.state.trackKey))) {
                const key = m.t === "prepare" ? m.trackKey : m.state.trackKey;
                if (i % 2 === 0) setTimeout(() => { try { l.send({ t: "saved", trackKey: key }); } catch { /* closed */ } }, 6_000 + i * 400);
            }
            // DECKS: sit down with a song 2 s after the decks open (staggered), and ready another each time theirs starts.
            if (m.t === "welcome") { l.id = m.memberId; if (m.state.decks) l.onFrame({ t: "decksState", ...m.state.decks }); }
            if (process.env.DECKS && m.t === "decksState" && m.open && !l.joined) {
                l.joined = true;
                setTimeout(() => l.send({ t: "deckJoin", track: SONGS[i % 2] }), 2_000 + i * 600);
            }
            if (process.env.DECKS && m.t === "decksState" && !m.open) l.joined = false;
            if (process.env.DECKS === "1" && m.t === "prepare" && m.track.by === l.id) {
                setTimeout(() => { try { l.send({ t: "deckPick", track: SONGS[(i + ++l.picks) % 2] }); } catch { /* closed */ } }, 1_500);
            }
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
