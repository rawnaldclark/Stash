import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { call, seed, songPost, KEY_A, KEY_B } from "./community-helpers.js";

test("the poster takes a post down: it's gone everywhere, and still counts toward today", async () => {
    const e = env();
    const { id } = await (await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).json();
    assert.equal((await call(e, "DELETE", `/v1/community/posts/${id}`, { key: KEY_A })).status, 204);
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT removed_by FROM posts").first("removed_by"), "poster"); // restore leaves it down
    assert.equal((await call(e, "GET", `/v1/community/posts/${id}`, { key: KEY_A })).status, 404);
    assert.equal((await (await call(e, "GET", "/v1/community/feed")).json()).posts.length, 0);
    assert.equal((await (await call(e, "GET", "/v1/community/mine", { key: KEY_A })).json()).posts.length, 0);
    assert.equal((await call(e, "DELETE", `/v1/community/posts/${id}`, { key: KEY_A })).status, 404);
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).status, 201);
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).status, 429);
});

test("only the poster can take a post down", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const r = await call(e, "DELETE", "/v1/community/posts/POST0001", { key: KEY_B });
    assert.equal(r.status, 403);
    assert.deepEqual(await r.json(), { error: "not_yours" });
    assert.equal((await call(e, "DELETE", "/v1/community/posts/POST0001")).status, 401);
    assert.equal((await call(e, "GET", "/v1/community/posts/POST0001")).status, 200);
});

test("a missing, expired or removed post is 404 gone, to its poster and to anyone else", async () => {
    const e = env();
    await seed(e, { id: "EXPIRED0", key: KEY_A, expires: Date.now() - 1 });
    await seed(e, { id: "REMOVED0", key: KEY_A, removed: Date.now() });
    for (const id of ["NOSUCHID", "EXPIRED0", "REMOVED0"]) {
        for (const key of [KEY_A, KEY_B]) {
            const r = await call(e, "DELETE", `/v1/community/posts/${id}`, { key });
            assert.equal(r.status, 404, `${id} ${key[0]}`);
            assert.deepEqual(await r.json(), { error: "gone" });
        }
    }
});

test("the poster can take down a post hidden by votes; to anyone else it's gone", async () => {
    const e = env();
    await seed(e, { id: "HIDDEN00", key: KEY_A, down: 3 });
    const other = await call(e, "DELETE", "/v1/community/posts/HIDDEN00", { key: KEY_B });
    assert.equal(other.status, 404);
    assert.deepEqual(await other.json(), { error: "gone" });
    assert.equal((await call(e, "DELETE", "/v1/community/posts/HIDDEN00", { key: KEY_A })).status, 204);
});

test("a post the owner vouched for isn't hidden at any score: to anyone else it's not_yours, not gone", async () => {
    const e = env();
    await seed(e, { id: "VOUCHED0", key: KEY_A, down: 5 });
    await e.COMMUNITY_DB.prepare("UPDATE posts SET vouched = 1").run();
    const other = await call(e, "DELETE", "/v1/community/posts/VOUCHED0", { key: KEY_B });
    assert.equal(other.status, 403);
    assert.deepEqual(await other.json(), { error: "not_yours" });
});
