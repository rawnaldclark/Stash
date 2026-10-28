/**
 * The phone against the web, on the phone's own taste and plays: a debug build writes what Your record's mixes were made from
 * and what they came to (`files/record-mixes.json`, RecordMixes' dump: the taste as the record shows it, the library's songs,
 * this week's stacked plays, `now`, the local day, Home's picks, every mix's item keys and song keys, and the Mixes tab's Top
 * artists, Top songs and "Not in your Stash yet"). This runs stash-player's own `historyMixes`, `weekMix` and `homePicks` over the
 * same and says where the two differ: the picks, and each mix's id, title, line, item keys and first 10 song keys; and years.ts's
 * `topSongs` and `notInLibrary` and mixes.ts's `artistMix` as routes/spotify/+page.svelte uses them (the first 10 artists, each
 * opening their mix or not; the top 10 songs; the lost favourites minus the library), in full.
 *
 *   adb -s <rig> exec-out run-as com.stash.app.debug cat files/record-mixes.json > build/record-mixes.json
 *   PLAYER=../stash-web/player-upi
 *   NODE_PATH=$PLAYER/node_modules $PLAYER/node_modules/.bin/esbuild feature/record/scripts/mix-device-check.ts --bundle --platform=node \
 *     --format=esm --alias:@player=$PLAYER/src/lib --outfile=build/mix-device-check.mjs
 *   node build/mix-device-check.mjs build/record-mixes.json
 */
import { readFileSync } from 'node:fs';
import type { LibraryItem } from '@player/api';
import { descriptorKey } from '@player/identity';
import { artistMix, artistSongs, firstSongs, haveIndex, historyMixes, homePicks, MIX_MIN, MIX_SIZE, weekMix, type HistoryMix } from '@player/spotify/mixes';
import type { ArchivePlay } from '@player/spotify/plays';
import { songKey, type Taste } from '@player/spotify/taste';
import { notInLibrary, statsOf, topSongs } from '@player/ui/spotify/years';

type AppMix = { id: string; title: string; sub: string; songKeys: string[]; items: string[] };
type Dump = {
	now: number;
	day: number;
	taste: Taste;
	have: { title: string; artist: string; album?: string; isrc?: string; durationMs?: number }[];
	rows: [string, string, string, string | null, string | null, number, number, number][];
	picks: string[];
	mixes: AppMix[];
	week: AppMix | null;
	artists?: { key: string; name: string; mixId: string | null }[];
	tops?: string[];
	lost?: string[];
};

const file = process.argv[2];
if (!file) throw new Error('usage: node mix-device-check.mjs <record-mixes.json>');
const d = JSON.parse(readFileSync(file, 'utf8')) as Dump;
const t = d.taste;
const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

const have = haveIndex(
	d.have.map((l) => ({ title: l.title, artist: l.artist, ...(l.album ? { album: l.album } : {}), ...(l.isrc ? { isrc: l.isrc } : {}), ...(l.durationMs ? { durationMs: l.durationMs } : {}), key: descriptorKey(l), refs: {}, addedAt: 0 }) as LibraryItem)
);
const rows: ArchivePlay[] = d.rows.map(([id, tt, a, al, sid, s, ms, f]) => ({ id, t: tt, a, ...(al ? { al } : {}), ...(sid ? { sid } : {}), s, ms, f }));
// IndexedDB's index on s: by start, then by id
const between = async (from: number, to: number) => rows.filter((p) => p.s >= from && p.s < to).sort((x, y) => x.s - y.s || cmp(x.id, y.id));

const all = historyMixes(t, { have });
const week = await weekMix({ between }, t, d.now, have);
const picks = homePicks(all, week, d.day).map((m) => m.id);

/** The song keys a web mix plays, by mixes.ts's own rule (`playable` over the list each mix is made from). */
const stats = new Map(t.songs.map((s) => [s.key, s]));
const playable = (keys: readonly string[]) => {
	const out: string[] = [];
	for (const k of keys) {
		const s = stats.get(k);
		if (!s || s.avoid) continue;
		out.push(k);
		if (out.length >= MIX_SIZE) break;
	}
	return out;
};
const stayed = new Set(playable(t.constants).length >= MIX_MIN ? playable(t.constants) : []);
function songKeysOf(m: HistoryMix): string[] {
	const [kind, rest] = [m.kind, m.id.slice(m.id.indexOf(':') + 1)];
	if (kind === 'era') return playable(t.eras.find((e) => `era:${e.start}` === m.id)!.songs);
	if (kind === 'constants') return playable(t.constants);
	if (kind === 'lost') return playable(t.lost.filter((k) => !stayed.has(k)));
	if (kind === 'repeat') return playable(t.obsessions.map((o) => o.key));
	if (kind === 'started') return playable(firstSongs(t).map((s) => s.key));
	if (kind === 'year') return playable(t.yearTops!.find((y) => `${y.year}` === rest)!.songs);
	if (kind === 'artist') return artistSongs(t, rest, MIX_SIZE).map((s) => s.key);
	return m.items.map((i) => songKey(i.title, i.artist));
}

