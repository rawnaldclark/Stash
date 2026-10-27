/**
 * Parity vectors for the app's Last.fm import and the record's source switches (feature/record `lastfm/`, `archive/RecordSources`):
 * cases crafted around every rule, run through the web player's own code, with what they gave written beside them. The app's
 * `LastfmVectorsTest` reads the same files and must give the same.
 *
 * - history.json: Last.fm answers read by the Worker's `historyPage` (stash-player worker/lastfm-history.ts), a fake fetch
 *   handing it each body, signed (`auth`) or not.
 * - plan.json: `planScrobbles` with `bestLengths` over `knownLengths` and `libraryLengths`, and `songKey` (src/lib/spotify/plays.ts,
 *   taste.ts), and `coveredBy`.
 * - runs.json: whole `importScrobbles` runs (src/lib/spotify/lastfm.ts) against a fake Last.fm (newest first, 200 a page,
 *   filtered by `to` and `from`, busy, failing or empty for a few asks of one page; withholding a stretch of time, counting a
 *   stale total, or emptying every page for a while), every ask, every progress, the mark it started from, the mark and the
 *   archive's ids; and `startMark` alone (start.json), an earlier build's mark included.
 * - lengths.json: the lengths pass (src/lib/spotify/lengths.ts): `libraryLengths`, `bestLengths`, `scrobbleMs`, `guessedBySong`
 *   with `planLengths` (failures and their waits), and `fillLengths` slices against a fake length reader, the streak carried from
 *   slice to slice as the service carries it, every ask, every save of the mark, the rows after.
 * - service.json: the service's done line state after each Last.fm run (src/lib/spotify/service.ts `importLastfm`).
 * - words.json: lastfm.ts's English (`doneLine`, with a page still missing too, the reconnect line, the hours note).
 * - sources.json: record.ts `readSources`, `present`, `shownSources` and `toggleSource` over every choice and source.
 *
 * Run from this repository's root, against a stash-player checkout with its node_modules (read only):
 *   PLAYER=../stash-web/player-record-lastfm
 *   NODE_PATH=$PLAYER/node_modules $PLAYER/node_modules/.bin/esbuild feature/record/scripts/lastfm-vectors.ts --bundle --platform=node --format=esm \
 *     --alias:@player=$PLAYER/src/lib --alias:@worker=$PLAYER/worker --outfile=build/lastfm-vectors.mjs
 *   node build/lastfm-vectors.mjs feature/record/src/test/resources/lastfm
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { get, writable } from 'svelte/store';
import { memoryArchive, type ArchiveStore } from '@player/spotify/archive';
import { importScrobbles, LastfmError, startMark, type HistoryPage, type LastfmMark, type PageReader } from '@player/spotify/lastfm';
import { fillLengths, guessedBySong, planLengths, type LengthReader, type LengthsMark } from '@player/spotify/lengths';
import { bestLengths, coveredBy, knownLengths, libraryLengths, planScrobbles, scrobbleMs, toArchive, type ArchivePlay, type Scrobble } from '@player/spotify/plays';
import { createSpotifyService } from '@player/spotify/service';
import { songKey } from '@player/spotify/taste';
import { doneLine, HOURS_NOTE, LASTFM_ERRORS, MISSING } from '@player/ui/spotify/lastfm';
import { present, readSources, shownSources, toggleSource, type Counts, type Source, type SourcesOn } from '@player/ui/spotify/record';
import { historyPage } from '@worker/lastfm-history';

const out = process.argv[2];
if (!out) throw new Error('usage: node lastfm-vectors.mjs <out dir>');
mkdirSync(out, { recursive: true });
/** Written readably, or ([compact]) on one line: the runs hold every row of every step. */
const write = (name: string, v: unknown, compact = false) => writeFileSync(join(out, name), `${compact ? JSON.stringify(v) : JSON.stringify(v, null, 1)}\n`);
const sortById = (rows: ArchivePlay[]) => [...rows].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
const sortedObject = <V>(m: ReadonlyMap<string, V>) => Object.fromEntries([...m].sort(([x], [y]) => (x < y ? -1 : x > y ? 1 : 0)));

const NOW_S = Date.UTC(2026, 9, 27, 12) / 1000;
const T = Date.UTC(2024, 4, 1, 12) / 1000;

// ── history.json ────────────────────────────────────────────────────────────────────────────────────────────────────

