/**
 * The home relay: a supporter's phone plays the library on their Stash desktop from anywhere.
 *
 * The desktop keeps one WebSocket to its home (a Durable Object, named by an id the desktop made up), connected with
 * its home key and a supporter token. A phone's Subsonic app uses https://<this host>/h/<home>: each request of the
 * Subsonic API is sent down the WebSocket, and the desktop POSTs the answer back, streamed (so a whole song never
 * sits here, and a phone that reads slowly slows the desktop down). The desktop checks the device's password as on
 * its own network; the relay only joins the two.
 *
 * Idle homes hibernate (their WebSocket stays open, unbilled; "ping" is answered without waking them). See README.md.
 */

import { DurableObject } from "cloudflare:workers";
import { supporterOf } from "./entitlement.js";
import { MOST_FORM, answerOf, homeKeyOf, requestMessage, routeOf, sameText, sha256Hex } from "./frames.js";

/** Requests waiting for the desktop at once, per home. */
const MOST_WAITING = 24;
/** How long the desktop has to start answering. */
const ANSWER_START_MS = 45_000;

const text = (status, body) =>
    new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" } });
const json = (status, body) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        if (url.pathname === "/") return text(200, "Stash home relay. Add the address Stash shows you to your Subsonic app.");
        const route = routeOf(url.pathname);
        if (!route) return text(404, "Not found");
        const home = env.HOME.get(env.HOME.idFromName(route.home));
        if (route.kind === "connect") {
            const supporter = await supporterOf(request, env);
            if (!supporter) return json(402, { error: "not_supporter" });
            if (supporter.revoked) return json(403, { error: "revoked" });
            return home.fetch(new Request(`https://home/connect?tid=${supporter.tid}`, request));
        }
        if (route.kind === "answer") return home.fetch(new Request(`https://home/answer?r=${route.request}`, request));
        const inside = new URL("https://home/phone");
        inside.searchParams.set("rest", route.rest);
        inside.searchParams.set("q", url.search);
        return home.fetch(new Request(inside, request));
    },
};

export class Home extends DurableObject {
    constructor(ctx, env) {
        super(ctx, env);
        /** Requests waiting for their answer: id → { resolve, timer, head }. */
        this.waiting = new Map();
        this.count = 0;
        /** The home key's hash, once read. */
        this.keyHash = undefined;
        ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    }

    async fetch(request) {
        const url = new URL(request.url);
        if (url.pathname === "/connect") return this.connect(request, url.searchParams.get("tid"));
        if (url.pathname === "/answer") return this.answer(request, url.searchParams.get("r"));
        if (url.pathname === "/phone") return this.phone(request, url.searchParams.get("rest"), url.searchParams.get("q"));
        return text(404, "Not found");
    }

    async storedHash() {
        if (this.keyHash === undefined) this.keyHash = (await this.ctx.storage.get("keyHash")) ?? null;
        return this.keyHash;
    }

    /** The desktop, connecting. The first to connect with a key owns the home; later ones need the same key. */
    async connect(request, tid) {
        if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return text(426, "expected a WebSocket");
        const key = homeKeyOf(request.headers);
        if (!key) return json(401, { error: "no_key" });
        const hash = await sha256Hex(key);
        const kept = await this.storedHash();
        if (kept && !sameText(kept, hash)) return json(403, { error: "not_yours" });
        if (!kept) {
            await this.ctx.storage.put({ keyHash: hash, createdAt: Date.now() });
            this.keyHash = hash;
        }
        await this.ctx.storage.put({ tid, connectedAt: Date.now() });
        // One desktop per home: a new connection (the same Stash after a network change) replaces the old one.
        for (const old of this.ctx.getWebSockets()) {
            try {
                old.close(4000, "replaced");
            } catch {}
        }
        const [client, server] = Object.values(new WebSocketPair());
        this.ctx.acceptWebSocket(server);
        return new Response(null, { status: 101, webSocket: client });
    }

