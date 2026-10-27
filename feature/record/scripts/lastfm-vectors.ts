/**
 * Parity vectors for the app's Last.fm import and the record's source switches (feature/record `lastfm/`, `archive/RecordSources`):
 * cases crafted around every rule, run through the web player's own code, with what they gave written beside them. The app's
 * `LastfmVectorsTest` reads the same files and must give the same.
 *
 * - history.json: Last.fm answers read by the Worker's `historyPage` (stash-player worker/lastfm-history.ts), a fake fetch
 *   handing it each body.
 * - plan.json: `planScrobbles` with `knownLengths` and `songKey` (src/lib/spotify/plays.ts, taste.ts), and `coveredBy`.
 * - runs.json: whole `importScrobbles` runs (src/lib/spotify/lastfm.ts) against a fake Last.fm (newest first, 200 a page,
 *   filtered by `to` and `from`, busy for a few asks of one page), every ask, every progress, the mark and the archive's ids.
 * - sources.json: record.ts `readSources`, `present`, `shownSources` and `toggleSource` over every choice and source.
 *
 * Run from this repository's root, against a stash-player checkout with its node_modules (read only):
 *   PLAYER=../stash-web/player-upi
 *   $PLAYER/node_modules/.bin/esbuild feature/record/scripts/lastfm-vectors.ts --bundle --platform=node --format=esm \
 *     --alias:@player=$PLAYER/src/lib --alias:@worker=$PLAYER/worker --outfile=build/lastfm-vectors.mjs
 *   node build/lastfm-vectors.mjs feature/record/src/test/resources/lastfm
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { memoryArchive } from '@player/spotify/archive';
import { importScrobbles, LastfmError, type HistoryPage, type LastfmMark, type PageReader } from '@player/spotify/lastfm';
import { coveredBy, knownLengths, planScrobbles, toArchive, type ArchivePlay, type Scrobble } from '@player/spotify/plays';
import { songKey } from '@player/spotify/taste';
import { present, readSources, shownSources, toggleSource, type Counts, type Source, type SourcesOn } from '@player/ui/spotify/record';
import { historyPage } from '@worker/lastfm-history';

const out = process.argv[2];
if (!out) throw new Error('usage: node lastfm-vectors.mjs <out dir>');
mkdirSync(out, { recursive: true });
/** Written readably, or ([compact]) on one line: the runs hold every row of every step. */
const write = (name: string, v: unknown, compact = false) => writeFileSync(join(out, name), `${compact ? JSON.stringify(v) : JSON.stringify(v, null, 1)}\n`);

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
const historyCases: { name: string; page: number; body: unknown }[] = [
	{ name: 'a page', page: 1, body: page([track('Song', 'Band', String(T)), track('Other', 'Band', String(T - 60))]) },
	{ name: 'the song playing now is left out', page: 1, body: page([{ ...track('Now', 'Band', undefined), '@attr': { nowplaying: 'true' }, date: undefined }, track('Song', 'Band', String(T))]) },
	{ name: 'nowplaying not the string true', page: 1, body: page([{ ...track('Now', 'Band', String(T)), '@attr': { nowplaying: true } }]) },
	{ name: 'one track is an object, not a list', page: 2, body: page(track('Alone', 'Band', String(T)), { page: '2', totalPages: '2', total: '201' }) },
	{ name: 'no tracks at all', page: 1, body: page(undefined, { page: '1', totalPages: '0', total: '0' }) },
	{ name: 'tracks of the wrong shape', page: 1, body: page([null, 5, 'x', [], { name: 'No artist', date: { uts: String(T) } }, track('Song', 'Band', String(T))]) },
	{ name: 'artist given by name, not #text', page: 1, body: page([{ ...track('Song', 'x', String(T)), artist: { name: 'Band' } }]) },
	{ name: 'artist as a string', page: 1, body: page([{ ...track('Song', 'x', String(T)), artist: 'Band' }]) },
	{ name: 'whitespace runs become one space, trimmed', page: 1, body: page([track('  A\t\tSong \n Title ', ' Band　', String(T))]) },
	{ name: 'a control character drops the scrobble', page: 1, body: page([track('Bad\u0007Song', 'Band', String(T)), track('Bad\u0085Song', 'Band', String(T)), track('Good', 'Band\u0001', String(T))]) },
	{ name: 'a bidi override drops the scrobble', page: 1, body: page([track('Evil‮Song', 'Band', String(T)), track('Iso⁦late', 'Band', String(T))]) },
	{ name: 'words longer than 300 drop it; 300 exactly stays', page: 1, body: page([track('x'.repeat(301), 'Band', String(T)), track('y'.repeat(300), 'Band', String(T)), track('z'.repeat(310), 'Band', String(T), {}), track(' '.repeat(5) + 'w'.repeat(300) + ' '.repeat(5), 'Band', String(T))]) },
	{ name: 'an album that is not clean is dropped, the scrobble kept', page: 1, body: page([{ ...track('Song', 'Band', String(T)), album: { '#text': 'Bad\u0002Album' } }, { ...track('Two', 'Band', String(T)), album: { '#text': '   ' } }, { ...track('Three', 'Band', String(T)), album: 'Flat' }, { ...track('Four', 'Band', String(T)), album: undefined }]) },
	{ name: 'times as JavaScript reads them', page: 1, body: page([track('Pad', 'Band', ` ${T} `), track('Hex', 'Band', '0x65FE0000'), track('Exp', 'Band', '1.7e9'), track('Frac', 'Band', `${T}.9`), track('Junk', 'Band', `${T}abc`), track('Num', 'Band', T), track('Empty', 'Band', ''), track('Neg', 'Band', '-5'), track('Inf', 'Band', 'Infinity'), track('Bool', 'Band', true), track('Plus', 'Band', `+${T}`)]) },
	{ name: 'times before 2002 and more than a day ahead are broken rows', page: 1, body: page([track('Old', 'Band', String(Date.UTC(2001, 11, 31, 23, 59, 59) / 1000)), track('First', 'Band', String(Date.UTC(2002, 0, 1) / 1000)), track('Day', 'Band', String(NOW_S + 86_400)), track('Past', 'Band', String(NOW_S + 86_401))]) },
	{ name: 'error 6: no such user, an empty last page', page: 4, body: { error: 6, message: 'User not found' } },
	{ name: 'error 17 as text: a private profile, an empty last page', page: 1, body: { error: '17', message: 'Login: User required to be logged in' } },
	{ name: 'error 29: try again', page: 1, body: { error: 29, message: 'Rate Limit Exceeded' } },
	{ name: 'error 8: try again', page: 1, body: { error: '8', message: 'Operation failed' } },
	{ name: 'error 0 is no error', page: 1, body: { error: 0, ...page([track('Song', 'Band', String(T))]) } },
	{ name: 'no recenttracks: try again', page: 1, body: { something: 'else' } },
	{ name: 'recenttracks a string: try again', page: 1, body: { recenttracks: 'nothing' } },
	{ name: 'recenttracks a list: an empty page', page: 3, body: { recenttracks: [] } },
	{ name: 'paging as text, missing or odd', page: 5, body: page([track('Song', 'Band', String(T))], { page: '0', totalPages: '-1', total: 'many' }) },
	{ name: 'paging as numbers', page: 1, body: page([track('Song', 'Band', String(T))], { page: 7, totalPages: 12.9, total: 2400 }) },
	{ name: 'no @attr', page: 2, body: { recenttracks: { track: [track('Song', 'Band', String(T))] } } },
	{ name: 'case and accents kept as written', page: 1, body: page([track('Björk', 'BJÖRK', String(T)), track('Jóga', 'Björk', String(T))]) }
];
const history = [];
for (const c of historyCases) {
	const fetchFn = async () => new Response(JSON.stringify(c.body), { status: 200, headers: { 'content-type': 'application/json' } });
	const answer = await historyPage('me', 'key', { page: c.page, to: NOW_S }, fetchFn, 'https://ws.audioscrobbler.com/2.0/', NOW_S);
	history.push({ name: c.name, page: c.page, nowS: NOW_S, body: c.body, answer });
}
write('history.json', history);

