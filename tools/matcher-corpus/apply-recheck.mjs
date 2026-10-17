// Folds a second model's labels into the first's: where they differ, the second (Fable 5.1) wins.
// Reports agreement on the audit songs (a random 10%), i.e. how often the first labeller is wrong.
//   node apply-recheck.mjs labelled.json labelled-recheck.json recheck-ids.json out.json
import { readFileSync, writeFileSync } from "node:fs";
const [basePath, recheckPath, idsPath, out] = process.argv.slice(2);
const base = JSON.parse(readFileSync(basePath, "utf8"));
const recheck = Object.fromEntries(JSON.parse(readFileSync(recheckPath, "utf8")).filter((r) => r.labels).map((r) => [r.query.qid, r]));
let pairs = 0, same = 0, songs = 0, changedSongs = 0;
const flips = {};
for (const r of base) {
  const second = recheck[r.query.qid];
  if (!r.labels || !second) continue;
  songs++;
  let changed = false;
  r.labels = r.labels.map((l, i) => {
    const s = second.labels[i];
    pairs++;
    if (s.verdict === l.verdict) { same++; return l; }
    changed = true;
    const k = `${l.verdict} -> ${s.verdict}`; flips[k] = (flips[k] || 0) + 1;
    return { ...s, recheckedFrom: l.verdict };
  });
  if (changed) changedSongs++;
}
writeFileSync(out, JSON.stringify(base));
console.log(`re-checked ${songs} songs / ${pairs} pairs: ${(100 * same / pairs).toFixed(1)}% pair agreement; ${changedSongs} songs changed`);
console.log(flips);