const track = (name: unknown, artist: unknown, uts: unknown, extra: Record<string, unknown> = {}) => ({
	name,
	artist: { '#text': artist, mbid: '' },
	album: { '#text': 'An Album', mbid: '' },
	date: { uts, '#text': '01 May 2024, 12:00' },
	...extra
});
const page = (tracks: unknown, attr: Record<string, unknown> = { page: '1', totalPages: '3', total: '450', perPage: '200', user: 'me' }) => ({ recenttracks: { track: tracks, '@attr': attr } });
const nowPlaying = { ...track('Now', 'Band', undefined), '@attr': { nowplaying: 'true' }, date: undefined };
const historyCases: { name: string; page: number; body: unknown; auth?: boolean }[] = [
	{ name: 'a page', page: 1, body: page([track('Song', 'Band', String(T)), track('Other', 'Band', String(T - 60))]) },
	{ name: 'a page, signed', page: 1, auth: true, body: page([track('Song', 'Band', String(T)), track('Other', 'Band', String(T - 60))]) },
	{ name: 'the song playing now is left out', page: 1, body: page([nowPlaying, track('Song', 'Band', String(T))]) },
	{ name: 'nowplaying not the string true', page: 1, body: page([{ ...track('Now', 'Band', String(T)), '@attr': { nowplaying: true } }]) },
	{ name: 'one track is an object, not a list', page: 2, body: page(track('Alone', 'Band', String(T)), { page: '2', totalPages: '2', total: '201' }) },
	{ name: 'no tracks at all', page: 1, body: page(undefined, { page: '1', totalPages: '0', total: '0' }) },
	{ name: 'tracks of the wrong shape', page: 1, body: page([null, 5, 'x', [], { name: 'No artist', date: { uts: String(T) } }, track('Song', 'Band', String(T))]) },
	{ name: 'artist given by name, not #text', page: 1, body: page([{ ...track('Song', 'x', String(T)), artist: { name: 'Band' } }]) },
	{ name: 'artist as a string', page: 1, body: page([{ ...track('Song', 'x', String(T)), artist: 'Band' }]) },
	{ name: 'whitespace runs become one space, trimmed', page: 1, body: page([track('  A\t\tSong \n Title ', ' Band　', String(T))]) },
	{ name: 'a control character drops the scrobble', page: 1, body: page([track('Bad\u0007Song', 'Band', String(T)), track('Bad\u0085Song', 'Band', String(T)), track('Good', 'Band\u0001', String(T))]) },
	{ name: 'a bidi override drops the scrobble', page: 1, body: page([track('Evil‮Song', 'Band', String(T)), track('Iso⁦late', 'Band', String(T))]) },
	{ name: 'words longer than 300 drop it; 300 exactly stays', page: 1, body: page([track('x'.repeat(301), 'Band', String(T)), track('y'.repeat(300), 'Band', String(T)), track('z'.repeat(310), 'Band', String(T), {}), track(' '.repeat(5) + 'w'.repeat(300) + ' '.repeat(5), 'Band', String(T))]) },
	{ name: 'an album that is not clean is dropped, the scrobble kept', page: 1, body: page([{ ...track('Song', 'Band', String(T)), album: { '#text': 'Bad\u0002Album' } }, { ...track('Two', 'Band', String(T)), album: { '#text': '   ' } }, { ...track('Three', 'Band', String(T)), album: 'Flat' }, { ...track('Four', 'Band', String(T)), album: undefined }]) },
	{ name: 'times as JavaScript reads them', page: 1, body: page([track('Pad', 'Band', ` ${T} `), track('Hex', 'Band', '0x65FE0000'), track('Exp', 'Band', '1.7e9'), track('Frac', 'Band', `${T}.9`), track('Junk', 'Band', `${T}abc`), track('Num', 'Band', T), track('Empty', 'Band', ''), track('Neg', 'Band', '-5'), track('Inf', 'Band', 'Infinity'), track('Bool', 'Band', true), track('Plus', 'Band', `+${T}`)]) },
	{ name: 'times before 2002 and more than a day ahead are broken rows', page: 1, body: page([track('Old', 'Band', String(Date.UTC(2001, 11, 31, 23, 59, 59) / 1000)), track('First', 'Band', String(Date.UTC(2002, 0, 1) / 1000)), track('Day', 'Band', String(NOW_S + 86_400)), track('Past', 'Band', String(NOW_S + 86_401))]) },
	{ name: 'error 6 on page 1: no such user, an empty last page', page: 1, body: { error: 6, message: 'User not found' } },
	{ name: 'error 6 on a later page: try again', page: 4, body: { error: 6, message: 'User not found' } },
	{ name: 'error 17 as text on page 1: an empty last page', page: 1, body: { error: '17', message: 'Login: User required to be logged in' } },
	{ name: 'error 17 on page 1, signed: an empty last page', page: 1, auth: true, body: { error: 17, message: 'Login: User required to be logged in' } },
	{ name: 'error 17 on a later page: try again', page: 2, auth: true, body: { error: 17, message: 'Login: User required to be logged in' } },
	{ name: 'error 9 signed: reconnect', page: 3, auth: true, body: { error: 9, message: 'Invalid session key - Please re-authenticate' } },
	{ name: 'error 9 as text, signed: reconnect', page: 1, auth: true, body: { error: '9', message: 'Invalid session key' } },
	{ name: 'error 9 unsigned: try again', page: 1, body: { error: 9, message: 'Invalid session key' } },
	{ name: 'error 29: try again', page: 1, body: { error: 29, message: 'Rate Limit Exceeded' } },
	{ name: 'error 8: try again', page: 1, body: { error: '8', message: 'Operation failed' } },
	{ name: 'error 26, signed: try again', page: 1, auth: true, body: { error: 26, message: 'Suspended API key' } },
	{ name: 'error 0 is no error', page: 1, body: { error: 0, ...page([track('Song', 'Band', String(T))]) } },
	{ name: 'no recenttracks: try again', page: 1, body: { something: 'else' } },
	{ name: 'recenttracks a string: try again', page: 1, body: { recenttracks: 'nothing' } },
	{ name: 'recenttracks a list: an empty page', page: 3, body: { recenttracks: [] } },
	{ name: 'a body that is a list: try again', page: 1, body: [] },
	{ name: 'a body that is null: try again', page: 1, body: null },
	{ name: 'a body that is a number: try again', page: 1, body: 5 },
	{ name: 'nothing listed on a page before the last: try again', page: 1, body: page([], { page: '1', totalPages: '3', total: '450' }) },
	{ name: 'no track field on a page before the last: try again', page: 2, body: page(undefined, { page: '2', totalPages: '3', total: '450' }) },
	{ name: 'only the song playing now, before the last: try again', page: 1, body: page([nowPlaying], { page: '1', totalPages: '2', total: '201' }) },
	{ name: 'every row broken on a page before the last: a page with no plays', page: 2, body: page([track('Old', 'Band', '100'), null, track('Bad\u0007', 'Band', String(T))], { page: '2', totalPages: '3', total: '450' }) },
	{ name: 'nothing listed on the last page: the end', page: 3, body: page([], { page: '3', totalPages: '3', total: '450' }) },
	{ name: 'nothing listed past the last page: the end', page: 5, body: page([], { page: '5', totalPages: '3', total: '450' }) },
	{ name: 'paging as text, missing or odd', page: 5, body: page([track('Song', 'Band', String(T))], { page: '0', totalPages: '-1', total: 'many' }) },
	{ name: 'paging as numbers', page: 1, body: page([track('Song', 'Band', String(T))], { page: 7, totalPages: 12.9, total: 2400 }) },
	{ name: 'no @attr', page: 2, body: { recenttracks: { track: [track('Song', 'Band', String(T))] } } },
	{ name: 'case and accents kept as written', page: 1, body: page([track('Björk', 'BJÖRK', String(T)), track('Jóga', 'Björk', String(T))]) }
];
const history = [];
for (const c of historyCases) {
	const fetchFn = async () => new Response(JSON.stringify(c.body), { status: 200, headers: { 'content-type': 'application/json' } });
	const auth = c.auth ? { sk: 'session-key', secret: 'secret' } : undefined;
	const answer = await historyPage('me', 'key', { page: c.page, to: NOW_S }, fetchFn, 'https://ws.audioscrobbler.com/2.0/', NOW_S, auth);
	history.push({ name: c.name, page: c.page, auth: !!c.auth, nowS: NOW_S, body: c.body, answer });
}
write('history.json', history);

// ── plan.json ───────────────────────────────────────────────────────────────────────────────────────────────────────

const spotify = (title: string, startS: number, ms = 200_000, o: { done?: boolean; artist?: string; account?: boolean } = {}) =>
	toArchive({ title, artist: o.artist ?? 'Band', startedAt: startS * 1000, endedAt: startS * 1000 + ms, ms, ...(o.account ? { src: 'a' as const } : { done: o.done ?? true, chosen: false, src: 'x' as const }) });
const sc = (t: string, uts: number, a = 'Band', al?: string): Scrobble => (al ? { t, a, al, uts } : { t, a, uts });
type LibItem = { title: string; artist: string; durationMs?: number };
const S = 180;
const planCases: { name: string; have: ArchivePlay[]; scrobbles: Scrobble[]; library?: LibItem[] }[] = [
	{
		name: 'covered by a Spotify play, kept, and known lengths',
		have: [spotify('Song', T), spotify('Other', T + 3600, 250_000)],
		scrobbles: [sc('Song', T + 30), sc('Song', T - 120), sc('Song', T + 7200), sc('Other', T + 9000), sc('New One', T + 9500, 'Band', 'Its Album')]
	},
	{
		name: 'the 180 s edges: before the start and after the end',
		have: [spotify('Edge', T, 200_000)],
		scrobbles: [sc('Edge', T - S), sc('Edge', T - S - 1), sc('Edge', T + 200 + S), sc('Edge', T + 200 + S + 1)]
	},
	{
		name: 'titles folded alike, the artist ignored',
		have: [spotify('Ünïcode Song (feat. Someone)', T, 200_000, { artist: 'Album Artist' })],
		scrobbles: [sc('unicode song', T + 10, 'Song Artist'), sc('UNICODE SONG!', T + 20, 'Other'), sc('Unicode Songs', T + 30, 'Song Artist')]
	},
	{
		name: 'a remaster is the same song for its length; an Account play or one not done gives none',
		have: [spotify('Hit - 2011 Remaster', T, 230_000), spotify('Hit', T + 100_000, 225_000), spotify('Half', T, 90_000, { done: false }), spotify('Acc', T + 50_000, 199_000, { account: true })],
		scrobbles: [sc('Hit', T + 900_000), sc('Hit (Remastered)', T + 901_000), sc('Half', T + 902_000), sc('Acc', T + 903_000)]
	},
	{
		name: 'the same scrobble twice in one page, and an id kept already',
		have: [],
		scrobbles: [sc('Twice', T, 'Band', 'B Album'), sc('Twice', T, 'Band', 'A Album'), sc('twice', T, 'band'), sc('Kept', T + 60)]
	},
	{
		name: 'a scrobble kept already is the same, not covered',
		have: [{ id: 'kept|band|' + (T + 60) * 1000, t: 'Kept', a: 'Band', s: (T + 60) * 1000, ms: 210_000, f: 64 | 128 }],
		scrobbles: [sc('Kept', T + 60), sc('Kept', T + 120)]
	},
	{
		name: 'a scrobble never covers a scrobble',
		have: [{ id: 'near|band|' + T * 1000, t: 'Near', a: 'Band', s: T * 1000, ms: 210_000, f: 64 | 128 }],
		scrobbles: [sc('Near', T + 30)]
	},
	{
		name: 'order: by time, then title, then artist',
		have: [],
		scrobbles: [sc('b', T + 1), sc('a', T + 1, 'Z'), sc('a', T + 1, 'A'), sc('c', T), sc('B', T + 1)]
	},
	{
		name: 'a finished Spotify play, else the library, else the guess; a scrobble is a listen however short',
		have: [spotify('Both', T - 100_000, 201_000), spotify('Quick', T - 90_000, 12_500)],
		library: [
			{ title: 'Both', artist: 'Band', durationMs: 999_000 },
			{ title: 'Library', artist: 'Band', durationMs: 187_400 },
			{ title: 'Library', artist: 'Band', durationMs: 186_000 },
			{ title: 'library - 2011 remaster', artist: 'BAND', durationMs: 187_900 },
			{ title: 'Short', artist: 'Band', durationMs: 12_000 },
			{ title: 'Second', artist: 'Band', durationMs: 1000 },
			{ title: 'Broken', artist: 'Band', durationMs: 7 * 3600_000 },
			{ title: 'Longest', artist: 'Band', durationMs: 6 * 3600_000 },
			{ title: 'Tiny', artist: 'Band', durationMs: 999 },
			{ title: 'Frac', artist: 'Band', durationMs: 200_000.5 },
			{ title: 'None', artist: 'Band' }
		],
		scrobbles: [sc('Both', T), sc('Library', T + 600), sc('Short', T + 1200), sc('Broken', T + 1800), sc('Guessed', T + 2400), sc('Quick', T + 3000), sc('Second', T + 3600), sc('Longest', T + 4200), sc('Tiny', T + 4800), sc('Frac', T + 5400), sc('None', T + 6000)]
	}
];
const plan = planCases.map((c) => {
	const library = libraryLengths(c.library ?? [], songKey);
	const lengths = bestLengths(knownLengths(c.have, songKey), library);
	const p = planScrobbles(c.have, c.scrobbles, lengths, songKey);
	return { name: c.name, have: c.have, scrobbles: c.scrobbles, library: c.library ?? [], libraryLengths: sortedObject(library), lengths: sortedObject(lengths), plan: p };
});
const coveredCases = [
	{ name: 'a play here covers its scrobble', rows: planScrobbles([], [sc('Here', T + 10), sc('There', T + 10)], new Map(), songKey).add, plays: [{ t: 'Here', s: T * 1000, ms: 180_000 }] },
	{
		name: 'the edges, folded titles, and only scrobbles',
		rows: [...planScrobbles([], [sc('Edge', T - S), sc('Edge', T - S - 1), sc('Edge', T + 100 + S), sc('Edge', T + 100 + S + 1), sc('ÉDGE', T + 50, 'Other')], new Map(), songKey).add, spotify('Edge', T + 20)],
		plays: [{ t: 'edge', s: T * 1000, ms: 100_000 }]
	}
];
const covered = coveredCases.map((c) => ({ ...c, covered: [...coveredBy(c.rows, c.plays)] }));
write('plan.json', { plan, covered });

