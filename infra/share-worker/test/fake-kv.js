import { roomsNamespace } from "./fake-room.js";

/** Just enough of Workers KV for src/store.js: get(key, "json"), put(key, value, opts), delete(key). */
export function fakeKV() {
    const map = new Map();
    return {
        map,
        async get(key, type) {
            const v = map.has(key) ? map.get(key).value : null;
            return v !== null && type === "json" ? JSON.parse(v) : v;
        },
        async put(key, value, opts = {}) { map.set(key, { value, opts }); },
        async delete(key) { map.delete(key); },
    };
}

export function env(over = {}) {
    return {
        SHARE_KV: fakeKV(),
        CREATE_RL: { limit: async () => ({ success: true }) },
        WRITE_RL: { limit: async () => ({ success: true }) },
        ROOM_RL: { limit: async () => ({ success: true }) },
        JOIN_RL: { limit: async () => ({ success: true }) },
        ROOMS: roomsNamespace(),
        ...over,
    };
}