// ── plan.json ───────────────────────────────────────────────────────────────────────────────────────────────────────

const spotify = (title: string, startS: number, ms = 200_000, o: { done?: boolean; artist?: string; account?: boolean } = {}) =>
	toArchive({ title, artist: o.artist ?? 'Band', startedAt: startS * 1000, endedAt: startS * 1000 + ms, ms, ...(o.account ? { src: 'a' as const } : { done: o.done ?? true, chosen: false, src: 'x' as const }) });
const sc = (t: string, uts: number, a = 'Band', al?: string): Scrobble => (al ? { t, a, al, uts } : { t, a, uts });
const S = 180;
const planCases: { name: string; have: ArchivePlay[]; scrobbles: Scrobble[] }[] = [
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
	}
];
const plan = planCases.map((c) => {
	const lengths = knownLengths(c.have, songKey);
	const p = planScrobbles(c.have, c.scrobbles, lengths, songKey);
	return { name: c.name, have: c.have, scrobbles: c.scrobbles, lengths: Object.fromEntries([...lengths].sort()), plan: p };
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

type Fail = { page: number; times: number; kind: 'busy' | 'reconnect' | 'unavailable' };
/** Last.fm as the web's test fakes it: the window below `to` (and from `from`), newest first, 200 a page. */
function fakeLastfm(all: Scrobble[], fails: Fail[], calls: { page: number; to: number; from?: number }[]): PageReader {
	const left = fails.map((f) => ({ ...f }));
	return async (q) => {
		calls.push(q.from ? { page: q.page, to: q.to, from: q.from } : { page: q.page, to: q.to });
		const f = left.find((x) => x.page === q.page && x.times > 0);
		if (f) {
			f.times--;
			throw new LastfmError(f.kind);
		}
		const window = all.filter((s) => s.uts <= q.to && (!q.from || s.uts >= q.from)).sort((a, b) => b.uts - a.uts);
		const pages = Math.ceil(window.length / 200);
		return { plays: window.slice((q.page - 1) * 200, q.page * 200), page: q.page, pages, total: window.length } satisfies HistoryPage;
	};
}
const many = (n: number, from = T, prefix = 'Song') => Array.from({ length: n }, (_, i) => sc(`${prefix} ${i}`, from + i * 60));
type Step = { user: string; nowS: number; scrobbles: Scrobble[]; fails?: Fail[] };
const runCases: { name: string; have?: ArchivePlay[]; mark?: LastfmMark; steps: Step[] }[] = [
	{ name: 'every page, newest first', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450) }] },
	{ name: 'carries on from the page it stopped at, waiting out a busy Last.fm', mark: { user: 'me', to: T + 100_000, next: 2, pages: 3, done: false, newest: 0, added: 0, covered: 0, at: 0 }, steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 2, times: 2, kind: 'busy' }] }] },
	{ name: 'busy six times is given up, the pages before it kept', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 2, times: 6, kind: 'busy' }] }] },
	{ name: 'a reconnect stops at once', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(450), fails: [{ page: 3, times: 1, kind: 'reconnect' }] }] },
	{ name: 'tops up with only what is newer', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(300) }, { user: 'me', nowS: T + 300_000, scrobbles: [...many(300), sc('Fresh', T + 200_000), sc('Fresher', T + 250_000)] }] },
	{ name: 'a top-up of more than a page', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(10) }, { user: 'me', nowS: T + 900_000, scrobbles: [...many(10), ...many(450, T + 200_000, 'New')] }] },
	{ name: 'a top-up that finds nothing', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(10) }, { user: 'me', nowS: T + 200_000, scrobbles: many(10) }] },
	{ name: 'another user starts over', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: many(250) }, { user: 'someone else', nowS: T + 300_000, scrobbles: [sc('Theirs', T)] }] },
	{ name: 'nothing scrobbled yet: done, and no top-up later', steps: [{ user: 'me', nowS: T + 100_000, scrobbles: [] }, { user: 'me', nowS: T + 900_000, scrobbles: many(3, T + 500_000) }] },
	{ name: 'a resumed run whose bound is old tops up straight after', mark: { user: 'me', to: T + 30_000, next: 2, pages: 2, done: false, newest: T + 29_940, added: 200, covered: 0, at: T + 30_000 }, steps: [{ user: 'me', nowS: T + 100_000, scrobbles: [...many(300), sc('Later', T + 50_000)] }] },
	{ name: 'Spotify plays cover some, and lend their lengths', have: [spotify('Song 3', T + 3 * 60 - 10, 240_000), spotify('Song 7', T + 7 * 60, 187_000)], steps: [{ user: 'me', nowS: T + 100_000, scrobbles: [...many(12), sc('Song 7', T + 9000)] }] }
];
const runs = [];
for (const c of runCases) {
	const archive = memoryArchive();
	if (c.have?.length) await archive.apply(c.have, []);
	if (c.mark) await archive.set('lastfm', c.mark);
	const steps = [];
	for (const st of c.steps) {
		const calls: { page: number; to: number; from?: number }[] = [];
		const progress: { page: number; pages: number; added: number }[] = [];
		let error: string | null = null;
		let mark: LastfmMark | null = null;
		try {
			mark = await importScrobbles(archive, fakeLastfm(st.scrobbles, st.fails ?? [], calls), { user: st.user, nowS: st.nowS, gapMs: 0, busyMs: 0, progress: (p) => progress.push({ ...p }) });
		} catch (e) {
			error = e instanceof LastfmError ? e.kind : String(e);
		}
		const rows = (await archive.all()).sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
		steps.push({ ...st, calls, progress, error, mark, stored: (await archive.get('lastfm')) ?? null, rows });
	}
	runs.push({ name: c.name, have: c.have ?? [], mark: c.mark ?? null, steps });
}
write('runs.json', runs, true);

