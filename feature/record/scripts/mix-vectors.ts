/**
 * Parity vectors for the app's history mixes (feature/record `mixes/HistoryMixes.kt`): tastes and plays crafted around every rule,
 * run through the web player's own code, with what it gave written beside them. The app's `HistoryMixesVectorsTest` reads the
 * same files and must give the same ids, kinds, titles, lines and item keys, in the same order.
 *
 * - history.json: `historyMixes` (src/lib/spotify/mixes.ts) over the golden fixture's taste (the export in
 *   src/test/resources/spotify-export/, through the web's own importer) and over crafted tastes: a year "so far", an era across
 *   years, Rediscover minus The ones that stayed (and not minus it when that mix isn't made), `firstSongs` ties, an artist with
 *   7 songs and one with 8, songs put back and songs in the library (`putBackIn`, as service.ts `taste()` reads it), the
 *   library's own copies (`haveIndex`: by key, by words, an ISRC). With each mix: `mixHeading`, `mixLine`, `aboutLength` and
 *   `card` (src/lib/ui/spotify/mix.ts), and the Mixes tab's two lists (routes/spotify/+page.svelte).
 * - week.json: `weekMix` over crafted archives (29 February in past years, the ±3 days' edges, one year and several, spellings,
 *   skips, avoided songs, ties, the 50 cut, too few) and over the fixture's archive, read by start then id (IndexedDB's index on
 *   `s`: the memory archive orders by start alone).
 * - rotation.json: `rotation` and `homePicks` for every day 0..n+1, with "this week" and without.
 * - words.json: `eraWhen`, `andList` and `aboutLength` edges, and the English of the mix page, Home's shelf and the Mixes tab
 *   (MixView.svelte, HomeShelf.svelte, routes/spotify/+page.svelte, read as the files are).
 *
 * Run from this repository's root, against a stash-player checkout with its node_modules (read only), at the live commit:
 *   PLAYER=../stash-web/player-upi
 *   NODE_PATH=$PLAYER/node_modules $PLAYER/node_modules/.bin/esbuild feature/record/scripts/mix-vectors.ts --bundle --platform=node --format=esm \
 *     --alias:@player=$PLAYER/src/lib --outfile=build/mix-vectors.mjs
 *   node build/mix-vectors.mjs feature/record/src/test/resources/mixes $PLAYER feature/record/src/test/resources/spotify-export
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { LibraryItem } from '@player/api';
import { descriptorKey } from '@player/identity';
import { memoryArchive } from '@player/spotify/archive';
import { commit, openFiles, prepare } from '@player/spotify/importer';
import {
	andList,
	artistMix,
	eraWhen,
	firstSongs,
	haveIndex,
	historyMixes,
	homePicks,
	putBackIn,
	rotation,
	weekMix,
	type HistoryMix
} from '@player/spotify/mixes';
import { F_DONE, F_LASTFM, F_REASONS, F_SKIPPED, playId, type ArchivePlay } from '@player/spotify/plays';
import { artistKey, computeTaste, songKey, type SongStat, type Taste, type TastePlay } from '@player/spotify/taste';
import { aboutLength, card, mixHeading, mixLine } from '@player/ui/spotify/mix';

const out = process.argv[2];
const player = process.argv[3];
const fixtureDir = process.argv[4];
if (!out || !player || !fixtureDir) throw new Error('usage: node mix-vectors.mjs <out dir> <stash-player checkout> <spotify-export fixture dir>');
mkdirSync(out, { recursive: true });
const write = (name: string, v: unknown) => writeFileSync(join(out, name), `${JSON.stringify(v, null, 1)}\n`);
const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

const DAY = 86_400_000;
const HOUR = 3_600_000;
const FULL = F_REASONS | F_DONE;

// ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────────────

/** A taste as JSON (the app reads it field by field): `found` and `yearTops` always there. */
const tasteJson = (t: Taste) => ({ ...t, found: t.found ?? null, yearTops: t.yearTops ?? [] });

/** A library song as the app's test makes it: its words, ISRC, album and length (the key is identity.ts's). */
type Lib = { title: string; artist: string; album?: string; isrc?: string; durationMs?: number };
const libItem = (l: Lib): LibraryItem => ({ ...l, key: descriptorKey(l), refs: {}, addedAt: 0 }) as LibraryItem;

