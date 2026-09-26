/**
 * Stash Community posts (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2): the pure
 * parts, turning a request into what is stored and a stored row into what phones see.
 */
import { allowedCover, cleanTrack } from "./validate.js";

export const MAX_POST_TRACKS = 500;
const KINDS = new Set(["playlist", "mix", "song"]);
const text = (v, max) => (typeof v === "string" && v.trim() && v.trim().length <= max ? v.trim() : null);

/** One song, cleaned like a Listen Together song, without `by` (that's a room member id). */
function track(t) {
    const c = cleanTrack(t);
    if (c) delete c.by;
    return c;
}

/**
 * `{kind, name, title, body}` → `{kind, name, title, body, summary, count}`, or null when anything is off.
 * A bad song fails the whole post, as a bad track fails a mix.
 */
export function cleanPost(input) {
    if (!input || typeof input !== "object" || !KINDS.has(input.kind)) return null;
    const name = text(input.name, 40);
    if (!name) return null;
    if (input.kind === "song") {
        const t = track(input.body?.track);
        if (!t) return null;
        // Titled by its own song, not by the request, so the list can't show one song and play another.
        // The replace drops the half of an emoji that the cut can leave at the end.
        const songTitle = t.t.trim().slice(0, 100).replace(/[\uD800-\uDBFF]$/, "");
        if (!songTitle) return null;
        return { kind: "song", name, title: songTitle, body: { track: t }, summary: { ...(t.art ? { art: t.art } : {}), artist: t.a }, count: 1 };
    }
    const title = text(input.title, 100);
    if (!title) return null;
    const raw = input.body?.tracks;
    if (!Array.isArray(raw) || raw.length < 1 || raw.length > MAX_POST_TRACKS) return null;
    const tracks = raw.map(track);
    if (tracks.includes(null)) return null;
    // Cut to 4 first: each check parses a URL, and a body can hold thousands of covers.
    const covers = (Array.isArray(input.body.covers) ? input.body.covers.slice(0, 4) : [])
        .filter((c) => typeof c === "string" && c.length <= 1000 && allowedCover(c));
    return { kind: input.kind, name, title, body: { covers, tracks }, summary: { covers }, count: tracks.length };
}

/**
 * A `posts` row (with `my_vote` from the voter join) → the public PostSummary. [me] is the caller's poster
 * id, or null without a key. `myVote` and `mine` appear only when [me] is set. The poster id itself never leaves.
 */
export function summaryOf(row, me) {
    const s = JSON.parse(row.summary);
    return {
        // `name`, not `by`: in a SharedTrack `by` is a Listen Together member id.
        id: row.id, kind: row.kind, title: row.title, name: row.poster_name, count: row.track_count,
        ...(s.covers ? { covers: s.covers } : {}),
        ...(s.art ? { art: s.art } : {}),
        ...(s.artist ? { artist: s.artist } : {}),
        createdAt: row.created_at, up: row.up, down: row.down,
        ...(me ? { myVote: row.my_vote ?? 0, mine: row.poster === me } : {}),
    };
}
