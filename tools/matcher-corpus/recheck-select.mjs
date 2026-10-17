// Picks songs whose labels deserve a second opinion: any "unsure", the same recording in both
// catalogs labelled differently, and a seeded 10% audit sample. node recheck-select.mjs labelled.json ids.json
import { readFileSync, writeFileSync } from "node:fs";
const [inPath, out] = process.argv.slice(2);
const results = JSON.parse(readFileSync(inPath, "utf8")).filter((r) => r.labels);
const key = (c) => `${c.title}|${c.artist}`.normalize("NFD").replace(/\p{Mn}+/gu, "").toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
let seed = 18; const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 2 ** 32);
const why = {};
for (const r of results) {
  const reasons = [];
  if (r.labels.some((l) => l.verdict === "unsure")) reasons.push("unsure");
  const byRec = {};
  r.candidates.forEach((c, i) => { const k = `${key(c)}|${Math.round(c.durationSec / 3)}`; (byRec[k] ??= new Set()).add(r.labels[i].verdict); });
  if (Object.values(byRec).some((v) => v.size > 1)) reasons.push("catalogs disagree");
  if (rnd() < 0.10) reasons.push("audit");
  if (reasons.length) why[r.query.qid] = reasons;
}
writeFileSync(out, JSON.stringify(Object.keys(why)));
const count = (k) => Object.values(why).filter((v) => v.includes(k)).length;
console.log(`${Object.keys(why).length} of ${results.length} songs: unsure ${count("unsure")}, catalogs disagree ${count("catalogs disagree")}, audit ${count("audit")}`);