const itemJson = (i: LibraryItem) => ({ key: i.key, title: i.title, artist: i.artist, ...(i.album ? { album: i.album } : {}) });
const mixJson = (m: HistoryMix, t: Taste) => ({
	id: m.id,
	kind: m.kind,
	title: m.title,
	sub: m.sub,
	items: m.items.map(itemJson),
	heading: mixHeading(m, t),
	line: mixLine(m, t),
	about: aboutLength(m.items, t),
	card: card(m, t)
});

/** service.ts `taste()`: the stored taste with the songs put back and the library's songs no longer kept out. */
const asRead = (t: Taste, keep: readonly string[], lib: readonly Lib[]) => putBackIn(t, new Set([...keep, ...lib.map((l) => songKey(l.title, l.artist))]));

/** IndexedDB's `getAllFromIndex('plays', 's', [from, to))`: by start, then by id (the store's key), in UTF-16 order. */
const betweenOf = (rows: readonly ArchivePlay[]) => ({
	between: async (from: number, to: number) => rows.filter((p) => p.s >= from && p.s < to).sort((a, b) => a.s - b.s || cmp(a.id, b.id))
});

/** A song of a crafted taste: its words, and whatever the case says (the key is song-key.ts's). */
function stat(title: string, artist: string, o: Partial<SongStat> = {}): SongStat {
	return {
		key: songKey(title, artist),
		title,
		artist,
		starts: 10,
		plays: 10,
		full: 10,
		chosen: 0,
		skips: 0,
		years: 1,
		listenedMs: 10 * 200_000,
		first: Date.UTC(2020, 0, 1),
		last: Date.UTC(2024, 0, 1),
		peak: 2,
		peakAt: Date.UTC(2020, 0, 1),
		weight: 1,
		depth: 1,
		avoid: false,
		...o
	};
}

/** A crafted taste: what the case gives, the rest empty. */
function taste(o: Partial<Taste> & { songs: SongStat[] }): Taste {
	return {
		v: 1,
		computedAt: Date.UTC(2026, 9, 27, 12),
		first: Date.UTC(2019, 0, 1),
		last: Date.UTC(2026, 9, 1),
		plays: 0,
		listenedMs: 0,
		artists: 0,
		eras: [],
		constants: [],
		lost: [],
		obsessions: [],
		found: null,
		years: [],
		topArtists: [],
		seedArtists: [],
		yearTops: [],
		...o
	};
}

const top = (name: string, plays = 100, listenedMs = 100 * HOUR) => ({ name, key: artistKey(name), plays, listenedMs });
/** n songs of one artist, "{prefix} 1".. */
const songsOf = (n: number, artist: string, prefix: string, o: (i: number) => Partial<SongStat> = () => ({})) =>
	Array.from({ length: n }, (_, i) => stat(`${prefix} ${i + 1}`, artist, o(i)));

// ── history.json ────────────────────────────────────────────────────────────────────────────────────────────────────

type HistoryCase = { name: string; taste: Taste; keep?: string[]; have?: Lib[] };
const history: HistoryCase[] = [];

// The golden fixture, through the web's own importer (golden.test.ts's run), its playlists and likes the library
const FIXTURE_NOW = Date.parse('2026-10-23T12:00:00Z');
const fixtureArchive = memoryArchive();
const files = ['my_spotify_data.zip', 'my_spotify_data (1).zip'].map((name) => ({ name, bytes: new Uint8Array(readFileSync(join(fixtureDir, name))) }));
const prep = await prepare(await openFiles(files), fixtureArchive, { now: FIXTURE_NOW });
const committed = await commit(prep, fixtureArchive, { now: FIXTURE_NOW });
const fixtureLib: Lib[] = [...committed.likes, ...committed.playlists.flatMap((p) => p.items)].map((s) => ({ title: s.title, artist: s.artist, ...(s.album ? { album: s.album } : {}) }));
const fixtureRows = await fixtureArchive.all();
history.push({ name: 'the golden fixture, its playlists and liked songs the library', taste: committed.taste, have: fixtureLib });
history.push({ name: 'the golden fixture, nothing in the library', taste: committed.taste });

