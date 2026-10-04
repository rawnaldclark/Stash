import { test } from "node:test";
import assert from "node:assert/strict";
import { limitKey, readForm } from "../http.js";

/** A form body sent in chunks with no Content-Length (chunked), counting how many chunks were read. */
function chunked(chunks) {
    let pulled = 0;
    const body = new ReadableStream({
        pull(controller) {
            if (pulled >= chunks.length) return controller.close();
            controller.enqueue(new TextEncoder().encode(chunks[pulled++]));
        },
    });
    const request = new Request("https://stashfm.app/access", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body, duplex: "half" });
    return { request, pulled: () => pulled };
}

test("a chunked body is read only up to the 4 KB cap, then refused", async () => {
    const { request, pulled } = chunked(Array.from({ length: 1000 }, () => "x".repeat(1024)));
    assert.equal(await readForm(request), null);
    assert.ok(pulled() <= 6, `read ${pulled()} chunks of 1 KB`);
});

test("a small chunked form is read whole", async () => {
    const { request } = chunked(["email=a%40", "example.com&co", "de=123456"]);
    assert.deepEqual(await readForm(request), { email: "a@example.com", code: "123456" });
});

test("a declared Content-Length over the cap is refused without reading", async () => {
    const request = new Request("https://stashfm.app/access", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", "content-length": "99999" }, body: "email=a" });
    assert.equal(await readForm(request), null);
});

test("limitKey: IPv4 as is, IPv6 by its /48", () => {
    assert.equal(limitKey("203.0.113.7"), "203.0.113.7");
    assert.equal(limitKey("2001:db8:1:2:3:4:5:6"), "2001:db8:1::/48");
    assert.equal(limitKey("2001:db8:1::9"), "2001:db8:1::/48");
    assert.equal(limitKey("2001:DB8:0001:ffff::1"), "2001:db8:1::/48");
    assert.equal(limitKey("::1"), "0:0:0::/48");
    assert.equal(limitKey("::ffff:203.0.113.7"), "::ffff:203.0.113.7");
});
