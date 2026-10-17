// labelled results (label.mjs output) -> the committed test resource matcher/corpus-v<N>.json[.gz]
//   node build-corpus.mjs <labelled.json> <out.json|out.json.gz> [version]   (.gz: compact + gzipped)
import { readFileSync, writeFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
const [inPath, out, versionArg] = process.argv.slice(2);
const version = Number(versionArg || 1);
const results = JSON.parse(readFileSync(inPath, "utf8"));
const items = results.filter((r) => r.labels).map((r) => ({
  qid: r.query.qid,
  source: r.query.source,
  query: { artist: r.query.artist, title: r.query.title, album: r.query.album, durationMs: r.query.durationMs, isrc: r.query.isrc },
  candidates: r.candidates.map((c, i) => ({
    cid: c.cid, title: c.title, version: c.version, artist: c.artist, album: c.album, durationSec: c.durationSec,
    verdict: r.labels[i].verdict, reason: r.labels[i].reason,
    ...(r.labels[i].recheckedFrom ? { recheckedFrom: r.labels[i].recheckedFrom } : {}),
  })),
}));
const SOURCES = {
  1: "Candidates: Deezer public search top 5 for 'artist title' (title_short as title, title_version as version).",
  2: "Candidates: Deezer (dz:) and Apple iTunes (it:) public search top 5 each; YouTube rows searched with the " +
    "YouTubeSongTitle-parsed artist/title, as the app searches. 532 songs (every 'unsure', every song where one recording " +
    "got different verdicts across the catalogs, and a 10% audit sample) re-labelled by claude-fable-5-1; where it " +
    "disagreed its verdict was kept (recheckedFrom holds the first one).",
};
const corpus = {
  version,
  policy: "Labelled 2026-10 by claude-opus-5-5 (effort high) under the app's version rules. match = same song, artist and " +
    "performance (remasters, same-recording mixes, clean/explicit, compilations count); wrong_version = live/remix/" +
    "acoustic/instrumental/sped-up/re-recorded etc. the library song is not, or an edit of clearly different length " +
    "(owner: the cut you know or nothing), except that a YouTube row's length alone never counts (often the video's); " +
    "wrong_song = other song, cover, or unrelated artist. Queries: owner's library (Spotify + YouTube Music rows, raw " +
    "title/artist). " + (SOURCES[version] ?? ""),
  items,
};
writeFileSync(out, out.endsWith(".gz") ? gzipSync(JSON.stringify(corpus)) : JSON.stringify(corpus, null, 1) + "\n");
const v = items.flatMap((i) => i.candidates.map((c) => c.verdict));
console.log(`${items.length} songs, ${v.length} pairs:`, Object.fromEntries(["match", "wrong_version", "wrong_song", "unsure"].map((k) => [k, v.filter((x) => x === k).length])), `; ${results.length - items.length} dropped (errors)`);
