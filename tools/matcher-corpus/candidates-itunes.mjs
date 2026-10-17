// Searches q.searchArtist/q.searchTitle when set (YouTube rows: the parsed song, as the app searches).
// Apple iTunes Search API: top-5 song candidates per query (a second catalog next to Deezer).
// Apple allows ~20 calls a minute, so this paces at one call per 3.2 s and resumes from <out>.
//   node candidates-itunes.mjs <queries.json> <out.json>
import { readFileSync, writeFileSync, existsSync } from "node:fs";
const [qPath, out] = process.argv.slice(2);
const queries = JSON.parse(readFileSync(qPath, "utf8"));
const done = existsSync(out) ? JSON.parse(readFileSync(out, "utf8")) : {};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let n = 0;
for (const q of queries) {
  if (done[q.qid]) continue;
  const url = `https://itunes.apple.com/search?media=music&entity=song&limit=5&term=${encodeURIComponent(`${q.searchArtist ?? q.artist} ${q.searchTitle ?? q.title}`.trim())}`;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
      if (r.status === 403 || r.status === 429) { await sleep(60000); continue; } // throttled: back off a minute
      const j = await r.json();
      done[q.qid] = (j.results || []).filter((t) => t.kind === "song").slice(0, 5).map((t) => ({
        cid: `it:${t.trackId}`, title: String(t.trackName ?? ""), version: "", fullTitle: String(t.trackName ?? ""),
        artist: String(t.artistName ?? ""), album: String(t.collectionName ?? ""),
        durationSec: Math.round((Number(t.trackTimeMillis) || 0) / 1000), explicit: t.trackExplicitness === "explicit",
      }));
      break;
    } catch { await sleep(5000); }
  }
  if (++n % 25 === 0) { writeFileSync(out, JSON.stringify(done)); console.log(`${n} fetched`); }
  await sleep(3200);
}
writeFileSync(out, JSON.stringify(done));
const counts = Object.values(done).map((c) => c.length);
console.log(`done: ${Object.keys(done).length} queries, ${counts.reduce((a, b) => a + b, 0)} pairs, ${counts.filter((c) => c === 0).length} with no candidates`);