// ── runs.json ───────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A page asked for that Last.fm answers oddly, `times` times: busy (or worse; `hole`: busy because the page listed nothing, the
 * Worker's `empty`, with the pages Last.fm claims: the window's), empty saying it has `pages`, or (`short`) its plays as they are
 * but saying it has `pages`. `topup`: only asks with a `from` (true) or only without (false).
 */
type Odd = { page: number; times: number; topup?: boolean; to?: number; kind?: 'busy' | 'reconnect' | 'unavailable'; hole?: boolean; empty?: { pages: number; total: number }; short?: { pages: number; total: number } };
type Q = { page: number; to: number; from?: number };
/**
 * What else Last.fm does in a step: withholds a stretch of time (`hide`: a page whose plays reach into [lo, hi] lists nothing; `to`:
 * only on asks with no `from` below that bound), counts a stale total (`stale`: `extra` more on asks with no `from` below `to`),
 * or empties every page once a whole-history ask of page `incident` has been answered; `exclusive`: leaves out the second of each
 * bound. A page that lists nothing is the Worker's: before the last, busy saying so (`empty`, with the pages it claims); the last,
 * just empty. An odd page with a `to` is only the asks below that bound.
 */
type Lies = { hide?: { lo: number; hi: number; to?: number }; stale?: { extra: number; to: number }; incident?: number; exclusive?: boolean };
/** Last.fm as the web's test fakes it: the window below `to` (and from `from`), newest first, 200 a page. */
function fakeLastfm(all: Scrobble[], odd: Odd[], calls: Q[], lies: Lies = {}): PageReader {
	const left = odd.map((f) => ({ ...f }));
	let incident = false;
	return async (q) => {
		calls.push(q.from ? { page: q.page, to: q.to, from: q.from } : { page: q.page, to: q.to });
		const f = left.find((x) => x.page === q.page && x.times > 0 && (x.topup === undefined || x.topup === !!q.from) && (x.to === undefined || x.to === q.to));
		const window = all
			.filter((s) => (lies.exclusive ? s.uts < q.to && (!q.from || s.uts > q.from) : s.uts <= q.to && (!q.from || s.uts >= q.from)))
			.sort((a, b) => b.uts - a.uts);
		const total = window.length + (lies.stale && !q.from && q.to === lies.stale.to ? lies.stale.extra : 0);
		const pages = Math.ceil(total / 200);
		if (f) {
			f.times--;
			if (f.kind) throw new LastfmError(f.kind, !!f.hole, f.hole ? pages : 0);
			if (f.short) return { plays: window.slice((q.page - 1) * 200, q.page * 200), page: q.page, pages: f.short.pages, total: f.short.total } satisfies HistoryPage;
			return { plays: [], page: q.page, pages: f.empty!.pages, total: f.empty!.total } satisfies HistoryPage;
		}
		const plays = window.slice((q.page - 1) * 200, q.page * 200);
		const h = lies.hide;
		const hidden = incident || (!!h && (h.to === undefined || (!q.from && q.to === h.to)) && plays.some((s) => s.uts >= h.lo && s.uts <= h.hi));
		if (hidden || (!plays.length && q.page < pages)) {
			if (q.page < pages) throw new LastfmError('busy', true, pages);
			return { plays: [], page: q.page, pages, total } satisfies HistoryPage;
		}
		if (lies.incident && !q.from && q.page === lies.incident) incident = true;
		return { plays, page: q.page, pages, total } satisfies HistoryPage;
	};
}
const many = (n: number, from = T, prefix = 'Song') => Array.from({ length: n }, (_, i) => sc(`${prefix} ${i}`, from + i * 60));
/** `n` scrobbles a day apart, read [LATER]: a top-up's two weeks never reach back to a page skipped below them. */
const DAY_S = 86_400;
const daily = (n: number) => Array.from({ length: n }, (_, i) => sc(`Day ${i}`, T + i * DAY_S));
const LATER = T + 700 * DAY_S;
const HALF_HOUR = 1800;
const dayOf = (i: number) => T + i * DAY_S;
/** Days 200 to 399 (page 2 of the whole history read later) withheld. */
const HIDE_2 = { lo: dayOf(200), hi: dayOf(399) };
/** 1400 scrobbles six hours apart, and the archive holding all but 200 to 1199, done, the hole between them found long ago. */
const six = Array.from({ length: 1400 }, (_, i) => sc(`Six ${i}`, T + i * 6 * 3600));
const sixOf = (i: number) => T + i * 6 * 3600;
const doneWithHole = (hole: { from: number; to: number; at: number }, newest: number, added: number) =>
	({ user: 'me', to: LATER - DAY_S, next: 9, pages: 8, done: true, newest, added, covered: 0, at: LATER - DAY_S, holes: [hole] }) as LastfmMark;
