// Labels (library song, catalog candidates) groups with Claude for the matcher test set.
//   node label.mjs trial <queries.json> <candidates.json> <out.json> <n>   -- n queries, direct calls
//   node label.mjs batch <queries.json> <candidates.json> <batch-id-file>  -- every query, Batch API
//   node label.mjs collect <batch-id-file> <out.json>                      -- fetch batch results
import Anthropic from "@anthropic-ai/sdk";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const KEY_FILE = join(homedir(), ".stash", "anthropic_api_key.txt");
const MODEL = process.env.LABEL_MODEL || "claude-opus-5-5"; // LABEL_MODEL=claude-fable-5-1 for the re-check

const SYSTEM = `You label test data for a music player's song matcher.

The player has a song from the user's library (from Spotify or YouTube Music) and searches a lossless
catalog for the same track. For each catalog candidate, decide whether downloading it would give the
user the recording they have.

Verdicts:
- "match": the same song by the same artist in the same performance. These are still a match:
  remasters and remastered years ("Remastered 2009", "2011 Remaster"), stereo/mono/single/album mixes
  of the same recording, clean/explicit variants, a different album or compilation
  carrying the same recording, small title or artist spelling differences, featured artists listed
  in the title on one side and in the artist field on the other, and a credited lead artist that is
  a subset of the library's artist list.
- "wrong_version": the same song but a different performance or edit the library song is not:
  live, remix (any named remixer), acoustic, unplugged, piano/orchestral version, instrumental,
  a cappella, karaoke, demo, sped up/slowed/nightcore/8D, re-recorded ("Taylor's Version"),
  or a different EDIT of a clearly different length: single/short/radio/edit/extended/album/full
  versions that run noticeably shorter or longer than the library song (roughly 20+ seconds, or 10%+,
  apart). The user wants the cut they know. An edit within a few seconds of the library length is
  the same cut, so it is a "match".
  Exception, YouTube Music library songs: their duration is often a music video's (intro, skit,
  outro), so a length gap ALONE never makes a candidate a wrong version for them. For a YouTube
  library song, the standard studio cut of the same song is a "match" unless the title, version
  field or album names a different performance.
  If the LIBRARY song itself is that version (e.g. its title or album says "Live"), the matching
  version is a "match" and the studio cut is the "wrong_version".
- "wrong_song": a different song, a cover or tribute by another artist, or the right title by an
  unrelated artist.
- "unsure": you cannot tell from the fields given. Use sparingly.

Use the durations: within a few seconds supports the same recording; a large gap (e.g. 30+ s on a
3-4 minute song) suggests a different cut or version. YouTube library titles can carry noise such as
"(Official Video)", "[HD]", "Lyrics" or "Artist - Song" — that noise is not a version.

Answer for every candidate, in the given order, with a one-sentence reason.`;

const SCHEMA = {
  type: "object",
  properties: {
    labels: {
      type: "array",
      items: {
        type: "object",
        properties: {
          cid: { type: "string" },
          verdict: { type: "string", enum: ["match", "wrong_version", "wrong_song", "unsure"] },
          reason: { type: "string" },
        },
        required: ["cid", "verdict", "reason"],
        additionalProperties: false,
      },
    },
  },
  required: ["labels"],
  additionalProperties: false,
};

const fmtDur = (s) => (s > 0 ? `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, "0")}` : "unknown");

function userPrompt(q, cands) {
  const lib = [
    `Library song (${q.source === "YOUTUBE" ? "YouTube Music" : "Spotify"}):`,
    `  title: ${q.title}`,
    `  artist: ${q.artist}`,
    `  album: ${q.album ?? "unknown"}`,
    `  duration: ${fmtDur((q.durationMs ?? 0) / 1000)}`,
  ].join("\n");
  const list = cands.map((c, i) => [
    `${i + 1}. cid=${c.cid}`,
    `   title: ${c.title}${c.version ? `  [version field: ${c.version}]` : ""}`,
    `   artist: ${c.artist}`,
    `   album: ${c.album}`,
    `   duration: ${fmtDur(c.durationSec)}`,
  ].join("\n")).join("\n");
  return `${lib}\n\nCatalog candidates:\n${list}`;
}

function params(q, cands) {
  return {
    model: MODEL,
    max_tokens: 16000,
    system: SYSTEM,
    output_config: { effort: "high", format: { type: "json_schema", schema: SCHEMA } },
    messages: [{ role: "user", content: userPrompt(q, cands) }],
  };
}

function client() {
  if (!existsSync(KEY_FILE)) throw new Error(`no API key file at ${KEY_FILE}`);
  return new Anthropic({ apiKey: readFileSync(KEY_FILE, "utf8").trim() });
}