// Computed from plays: a year still going ("so far"), years with too few songs, eras
{
	const plays: TastePlay[] = [];
	const add = (t: string, a: string, s: number, n: number, step = DAY) => {
		for (let i = 0; i < n; i++) plays.push({ t, a, s: s + i * step, ms: 200_000, f: FULL });
	};
	for (let y = 2023; y <= 2026; y++) {
		const per = y === 2024 ? 7 : 12; // 2024 has 7 songs: no year mix
		for (let k = 0; k < per; k++) add(`Song ${y}-${k}`, k % 3 ? 'Lumen' : 'Harbor Lights', Date.UTC(y, 1 + (k % 6), 3 + k), 3 + (k % 4));
	}
	// An era of Harbor Lights through spring 2025 (two months over 8 h, the lead standing out)
	for (let m = 2; m <= 4; m++) for (let k = 0; k < 10; k++) add(`Tide ${k}`, 'Harbor Lights', Date.UTC(2025, m, 1 + k * 2), 1, HOUR);
	history.push({ name: 'a year still going is "so far"; a year of 7 songs makes no mix', taste: computeTaste(plays, { now: Date.UTC(2026, 9, 27, 12) }) });
}

// Eras across years, in one year (early, mid, late), and in one month, with their words
{
	const sA = songsOf(9, 'Northern Lights', 'North');
	const sB = songsOf(9, 'Glass Harbor', 'Glass');
	const sC = songsOf(9, 'Velvet Static', 'Velvet');
	const sD = songsOf(9, 'Paper Moons', 'Paper');
	const sE = songsOf(7, 'Seven Seas', 'Sea');
	const era = (artist: string, start: number, end: number, songs: SongStat[], artists: string[], share = 0.42) => ({
		start,
		end,
		artist,
		artists,
		share,
		listenedMs: 40 * HOUR,
		songs: songs.map((s) => s.key)
	});
	history.push({
		name: 'eras newest first: across years, early, mid and late in one year, one month; an era of 7 songs makes no mix',
		taste: taste({
			songs: [...sA, ...sB, ...sC, ...sD, ...sE],
			eras: [
				era('Northern Lights', Date.UTC(2019, 10, 1), Date.UTC(2020, 1, 29, 23, 59, 59, 999), sA, ['Northern Lights', 'Glass Harbor', 'Velvet Static'], 0.61),
				era('Glass Harbor', Date.UTC(2021, 0, 1), Date.UTC(2021, 4, 31, 23, 59, 59, 999), sB, ['Glass Harbor'], 0.099),
				era('Velvet Static', Date.UTC(2022, 3, 1), Date.UTC(2022, 7, 31, 23, 59, 59, 999), sC, ['Velvet Static', 'Paper Moons'], 0.3),
				era('Paper Moons', Date.UTC(2023, 7, 1), Date.UTC(2023, 11, 31, 23, 59, 59, 999), sD, ['Paper Moons', 'Northern Lights', 'Glass Harbor', 'Velvet Static']),
				era('Paper Moons', Date.UTC(2024, 5, 1), Date.UTC(2024, 5, 30, 23, 59, 59, 999), sD, ['Paper Moons', 'Velvet Static']),
				era('Seven Seas', Date.UTC(2025, 0, 1), Date.UTC(2025, 2, 31, 23, 59, 59, 999), sE, ['Seven Seas'])
			]
		})
	});
}

// Rediscover minus The ones that stayed (by song key), and not minus it when The ones that stayed isn't made
{
	const songs = songsOf(14, 'Evergreen', 'Ever', (i) => ({ years: 3 + (i % 3), last: Date.UTC(2023, 0, 1) }));
	const more = songsOf(10, 'Lantern', 'Lamp', () => ({ last: Date.UTC(2022, 0, 1) }));
	const keys = songs.map((s) => s.key);
	history.push({
		name: 'Rediscover leaves out what The ones that stayed plays',
		taste: taste({ songs: [...songs, ...more], constants: keys.slice(0, 10), lost: [...keys.slice(4, 14), ...more.map((s) => s.key)] })
	});
	history.push({
		name: 'The ones that stayed with 7 songs is no mix, and Rediscover keeps them all',
		taste: taste({ songs: [...songs, ...more], constants: keys.slice(0, 7), lost: [...keys.slice(0, 14), ...more.map((s) => s.key)] })
	});
	// An avoided song among the constants: not played, so Rediscover keeps it too (it's avoided there as well: out)
	const av = songs.map((s, i) => (i === 2 ? { ...s, avoid: true } : s));
	history.push({
		name: 'an avoided constant is played by neither; the 11th constant goes to Rediscover',
		taste: taste({ songs: [...av, ...more], constants: keys.slice(0, 11), lost: [...keys, ...more.map((s) => s.key)] })
	});
}