const kept2 = [...daily(600).slice(0, 200), ...daily(600).slice(400)];
/** What the archive is given before a step's run (a Spotify merge dropping rows, rows a closed tab wrote): explicit in the file. */
type Before = { add?: ArchivePlay[]; drop?: string[] };
type Step = { user: string; nowS: number; scrobbles: Scrobble[]; fails?: Odd[]; library?: LibItem[]; before?: (a: ArchiveStore) => Promise<Before>; holePages?: number } & Lies;
const runCases: { name: string; have?: ArchivePlay[]; mark?: LastfmMark; steps: Step[] }[] = [
	{ name: 'every page, newest first', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450) }] },
	{ name: 'carries on from the page it stopped at, waiting out a busy Last.fm', mark: { user: 'me', to: T + 100_000, next: 2, pages: 3, done: false, newest: 0, added: 0, covered: 0, at: 0 }, steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 2, times: 2, kind: 'busy' }] }] },
	{ name: 'busy six times is given up, the pages before it kept', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 2, times: 6, kind: 'busy' }] }] },
	{ name: 'a reconnect stops at once', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 3, times: 1, kind: 'reconnect' }] }] },
	{ name: 'tops up with what is newer, from two weeks below the newest', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(300) }, { user: 'me', nowS: T + 300_000, scrobbles: [...many(300), sc('Fresh', T + 200_000), sc('Fresher', T + 250_000)] }] },
	{ name: 'a top-up of more than a page', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(10) }, { user: 'me', nowS: T + 900_000, scrobbles: [...many(10), ...many(450, T + 200_000, 'New')] }] },
	{ name: 'a top-up that finds nothing', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(10) }, { user: 'me', nowS: T + 200_000, scrobbles: many(10) }] },
	{ name: 'another user starts over, beside the first', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(250) }, { user: 'someone else', nowS: T + 300_000, scrobbles: [sc('Theirs', T)] }] },
	{ name: 'nothing scrobbled yet: done, and the whole history read again later', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: [] }, { user: 'me', nowS: T + 900_000, scrobbles: many(3, T + 500_000) }, { user: 'me', nowS: T + 1_000_000, scrobbles: [...many(3, T + 500_000), sc('After', T + 950_000)] }] },
	{ name: 'a resumed run whose bound is old tops up straight after', mark: { user: 'me', to: T + 30_000, next: 2, pages: 2, done: false, newest: T + 29_940, added: 200, covered: 0, at: T + 30_000 }, steps: [{ user: 'me', nowS: T + 100_000, scrobbles: [...many(300), sc('Later', T + 50_000)] }] },
	{ name: 'Spotify plays cover some, and lend their lengths', have: [spotify('Song 3', T + 3 * 60 - 10, 240_000), spotify('Song 7', T + 7 * 60, 187_000)], steps: [{ user: 'me', nowS: T + 100_000, scrobbles: [...many(12), sc('Song 7', T + 9000)] }] },
	{ name: 'an empty page before the last is not the end', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(600), fails: [{ page: 2, times: 1, empty: { pages: 3, total: 600 } }] }] },
	{ name: 'a page busy every time stops there, and the next run carries on from it', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(600), fails: [{ page: 2, times: 6, kind: 'busy' }] }, { user: 'me', nowS: T + 100_000, scrobbles: many(600) }] },
	{ name: 'an empty page saying there are fewer pages is asked again, and a stumble passes', mark: { user: 'me', to: T + 100_000, next: 2, pages: 3, done: false, newest: T + 599 * 60, added: 0, covered: 0, at: 0 }, steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(600), fails: [{ page: 2, times: 2, empty: { pages: 0, total: 0 } }] }] },
	{ name: 'a history that really shrank is believed once asked enough', mark: { user: 'me', to: T + 100_000, next: 3, pages: 3, done: false, newest: T + 599 * 60, added: 400, covered: 0, at: 0 }, steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(300) }] },
	{
		name: 'two accounts: each counts its own',
		steps: [
			{ user: 'alice', nowS: T + 100_000, scrobbles: many(10, T, 'Alice') },
			{ user: 'bob', nowS: T + 100_000, scrobbles: many(5, T + 50_000, 'Bob') },
			{ user: 'alice', nowS: T + 200_000, scrobbles: many(10, T, 'Alice') },
			{ user: 'alice', nowS: T + 300_000, scrobbles: [...many(10, T, 'Alice'), sc('Alice New', T + 250_000)] },
			{ user: 'alice', nowS: T + 400_000, scrobbles: [...many(10, T, 'Alice'), sc('Alice New', T + 250_000)] }
		]
	},
	{
		name: 'another account’s rows taken over by Spotify leave this account’s count alone',
		steps: [
			{ user: 'alice', nowS: T + 100_000, scrobbles: many(10, T, 'Alice') },
			{ user: 'bob', nowS: T + 100_000, scrobbles: many(5, T + 50_000, 'Bob') },
			{ user: 'bob', nowS: T + 200_000, scrobbles: many(5, T + 50_000, 'Bob'), before: async (a) => ({ drop: (await a.all()).filter((r) => r.t.startsWith('Alice') && Number(r.t.split(' ')[1]) < 4).map((r) => r.id) }) }
		]
	},
	{
		name: 'a late scrobble, stamped below the newest kept, comes in with the next top-up, once',
		steps: [
			{ user: 'me', nowS: T + 100_000, scrobbles: many(10) },
			{ user: 'me', nowS: T + 200_000, scrobbles: [...many(10), sc('Played Offline', T + 9 * 60 - 3 * 86_400), sc('New', T + 150_000)] },
			{ user: 'me', nowS: T + 300_000, scrobbles: [...many(10), sc('Played Offline', T + 9 * 60 - 3 * 86_400), sc('New', T + 150_000)] }
		]
	},
	{ name: 'the overlap counts what Spotify covers once, not again on every top-up', have: [spotify('Song 5', T + 5 * 60 - 10)], steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(10) }, { user: 'me', nowS: T + 200_000, scrobbles: [...many(10), sc('New', T + 150_000)] }] },
	{
		name: 'a top-up cut short counts what Spotify covers once, when the window is all in',
		steps: [
			{ user: 'me', nowS: T + 100_000, scrobbles: many(5) },
			{ user: 'me', nowS: T + 900_000, scrobbles: [...many(5), ...many(450, T + 200_000, 'New')], fails: [{ page: 2, times: 1, topup: true, kind: 'reconnect' }], before: async () => ({ add: [spotify('New 449', T + 200_000 + 449 * 60 - 10)] }) },
			{ user: 'me', nowS: T + 900_000, scrobbles: [...many(5), ...many(450, T + 200_000, 'New')] }
		]
	},
	{
		name: 'a top-up cut short keeps its count but not its window',
		steps: [
			{ user: 'me', nowS: T + 100_000, scrobbles: many(5) },
			{ user: 'me', nowS: T + 900_000, scrobbles: [...many(5), ...many(450, T + 200_000, 'New')], fails: [{ page: 2, times: 1, topup: true, kind: 'reconnect' }] },
			{ user: 'me', nowS: T + 900_000, scrobbles: [...many(5), ...many(450, T + 200_000, 'New')] }
		]
	},
	{
		name: 'the count is the archive’s: rows written before the app closed count on the next run',
		steps: [
			{ user: 'me', nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 2, times: 1, kind: 'reconnect' }] },
			{ user: 'me', nowS: T + 100_000, scrobbles: many(450), before: async (a) => ({ add: planScrobbles(await a.all(), many(450).slice(50, 250), new Map(), songKey).add }) }
		]
	},
	{ name: 'a page with plays saying fewer pages is asked again, and the read goes on', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(600), fails: [{ page: 2, times: 1, short: { pages: 2, total: 400 } }] }] },
	{ name: 'a smaller total that stays is believed, after six asks', mark: { user: 'me', to: T + 100_000, next: 2, pages: 3, done: false, newest: T + 599 * 60, added: 200, covered: 0, at: 0 }, steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(600), fails: [{ page: 2, times: 9, short: { pages: 2, total: 400 } }] }] },
	{
		name: 'three runs in a row within the hour never skip a page: a short incident is not a hole',
		steps: [0, 1, 2, 3].map((i) => ({ user: 'me', nowS: T + 100_000 + i * 60, scrobbles: many(600), fails: [{ page: 2, times: 99, kind: 'busy' as const, hole: true }] }))
	},
	{
		name: 'a page empty before the last, three runs over an hour, is skipped and kept as a stretch of time; the import completes',
		steps: [0, 1, 2, 3].map((i) => ({ user: 'me', nowS: LATER + i * HALF_HOUR, scrobbles: daily(600), hide: HIDE_2 }))
	},
	{
		name: 'a first page stuck and skipped never marks a fresh import done: the page count comes from the empty answer',
		steps: [
			...[0, 1, 2, 3].map((i) => ({ user: 'me', nowS: LATER + i * HALF_HOUR, scrobbles: daily(600), hide: { lo: dayOf(400), hi: dayOf(599), to: LATER } })),
			{ user: 'me', nowS: LATER + 3 * HALF_HOUR + DAY_S, scrobbles: daily(600) }
		]
	},
	{
		name: 'an incident, every page empty for a run, never ends the read as complete: it stays busy, and the next run finishes',
		steps: [
			{ user: 'me', nowS: LATER, scrobbles: daily(600), incident: 1 },
			{ user: 'me', nowS: LATER + 60, scrobbles: daily(600) }
		]
	},
	{
		name: 'a tail gone under a stale total: three runs over an hour, then a hole down to the start, until it reads as nothing there',
		steps: [
			...[0, 1, 2, 3].map((i) => ({ user: 'me', nowS: LATER + i * HALF_HOUR, scrobbles: daily(600).slice(200), stale: { extra: 400, to: LATER } })),
			{ user: 'me', nowS: LATER + 3 * HALF_HOUR + DAY_S, scrobbles: daily(600).slice(200), stale: { extra: 400, to: LATER } },
			{ user: 'me', nowS: LATER + 3 * HALF_HOUR + DAY_S + 3600, scrobbles: daily(600).slice(200), stale: { extra: 400, to: LATER } }
		]
	},
	{
		name: 'a hole is asked again by time: kept while withheld, read in once sent, with the history shifted above it, nothing lost',
		steps: [
			...[0, 1, 2, 3].map((i) => ({ user: 'me', nowS: LATER + i * HALF_HOUR, scrobbles: daily(600), hide: HIDE_2 })),
			{ user: 'me', nowS: LATER + DAY_S, scrobbles: daily(600), hide: HIDE_2 },
			// 50 scrobbles above it deleted: every page number below them moves up by 50
			{ user: 'me', nowS: LATER + 2 * DAY_S, scrobbles: daily(550) },
			{ user: 'me', nowS: LATER + 3 * DAY_S, scrobbles: daily(550) }
		]
	},
	{
		name: 'a big hole is asked a few pages a run, kept as it narrows page by page; new scrobbles come in first, every run',
		have: planScrobbles([], [...six.slice(0, 200), ...six.slice(1200)], new Map(), songKey).add,
		mark: doneWithHole({ from: sixOf(199), to: sixOf(1200), at: 0 }, sixOf(1399), 400),
		steps: [
			{ user: 'me', nowS: LATER, scrobbles: [...six, sc('New 0', LATER - 100)], holePages: 2 },
			{ user: 'me', nowS: LATER + 3600, scrobbles: [...six, sc('New 0', LATER - 100), sc('New 1', LATER + 1000)], holePages: 2 },
			{ user: 'me', nowS: LATER + 7200, scrobbles: [...six, sc('New 0', LATER - 100), sc('New 1', LATER + 1000)], holePages: 10 }
		]
	},
	{
		name: 'each bound of a hole is asked a second wider: a play in the same second as a bound, left out by Last.fm’s bounds, comes in',
		have: planScrobbles([], kept2, new Map(), songKey).add,
		mark: doneWithHole({ from: dayOf(199), to: dayOf(400), at: 0 }, dayOf(599), 400),
		steps: [{ user: 'me', nowS: LATER, scrobbles: [...daily(600), sc('Twin', dayOf(400))], exclusive: true }]
	},
	{
		name: 'a hole that won’t come is asked once, and again only after a day, doubling',
		have: planScrobbles([], kept2, new Map(), songKey).add,
		mark: doneWithHole({ from: dayOf(199), to: dayOf(400), at: 0 }, dayOf(599), 400),
		steps: [LATER, LATER + 3600, LATER + DAY_S].map((nowS) => ({ user: 'me', nowS, scrobbles: daily(600), fails: [{ page: 1, times: 99, to: dayOf(400) + 1, kind: 'busy' as const }] }))
	},
	{
		name: 'a hole Last.fm says is empty is dropped only when it says so again, at least an hour later',
		have: planScrobbles([], kept2, new Map(), songKey).add,
		mark: doneWithHole({ from: dayOf(200), to: dayOf(399), at: 0 }, dayOf(599), 400),
		steps: [LATER, LATER + 1800, LATER + 3600].map((nowS) => ({ user: 'me', nowS, scrobbles: kept2 }))
	},
	{
		name: 'a same-second play left alone at a bound is kept',
		have: planScrobbles([], kept2, new Map(), songKey).add,
		mark: doneWithHole({ from: dayOf(199), to: dayOf(400), at: 0 }, dayOf(599), 400),
		steps: [LATER, LATER + 3600].map((nowS) => ({ user: 'me', nowS, scrobbles: [...kept2, sc('Twin', dayOf(400))] }))
	},
	{
		name: 'beside another account’s rows, a hole asked again counts this account’s own plays, as the whole read does',
		steps: [
			{ user: 'alice', nowS: LATER, scrobbles: daily(600) },
			...[0, 1, 2, 3].map((i) => ({ user: 'bob', nowS: LATER + i * HALF_HOUR, scrobbles: daily(600), hide: HIDE_2 })),
			{ user: 'bob', nowS: LATER + 3 * HALF_HOUR + DAY_S, scrobbles: daily(600) }
		]
	},
	{
		name: 'a fresh whole read after one that found nothing drops the old stuck page and holes',
		mark: { user: 'me', to: T + 50_000, next: 2, pages: 0, done: true, newest: 0, added: 0, covered: 0, at: T + 50_000, oldest: T + 10_000, stuck: { page: 1, runs: 2, since: T, last: T + 40_000, pages: 3 }, holes: [{ from: 0, to: T + 50_000, at: T + 50_000 }] },
		steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(5) }]
	},
	{
		name: 'an earlier build’s skipped page loads as the stretch it could hold, and the next top-up reads it through',
		mark: { user: 'me', to: T + 100_000, next: 4, pages: 3, done: true, newest: T + 599 * 60, added: 400, covered: 0, at: T + 100_000, skipped: [{ page: 2, to: T + 100_000, at: T + 100_000 }] } as never,
		have: planScrobbles([], [...many(600).slice(0, 200), ...many(600).slice(400)], new Map(), songKey).add,
		steps: [{ user: 'me', nowS: T + 200_000, scrobbles: many(600) }]
	},
	{ name: 'an outage is not a hole: plain busy pages are never skipped', steps: [1, 2, 3, 4].map(() => ({ user: 'me', nowS: T + 100_000, scrobbles: many(600), fails: [{ page: 2, times: 99, kind: 'busy' as const }] })) },
	{
		name: 'a stuck page that reads again is no longer stuck',
		steps: [
			{ user: 'me', nowS: T + 100_000, scrobbles: many(600), fails: [{ page: 2, times: 99, kind: 'busy', hole: true }] },
			{ user: 'me', nowS: T + 100_000, scrobbles: many(600) }
		]
	},
	{
		name: 'a top-up’s page empty before its last, three top-ups over an hour, is kept as a stretch of time and the window moves',
		steps: [
			{ user: 'me', nowS: T + 100_000, scrobbles: many(5) },
			...[0, 1, 2, 3].map((i) => ({ user: 'me', nowS: T + 900_000 + i * HALF_HOUR, scrobbles: [...many(5), ...many(450, T + 200_000, 'New')], fails: [{ page: 2, times: 99, topup: true, kind: 'busy' as const, hole: true }] }))
		]
	},
	{
		name: 'lengths at write time, from Spotify and the library',
		have: [spotify('Both', T - 100_000, 201_000)],
		steps: [{ user: 'me', nowS: T + 100_000, library: [{ title: 'Both', artist: 'Band', durationMs: 999_000 }, { title: 'Library', artist: 'Band', durationMs: 187_400 }, { title: 'Short', artist: 'Band', durationMs: 12_000 }, { title: 'Broken', artist: 'Band', durationMs: 7 * 3600_000 }], scrobbles: [sc('Both', T), sc('Library', T + 600), sc('Short', T + 1200), sc('Broken', T + 1800), sc('Guessed', T + 2400)] }]
	}
];
const runs = [];
for (const c of runCases) {
	const archive = memoryArchive();
	if (c.have?.length) await archive.apply(c.have, []);
	if (c.mark) await archive.set('lastfm', c.mark);
	const steps = [];
	for (const st of c.steps) {
		const before = st.before ? await st.before(archive) : {};
		if (before.add?.length || before.drop?.length) await archive.apply(before.add ?? [], before.drop ?? []);
		const calls: Q[] = [];
		const progress: { page: number; pages: number; added: number }[] = [];
		let error: string | null = null;
		let mark: LastfmMark | null = null;
		let start: LastfmMark | null = null;
		try {
			mark = await importScrobbles(archive, fakeLastfm(st.scrobbles, st.fails ?? [], calls, { hide: st.hide, stale: st.stale, incident: st.incident, exclusive: st.exclusive }), {
				...(st.holePages ? { holePages: st.holePages } : {}),
				user: st.user,
				nowS: st.nowS,
				lengths: libraryLengths(st.library ?? [], songKey),
				gapMs: 0,
				busyMs: 0,
				onStart: (m) => (start = { ...m }),
				progress: (p) => progress.push({ ...p })
			});
		} catch (e) {
			error = e instanceof LastfmError ? e.kind : String(e);
		}
		const rows = sortById(await archive.all());
		const { before: _, ...plain } = st;
		steps.push({ ...plain, library: st.library ?? [], before: { add: before.add ?? [], drop: before.drop ?? [] }, calls, progress, error, start, mark, stored: (await archive.get('lastfm')) ?? null, rows });
	}
	runs.push({ name: c.name, have: c.have ?? [], mark: c.mark ?? null, steps });
}
write('runs.json', runs, true);