/** The labels from one response, checked against the candidates asked about; null if unusable. */
function parse(message, cands) {
  if (message.stop_reason !== "end_turn") return { error: `stop_reason ${message.stop_reason}` };
  const text = message.content.filter((b) => b.type === "text").map((b) => b.text).join("");
  let labels;
  try { labels = JSON.parse(text).labels; } catch { return { error: "unparseable" }; }
  const want = cands.map((c) => c.cid);
  if (!Array.isArray(labels) || labels.length !== want.length || labels.some((l, i) => l.cid !== want[i])) {
    return { error: "labels do not line up with candidates" };
  }
  return { labels };
}

const load = (p) => JSON.parse(readFileSync(p, "utf8"));
const [mode, ...args] = process.argv.slice(2);

if (mode === "trial") {
  const [qPath, cPath, out, nArg] = args;
  const cands = load(cPath);
  const queries = load(qPath).filter((q) => cands[q.qid]?.length);
  // Spread the trial over the sample: every k-th query, both sources and hard/easy mixed.
  const n = Number(nArg || 10), k = Math.max(1, Math.floor(queries.length / n));
  const picked = queries.filter((_, i) => i % k === 0).slice(0, n);
  const c = client();
  const results = [];
  let usage = { input: 0, output: 0 };
  for (const q of picked) {
    const msg = await c.messages.create(params(q, cands[q.qid]));
    usage.input += msg.usage.input_tokens; usage.output += msg.usage.output_tokens;
    results.push({ query: q, candidates: cands[q.qid], ...parse(msg, cands[q.qid]) });
  }
  writeFileSync(out, JSON.stringify(results, null, 1));
  const cost = (usage.input * 4 + usage.output * 20) / 1e6;
  console.log(`${results.length} queries labelled; tokens in=${usage.input} out=${usage.output}; ~$${cost.toFixed(2)} (standard price)`);
} else if (mode === "batch") {
  const [qPath, cPath, idFile] = args;
  const cands = load(cPath);
  const requests = load(qPath).filter((q) => cands[q.qid]?.length).map((q) => ({ custom_id: q.qid, params: params(q, cands[q.qid]) }));
  const batch = await client().messages.batches.create({ requests });
  writeFileSync(idFile, batch.id);
  console.log(`batch ${batch.id}: ${requests.length} requests, status ${batch.processing_status}`);
} else if (mode === "batch-ids") {
  // Re-label only the songs in <ids.json> (e.g. with LABEL_MODEL=claude-fable-5-1): node label.mjs batch-ids q.json c.json ids.json batch-id.txt
  const [qPath, cPath, idsPath, idFile] = args;
  const cands = load(cPath);
  const ids = new Set(load(idsPath));
  const requests = load(qPath).filter((q) => ids.has(q.qid) && cands[q.qid]?.length).map((q) => ({ custom_id: q.qid, params: params(q, cands[q.qid]) }));
  const batch = await client().messages.batches.create({ requests });
  writeFileSync(idFile, batch.id);
  console.log(`batch ${batch.id} (${MODEL}): ${requests.length} requests, status ${batch.processing_status}`);
} else if (mode === "collect") {
  const [idFile, qPath, cPath, out] = args;
  const c = client();
  const id = readFileSync(idFile, "utf8").trim();
  const batch = await c.messages.batches.retrieve(id);
  if (batch.processing_status !== "ended") { console.log(`not ended: ${JSON.stringify(batch.request_counts)}`); process.exit(2); }
  const byId = Object.fromEntries(load(qPath).map((q) => [q.qid, q]));
  const cands = load(cPath);
  const results = [];
  for await (const r of await c.messages.batches.results(id)) {
    const q = byId[r.custom_id];
    if (r.result.type !== "succeeded") { results.push({ query: q, candidates: cands[q.qid], error: r.result.type }); continue; }
    results.push({ query: q, candidates: cands[q.qid], ...parse(r.result.message, cands[q.qid]) });
  }
  writeFileSync(out, JSON.stringify(results, null, 1));
  console.log(`${results.length} results, ${results.filter((r) => r.error).length} errors`);
} else if (mode === "relabel") {
  // Re-label one library source in place (direct calls, 8 at a time): node label.mjs relabel labelled.json YOUTUBE
  const [path, source] = args;
  const results = load(path);
  const todo = results.filter((r) => r.query.source === source && r.candidates?.length);
  const c = client();
  let next = 0, cost = 0, failed = 0;
  await Promise.all(Array.from({ length: 8 }, async () => {
    while (next < todo.length) {
      const r = todo[next++];
      try {
        const msg = await c.messages.create(params(r.query, r.candidates));
        cost += (msg.usage.input_tokens * 4 + msg.usage.output_tokens * 20) / 1e6;
        const parsed = parse(msg, r.candidates);
        if (parsed.labels) { r.labels = parsed.labels; delete r.error; } else failed++;
      } catch { failed++; }
    }
  }));
  writeFileSync(path, JSON.stringify(results, null, 1));
  console.log(`relabelled ${todo.length - failed}/${todo.length} ${source} songs; ~$${cost.toFixed(2)}; ${failed} kept their old labels`);
} else {
  console.error("usage: label.mjs trial|batch|collect|relabel ...");
  process.exit(1);
}