// How it started: ties on the first listen go to the key; an avoided first song gives way to the next; only the top 50 artists
{
	const t0 = Date.UTC(2018, 4, 1);
	const songs = [
		stat('Bravo', 'Alpha Band', { first: t0 }),
		stat('Able', 'Alpha Band', { first: t0 }),
		stat('Charlie', 'Alpha Band', { first: t0 - DAY, avoid: true }),
		stat('Dawn', 'Beta Crew', { first: t0 + DAY }),
		stat('Dusk', 'Beta Crew', { first: t0 + DAY, plays: 0 }),
		stat('Early', 'Gamma feat. Delta', { first: t0 + 2 * DAY }),
		stat('Earlier', 'Gamma & Epsilon', { first: t0 + 2 * DAY - 1 }),
		...Array.from({ length: 52 }, (_, i) => stat(`Only ${i}`, `Artist ${String(i).padStart(2, '0')}`, { first: t0 + (60 - i) * DAY }))
	];
	const topArtists = [top('Alpha Band'), top('Beta Crew'), top('Gamma'), ...Array.from({ length: 52 }, (_, i) => top(`Artist ${String(i).padStart(2, '0')}`, 50 - i))];
	history.push({ name: 'How it started: first-listen ties go to the key, an avoided first song gives way, the top 50 artists only', taste: taste({ songs, topArtists }) });
}

// Artists' mixes: 7 songs is none, 8 is one; their songs by plays then key; skipped songs out; the 10 first artists only
{
	const songs = [
		...songsOf(8, 'Eight Fold', 'Fold', (i) => ({ plays: 20 - (i % 3), first: Date.UTC(2017 + (i % 4), 2, 1) })),
		...songsOf(8, 'Seven Ways', 'Way', (i) => ({ avoid: i === 0 })),
		...songsOf(7, 'Just Seven', 'Seven'),
		...songsOf(55, 'Many Hands', 'Hand', (i) => ({ plays: 5 + (i % 9) })),
		...songsOf(9, 'Zero Plays', 'Zero', (i) => ({ plays: i < 2 ? 0 : 3 })),
		...songsOf(8, 'Feat Artist feat. Guest', 'Feat'),
		...Array.from({ length: 9 }, (_, a) => songsOf(8, `Also ${a}`, `Also ${a}`)).flat()
	];
	const topArtists = [
		top('Eight Fold'),
		top('Seven Ways'),
		top('Just Seven'),
		top('Many Hands'),
		top('Zero Plays'),
		top('Feat Artist'),
		...Array.from({ length: 9 }, (_, a) => top(`Also ${a}`, 40 - a))
	];
	history.push({ name: 'an artist with 7 songs makes no mix, 8 does; plays then key; 50 at most; the first 10 artists only', taste: taste({ songs, topArtists }) });
}

// Songs put back and songs in the library are no longer kept out; the library's own copies (by key, by words, an ISRC)
{
	const songs = [
		...songsOf(10, 'Paper Moons', 'Moon', (i) => ({ avoid: i < 3 })),
		stat('Old Friend - 2011 Remaster', 'Evergreen', { avoid: true }),
		...songsOf(8, 'Evergreen', 'Ever'),
		stat('Glow', 'Lumen', { album: 'Glow' }),
		stat('Fade', 'Lumen', { album: '' })
	];
	const keys = songs.map((s) => s.key);
	const t = taste({ songs, constants: keys, lost: keys, topArtists: [top('Paper Moons'), top('Evergreen'), top('Lumen')] });
	const have: Lib[] = [
		{ title: 'Moon 2', artist: 'Paper Moons', album: 'Moons', durationMs: 181_000 },
		{ title: 'Old Friend', artist: 'Evergreen', album: 'Old Friends', isrc: 'usabc1234567' },
		{ title: 'Ever 3', artist: 'Evergreen', isrc: 'GBXYZ0000001', durationMs: 0 },
		{ title: 'Ever 3', artist: 'Evergreen', album: 'Second copy' },
		{ title: 'Glow', artist: 'Lumen (feat. Someone)' }
	];
	history.push({ name: 'nothing put back, nothing in the library: the avoided songs stay out', taste: t });
	history.push({ name: 'a song put back plays again; one in the library plays from your copy', taste: t, keep: [keys[0]!], have });
	history.push({ name: 'put back and liked: every song plays, the library copies by key, by words and by ISRC', taste: t, keep: [keys[0]!, keys[1]!], have });
}