// ── start.json ──────────────────────────────────────────────────────────────────────────────────────────────────────

const m = (x: Partial<LastfmMark>): LastfmMark => ({ user: 'me', to: T, next: 1, pages: 0, done: false, newest: 0, added: 0, covered: 0, at: 0, ...x });
const startCases: { name: string; prev: LastfmMark | null; user: string; nowS: number; held: number }[] = [
	{ name: 'none yet', prev: null, user: 'me', nowS: 100, held: 0 },
	{ name: 'none yet, but rows another account brought', prev: null, user: 'me', nowS: 100, held: 12 },
	{ name: 'another user’s mark: starts over, theirs the base', prev: m({ user: 'them', added: 40, done: true, newest: 90 }), user: 'me', nowS: 100, held: 40 },
	{ name: 'another user’s, nothing held', prev: m({ user: 'them', added: 0 }), user: 'me', nowS: 100, held: 0 },
	{ name: 'user names compared as written', prev: m({ user: 'Me', added: 3 }), user: 'me', nowS: 100, held: 3 },
	{ name: 'a whole read that found nothing reads again below now', prev: m({ done: true, newest: 0, next: 2, pages: 0, to: 50, at: 50 }), user: 'me', nowS: 100, held: 0 },
	{ name: 'a mark short of the rows it wrote: the archive decides', prev: m({ added: 190, next: 3, pages: 5 }), user: 'me', nowS: 100, held: 200 },
	{ name: 'over rows Spotify took over since: the archive decides', prev: m({ added: 200, done: true, newest: 90 }), user: 'me', nowS: 100, held: 150 },
	{ name: 'beside another account’s rows, the count stays its own', prev: m({ added: 3, base: 40 }), user: 'me', nowS: 100, held: 43 },
	{ name: 'beside another account’s rows, fewer held', prev: m({ added: 3, base: 40 }), user: 'me', nowS: 100, held: 10 },
	{ name: 'beside, a whole read that found nothing', prev: m({ added: 0, base: 7, done: true, newest: 0, next: 2 }), user: 'me', nowS: 100, held: 7 },
	{
		name: 'an earlier build’s skipped pages load as the stretches they could hold, after any holes',
		prev: m({ done: true, newest: 90, added: 3, holes: [{ from: 10, to: 20, at: 30 }], skipped: [{ page: 2, to: 50, at: 60 }, { page: 1, to: 95, from: 70, at: 96 }] } as never),
		user: 'me',
		nowS: 100,
		held: 3
	},
	{ name: 'an earlier build’s bare count of skipped pages is dropped, and a stuck page kept without its times', prev: m({ added: 3, skipped: 2, stuck: { page: 2, runs: 1 } } as never), user: 'me', nowS: 100, held: 3 },
	{ name: 'a stuck tail and the oldest read carry on', prev: m({ added: 3, next: 3, pages: 4, oldest: 40, stuck: { page: 3, runs: 2, since: 10, last: 20, pages: 4, tail: true } }), user: 'me', nowS: 100, held: 3 }
];
write('start.json', startCases.map((c) => ({ ...c, out: startMark(c.prev, c.user, c.nowS, c.held) })));