// ── sources.json ────────────────────────────────────────────────────────────────────────────────────────────────────

const SOURCES: Source[] = ['spotify', 'stash', 'lastfm'];
const raws = [null, '', 'nope', 'null', '[1]', '7', '{}', '{"spotify":false,"lastfm":true}', '{"stash":"false"}', '{"stash":0,"lastfm":null}', '{"spotify":false,"stash":false,"lastfm":false}', '{"lastfm":false,"evil":1}'];
const read = raws.map((raw) => ({ raw, on: readSources(raw) }));
const countsList: (Counts | null)[] = [null, { spotify: 0, stash: 0, lastfm: 0 }, { spotify: 3, stash: 0, lastfm: 0 }, { spotify: 3, stash: 2, lastfm: 0 }, { spotify: 0, stash: 5, lastfm: 9 }, { spotify: 6086, stash: 333, lastfm: 48210 }];
const ons: SourcesOn[] = [];
for (let m = 0; m < 8; m++) ons.push({ spotify: !!(m & 1), stash: !!(m & 2), lastfm: !!(m & 4) });
const rules = [];
for (const counts of countsList) {
	for (const on of ons) {
		rules.push({ counts, on, present: present(counts), shown: shownSources(on, counts), toggle: Object.fromEntries(SOURCES.map((s) => [s, toggleSource(on, s, counts)])) });
	}
}
write('sources.json', { read, rules });

console.log(`wrote ${history.length} history, ${plan.length} plan, ${covered.length} covered, ${runs.length} run and ${rules.length} rule vectors to ${out}`);