// On repeat and its card; a taste with nothing
{
	const songs = songsOf(12, 'Loop', 'Loop', (i) => ({ peak: 12 + i, peakAt: Date.UTC(2021, i % 12, 5) }));
	history.push({
		name: 'On repeat, back then, its card naming the top obsession',
		taste: taste({ songs, obsessions: songs.map((s) => ({ key: s.key, n: s.peak, at: s.peakAt })).reverse() })
	});
	history.push({ name: 'a taste with nothing makes nothing', taste: taste({ songs: [] }) });
}

const historyOut = history.map((c) => {
	const t = asRead(c.taste, c.keep ?? [], c.have ?? []);
	const have = haveIndex((c.have ?? []).map(libItem));
	const mixes = historyMixes(t, { have });
	const eras = mixes.filter((m) => m.kind === 'era').reverse();
	return {
		name: c.name,
		// The fixture's taste is written once, in fixture.json
		taste: c.taste === committed.taste ? 'fixture' : tasteJson(c.taste),
		keep: c.keep ?? [],
		have: c.have ?? [],
		mixes: mixes.map((m) => mixJson(m, t)),
		firstSongs: firstSongs(t).map((s) => s.key),
		artistMixes: t.topArtists.slice(0, 12).map((a) => artistMix(t, a)?.id ?? null),
		tab: {
			mixes: [...eras, ...mixes.filter((m) => m.kind !== 'era' && m.kind !== 'year' && m.kind !== 'artist')].map((m) => m.id),
			years: mixes.filter((m) => m.kind === 'year').reverse().map((m) => m.id)
		}
	};
});
write('history.json', historyOut);

// ── week.json ───────────────────────────────────────────────────────────────────────────────────────────────────────

type WeekCase = { name: string; now: number; rows: ArchivePlay[]; taste: Taste; have?: Lib[] };
const weeks: WeekCase[] = [];
const row = (t: string, a: string, s: number, ms = 200_000, f = FULL, extra: { al?: string; sid?: string } = {}): ArchivePlay => ({ id: playId(t, a, s), t, a, s, ms, f, ...extra });
/** n plays of one song, an hour apart from [s]. */
const rowsOf = (n: number, t: string, a: string, s: number, extra: { al?: string; sid?: string } = {}) => Array.from({ length: n }, (_, i) => row(t, a, s + i * HOUR, 200_000, FULL, extra));
const weekTaste = (first: number, songs: SongStat[] = []) => taste({ first, songs });