// ── lengths.json ────────────────────────────────────────────────────────────────────────────────────────────────────

const NOW = T + 1_000_000;
const guessedRows = (list: Scrobble[]) => planScrobbles([], list, new Map(), songKey).add;
const libraryCases: { name: string; items: LibItem[] }[] = [
	{ name: 'the longest per song key, broken lengths left out', items: planCases[planCases.length - 1]!.library! },
	{ name: 'remasters and case folded into one song', items: [{ title: 'Hit', artist: 'Band', durationMs: 200_000 }, { title: 'HIT - 2011 Remaster', artist: 'band', durationMs: 201_000 }, { title: 'Hit (Live)', artist: 'Band', durationMs: 300_000 }] },
	{ name: 'nothing', items: [] }
];
const library = libraryCases.map((c) => ({ ...c, out: sortedObject(libraryLengths(c.items, songKey)) }));
const bestCases = [
	{ name: 'the archive wins', archive: { a: 1, b: 2 }, library: { b: 3, c: 4 } },
	{ name: 'no library', archive: { a: 1 }, library: null },
	{ name: 'no archive', archive: {}, library: { c: 4 } }
];
const best = bestCases.map((c) => ({ ...c, out: sortedObject(bestLengths(new Map(Object.entries(c.archive)), c.library ? new Map(Object.entries(c.library)) : undefined)) }));
const msCases = [0, 1, 9_000, 29_999, 29_999.5, 30_000, 30_000.4, 30_000.5, 187_412.6, 210_000, 6 * 3600_000];
const ms = msCases.map((x) => ({ length: x, ms: scrobbleMs(x) }));

const k = (t: string) => songKey(t, 'Band');
const planRows = guessedRows([sc('Rare', T), sc('Hit', T + 1), sc('Hit', T + 2), sc('hit', T + 3), sc('Hit - 2011 Remaster', T + 4), sc('Known', T + 5), sc('Known', T + 6), sc('Missed', T + 7), sc('Missed', T + 8), sc('Old Miss', T + 9)]);
const planAll: ArchivePlay[] = [...planRows, spotify('Spotify Song', T - 9000), { ...guessedRows([sc('Timed', T + 10)])[0]!, ms: 190_000, f: 64 }];
const lengthPlanCases: { name: string; rows: ArchivePlay[]; known: Record<string, number>; none: Record<string, number>; failed: Record<string, number>; failures?: Record<string, number>; nowS: number }[] = [
	{ name: 'failed twice in a row, a day and a half ago: two days to wait, its count kept', rows: guessedRows([sc('Bad', T), sc('Good', T + 1)]), known: {}, none: {}, failed: { [k('Bad')]: NOW - 1.5 * 86_400 }, failures: { [k('Bad')]: 2 }, nowS: NOW },
	{ name: 'failed twice, two days ago: asked again, its count kept', rows: guessedRows([sc('Bad', T), sc('Good', T + 1)]), known: {}, none: {}, failed: { [k('Bad')]: NOW - 2 * 86_400 }, failures: { [k('Bad')]: 2 }, nowS: NOW },
	{ name: 'failed nine times: sixteen days at most', rows: guessedRows([sc('Bad', T)]), known: {}, none: {}, failed: { [k('Bad')]: NOW - 15 * 86_400 }, failures: { [k('Bad')]: 9 }, nowS: NOW },
	{ name: 'a failure long ago is kept with its count until answered', rows: guessedRows([sc('Bad', T)]), known: {}, none: {}, failed: { [k('Bad')]: NOW - 400 * 86_400 }, failures: { [k('Bad')]: 1 }, nowS: NOW },
	{ name: 'a failed song Spotify now knows is filled, its failure gone', rows: guessedRows([sc('Bad', T)]), known: { [k('Bad')]: 201_000 }, none: {}, failed: { [k('Bad')]: NOW - 10 }, failures: { [k('Bad')]: 3 }, nowS: NOW },
	{ name: 'fills what is known, asks the most played first in the spelling most use', rows: planAll, known: { [k('Known')]: 222_000 }, none: { [k('Missed')]: NOW - 86_400, [k('Old Miss')]: NOW - 30 * 86_400 - 1, [k('Gone')]: NOW - 10 }, failed: {}, nowS: NOW },
	{ name: 'a miss exactly 30 days old is asked again; one a second younger waits', rows: planAll, known: {}, none: { [k('Missed')]: NOW - 30 * 86_400, [k('Old Miss')]: NOW - 30 * 86_400 + 1 }, failed: {}, nowS: NOW },
	{ name: 'a miss from the future is asked again', rows: planAll, known: {}, none: { [k('Missed')]: NOW + 5 }, failed: { [k('Rare')]: NOW + 5 }, nowS: NOW },
	{ name: 'a failed song waits a day; a lately missed one stays a miss', rows: guessedRows([sc('Bad', T), sc('Bad', T + 1), sc('Good', T + 2), sc('Both', T + 3)]), known: {}, none: { [k('Both')]: NOW - 60 }, failed: { [k('Bad')]: NOW - 3600, [k('Both')]: NOW - 60, [k('Gone')]: NOW - 10 }, nowS: NOW },
	{ name: 'a day on, the failed song is asked', rows: guessedRows([sc('Bad', T), sc('Bad', T + 1), sc('Good', T + 2)]), known: {}, none: {}, failed: { [k('Bad')]: NOW - 86_400 }, nowS: NOW },
	{ name: 'ties of spellings to string order, ties of plays to the key', rows: guessedRows([sc('b song', T), sc('B Song', T + 1), sc('Zed', T + 2), sc('Abc', T + 3), sc('Abc', T + 4, 'band'), sc('Abc', T + 5, 'Band')]), known: {}, none: {}, failed: {}, nowS: NOW },
	{ name: 'a known length of 0 is no length', rows: guessedRows([sc('Zero', T)]), known: { [k('Zero')]: 0 }, none: {}, failed: {}, nowS: NOW }
];
const lengthPlans = lengthPlanCases.map((c) => {
	const g = guessedBySong(c.rows);
	return { ...c, guessed: sortedObject(new Map([...g].map(([key, rows]) => [key, rows.map((r) => r.id)]))), plan: planLengths(g, new Map(Object.entries(c.known)), c.none, c.nowS, c.failed, c.failures ?? {}) };
});

