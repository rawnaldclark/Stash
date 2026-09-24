/** Just enough of the Durable Object runtime for src/listen-room.js: storage, alarm, hibernatable sockets. */
import { ListenRoom } from "../src/listen-room.js";

export function fakeCtx() {
    const store = new Map();
    const sockets = [];
    const ctx = {
        store,
        sockets,
        alarm: null,
        storage: {
            async get(key) { return store.has(key) ? structuredClone(store.get(key)) : undefined; },
            async put(key, value) { store.set(key, structuredClone(value)); },
            async deleteAll() { store.clear(); },
            async setAlarm(at) { ctx.alarm = at; },
            async deleteAlarm() { ctx.alarm = null; },
        },
        acceptWebSocket(ws) { sockets.push(ws); },
        getWebSockets() { return sockets.filter((s) => !s.closed); },
    };
    return ctx;
}

export function fakeSocket() {
    let attachment = null;
    return {
        sent: [],
        closed: false,
        closeCode: null,
        send(text) { this.sent.push(JSON.parse(text)); },
        close(code) { this.closed = true; this.closeCode = code; },
        serializeAttachment(value) { attachment = structuredClone(value); },
        deserializeAttachment() { return attachment; },
    };
}

/** A fake `ROOMS` namespace: idFromName is the identity, get() makes one ListenRoom per name. */
export function roomsNamespace(now = () => Date.now()) {
    const rooms = new Map();
    return {
        rooms,
        idFromName: (name) => name,
        get(id) {
            if (!rooms.has(id)) rooms.set(id, new ListenRoom(fakeCtx(), {}, now));
            return rooms.get(id);
        },
    };
}