    /** A phone's request: passed to the desktop, answered when the desktop answers. */
    async phone(request, rest, search) {
        const desktop = this.ctx.getWebSockets()[0];
        if (!desktop) {
            return text(503, "Your Stash at home isn't connected. Open Stash on that computer and check it's online.");
        }
        if (this.waiting.size >= MOST_WAITING) return text(429, "Your Stash at home is busy. Try again in a moment.");
        let body = null;
        if (request.method === "POST") {
            if (Number(request.headers.get("content-length") || 0) > MOST_FORM) return text(413, "that form is too big");
            body = new Uint8Array(await request.arrayBuffer());
        }
        const id = `${(++this.count).toString(36)}${Math.floor(Math.random() * 36 ** 6).toString(36)}`;
        const built = requestMessage({ id, method: request.method, rest, search, headers: request.headers, body });
        if (built.error) return text(built.status, built.error);
        const answered = new Promise((resolve) => {
            const timer = setTimeout(() => {
                this.waiting.delete(id);
                resolve(text(504, "Your Stash at home didn't answer in time."));
            }, ANSWER_START_MS);
            this.waiting.set(id, { resolve, timer, head: request.method === "HEAD" });
        });
        try {
            desktop.send(JSON.stringify(built.message));
        } catch {
            this.settle(id, text(503, "Your Stash at home isn't connected."));
        }
        return answered;
    }

    settle(id, response) {
        const waiting = this.waiting.get(id);
        if (!waiting) return;
        this.waiting.delete(id);
        clearTimeout(waiting.timer);
        waiting.resolve(response);
    }

    /** The desktop's answer to request `rid`: its body streams to the phone as it comes. */
    async answer(request, rid) {
        const key = homeKeyOf(request.headers);
        const kept = await this.storedHash();
        if (!key || !kept || !sameText(kept, await sha256Hex(key))) {
            await request.body?.cancel();
            return json(403, { error: "not_yours" });
        }
        const waiting = this.waiting.get(rid);
        if (!waiting) {
            await request.body?.cancel();
            return json(410, { error: "nobody_waiting" });
        }
        this.waiting.delete(rid);
        clearTimeout(waiting.timer);
        const answer = answerOf(request.headers);
        if (!answer) {
            await request.body?.cancel();
            waiting.resolve(text(502, "Your Stash at home answered something this relay can't read."));
            return json(400, { error: "bad_answer" });
        }
        const headers = new Headers(answer.headers);
        headers.set("X-Content-Type-Options", "nosniff");
        if (waiting.head || !request.body) {
            await request.body?.cancel();
            if (answer.length != null) headers.set("Content-Length", String(answer.length));
            waiting.resolve(new Response(null, { status: answer.status, headers }));
            return new Response(null, { status: 204 });
        }
        const pipe = answer.length != null ? new FixedLengthStream(answer.length) : new TransformStream();
        waiting.resolve(new Response(pipe.readable, { status: answer.status, headers }));
        try {
            await request.body.pipeTo(pipe.writable);
        } catch {
            // The phone stopped listening (skipped, seeked): the desktop's upload ends with it.
            return json(200, { stopped: true });
        }
        return new Response(null, { status: 204 });
    }

    /** `{"t":"fail","id":...}`: the desktop could not answer a request at all. */
    async webSocketMessage(ws, message) {
        if (typeof message !== "string") return;
        let m;
        try {
            m = JSON.parse(message);
        } catch {
            return;
        }
        if (m?.t === "fail" && typeof m.id === "string") {
            this.settle(m.id, text(502, "Your Stash at home couldn't answer that."));
        }
    }

    async webSocketClose() {
        for (const id of [...this.waiting.keys()]) this.settle(id, text(503, "Your Stash at home disconnected."));
    }

    async webSocketError() {
        await this.webSocketClose();
    }
}