/** A Last.fm that knows `lengths` (by title); `fails` throw for the asks numbered `at` (from 1, across the slice) or of `track`. */
/** `throws`: the ask throws something that isn't a LastfmError (an answer that couldn't be read). */
type LengthFail = { at?: number[]; track?: string; kind: 'busy' | 'reconnect' | 'unavailable' | 'throws' };
type Slice = { nowS: number; max?: number; library?: LibItem[]; lengths: Record<string, number | null>; hit?: boolean; fails?: LengthFail[] };
const fillCases: { name: string; have: ArchivePlay[]; mark?: LengthsMark; slices: Slice[] }[] = [
	{
		name: 'each length written as it comes, the misses kept, the next slice asks what is left',
		have: guessedRows([sc('A', T), sc('A', T + 1), sc('A', T + 2), sc('B', T + 3), sc('B', T + 4), sc('C', T + 5), sc('D', T + 6)]),
		slices: [
			{ nowS: NOW, max: 2, lengths: { A: 201_000, B: null, C: 150_000, D: 99_000 } },
			{ nowS: NOW + 60, lengths: { A: 201_000, B: null, C: 150_000, D: 99_000 } },
			{ nowS: NOW + 120, lengths: { B: null } },
			{ nowS: NOW + 30 * 86_400 + 1, lengths: { B: null } }
		]
	},
	{ name: 'songs Spotify or the library now know are timed without asking', have: [...guessedRows([sc('Exported', T), sc('Liked', T + 1), sc('Asked', T + 2)]), spotify('Exported', T - 90_000, 240_000)], slices: [{ nowS: NOW, library: [{ title: 'Liked', artist: 'Band', durationMs: 199_000 }, { title: 'Exported', artist: 'Band', durationMs: 1000 }], lengths: { Asked: 9_000 }, hit: false }] },
	{ name: 'a busy Last.fm waited out', have: guessedRows([sc('A', T), sc('B', T + 1), sc('C', T + 2)]), slices: [{ nowS: NOW, lengths: { A: 200_000, B: 200_000, C: 200_000 }, fails: [{ at: [2, 3], kind: 'busy' }] }] },
	{ name: 'busy after every try: that song waits a day and the next is asked', have: guessedRows([sc('A', T), sc('B', T + 1), sc('C', T + 2)]), slices: [{ nowS: NOW, lengths: { A: 200_000 }, fails: [{ at: Array.from({ length: 20 }, (_, i) => i + 2), kind: 'busy' }] }] },
	{
		name: 'one song Last.fm keeps failing on never holds up the rest, and is asked a day on',
		have: guessedRows([sc('Bad', T), sc('Bad', T + 1), sc('Bad', T + 2), sc('Good', T + 3), sc('Fine', T + 4)]),
		slices: [
			{ nowS: NOW, lengths: { Good: 180_000, Fine: 180_000 }, fails: [{ track: 'Bad', kind: 'busy' }] },
			{ nowS: NOW + 60, lengths: {}, fails: [{ track: 'Bad', kind: 'busy' }] },
			{ nowS: NOW + 86_400, lengths: { Bad: 240_000 } }
		]
	},
	{ name: 'three songs failing in a row is Last.fm down: the slice stops', have: guessedRows(Array.from({ length: 6 }, (_, i) => sc(`S${i}`, T + i))), slices: [{ nowS: NOW, lengths: {}, fails: [{ at: Array.from({ length: 30 }, (_, i) => i + 1), kind: 'busy' }] }] },
	{ name: 'two failing, then one answered, then two more: the slice goes on', have: guessedRows(Array.from({ length: 6 }, (_, i) => sc(`S${i}`, T + i))), slices: [{ nowS: NOW, lengths: { S2: 100_000, S5: 100_000 }, fails: [{ track: 'S0', kind: 'busy' }, { track: 'S1', kind: 'busy' }, { track: 'S3', kind: 'busy' }, { track: 'S4', kind: 'busy' }] }] },
	{ name: 'a reconnect stops at once and marks nothing', have: guessedRows([sc('A', T), sc('B', T + 1)]), slices: [{ nowS: NOW, lengths: {}, fails: [{ at: [1], kind: 'reconnect' }] }] },
	{ name: 'an unavailable reader stops at once and marks nothing', have: guessedRows([sc('A', T), sc('B', T + 1)]), slices: [{ nowS: NOW, lengths: {}, fails: [{ at: [1], kind: 'unavailable' }] }] },
	{ name: 'a reconnect after a length keeps the length', have: guessedRows([sc('A', T), sc('A', T + 1), sc('B', T + 2)]), slices: [{ nowS: NOW, lengths: { A: 222_000 }, fails: [{ at: [2], kind: 'reconnect' }] }] },
	{ name: 'a mark from before: old misses pruned, the timed count grows', have: guessedRows([sc('A', T), sc('B', T + 1)]), mark: { none: { [k('B')]: NOW - 100, [k('Gone')]: NOW - 100, [k('A')]: NOW - 40 * 86_400 }, failed: { [k('Gone')]: NOW - 5 }, timed: 7, at: NOW - 100 }, slices: [{ nowS: NOW, lengths: { A: 31_000 } }] },
	{ name: 'nothing guessed: no asks, the mark saved', have: [spotify('Only Spotify', T)], slices: [{ nowS: NOW, lengths: {} }] },
	{
		name: 'three songs failing in a row end the run, however its slices fall (the streak carries)',
		have: guessedRows(Array.from({ length: 6 }, (_, i) => sc(`S${i}`, T + i))),
		slices: [1, 2, 3].map(() => ({ nowS: NOW, max: 1, lengths: {}, fails: [{ at: [1, 2], kind: 'busy' as const }] }))
	},
	{
		name: 'an answer ends a streak',
		have: guessedRows([sc('A', T), sc('B', T + 1), sc('C', T + 2), sc('D', T + 3)]),
		slices: [
			{ nowS: NOW, max: 2, lengths: { C: 200_000, D: 200_000 }, fails: [{ track: 'A', kind: 'busy' }, { track: 'B', kind: 'busy' }] },
			{ nowS: NOW, lengths: { C: 200_000, D: 200_000 } }
		]
	},
	{
		name: 'a song failing day after day waits longer each time; an answer clears it',
		have: guessedRows([sc('Bad', T), sc('Bad', T + 1)]),
		slices: [
			{ nowS: NOW, lengths: {}, fails: [{ track: 'Bad', kind: 'busy' }] },
			{ nowS: NOW + 86_400, lengths: {}, fails: [{ track: 'Bad', kind: 'busy' }] },
			{ nowS: NOW + 2 * 86_400, lengths: {}, fails: [{ track: 'Bad', kind: 'busy' }] },
			{ nowS: NOW + 3 * 86_400, lengths: { Bad: null } }
		]
	},
	{
		name: 'an unusable answer marks its song before the pass stops; the next pass goes on to the next',
		have: guessedRows([sc('A', T), sc('A', T + 1), sc('B', T + 2)]),
		slices: [
			{ nowS: NOW, lengths: { B: 200_000 }, fails: [{ track: 'A', kind: 'unavailable' }] },
			{ nowS: NOW + 60, lengths: { B: 200_000 }, fails: [{ track: 'A', kind: 'unavailable' }] }
		]
	},
	{ name: 'an ask that throws marks its song too', have: guessedRows([sc('A', T), sc('B', T + 1)]), slices: [{ nowS: NOW, lengths: {}, fails: [{ track: 'A', kind: 'throws' }] }] },
	{ name: 'lengths over six hours or under 0 are none; under 30 s a listen', have: guessedRows([sc('Long', T), sc('Neg', T + 1), sc('Tiny', T + 2), sc('Max', T + 3)]), slices: [{ nowS: NOW, lengths: { Long: 6 * 3600_000 + 1, Neg: -5, Tiny: 1, Max: 6 * 3600_000 } }] }
];
/** The Worker's answer read as httpLengthReader reads it (a length over MAX_SONG_MS or not above 0 is none). */
const asRead = (v: number | null | undefined) => (typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 6 * 3600_000 ? v : null);
const fills = [];
for (const c of fillCases) {
	const base = memoryArchive();
	await base.apply(c.have, []);
	if (c.mark) await base.set('lengths', c.mark);
	const slices = [];
	let streak = 0;
	for (const sl of c.slices) {
		const saves: LengthsMark[] = [];
		// Every save of the mark, as it was given
		const archive: ArchiveStore = { ...base, set: async (key: never, v: never) => (key === 'lengths' && saves.push(structuredClone(v)), base.set(key, v)) } as ArchiveStore;
		const asked: string[] = [];
		const look: LengthReader = async ({ artist, track: title }) => {
			asked.push(`${title} / ${artist}`);
			const f = (sl.fails ?? []).find((x) => (x.at && x.at.includes(asked.length)) || x.track === title);
			if (f) throw f.kind === 'throws' ? new TypeError('not a length') : new LastfmError(f.kind);
			return { ms: asRead(sl.lengths[title]), hit: sl.hit ?? true };
		};
		// The streak carries from slice to slice, as the service runs them
		const run = await fillLengths(archive, look, { nowS: sl.nowS, lengths: libraryLengths(sl.library ?? [], songKey), max: sl.max, gapMs: 0, busyMs: 0, streak });
		slices.push({ ...sl, library: sl.library ?? [], fails: sl.fails ?? [], streak, asked, run, saves, rows: sortById(await base.all()) });
		streak = run.streak;
	}
	fills.push({ name: c.name, have: c.have, mark: c.mark ?? null, slices });
}
write('lengths.json', { library, best, ms, plan: lengthPlans, fill: fills }, true);