{
	// 29 February: in a year without one, the window is around 1 March; in a leap year around the 29th
	const now = Date.UTC(2028, 1, 29, 10);
	const rows = [
		...rowsOf(3, 'Leap 1', 'Calendar', Date.UTC(2027, 2, 1, 9)),
		...rowsOf(2, 'Leap 2', 'Calendar', Date.UTC(2027, 1, 26, 0)),
		row('Leap 3', 'Calendar', Date.UTC(2027, 1, 25, 23, 59, 59, 999)), // out: before 26 February 2027
		...rowsOf(2, 'Leap 4', 'Calendar', Date.UTC(2024, 1, 29, 12)),
		...rowsOf(2, 'Leap 5', 'Calendar', Date.UTC(2024, 2, 3, 23)),
		row('Leap 6', 'Calendar', Date.UTC(2024, 2, 4)), // out: 4 March 2024 is past the leap year's window
		...Array.from({ length: 6 }, (_, i) => row(`Filler ${i}`, 'Calendar', Date.UTC(2025, 2, 1, i))),
		...Array.from({ length: 3 }, (_, i) => row(`Old ${i}`, 'Calendar', Date.UTC(2021, 2, 1, i)))
	];
	weeks.push({ name: '29 February: a year without one looks around 1 March, a leap year around the 29th', now, rows, taste: weekTaste(Date.UTC(2020, 5, 1)) });
}
{
	// The edges: mid − 3 days is in, a millisecond before isn't; mid + 4 days less a millisecond is in, mid + 4 days isn't
	const now = Date.UTC(2026, 9, 28, 15, 30);
	const mid = Date.UTC(2025, 9, 28);
	const rows = [
		row('Edge in early', 'Edges', mid - 3 * DAY),
		row('Edge out early', 'Edges', mid - 3 * DAY - 1),
		row('Edge in late', 'Edges', mid + 4 * DAY - 1),
		row('Edge out late', 'Edges', mid + 4 * DAY),
		...Array.from({ length: 7 }, (_, i) => row(`Middle ${i}`, 'Edges', mid + i * HOUR))
	];
	weeks.push({ name: 'the window is [mid − 3 days, mid + 4 days): its edges; one past year says its year', now, rows, taste: weekTaste(Date.UTC(2025, 0, 1)) });
	weeks.push({ name: 'a taste whose first listen is this year looks at no past year', now, rows, taste: weekTaste(Date.UTC(2026, 0, 1)) });
	weeks.push({ name: 'a taste with no first listen looks at no past year', now, rows, taste: weekTaste(0) });
}
{
	// Several years; spellings (the first read wins, by start then id); skips and short plays; avoided songs; ties by key; 50 at most
	const now = Date.UTC(2026, 6, 4, 8);
	const rows: ArchivePlay[] = [];
	for (let y = 2018; y <= 2025; y++) {
		const mid = Date.UTC(y, 6, 4);
		for (let k = 0; k < 9; k++) rows.push(...rowsOf(1 + ((k + y) % 4), `Summer ${k}`, 'Season', mid - 2 * DAY + k * HOUR, { al: k % 2 ? 'Summers' : '', sid: `sid${y}${k}` }));
	}
	const mid = Date.UTC(2023, 6, 4);
	rows.push(row('Same Start B', 'Ties', mid), row('Same Start A', 'Ties', mid)); // one start: the id decides which is read first
	rows.push(row('Song', 'Spelling', mid + HOUR), row('Song - 2011 Remaster', 'Spelling', mid), row('song', 'spelling', mid + 2 * HOUR));
	rows.push(row('Skipped', 'Short', mid, 29_999), row('Skipped', 'Short', mid + DAY, 10_000, F_REASONS | F_SKIPPED), row('Long enough', 'Short', mid, 30_000, F_REASONS | F_SKIPPED));
	rows.push(...rowsOf(9, 'Never', 'Avoided', mid));
	rows.push(row('Scrobble', 'Last', mid, 210_000, F_LASTFM));
	for (let i = 0; i < 60; i++) rows.push(row(`Many ${String(i).padStart(2, '0')}`, 'Crowd', Date.UTC(2019, 6, 3, i % 24, i)));
	const avoided = stat('Never', 'Avoided', { avoid: true });
	const have: Lib[] = [{ title: 'Summer 3', artist: 'Season', album: 'Library album', durationMs: 199_000 }, { title: 'Song', artist: 'Spelling', isrc: 'QZ1234500001' }];
	weeks.push({ name: 'several past years: most played first, ties by key, the first spelling read wins, avoided out, 50 at most', now, rows, taste: weekTaste(Date.UTC(2018, 0, 1), [avoided]), have });
	weeks.push({ name: 'the same, nothing in the library', now, rows, taste: weekTaste(Date.UTC(2018, 0, 1), [avoided]) });
}
{
	// Too few: 7 songs is no mix
	const now = Date.UTC(2026, 0, 10);
	const rows = Array.from({ length: 7 }, (_, i) => row(`Few ${i}`, 'Few', Date.UTC(2025, 0, 9, i)));
	weeks.push({ name: '7 songs are no mix', now, rows, taste: weekTaste(Date.UTC(2024, 0, 1)) });
	weeks.push({ name: '8 songs are one', now, rows: [...rows, row('Few 7', 'Few', Date.UTC(2025, 0, 12))], taste: weekTaste(Date.UTC(2024, 0, 1)) });
}
// The fixture's own archive, on a few days its years have plays around
for (const now of [FIXTURE_NOW, Date.UTC(2026, 2, 15, 9), Date.UTC(2026, 6, 1, 23, 30), Date.UTC(2027, 1, 28, 12), Date.UTC(2028, 1, 29, 12)]) {
	weeks.push({ name: `the golden fixture's archive on ${new Date(now).toISOString()}`, now, rows: fixtureRows, taste: committed.taste, have: fixtureLib });
}

