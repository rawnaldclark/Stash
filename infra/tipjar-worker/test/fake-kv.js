/** Just enough of Workers KV for the tip jar: get (text, "json", "arrayBuffer"), put with options and metadata, delete, list. */
export function fakeKV(initial = {}) {
    const map = new Map(Object.entries(initial).map(([k, v]) => [k, { value: v, opts: {} }]));
    return {
        map,
        puts: [],
        async get(key, type) {
            if (!map.has(key)) return null;
            const v = map.get(key).value;
            const t = typeof type === "object" ? type?.type : type;
            if (t === "json") return JSON.parse(v);
            if (t === "arrayBuffer") return new TextEncoder().encode(v).buffer;
            return v;
        },
        async put(key, value, opts = {}) {
            if (typeof value !== "string") throw new Error("fake KV takes strings");
            if (opts.metadata && JSON.stringify(opts.metadata).length > 1024) throw new Error("KV metadata over 1024 bytes");
            this.puts.push(key);
            map.set(key, { value, opts });
        },
        async delete(key) {
            map.delete(key);
        },
        async list({ prefix = "", limit = 1000, cursor } = {}) {
            const names = [...map.keys()].filter((k) => k.startsWith(prefix)).sort();
            const start = cursor ? Number(cursor) : 0;
            const page = names.slice(start, start + limit);
            const done = start + limit >= names.length;
            return { keys: page.map((name) => ({ name, metadata: map.get(name).opts.metadata })), list_complete: done, cursor: done ? undefined : String(start + limit) };
        },
        json(key) {
            return map.has(key) ? JSON.parse(map.get(key).value) : null;
        },
    };
}

export const env = (over = {}) => ({ STASH_KV: fakeKV(), ACCESS_KV: fakeKV(), KOFI_VERIFICATION_TOKEN: "secret", ...over });

/** A Ko-fi webhook post, form-encoded like the real thing. */
export function kofiPost(fields, url = "https://stash-tipjar.example.workers.dev/") {
    const body = new URLSearchParams({ data: JSON.stringify({ verification_token: "secret", ...fields }) });
    return new Request(url, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body });
}

export const donation = (over = {}) => ({
    message_id: "m1",
    timestamp: "2026-10-04T12:00:00Z",
    type: "Donation",
    is_public: true,
    from_name: "Ann",
    message: "Love the app",
    amount: "5.00",
    url: "https://ko-fi.com/Home/CoffeeShop?txid=tx-1",
    email: "Ann@Example.org",
    currency: "USD",
    is_subscription_payment: false,
    is_first_subscription_payment: false,
    kofi_transaction_id: "tx-1",
    ...over,
});

/** The month's goal entries the tip jar wrote: [{ key, ...metadata }]. */
export function goalEntries(kv, month) {
    return [...kv.map.entries()].filter(([k]) => k.startsWith(`goal:${month}:`)).map(([key, e]) => ({ key, ...e.opts.metadata }));
}