// ── service.json ────────────────────────────────────────────────────────────────────────────────────────────────────

const lib = () => ({ importData: async () => ({ likes: 0, playlists: 0, songs: 0, history: 0, skipped: 0 }), likes: writable([]), playlists: writable([]), history: writable([]) });
type Run = { nowS: number; scrobbles: Scrobble[]; user?: string; fails?: Odd[]; hide?: { lo: number; hi: number } };
const serviceCases: { name: string; runs: Run[] }[] = [
	{ name: 'a run that carries on counts the whole import and its own part; a top-up, what was newer', runs: [{ nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 2, times: 1, kind: 'reconnect' }] }, { nowS: T + 100_000, scrobbles: many(450) }, { nowS: T + 200_000, scrobbles: [...many(450), sc('Newer', T + 150_000)] }, { nowS: T + 300_000, scrobbles: [...many(450), sc('Newer', T + 150_000)] }] },
	{ name: 'a whole first read that finds nothing says so; the next reads the whole history again', runs: [{ nowS: T + 100_000, scrobbles: [] }, { nowS: T + 200_000, scrobbles: many(3, T + 120_000) }] },
	{ name: 'a whole read in one go: all of it this time', runs: [{ nowS: T + 100_000, scrobbles: many(250) }] },
	{ name: 'another account: its own whole read beside the first', runs: [{ nowS: T + 100_000, scrobbles: many(20, T, 'Alice'), user: 'alice' }, { nowS: T + 200_000, scrobbles: many(5, T + 50_000, 'Bob'), user: 'bob' }, { nowS: T + 300_000, scrobbles: many(20, T, 'Alice'), user: 'alice' }] },
	{ name: 'busy part way: an error, and the next run counts what came in before', runs: [{ nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 3, times: 6, kind: 'busy' }] }, { nowS: T + 100_000, scrobbles: many(450) }] },
	{
		name: 'a hole still withheld: the done line says part is missing, until a top-up reads it through',
		runs: [
			...[0, 1, 2, 3].map((i) => ({ nowS: LATER + i * HALF_HOUR, scrobbles: daily(600), hide: HIDE_2 })),
			{ nowS: LATER + DAY_S, scrobbles: daily(600), hide: HIDE_2 },
			{ nowS: LATER + 2 * DAY_S, scrobbles: daily(600) }
		]
	}
];
const service = [];
for (const c of serviceCases) {
	const archive = memoryArchive();
	let nowS = c.runs[0]!.nowS;
	let reader: PageReader = async () => ({ plays: [], page: 1, pages: 0, total: 0 });
	// (the import's waits made short: what it does is the same)
	const svc = createSpotifyService({ library: lib() as never, archive, lastfmPages: (q) => reader(q), lastfmLengths: async () => ({ ms: null, hit: true }), now: () => nowS * 1000, lastfmTiming: { busyMs: 0, gapMs: 0 } });
	const states = [];
	for (const r of c.runs) {
		nowS = r.nowS;
		reader = fakeLastfm(r.scrobbles, r.fails ?? [], [], { hide: r.hide });
		await svc.importLastfm(r.user ?? 'me');
		await svc.timeSongs();
		states.push({ ...r, user: r.user ?? 'me', fails: r.fails ?? [], state: get(svc.lastfm), mark: get(svc.info).lastfm });
	}
	service.push({ name: c.name, runs: states });
}
write('service.json', service, true);

// ── words.json ──────────────────────────────────────────────────────────────────────────────────────────────────────

const doneCases: [number, number | undefined][] = [[0, undefined], [1, undefined], [7660, undefined], [0, 0], [3, 3], [1, 1], [0, 30_060], [7660, 30_060], [30_060, 30_060], [1, 2], [5, 3]];
const missingCases: [number, number | undefined][] = [[0, undefined], [200, undefined], [400, 400], [0, 0], [7660, 30_060]];
write('words.json', {
	done: [
		...doneCases.map(([added, total]) => ({ added, total: total ?? null, missing: false, line: doneLine(added, total) })),
		...missingCases.map(([added, total]) => ({ added, total: total ?? null, missing: true, line: doneLine(added, total, true) }))
	],
	missing: MISSING,
	reconnect: LASTFM_ERRORS.reconnect.line,
	hours: HOURS_NOTE
});

// ── sources.json ────────────────────────────────────────────────────────────────────────────────────────────────────

const SOURCES: Source[] = ['spotify', 'stash', 'lastfm'];
const raws = [null, '', 'nope', 'null', '[1]', '7', '{}', '{"spotify":false,"lastfm":true}', '{"stash":"false"}', '{"stash":0,"lastfm":null}', '{"spotify":false,"stash":false,"lastfm":false}', '{"lastfm":false,"evil":1}'];
const read = raws.map((raw) => ({ raw, on: readSources(raw) }));
const countsList: (Counts | null)[] = [null, { spotify: 0, stash: 0, lastfm: 0 }, { spotify: 3, stash: 0, lastfm: 0 }, { spotify: 3, stash: 2, lastfm: 0 }, { spotify: 0, stash: 5, lastfm: 9 }, { spotify: 6086, stash: 333, lastfm: 48210 }];
const ons: SourcesOn[] = [];
for (let mm = 0; mm < 8; mm++) ons.push({ spotify: !!(mm & 1), stash: !!(mm & 2), lastfm: !!(mm & 4) });
const rules = [];
for (const counts of countsList) {
	for (const on of ons) {
		rules.push({ counts, on, present: present(counts), shown: shownSources(on, counts), toggle: Object.fromEntries(SOURCES.map((s) => [s, toggleSource(on, s, counts)])) });
	}
}
write('sources.json', { read, rules });

console.log(
	`wrote ${history.length} history, ${plan.length} plan, ${covered.length} covered, ${runs.length} run, ${startCases.length} start, ` +
		`${lengthPlans.length} length plan, ${fills.length} fill, ${service.length} service, ${doneCases.length + missingCases.length} done line and ${rules.length} rule vectors to ${out}`
);