const weekOut = [];
for (const c of weeks) {
	const have = haveIndex((c.have ?? []).map(libItem));
	const m = await weekMix(betweenOf(c.rows), c.taste, c.now, have);
	const fixture = c.rows === fixtureRows;
	weekOut.push({
		name: c.name,
		now: c.now,
		// The fixture's rows are the fixture's (the test reads them from history.json's taste and the export): not written twice
		rows: fixture ? 'fixture' : c.rows,
		taste: fixture ? 'fixture' : { first: c.taste.first, songs: c.taste.songs },
		have: c.have ?? [],
		mix: m ? { id: m.id, kind: m.kind, title: m.title, sub: m.sub, items: m.items.map(itemJson), about: aboutLength(m.items, c.taste), card: card(m, c.taste) } : null
	});
}
write('week.json', weekOut);
// The fixture's taste and archive, for the cases that read them: each row [id, t, a, al, sid, s, ms, f], by start then id
writeFileSync(
	join(out, 'fixture.json'),
	`${JSON.stringify({
		taste: tasteJson(committed.taste),
		rows: [...fixtureRows].sort((a, b) => a.s - b.s || cmp(a.id, b.id)).map((p) => [p.id, p.t, p.a, p.al ?? null, p.sid ?? null, p.s, p.ms, p.f])
	})}
`
);

// ── rotation.json ───────────────────────────────────────────────────────────────────────────────────────────────────

const rotationOut = [];
for (const c of history) {
	const t = asRead(c.taste, c.keep ?? [], c.have ?? []);
	const all = historyMixes(t, { have: haveIndex((c.have ?? []).map(libItem)) });
	const week = { id: 'week', kind: 'week', title: 'This week, years ago', sub: '', items: [] } as HistoryMix;
	const rest = rotation(all);
	const days = Array.from({ length: rest.length + 2 }, (_, d) => d);
	rotationOut.push({
		name: c.name,
		rotation: rest.map((m) => m.id),
		picks: days.map((d) => ({ day: d, ids: homePicks(all, null, d).map((m) => m.id), withWeek: homePicks(all, week, d).map((m) => m.id) })),
		// Days far from 1970 (today's and a negative one): the turn starts at day % n
		far: [20_389, 20_390, -1, -7].map((d) => ({ day: d, ids: homePicks(all, null, d).map((m) => m.id) }))
	});
}
write('rotation.json', rotationOut);

// ── words.json ──────────────────────────────────────────────────────────────────────────────────────────────────────

