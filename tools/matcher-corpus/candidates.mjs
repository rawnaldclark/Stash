// Searches q.searchArtist/q.searchTitle when set (YouTube rows: the parsed song, as the app searches).
// Deezer public search: top-5 candidates per query, the "artist title" text the app searches with.
import { readFileSync, writeFileSync, existsSync } from "node:fs";
const [qPath, out] = process.argv.slice(2);
const queries = JSON.parse(readFileSync(qPath, "utf8"));
const done = existsSync(out) ? JSON.parse(readFileSync(out, "utf8")) : {};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let n = 0;
for (const q of queries) {
  if (done[q.qid]) continue;
  const url = `https://api.deezer.com/search?limit=5&q=${encodeURIComponent(`${q.searchArtist ?? q.artist} ${q.searchTitle ?? q.title}`.trim())}`;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(15000) });
      const j = await r.json();
      if (j.error) { await sleep(5000); continue; } // quota: wait and retry
      done[q.qid] = (j.data || []).slice(0, 5).map((t) => ({
        cid: `dz:${t.id}`, title: String(t.title_short ?? t.title ?? ""), version: String(t.title_version ?? ""),
        fullTitle: String(t.title ?? ""), artist: String(t.artist?.name ?? ""), album: String(t.album?.title ?? ""),
        durationSec: Number(t.duration) || 0, explicit: Boolean(t.explicit_lyrics),
      }));
      break;
    } catch { await sleep(3000); }
  }
  if (++n % 50 === 0) { writeFileSync(out, JSON.stringify(done)); console.log(`${n} fetched`); }
  await sleep(220);
}
writeFileSync(out, JSON.stringify(done));
const counts = Object.values(done).map((c) => c.length);
console.log(`done: ${Object.keys(done).length} queries, ${counts.reduce((a, b) => a + b, 0)} pairs, ${counts.filter((c) => c === 0).length} with no candidates`);
