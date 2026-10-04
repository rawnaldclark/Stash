/** Just enough of Workers KV for the tip jar: get (text, "json", "arrayBuffer"), put with options, delete. */
export function fakeKV(initial = {}) {
    const map = new Map(Object.entries(initial).map(([k, v]) => [k, { value: v, opts: {} }]));
    return {
        map,
        puts: [],
        async get(key, type) {
            if (!map.has(key)) return null;
            const v = map.get(key).value;
            if (type === "json") return JSON.parse(v);
            if (type === "arrayBuffer") return new TextEncoder().encode(v).buffer;
            return v;
        },
        async put(key, value, opts = {}) {
            this.puts.push(key);
            map.set(key, { value, opts });
        },
        async delete(key) { map.delete(key); },
    };
}

export const env = (over = {}) => ({ STASH_KV: fakeKV(), KOFI_VERIFICATION_TOKEN: "secret", ...over });

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