const eraCases: [number, number][] = [
	[Date.UTC(2016, 8, 1), Date.UTC(2016, 11, 31, 23, 59, 59, 999)],
	[Date.UTC(2018, 0, 1), Date.UTC(2018, 2, 31, 23, 59, 59, 999)],
	[Date.UTC(2018, 0, 1), Date.UTC(2018, 7, 31, 23, 59, 59, 999)],
	[Date.UTC(2018, 0, 1), Date.UTC(2018, 6, 31, 23, 59, 59, 999)],
	[Date.UTC(2018, 0, 1), Date.UTC(2018, 8, 30, 23, 59, 59, 999)],
	[Date.UTC(2018, 3, 1), Date.UTC(2018, 11, 31, 23, 59, 59, 999)],
	[Date.UTC(2022, 5, 1), Date.UTC(2022, 5, 30, 23, 59, 59, 999)],
	[Date.UTC(2022, 5, 1), Date.UTC(2023, 2, 31, 23, 59, 59, 999)],
	[Date.UTC(2023, 11, 1), Date.UTC(2024, 0, 31, 23, 59, 59, 999)],
	// The middle on a month's edge: (start + end) / 2 half a millisecond before 1 May (April: Early) or on it (May: Mid), half a
	// millisecond before 1 September (August: Mid) or on it (September: Late). `new Date(x.5)` reads the whole millisecond before.
	[Date.UTC(2020, 0, 1), 2 * Date.UTC(2020, 4, 1) - 1 - Date.UTC(2020, 0, 1)],
	[Date.UTC(2020, 0, 1), 2 * Date.UTC(2020, 4, 1) - Date.UTC(2020, 0, 1)],
	[Date.UTC(2020, 5, 1), 2 * Date.UTC(2020, 8, 1) - 1 - Date.UTC(2020, 5, 1)],
	[Date.UTC(2020, 5, 1), 2 * Date.UTC(2020, 8, 1) - Date.UTC(2020, 5, 1)]
];
const t0 = taste({
	songs: [
		stat('Known Average', 'Lengths', { plays: 4, listenedMs: 4 * 241_337 }),
		stat('Left Playing', 'Lengths', { plays: 2, listenedMs: 2 * 20 * 60_000 }),
		stat('Just Under', 'Lengths', { plays: 3, listenedMs: 3 * (20 * 60_000 - 1) }),
		stat('Never Listened', 'Lengths', { plays: 0, listenedMs: 0 })
	]
});
const lengthCases: Lib[][] = [
	[],
	[{ title: 'Known Average', artist: 'Lengths' }],
	[{ title: 'Left Playing', artist: 'Lengths' }],
	[{ title: 'Just Under', artist: 'Lengths' }],
	[{ title: 'Never Listened', artist: 'Lengths' }, { title: 'Nobody', artist: 'Knows' }],
	[{ title: 'Own Length', artist: 'Lengths', durationMs: 449_999 }],
	[{ title: 'Own Length', artist: 'Lengths', durationMs: 450_000 }],
	Array.from({ length: 17 }, () => ({ title: 'Nobody', artist: 'Knows' })),
	Array.from({ length: 50 }, (_, i) => ({ title: i % 2 ? 'Known Average' : 'Own', artist: 'Lengths', ...(i % 2 ? {} : { durationMs: 200_000 + i }) })),
	[{ title: 'Known Average - 2011 Remaster', artist: 'Lengths' }, { title: 'Own Length', artist: 'Lengths', durationMs: 3_600_000 }],
	[{ title: 'Own Length', artist: 'Lengths', durationMs: 3_300_000 }, { title: 'Own Length', artist: 'Lengths', durationMs: 449_999 }]
];

/** The English of a Svelte file's words that the app's strings hold: each must be in the file as it is. */
function wordsOf(file: string, list: string[]): Record<string, string> {
	const src = readFileSync(join(player, file), 'utf8');
	const outW: Record<string, string> = {};
	for (const w of list) {
		if (!src.includes(w)) throw new Error(`${file} no longer says ${JSON.stringify(w)}`);
		outW[w] = w;
	}
	return outW;
}

write('words.json', {
	eraWhen: eraCases.map(([start, end]) => ({ start, end, when: eraWhen(start, end) })),
	andList: [[], ['A'], ['A', 'B'], ['A', 'B', 'C'], ['Sepultura', 'Slayer', 'Deftones', 'Death']].map((xs) => ({ xs, line: andList(xs) })),
	about: { taste: tasteJson(t0), cases: lengthCases.map((libs) => ({ items: libs, about: aboutLength(libs.map(libItem), t0) })) },
	mixView: wordsOf('src/lib/ui/spotify/MixView.svelte', [
		'Mix from your record',
		'Save as playlist',
		'Saving…',
		'Open “{saved.name}”',
		'Saved “${p.name}” to your playlists',
		'Couldn’t save it just now. Try again.',
		'That mix isn’t in your record any more: mixes change as your listening does.',
		'Back to Your record',
		'aria-label="Play {mix.title}"',
		'aria-label="Shuffle {mix.title}"'
	]),
	homeShelf: wordsOf('src/lib/ui/spotify/HomeShelf.svelte', ['From your record', 'Show all', 'label="Open {m.title}"']),
	recordPage: wordsOf('src/routes/spotify/+page.svelte', [
		'>The record</button>',
		'>Mixes</button>',
		'>Mixes from your years</h2>',
		'>Your years</h2>',
		'Bring your listening',
		'aria-label="Play {m.title}"'
	])
});

console.log(`history ${historyOut.length}, week ${weekOut.length}, rotation ${rotationOut.length}: written to ${out}`);