let differ = 0;
const say = (ok: boolean, what: string) => {
	if (!ok) differ++;
	console.log(`${ok ? 'same  ' : 'DIFFER'} ${what}`);
};
console.log(`now ${new Date(d.now).toISOString()}, local day ${d.day}; taste of ${t.songs.length} songs, ${d.have.length} library songs, ${rows.length} plays this week`);
say(JSON.stringify(picks) === JSON.stringify(d.picks), `Home's picks: web ${JSON.stringify(picks)} / app ${JSON.stringify(d.picks)}`);
say(all.length === d.mixes.length, `mixes: web ${all.length} / app ${d.mixes.length}`);
const both = [...all.map((m) => [m, d.mixes.find((x) => x.id === m.id)] as const), ...(week || d.week ? [[week, d.week] as const] : [])];
for (const [w, a] of both) {
	if (!w || !a) {
		say(false, `${w?.id ?? a?.id}: only on the ${w ? 'web' : 'app'}`);
		continue;
	}
	const keys = songKeysOf(w);
	const same =
		w.title === a.title &&
		w.sub === a.sub &&
		JSON.stringify(w.items.map((i) => i.key)) === JSON.stringify(a.items) &&
		JSON.stringify(keys.slice(0, 10)) === JSON.stringify(a.songKeys.slice(0, 10)) &&
		keys.length === w.items.length;
	say(same, `${w.id} "${w.title}" · ${w.sub} · ${w.items.length} songs; first song keys ${JSON.stringify(keys.slice(0, 3))}…`);
	if (!same) console.log(`   app: "${a.title}" · ${a.sub} · ${a.items.length} songs; first song keys ${JSON.stringify(a.songKeys.slice(0, 3))}…`);
}
// The rest of the Mixes tab, as the page makes it (its <script>: artists = topArtists.slice(0, 10), artistMix(...)?.id,
// topSongs(taste, 10), notInLibrary(taste.lost, stats, [...likes, ...playlists' songs]))
if (d.artists && d.tops && d.lost) {
	const library = d.have.map((l) => ({ title: l.title, artist: l.artist, ...(l.isrc ? { isrc: l.isrc } : {}), key: descriptorKey(l) }));
	const artists = t.topArtists.slice(0, 10).map((a) => ({ key: a.key, name: a.name, mixId: artistMix(t, a)?.id ?? null }));
	const tops = topSongs(t, 10).map((s) => s.key);
	const lost = notInLibrary(t.lost, statsOf(t), library).map((s) => s.key);
	say(JSON.stringify(artists) === JSON.stringify(d.artists), `Top artists: web ${artists.map((a) => `${a.name}${a.mixId ? ' (opens)' : ' (plays)'}`).join(', ')}`);
	if (JSON.stringify(artists) !== JSON.stringify(d.artists)) console.log(`   app: ${d.artists.map((a) => `${a.name}${a.mixId ? ' (opens)' : ' (plays)'}`).join(', ')}`);
	say(JSON.stringify(tops) === JSON.stringify(d.tops), `Top songs: web ${JSON.stringify(tops)}`);
	if (JSON.stringify(tops) !== JSON.stringify(d.tops)) console.log(`   app: ${JSON.stringify(d.tops)}`);
	say(JSON.stringify(lost) === JSON.stringify(d.lost), `Not in your Stash yet: web ${lost.length} songs, first ${JSON.stringify(lost.slice(0, 5))}…; app ${d.lost.length}`);
}
console.log(differ ? `${differ} DIFFER` : 'THE PHONE AND THE WEB AGREE: the picks, every mix (ids, words, item keys, first 10 song keys), Top artists, Top songs and Not in your Stash yet');
process.exit(differ ? 1 : 0);
