// Labels YouTube Music library rows with the song they really are (artist, title, version), for a
// YouTube title-cleaner test set.
//   node label-youtube-titles.mjs extract <stash.db> <rows.json>
//   node label-youtube-titles.mjs batch <rows.json> <batch-id-file>
//   node label-youtube-titles.mjs collect <batch-id-file> <rows.json> <out.json>
import Anthropic from "@anthropic-ai/sdk";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const KEY_FILE = join(homedir(), ".stash", "anthropic_api_key.txt");
const MODEL = "claude-opus-5-5";
const PER_REQUEST = 20;

const SYSTEM = `You clean up song identities for a music player's test data.

Each row is a song in a user's library that came from YouTube Music. Its "title" and "artist"
fields are whatever YouTube had: often a video title ("Artist - Song (Official Video)", "Song [HD]",
"Song | Audiotree Live", "ARTIST - Song (Lyrics)") and an uploader or channel name ("ArtistVEVO",
"Audiotree", "Topic" channels, label channels). Some rows are already clean.

For each row give the song as a music catalog (Spotify, Qobuz) would list it:
- artist: the performing artist(s), lead first, as the catalog spells them. Not the channel or
  label, unless the channel is the artist.
- title: the song title only, without the artist, video/upload noise, or the version.
- version: the recording's version when the row names one (e.g. "Live", "Audiotree Live",
  "Acoustic", "2018 Remix", "Radio Edit", "Remastered 2011"), else "". Video noise is not a version:
  "Official Video", "Official Audio", "Lyric Video", "Visualizer", "HD", "4K", "MV", "Lyrics".
- featured: featured artists named in the row, else [].
- is_song: false for things that are not a song recording (podcasts, interviews, trailers, full
  albums or mixes over an hour, ASMR, sound effects, compilations of many songs).
- from_text_only: true when the row's own text gives artist and title without outside knowledge;
  false when you had to use what you know (e.g. the channel is a label, the artist is misspelled,
  or only the song title is given).
- noise: the pieces you dropped, verbatim.

Answer for every row, in the given order, keyed by its id.`;

const SCHEMA = {
  type: "object",
  properties: {
    rows: {
      type: "array",
      items: {
        type: "object",
        properties: {
          id: { type: "string" },
          artist: { type: "string" },
          title: { type: "string" },
          version: { type: "string" },
          featured: { type: "array", items: { type: "string" } },
          is_song: { type: "boolean" },
          from_text_only: { type: "boolean" },
          noise: { type: "array", items: { type: "string" } },
        },
        required: ["id", "artist", "title", "version", "featured", "is_song", "from_text_only", "noise"],
        additionalProperties: false,
      },
    },
  },
  required: ["rows"],
  additionalProperties: false,
};

const load = (p) => JSON.parse(readFileSync(p, "utf8"));
const client = () => {
  if (!existsSync(KEY_FILE)) throw new Error(`no API key file at ${KEY_FILE}`);
  return new Anthropic({ apiKey: readFileSync(KEY_FILE, "utf8").trim() });
};
const fmtDur = (ms) => (ms > 0 ? `${Math.floor(ms / 60000)}:${String(Math.round((ms % 60000) / 1000)).padStart(2, "0")}` : "unknown");

function params(chunk) {
  const text = chunk.map((r) => `id=${r.id}\n  title: ${r.title}\n  artist: ${r.artist}\n  album: ${r.album || "unknown"}\n  duration: ${fmtDur(r.durationMs)}`).join("\n");
  return {
    model: MODEL,
    max_tokens: 16000,
    system: SYSTEM,
    output_config: { effort: "high", format: { type: "json_schema", schema: SCHEMA } },
    messages: [{ role: "user", content: `Rows:\n${text}` }],
  };
}

const chunks = (rows) => Array.from({ length: Math.ceil(rows.length / PER_REQUEST) }, (_, i) => rows.slice(i * PER_REQUEST, (i + 1) * PER_REQUEST));
const [mode, ...args] = process.argv.slice(2);

if (mode === "extract") {
  const { DatabaseSync } = await import("node:sqlite");
  const [dbPath, out] = args;
  const db = new DatabaseSync(dbPath, { readOnly: true });
  const rows = db.prepare(`SELECT id, title, artist, album, duration_ms FROM tracks WHERE source='YOUTUBE'
    AND title IS NOT NULL AND title!='' ORDER BY id`).all()
    .map((r) => ({ id: `y${r.id}`, title: r.title, artist: r.artist || "", album: r.album || "", durationMs: r.duration_ms || 0 }));
  writeFileSync(out, JSON.stringify(rows));
  console.log(`${rows.length} YouTube rows`);
} else if (mode === "trial") {
  // One chunk of PER_REQUEST rows spread over the library, direct call: node label-youtube-titles.mjs trial <rows.json>
  const rows = load(args[0]);
  const step = Math.floor(rows.length / PER_REQUEST);
  const chunk = Array.from({ length: PER_REQUEST }, (_, i) => rows[i * step]);
  const msg = await client().messages.create(params(chunk));
  const out = JSON.parse(msg.content.filter((b) => b.type === "text").map((b) => b.text).join("")).rows;
  for (const [i, r] of chunk.entries()) {
    const l = out.find((x) => x.id === r.id) || {};
    console.log(`${r.artist} | ${r.title}\n   -> ${l.artist} | ${l.title}${l.version ? ` [${l.version}]` : ""}${l.featured?.length ? ` ft. ${l.featured.join(", ")}` : ""}${l.is_song === false ? " NOT A SONG" : ""}${l.from_text_only === false ? " (knowledge)" : ""}`);
  }
  console.log(`tokens in=${msg.usage.input_tokens} out=${msg.usage.output_tokens} ~$${((msg.usage.input_tokens * 4 + msg.usage.output_tokens * 20) / 1e6).toFixed(3)}`);
} else if (mode === "batch") {
  const [rowsPath, idFile] = args;
  const requests = chunks(load(rowsPath)).map((chunk, i) => ({ custom_id: `c${i}`, params: params(chunk) }));
  const batch = await client().messages.batches.create({ requests });
  writeFileSync(idFile, batch.id);
  console.log(`batch ${batch.id}: ${requests.length} requests, status ${batch.processing_status}`);
} else if (mode === "collect") {
  const [idFile, rowsPath, out] = args;
  const c = client();
  const id = readFileSync(idFile, "utf8").trim();
  const batch = await c.messages.batches.retrieve(id);
  if (batch.processing_status !== "ended") { console.log(`not ended: ${JSON.stringify(batch.request_counts)}`); process.exit(2); }
  const all = chunks(load(rowsPath));
  const labelled = [];
  let bad = 0;
  for await (const r of await c.messages.batches.results(id)) {
    const chunk = all[Number(r.custom_id.slice(1))];
    if (r.result.type !== "succeeded" || r.result.message.stop_reason !== "end_turn") { bad++; continue; }
    let rows;
    try { rows = JSON.parse(r.result.message.content.filter((b) => b.type === "text").map((b) => b.text).join("")).rows; } catch { bad++; continue; }
    const byId = Object.fromEntries(rows.map((x) => [x.id, x]));
    for (const row of chunk) if (byId[row.id]) labelled.push({ ...row, label: byId[row.id] });
  }
  writeFileSync(out, JSON.stringify(labelled));
  console.log(`${labelled.length} rows labelled, ${bad} chunks failed`);
} else {
  console.error("usage: label-youtube-titles.mjs extract|batch|collect ...");
  process.exit(1);
}
