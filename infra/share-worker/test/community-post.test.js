import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanPost, summaryOf, MAX_POST_TRACKS } from "../src/community-post.js";

const song = { t: "garden", a: "Death Plus", yt: "9Vz-MkbnSg4", art: "https://i.ytimg.com/vi/9Vz-MkbnSg4/maxresdefault.jpg", by: "M1" };
const playlist = (tracks = [song], covers = ["https://i.scdn.co/image/a"]) =>
    ({ kind: "playlist", name: "Maya", title: "sad boy hours", body: { covers, tracks } });

test("a song post keeps the cleaned track without its Listen Together adder", () => {
    const p = cleanPost({ kind: "song", name: " Sam ", title: "garden", body: { track: song } });
    assert.equal(p.name, "Sam");
    assert.equal(p.count, 1);
    assert.equal(p.body.track.by, undefined);
    assert.equal(p.body.track.art, song.art);
    assert.deepEqual(p.summary, { art: song.art, artist: "Death Plus" });
});

test("a song post is titled by its song, not the request, cut to 100 characters", () => {
    const post = (track) => cleanPost({ kind: "song", name: "Sam", title: "something else", body: { track } });
    assert.equal(post(song).title, "garden");
    assert.equal(post({ ...song, t: "x".repeat(100) + "y" }).title, "x".repeat(100));
    assert.equal(post({ ...song, t: "a".repeat(99) + "😀" }).title, "a".repeat(99), "the cut doesn't leave half an emoji");
    assert.equal(post({ ...song, t: "\ud83d" }), null, "a title is never empty");
});

test("a playlist post keeps allowed covers and cleaned tracks, and summarises the covers", () => {
    const p = cleanPost(playlist([song, { t: "heart", a: "Lil Tracy", art: "https://evil.example/x.jpg" }], [
        "https://i.scdn.co/image/a", "https://evil.example/log.gif", "https://lh3.googleusercontent.com/b",
    ]));
    assert.equal(p.kind, "playlist");
    assert.equal(p.count, 2);
    assert.deepEqual(p.body.covers, ["https://i.scdn.co/image/a", "https://lh3.googleusercontent.com/b"]);
    assert.deepEqual(p.summary, { covers: p.body.covers });
    assert.equal(p.body.tracks[1].art, undefined, "an art link off COVER_HOSTS is dropped");
    assert.ok(p.body.tracks.every((t) => t.by === undefined));
    assert.equal(cleanPost(playlist([song], Array(5).fill("https://i.scdn.co/image/a"))).body.covers.length, 4);
    assert.deepEqual(cleanPost(playlist([song], ["", "", "", "", "https://i.scdn.co/image/a"])).body.covers, []);
});

test("bad requests are null", () => {
    const bad = [
        null,
        { ...playlist(), kind: "album" },
        { ...playlist(), name: "" },
        { ...playlist(), name: "x".repeat(41) },
        { ...playlist(), title: "" },
        { ...playlist(), title: "x".repeat(101) },
        playlist([]),
        playlist(Array.from({ length: MAX_POST_TRACKS + 1 }, () => song)),
        playlist([{ t: "", a: "A" }]),
        { kind: "song", name: "Sam", title: "garden", body: {} },
    ];
    for (const b of bad) assert.equal(cleanPost(b), null, JSON.stringify(b)?.slice(0, 80));
    assert.ok(cleanPost(playlist(Array.from({ length: MAX_POST_TRACKS }, () => song))), "500 songs is allowed");
    assert.ok(cleanPost({ ...playlist(), name: "x".repeat(40), title: "x".repeat(100) }), "40 and 100 are allowed");
});

test("a mix is a playlist-shaped post with kind mix", () => {
    assert.equal(cleanPost({ ...playlist(), kind: "mix" }).kind, "mix");
});

test("summaryOf exposes the public fields only, and myVote/mine only when asked by a key", () => {
    const r = {
        id: "AAAAAAAA", kind: "song", title: "garden", poster_name: "Sam", poster: "p".repeat(64),
        summary: JSON.stringify({ art: "https://i.ytimg.com/x", artist: "Death Plus" }), track_count: 1,
        created_at: 5, up: 3, down: 1, my_vote: 1,
    };
    assert.deepEqual(summaryOf(r, null), {
        id: "AAAAAAAA", kind: "song", title: "garden", name: "Sam", count: 1,
        art: "https://i.ytimg.com/x", artist: "Death Plus", createdAt: 5, up: 3, down: 1,
    });
    assert.deepEqual(summaryOf(r, "p".repeat(64)), { ...summaryOf(r, null), myVote: 1, mine: true });
    assert.equal(summaryOf({ ...r, my_vote: null }, "q".repeat(64)).myVote, 0);
    assert.equal(summaryOf({ ...r, my_vote: null }, "q".repeat(64)).mine, false);
});
