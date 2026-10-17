// Deezer + Apple candidates per song -> one list (Deezer first), for label.mjs.
import { readFileSync, writeFileSync } from "node:fs";
const [out, ...files] = process.argv.slice(2);
const merged = {};
for (const f of files) for (const [qid, cands] of Object.entries(JSON.parse(readFileSync(f, "utf8")))) (merged[qid] ??= []).push(...cands);
writeFileSync(out, JSON.stringify(merged));
const n = Object.values(merged).map((c) => c.length);
console.log(`${n.length} songs, ${n.reduce((a, b) => a + b, 0)} pairs, ${n.filter((x) => !x).length} empty`);
