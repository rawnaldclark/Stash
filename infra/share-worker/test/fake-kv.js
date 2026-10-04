import { fakeD1 } from "./fake-d1.js";
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
        TRACK_RL: { limit: async () => ({ success: true }) },
        ART_RL: { limit: async () => ({ success: true }) },
        ROOMS: roomsNamespace(),
        COMMUNITY_DB: fakeD1(),
        COMMUNITY_SALT: "test-salt",
        COMMUNITY_WRITE_RL: { limit: async () => ({ success: true }) },
        COMMUNITY_VOTE_RL: { limit: async () => ({ success: true }) },
        COMMUNITY_READ_RL: { limit: async () => ({ success: true }) },
        ...over,
    };
}
