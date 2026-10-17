// Seeded, stratified sample of library tracks -> queries.json
//   node sample.mjs <stash.db> <out.json> <n> [seed] [exclude-queries.json ...]
import { DatabaseSync } from "node:sqlite";
import { readFileSync, writeFileSync } from "node:fs";
const [dbPath, out, nArg, seedArg, ...excludeFiles] = process.argv.slice(2);
const N = Number(nArg || 1000);
const excluded = new Set(excludeFiles.flatMap((f) => JSON.parse(readFileSync(f, "utf8")).map((q) => q.qid)));
const db = new DatabaseSync(dbPath, { readOnly: true });
const rows = db.prepare(`SELECT id, title, artist, album, duration_ms, source, isrc FROM tracks
  WHERE title IS NOT NULL AND title!='' AND artist IS NOT NULL AND artist!=''`).all().filter((r) => !excluded.has(`q${r.id}`));
let seed = Number(seedArg || 20261016); const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 2 ** 32);
const hard = (r) => /[([\]]| [-–—] |remix|live|version|edit|remaster|acoustic|feat|ft\.|mix\b/i.test(r.title)
  || r.title.length <= 4 || /,|&| x | and /i.test(r.artist) || /[^\u0000-\u024f]/.test(r.title + r.artist);
const pick = (pool, k) => { const a = [...pool]; for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a.slice(0, k); };
const sources = ["SPOTIFY", "YOUTUBE"];
const chosen = [];
for (const s of sources) {
  const pool = rows.filter((r) => (r.source === s || (s === "SPOTIFY" && r.source === "BOTH")));
  const h = pool.filter(hard), e = pool.filter((r) => !hard(r));
  const k = Math.floor(N / sources.length);
  chosen.push(...pick(h, Math.round(k * 0.6)), ...pick(e, k - Math.round(k * 0.6)));
}
const seen = new Set();
const queries = chosen.filter((r) => { const key = `${r.artist}|${r.title}`.toLowerCase(); if (seen.has(key)) return false; seen.add(key); return true; })
  .map((r) => ({ qid: `q${r.id}`, source: r.source, artist: r.artist, title: r.title, album: r.album || null, durationMs: r.duration_ms || null, isrc: r.isrc || null }));
writeFileSync(out, JSON.stringify(queries, null, 1));
console.log(`${queries.length} queries (${queries.filter(hard).length} hard)`);
