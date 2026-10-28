/**
 * Parity vectors for the app's taste (feature/record `taste/`) and the mixes' seam (core/data `record/RecordMixTaste`): cases
 * crafted around every rule, run through the web player's own code, with what it gave written beside them. The app's
 * `TasteVectorsTest` reads the same files and must give the same, doubles to the last bit.
 *
 * - taste.json: `computeTaste` (src/lib/spotify/taste.ts) over crafted plays: remaster titles; KELVIN SIGN, LONG S, a dotless i
 *   and Arabic-Indic digits; ASCII and non-ASCII word boundaries around "intro"; astral characters against U+E000–U+FFFF in
 *   the play order; mode ties; songs only ever started; an artist that folds to ""; an era cosine of exactly 0.4 after round9 and
 *   one just under; quiet months; a gap of 3 months and of 4; the era's longest; a lead tie; a same-lead join; a peak of exactly
 *   14 days; UTC year edges; kept beats hidden; put back against hidden. Every field of the taste.
 * - mix-taste.json: `mixTasteOf` (src/lib/spotify/mix-taste.ts) over those tastes, with songs put back and songs in the library
 *   (`kept`: liked or in a playlist now, never kept out at read time) and without.
 * - seeds.json: `seedArtists` (src/lib/discover/mix.ts) for crafted histories and taste artists: the own seeds alone, then the
 *   blend.
 * - refresh.json: `refreshTaste` (src/lib/spotify/importer.ts) over a memory archive and this device's own plays: the scrobbles
 *   they cover left out, an own play the archive holds already counted once, hidden, kept and put back.
 * - kept-out.json: `keptOut` and `whyKeptOut` (src/lib/spotify/kept-out.ts) with `whyLine` (src/lib/ui/spotify/years.ts), and the
 *   put-back rules of the archive and the service (src/lib/spotify/archive.ts `putBack`, `clear`; service.ts `removeLastfm`).
 * - words.json: song-key.ts `bareTitle`/`songKey`, station.ts `primaryArtist` and taste.ts `INTERLUDE` over tricky words, and the
 *   English of Settings' rows (src/routes/settings/RecordInMixes.svelte, read from the file) and the import's taste step.
 *
 * Run from this repository's root, against a stash-player checkout with its node_modules (read only), at the rules the app ports
 * (stash-player fix/record-taste-seeds, PR #29, until it is merged; master after):
 *   PLAYER=../stash-web/player-mix-taste
 *   NODE_PATH=$PLAYER/node_modules $PLAYER/node_modules/.bin/esbuild feature/record/scripts/taste-vectors.ts --bundle --platform=node --format=esm \
 *     --alias:@player=$PLAYER/src/lib --outfile=build/taste-vectors.mjs
 *   node build/taste-vectors.mjs feature/record/src/test/resources/taste $PLAYER
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { writable } from 'svelte/store';
import type { HistoryEntry, LibraryItem } from '@player/api';
import { descriptorKey } from '@player/identity';
import { seedArtists, type MixTaste } from '@player/discover/mix';
import { primaryArtist } from '@player/radio/station';
import { memoryArchive } from '@player/spotify/archive';
import { refreshTaste } from '@player/spotify/importer';
import { keptOut, whyKeptOut } from '@player/spotify/kept-out';
import { mixTasteOf } from '@player/spotify/mix-taste';
import { F_ACCOUNT, F_CHOSEN, F_DONE, F_ESTIMATED, F_LASTFM, F_REASONS, F_SKIPPED, playId, type ArchivePlay } from '@player/spotify/plays';
import { createSpotifyService } from '@player/spotify/service';
import { bareTitle, songKey } from '@player/spotify/song-key';
import { computeTaste, INTERLUDE, type Taste, type TastePlay } from '@player/spotify/taste';
import { progressOf, whyLine } from '@player/ui/spotify/years';

const out = process.argv[2];
const player = process.argv[3];
if (!out || !player) throw new Error('usage: node taste-vectors.mjs <out dir> <stash-player checkout>');
mkdirSync(out, { recursive: true });
const write = (name: string, v: unknown) => writeFileSync(join(out, name), `${JSON.stringify(v, null, 1)}\n`);
const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

const DAY = 86_400_000;
const HOUR = 3_600_000;
const NOW = Date.UTC(2026, 9, 27, 12);
const R = F_REASONS;
const FULL = F_REASONS | F_DONE;
const PICKED = F_REASONS | F_DONE | F_CHOSEN;
const SKIP = F_REASONS | F_SKIPPED;

const p = (t: string, a: string, s: number, ms = 200_000, f = FULL, extra: { al?: string; sid?: string } = {}): TastePlay => ({ t, a, s, ms, f, ...extra });
/** n plays of one song, a day apart from [s]. */
const many = (n: number, t: string, a: string, s: number, ms = 200_000, f = FULL, step = DAY): TastePlay[] => Array.from({ length: n }, (_, i) => p(t, a, s + i * step, ms, f));

// ── taste.json ──────────────────────────────────────────────────────────────────────────────────────────────────────

type Case = { name: string; now: number; plays: TastePlay[]; hidden?: string[]; kept?: string[]; putBack?: string[] };
const cases: Case[] = [];
const Y24 = Date.UTC(2024, 0, 15);

cases.push({
	name: 'remaster titles are one song; the words most seen win; a play picked counts twice',
	now: NOW,
	plays: [
		...many(3, 'Old Friend - 2011 Remaster', 'Evergreen', Y24),
		...many(2, 'Old Friend (Remastered)', 'Evergreen', Y24 + 10 * DAY),
		...many(4, 'Old Friend', 'Evergreen', Y24 + 20 * DAY, 200_000, PICKED),
		p('Old Friend – Remastered 2009', 'Evergreen', Y24 + 30 * DAY),
		p('Old Friend [2015 Remaster Version]', 'Evergreen', Y24 + 31 * DAY),
		p('Old Friend (Digitally Remastered)', 'Evergreen', Y24 + 32 * DAY),
		p('Old Friend - Digital Remaster', 'Evergreen', Y24 + 33 * DAY),
		p('Remastered', 'Evergreen', Y24 + 34 * DAY),
		p(' - Remaster', 'Evergreen', Y24 + 35 * DAY),
		p('Song - 2011 Remaster ', 'Band', Y24 + 36 * DAY),
		p('Song  -  2011   Remaster', 'Band', Y24 + 37 * DAY),
		p('Song - 2011 Remaster\n', 'Band', Y24 + 38 * DAY),
		p('Song - 2011 Remastered Version (Live)', 'Band', Y24 + 39 * DAY),
		p('Song — 2011 Remaster', 'Band', Y24 + 40 * DAY),
		p('Song - 2011 Remaster', 'Band', Y24 + 41 * DAY),
		p('Song\u0085-\u0085Remaster', 'Band', Y24 + 42 * DAY),
		p('Song﻿- Remaster', 'Band', Y24 + 43 * DAY)
	]
});

cases.push({
	name: 'JavaScript folds ASCII letters only, and its \\d is 0-9: KELVIN SIGN, LONG S, a dotless i, Arabic-Indic and full-width digits',
	now: NOW,
	plays: [
		p('Song - ٢٠١١ Remaster', 'Band', Y24),
		p('Song - ２０１１ Remaster', 'Band', Y24 + DAY),
		p('Song - 2011 Remaſter', 'Band', Y24 + 2 * DAY),
		p('Song - 2011 REMASTER', 'Band', Y24 + 3 * DAY),
		p('Song - 2011 remaster VERSION', 'Band', Y24 + 4 * DAY),
		p('Song - 2011 Remaster versıon', 'Band', Y24 + 5 * DAY),
		p('Song - DIGITALLY Remastered', 'Band', Y24 + 6 * DAY),
		// "skit" spelt with KELVIN SIGN is no skit in JavaScript
		...many(4, 'SKit', 'Band', Y24 + 10 * DAY, 3_000, SKIP),
		...many(4, 'Skit', 'Band', Y24 + 20 * DAY, 3_000, SKIP),
		...many(4, 'İntro', 'Band', Y24 + 30 * DAY, 3_000, SKIP),
		// the credit's cut: a dotless i is no "with"
		...many(3, 'Duet', 'Ana wıth Ben', Y24 + 40 * DAY),
		...many(3, 'Duet Two', 'Ana WITH Ben', Y24 + 50 * DAY),
		...many(3, 'Duet Three', 'Ana feat. Ben', Y24 + 60 * DAY),
		...many(3, 'Duet Four', 'Ana\u0085feat. Ben', Y24 + 70 * DAY)
	]
});

cases.push({
	name: 'an interlude is a whole ASCII word: non-ASCII letters are boundaries, digits and _ are not',
	now: NOW,
	plays: ['Intro', 'Introé', 'éIntro', 'Intro_', 'Intro1', 'Intro-1', 'Outro.', 'commentary track', 'INTERLUDE', 'Interludes', 'Skït', 'Track (intro)', 'introduction'].flatMap((t, i) =>
		// 2 of 3 starts left early: a rate of 0.67, enough for an interlude, never for the skip rule
		[p(t, 'Band', Y24 + i * 10 * DAY, 3_000, SKIP), p(t, 'Band', Y24 + i * 10 * DAY + DAY, 3_000, SKIP), p(t, 'Band', Y24 + i * 10 * DAY + 2 * DAY)]
	)
});

cases.push({
	name: 'the play order is UTF-16: an astral character comes before U+E000-U+FFFF, and the first spelling seen wins a tie',
	now: NOW,
	plays: [
		p('Song ！', 'Band', Y24, 200_000, FULL, { al: 'Album ！' }),
		p('Song 🎵', 'Band', Y24, 200_000, FULL, { al: 'Album 🎵' }),
		p('Song ', 'Band', Y24 + DAY),
		p('Song 😀', 'Band', Y24 + DAY),
		p('Tie', 'Band', Y24 + 2 * DAY, 200_000, FULL, { al: 'B side', sid: '1111111111111111111111' }),
		p('TIE', 'Band', Y24 + 3 * DAY, 200_000, FULL, { al: 'A side', sid: '2222222222222222222222' }),
		p('tie', 'BAND', Y24 + 4 * DAY),
		p('Same', 'Band', Y24 + 5 * DAY, 250_000),
		p('Same', 'Band', Y24 + 5 * DAY, 240_000)
	]
});

cases.push({
	name: 'songs only ever started: no listen, no weight, first and last 0; skips and the 75% rule',
	now: NOW,
	plays: [
		...many(6, 'Never Again', 'Artist Y', Y24, 5_000, SKIP),
		...many(5, 'Almost', 'Artist Y', Y24, 5_000, SKIP),
		...many(2, 'Almost', 'Artist Y', Y24 + 10 * DAY),
		...many(6, 'Edge', 'Artist Y', Y24 + 20 * DAY, 29_999, R),
		...many(2, 'Edge', 'Artist Y', Y24 + 30 * DAY, 30_000, R),
		...many(3, 'Shuffled', 'Artist Y', Y24 + 40 * DAY, 200_000, F_REASONS),
		...many(3, 'Account', 'Artist Y', Y24 + 50 * DAY, 200_000, F_ACCOUNT),
		...many(3, 'Phone', 'Artist Y', Y24 + 60 * DAY, 200_000, 0),
		...many(3, 'Scrobbled', 'Artist Y', Y24 + 70 * DAY, 210_000, F_LASTFM | F_ESTIMATED)
	]
});

cases.push({
	name: 'an artist that folds to nothing counts in the totals and the songs, never in the artists, months or years',
	now: NOW,
	plays: [...many(10, 'Strange', '!!!', Y24), ...many(10, 'Emoji', '🎵', Y24 + 100 * DAY), ...many(5, 'Real', 'Band', Y24 + 200 * DAY), ...many(3, 'Later', '!!!', Date.UTC(2025, 5, 1))]
});

cases.push({
	name: 'kept beats hidden and the skip rules; put back beats hidden, and a put-back song can still be lost',
	now: NOW,
	hidden: [songKey('Hidden Kept', 'Band'), songKey('Hidden Back', 'Band'), songKey('Hidden', 'Band'), songKey('Lost Back', 'Band')],
	kept: [songKey('Hidden Kept', 'Band'), songKey('Skipped Kept', 'Band'), songKey('Lost Kept', 'Band')],
	putBack: [songKey('Hidden Back', 'Band'), songKey('Lost Back', 'Band')],
	plays: [
		...many(3, 'Hidden Kept', 'Band', Y24),
		...many(3, 'Hidden Back', 'Band', Y24),
		...many(3, 'Hidden', 'Band', Y24),
		...many(8, 'Skipped Kept', 'Band', Y24 + 10 * DAY, 2_000, SKIP),
		...many(10, 'Lost Kept', 'Band', Date.UTC(2022, 0, 1)),
		...many(10, 'Lost Back', 'Band', Date.UTC(2022, 0, 1)),
		...many(10, 'Lost', 'Band', Date.UTC(2022, 0, 1)),
		...many(10, 'Not Yet Lost', 'Band', NOW - 364 * DAY),
		...many(10, 'Just Lost', 'Band', NOW - 365 * DAY - 9 * DAY)
	]
});

cases.push({
	name: 'peak of exactly 14 days, and one millisecond more; obsessions; UTC year edges; the years, found and constants',
	now: NOW,
	plays: [
		...many(11, 'Fortnight', 'Peak', Y24, 200_000, FULL, HOUR),
		p('Fortnight', 'Peak', Y24 + 14 * DAY),
		...many(11, 'Fortnight And A Bit', 'Peak', Y24 + 100 * DAY, 200_000, FULL, HOUR),
		p('Fortnight And A Bit', 'Peak', Y24 + 100 * DAY + 14 * DAY + 1),
		p('Year End', 'Edge', Date.UTC(2023, 11, 31, 23, 59, 59, 999)),
		p('Year End', 'Edge', Date.UTC(2024, 0, 1)),
		p('Month End', 'Edge', Date.UTC(2024, 1, 29, 23, 59, 59, 999)),
		p('Month End', 'Edge', Date.UTC(2024, 2, 1)),
		...[2019, 2020, 2021, 2022, 2023, 2024, 2025].flatMap((y) => many(2, 'Constant', 'Forever', Date.UTC(y, 3, 1))),
		...[2019, 2020, 2021].flatMap((y) => many(2, 'Three Years', 'Forever', Date.UTC(y, 3, 1))),
		...Array.from({ length: 24 }, (_, i) => p(`New ${i}`, `Artist ${i}`, Date.UTC(2021, 6, 1) + i * HOUR)),
		...Array.from({ length: 25 }, (_, i) => p(`Newer ${i}`, `Later ${i}`, Date.UTC(2022, 6, 1) + i * HOUR)),
		...Array.from({ length: 20 }, (_, i) => p(`Few ${i}`, `Few ${i}`, Date.UTC(2023, 6, 1) + i * HOUR))
	]
});

// ── eras: built month by month ──────────────────────────────────────────────────────────────────────────────────────

/** One month's listening: artist -> hours, each as one play per hour (a day apart) from the month's 2nd. */
const month = (y: number, m: number, hours: Record<string, number>, ms = HOUR): TastePlay[] =>
	Object.entries(hours).flatMap(([a, h]) => Array.from({ length: h }, (_, i) => p(`${a} song ${i % 7}`, a, Date.UTC(y, m, 2) + i * (DAY / 4), ms)));
/** A big era month: [lead] most of it, the rest a few minutes each (an era needs a lead three times the average artist). */
const eraMonth = (y: number, m: number, lead: string, hours: number, others = ['Filler A', 'Filler B', 'Filler C', 'Filler D', 'Filler E']) => [
	...month(y, m, { [lead]: hours }),
	...others.map((o, i) => p(`${o} tune`, o, Date.UTC(y, m, 20) + i * HOUR, 120_000))
];

cases.push({
	name: 'eras: a run, a quiet month that breaks nothing, a gap of 3 joins and of 4 does not, the era is at most 4 months',
	now: NOW,
	plays: [
		...eraMonth(2021, 0, 'Northern Lights', 6),
		...month(2021, 1, { 'Northern Lights': 1 }),
		...eraMonth(2021, 2, 'Northern Lights', 6),
		...eraMonth(2021, 3, 'Northern Lights', 6),
		...eraMonth(2021, 4, 'Northern Lights', 6),
		...eraMonth(2021, 5, 'Northern Lights', 6),
		...eraMonth(2022, 0, 'Glass Harbor', 6),
		...eraMonth(2022, 3, 'Glass Harbor', 6),
		...eraMonth(2023, 0, 'Velvet Static', 6),
		...eraMonth(2023, 4, 'Velvet Static', 6)
	]
});

cases.push({
	name: 'eras: a lead tie goes to the key first in string order; two eras in a row with one lead are one; an even spread is no era',
	now: NOW,
	plays: [
		// Beta comes first in the plays, Alpha first in string order: the same time each, Alpha leads
		...Array.from({ length: 5 }, (_, i) => p('Beta song', 'Beta', Date.UTC(2021, 0, 2) + i * DAY, HOUR)),
		...Array.from({ length: 5 }, (_, i) => p('Alpha song', 'Alpha', Date.UTC(2021, 0, 12) + i * DAY, HOUR)),
		...['Filler A', 'Filler B', 'Filler C', 'Filler D', 'Filler E', 'Filler F'].map((o, i) => p(`${o} tune`, o, Date.UTC(2021, 0, 25) + i * HOUR, 120_000)),
		...Array.from({ length: 5 }, (_, i) => p('Beta song', 'Beta', Date.UTC(2021, 1, 2) + i * DAY, HOUR)),
		...Array.from({ length: 5 }, (_, i) => p('Alpha song', 'Alpha', Date.UTC(2021, 1, 12) + i * DAY, HOUR)),
		...['Filler A', 'Filler B', 'Filler C', 'Filler D', 'Filler E', 'Filler F'].map((o, i) => p(`${o} tune`, o, Date.UTC(2021, 1, 25) + i * HOUR, 120_000)),
		...eraMonth(2022, 0, 'Paper Moons', 6),
		...eraMonth(2022, 1, 'Paper Moons', 6),
		// a month of someone else entirely: a new era starts, led by Paper Moons again
		...month(2022, 2, { 'Coral Choir': 3 }),
		...eraMonth(2022, 3, 'Paper Moons', 6),
		...eraMonth(2022, 4, 'Paper Moons', 6),
		...month(2023, 0, { A1: 2, A2: 2, A3: 2, A4: 2 }),
		...month(2023, 1, { A1: 2, A2: 2, A3: 2, A4: 2 })
	]
});

/** taste.ts's cosine and round9, to build a month whose cosine with the one before rounds to exactly 0.4 (the taste decides it). */
const round9 = (x: number) => Math.round(x * 1e9) / 1e9;
const cosine = (a: Map<string, number>, b: Map<string, number>) => {
	let dot = 0, na = 0, nb = 0;
	for (const [k, v] of a) {
		na += v * v;
		const w = b.get(k);
		if (w) dot += v * w;
	}
	for (const v of b.values()) nb += v * v;
	return na && nb ? round9(dot / Math.sqrt(na * nb)) : 0;
};
{
	// month 1: Lead L ms, five fillers F ms each; month 2: Lead x, Other y. Find y with round9(cos) === 0.4, and one just under.
	const L = 40 * HOUR;
	const F = 120_000;
	const m1 = new Map<string, number>([['lead', L], ...['f a', 'f b', 'f c', 'f d', 'f e'].map((k) => [k, F] as [string, number])]);
	const x = 2_000 * HOUR;
	let lo = 1, hi = 10_000 * HOUR;
	// cos falls as y grows: the largest y with cos >= 0.4
	while (hi - lo > 1) {
		const mid = Math.floor((lo + hi) / 2);
		if (cosine(new Map([['lead', x], ['other', mid]]), m1) >= 0.4) lo = mid;
		else hi = mid;
	}
	const at = cosine(new Map([['lead', x], ['other', lo]]), m1);
	const under = cosine(new Map([['lead', x], ['other', hi]]), m1);
	if (at !== 0.4 || !(under < 0.4)) throw new Error(`no exact 0.4: ${at}, ${under}`);
	const eraPlays = (other: number, y: number): TastePlay[] => [
		p('Lead song', 'Lead', Date.UTC(y, 0, 5), L),
		...['F A', 'F B', 'F C', 'F D', 'F E'].map((o, i) => p(`${o} tune`, o, Date.UTC(y, 0, 20) + i * HOUR, F)),
		p('Lead song', 'Lead', Date.UTC(y, 1, 5), x),
		p('Other song', 'Other', Date.UTC(y, 1, 6), other)
	];
	cases.push({ name: `eras: a cosine of exactly 0.4 after round9 joins (other ${lo} ms), and one just under (${hi} ms) does not`, now: NOW, plays: [...eraPlays(lo, 2021), ...eraPlays(hi, 2023)] });
}

cases.push({
	name: 'recency halves every two years; a play from the future counts as now; constants need half the active years',
	now: NOW,
	plays: [...many(5, 'Fresh', 'Band', NOW - 10 * DAY), ...many(5, 'Two Years', 'Band', NOW - 2 * 365.25 * DAY - 4 * DAY), ...many(5, 'Future', 'Band', NOW + 3 * DAY), ...many(5, 'Ancient', 'Band', Date.UTC(2006, 0, 1))]
});

const tastes = cases.map((c) => ({
	...c,
	taste: computeTaste(c.plays, { now: c.now, hidden: new Set(c.hidden ?? []), kept: new Set(c.kept ?? []), putBack: new Set(c.putBack ?? []) })
}));
write(
	'taste.json',
	tastes.map((c) => ({ name: c.name, now: c.now, hidden: c.hidden ?? [], kept: c.kept ?? [], putBack: c.putBack ?? [], plays: c.plays, taste: c.taste }))
);

// ── mix-taste.json ──────────────────────────────────────────────────────────────────────────────────────────────────

const mixOut = (m: MixTaste) => ({ artists: m.artists, avoid: [...m.avoid].sort(cmp), weights: Object.fromEntries([...m.weights].sort(([a], [b]) => cmp(a, b))) });
write(
	'mix-taste.json',
	tastes.flatMap((c) => {
		const avoided = c.taste.songs.filter((s) => s.avoid).map((s) => s.key);
		const variants: [string[], string[]][] = [
			[[], []],
			[avoided.slice(0, 1), []],
			[avoided, []],
			[[...avoided.slice(0, 1), 'not|a song'], []],
			[[], avoided.slice(0, 1)],
			[avoided.slice(0, 1), avoided.slice(1, 2)],
			[[], [...avoided, 'not|a song']]
		];
		return variants.map(([pb, kept]) => ({ name: c.name, putBack: pb, kept, out: mixOut(mixTasteOf(c.taste, new Set(pb), new Set(kept))) }));
	})
);

// ── seeds.json ──────────────────────────────────────────────────────────────────────────────────────────────────────

const item = (title: string, artist: string): LibraryItem => ({ title, artist, key: descriptorKey({ title, artist }), refs: {}, addedAt: 0 });
const played = (artist: string, agoMs: number, title = 'x'): HistoryEntry => ({ item: item(title, artist), playedAt: NOW - agoMs });
type SeedCase = { name: string; history: HistoryEntry[]; taste: string[] };
const seedCases: SeedCase[] = [
	{ name: 'no plays: the taste alone', history: [], taste: ['Loved Band', 'Second Band', 'Third'] },
	{ name: 'no plays, a taste of ten: eight of them', history: [], taste: Array.from({ length: 10 }, (_, i) => `T${i}`) },
	{ name: 'a busy listener: the taste keeps three places', history: Array.from({ length: 12 }, (_, i) => played(`Here ${i}`, i * HOUR)), taste: ['T1', 'T2', 'T3', 'T4', 'T5'] },
	{ name: 'a quiet listener: the taste fills the places left', history: [0, 1].map((i) => played(`Here ${i}`, i * HOUR)), taste: ['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8'] },
	{ name: 'an artist played here is not counted twice (by the folded primary artist)', history: Array.from({ length: 12 }, (_, i) => played(i === 0 ? 'Ana' : `Here ${i}`, i * HOUR)), taste: ['ANA feat. Ben', 'Ána', 'T1', 'T2'] },
	{ name: 'a short taste: fewer than three', history: Array.from({ length: 12 }, (_, i) => played(`Here ${i}`, i * HOUR)), taste: ['Only'] },
	{ name: 'recent and long-run seeds, then the taste', history: [played('Recent', HOUR), played('Recent', 2 * HOUR), played('Older', 10 * DAY), played('Older', 11 * DAY), played('Oldest', 100 * DAY)], taste: ['T1', 'Older', 'T2', 'T3', 'T4', 'T5', 'T6'] },
	{ name: 'plays only from long ago (past 180 days): no own seeds, the taste alone', history: [played('Gone', 200 * DAY)], taste: ['T1', 'T2'] },
	{ name: 'an empty taste: the own seeds alone', history: [played('Here', HOUR)], taste: [] },
	{ name: 'a taste whose artists are all played here', history: [played('A', HOUR), played('B', 2 * HOUR)], taste: ['a', 'B with C'] },
	{ name: 'an empty artist in the taste', history: [played('Here', HOUR)], taste: ['', 'T1'] }
];
const mt = (artists: string[]): MixTaste => ({ artists, avoid: new Set(), weights: new Map() });
write(
	'seeds.json',
	seedCases.map((c) => {
		const base = { history: c.history, likes: [], playlists: [], skips: [], searchArtists: [], now: NOW };
		const alone = seedArtists({ ...base, taste: null });
		const blend = seedArtists({ ...base, taste: mt(c.taste) });
		return { name: c.name, own: alone.from === 'plays' ? alone.seeds : [], taste: c.taste, seeds: blend.seeds, from: blend.from };
	})
);

// ── refresh.json ────────────────────────────────────────────────────────────────────────────────────────────────────

const row = (t: string, a: string, s: number, ms: number, f: number, al?: string): ArchivePlay => ({ id: playId(t, a, s), t, a, s, ms, f, ...(al ? { al } : {}) });
/** One of this device's own plays as service.ts `live` makes it, from its words, start and the song's length. */
const live = (t: string, a: string, s: number, d?: number, al?: string) => ({ own: { t, a, s, ...(d !== undefined ? { d } : {}), ...(al ? { al } : {}) }, play: { t, a, ...(al ? { al } : {}), s, ms: Math.max(30_000, d ?? 0), f: 0 } as TastePlay });
type RefreshCase = { name: string; rows: ArchivePlay[]; live: ReturnType<typeof live>[]; hidden?: string[]; kept?: string[]; keep?: string[] };
const T0 = Date.UTC(2025, 4, 1, 12);
const refreshCases: RefreshCase[] = [
	{
		name: 'a scrobble an own play covers is left out: the same folded title, 180 s before its start to 180 s after its end',
		rows: [
			row('Song', 'Band', T0 - 180_000, 210_000, F_LASTFM | F_ESTIMATED),
			row('Song', 'Band', T0 - 180_001, 210_000, F_LASTFM | F_ESTIMATED),
			row('SONG!', 'Another Band', T0 + 240_000 + 180_000, 210_000, F_LASTFM),
			row('Song', 'Band', T0 + 240_000 + 180_001, 210_000, F_LASTFM),
			row('Song', 'Band', T0 + 100_000, 200_000, FULL)
		],
		live: [live('Song', 'Band', T0, 240_000)]
	},
	{
		name: 'an own play with no length lasts 30 s; one the archive holds by id is counted once (the archive’s)',
		rows: [row('Mine', 'Band', T0, 123_000, FULL), row('Short', 'Band', T0 + 600_000 + 30_000 + 180_000, 210_000, F_LASTFM), row('Short', 'Band', T0 + 600_000 + 30_000 + 180_001, 210_000, F_LASTFM)],
		live: [live('Mine', 'Band', T0, 500_000), live('Short', 'Band', T0 + 600_000), live('Short', 'Band', T0 + 600_000, 10_000)]
	},
	{
		name: 'a scrobble with an own play’s very id: the scrobble is covered and the own play held, so neither counts',
		rows: [row('Twin', 'Band', T0, 210_000, F_LASTFM)],
		live: [live('Twin', 'Band', T0, 200_000), live('Other', 'Band', T0 + DAY, 200_000, 'An Album')]
	},
	{
		name: 'hidden, kept and put back over the archive and the own plays',
		rows: [...many(3, 'Hidden', 'Band', T0).map((x) => row(x.t, x.a, x.s, x.ms, x.f)), ...many(8, 'Skipped', 'Band', T0, 1_000, SKIP).map((x) => row(x.t, x.a, x.s, x.ms, x.f)), ...many(8, 'Back', 'Band', T0, 1_000, SKIP).map((x) => row(x.t, x.a, x.s, x.ms, x.f))],
		live: [live('Kept', 'Band', T0 + DAY, 180_000)],
		hidden: [songKey('Hidden', 'Band'), songKey('Kept', 'Band')],
		kept: [songKey('Kept', 'Band')],
		keep: [songKey('Back', 'Band')]
	},
	{ name: 'no own plays: the archive as it is', rows: [row('Alone', 'Band', T0, 200_000, FULL), row('Scrobble', 'Band', T0 + HOUR, 210_000, F_LASTFM | F_ESTIMATED)], live: [] }
];
const refreshed = [];
for (const c of refreshCases) {
	const archive = memoryArchive();
	await archive.apply(c.rows, []);
	if (c.hidden) await archive.set('hidden', [...c.hidden].sort());
	if (c.keep) await archive.set('keep', [...c.keep].sort());
	const taste = await refreshTaste(archive, { now: NOW, live: c.live.map((l) => l.play), kept: c.kept ?? [] });
	const { imported, bySource, ...rest } = taste;
	refreshed.push({ name: c.name, now: NOW, rows: c.rows, own: c.live.map((l) => l.own), hidden: c.hidden ?? [], kept: c.kept ?? [], keep: c.keep ?? [], imported, bySource, taste: rest });
}
write('refresh.json', refreshed);

// ── kept-out.json ───────────────────────────────────────────────────────────────────────────────────────────────────

const keptCases = tastes.filter((c) => c.taste.songs.some((s) => s.avoid));
const why = keptCases.flatMap((c) => {
	const hidden = new Set(c.hidden ?? []);
	const avoided = c.taste.songs.filter((s) => s.avoid);
	const putBacks: string[][] = [[], avoided.slice(0, 1).map((s) => s.key)];
	return putBacks.map((pb) => ({
		name: c.name,
		hidden: c.hidden ?? [],
		putBack: pb,
		list: keptOut(c.taste, hidden, new Set(pb)).map((k) => ({ ...k, line: whyLine(k.why) }))
	}));
});
// whyKeptOut alone, around its thresholds (a song no rule explains can only have been hidden)
const whyAlone = [
	[6, 5], [6, 4], [8, 6], [5, 5], [3, 2], [3, 1], [2, 2], [12_345, 10_000], [0, 0]
].flatMap(([starts, skips]) => ['Track', 'Intro', 'Interlude (Reprise)'].map((title) => ({ key: songKey(title, 'Band'), title, starts, skips, hidden: false, why: whyKeptOut({ key: songKey(title, 'Band'), title, starts: starts!, skips: skips! }, new Set()) })));
whyAlone.push({ key: songKey('Track', 'Band'), title: 'Track', starts: 8, skips: 8, hidden: true, why: whyKeptOut({ key: songKey('Track', 'Band'), title: 'Track', starts: 8, skips: 8 }, new Set([songKey('Track', 'Band')])) });

// The put-back rules: the archive's (one transaction, only with an import, sorted, each once) and the removals'
const rules: { step: string; result?: unknown; keep: string[] | null; asked: unknown; import: boolean }[] = [];
{
	const a = memoryArchive();
	const note = async (step: string, result?: unknown) => rules.push({ step, ...(result !== undefined ? { result } : {}), keep: (await a.get('keep')) ?? null, asked: (await a.get('asked')) ?? null, import: !!(await a.get('import')) });
	await note('put back with no import', await a.putBack('zed|band'));
	await a.set('asked', 5);
	await a.set('import', { firstAt: 1, lastAt: 1, imports: 1, plays: 1, first: 1, last: 1, playlists: 0, likes: 0 });
	await note('put back', await a.putBack('zed|band'));
	await note('put back another, sorted', await a.putBack('alpha|band'));
	await note('put back the same again: once', await a.putBack('zed|band'));
	await a.clear();
	await note('remove import: the songs put back go, asked stays');
}
const lib = () => ({ importData: async () => ({ likes: 0, playlists: 0, songs: 0, history: 0, skipped: 0 }), likes: writable([]), playlists: writable([]), history: writable([]) });
for (const withSpotify of [false, true]) {
	const a = memoryArchive();
	await a.apply([row('Scrobble', 'Band', T0, 210_000, F_LASTFM | F_ESTIMATED), ...(withSpotify ? [row('Spotify', 'Band', T0 + HOUR, 200_000, FULL)] : [])], []);
	await a.set('import', { firstAt: 1, lastAt: 1, imports: 1, plays: 1, first: 1, last: 1, playlists: 0, likes: 0 });
	await a.set('keep', ['back|band']);
	await a.set('hidden', ['hidden|band']);
	const svc = createSpotifyService({ library: lib() as never, archive: a, now: () => NOW });
	await svc.removeLastfm();
	rules.push({ step: withSpotify ? 'remove Last.fm beside Spotify plays: the songs put back stay' : 'remove Last.fm when it was all there was: they go', keep: (await a.get('keep')) ?? null, asked: null, import: !!(await a.get('import')) });
}
write('kept-out.json', { lists: why, why: whyAlone, rules });

// ── words.json ──────────────────────────────────────────────────────────────────────────────────────────────────────

const svelte = readFileSync(join(player, 'src/routes/settings/RecordInMixes.svelte'), 'utf8');
/** A line of RecordInMixes.svelte, proved to be in the file as it is. */
const said = (s: string) => {
	if (!svelte.includes(s)) throw new Error(`not in RecordInMixes.svelte: ${s}`);
	return s;
};
const tricky = [
	'Old Friend - 2011 Remaster', 'Song (Remastered)', 'Song [2015 Remaster Version]', 'Song – Remastered 2009', 'Song - Digitally Remastered 2011 Version', 'Remastered', ' - Remaster',
	'Song - ٢٠١١ Remaster', 'Song - ２０１１ Remaster', 'Song - 2011 Remaſter', 'Song - REMASTER', 'Song - Remaster versıon', 'Song - Remaster\n', 'Song - Remaster ', 'Song\u0085- Remaster', 'Song - Remaster ', 'Song﻿(Remastered)',
	'Song — Remaster', 'Song (Remastered) (Live)', 'SKit', 'Skit', 'İntro', 'Intro', 'Introé', 'éIntro', 'Intro_', 'Intro1', 'Intro-1', 'introduction', 'An Interlude', 'commentary', 'OUTRO'
];
const credits = ['Frank Ocean, André 3000', 'Calvin Harris feat. Frank Ocean & Migos', 'A ft B', 'A Ft. B', 'A FEATURING B', 'Simon & Garfunkel', 'Beyoncé with Jay-Z', 'Ana wıth Ben', 'Ana WITH Ben', 'The Withdrawals', ', Someone', ' , X', ' feat X', '   ', 'A feat. B', 'A\u0085feat. B', 'A; B', 'Tyler, The Creator', 'A x B', 'A featuring', 'Ａ feat Ｂ'];
write('words.json', {
	keys: tricky.map((t) => ({ title: t, bare: bareTitle(t), key: songKey(t, 'Band'), interlude: INTERLUDE.test(t) })),
	credits: credits.map((c) => ({ credit: c, primary: primaryArtist(c) })),
	why: { hidden: whyLine({ kind: 'hidden' }), skips: [[9, 11], [1234, 1500], [6, 6]].map(([s, n]) => ({ skips: s, starts: n, line: whyLine({ kind: 'skips', skips: s!, starts: n! }) })), interlude: whyLine({ kind: 'interlude' }) },
	settings: {
		switch: said('Use your record in mixes'),
		switchLine: said('Daily Discover leans on the artists you’ve loved for years, and leaves out the songs you skip. Off, it goes by what you play here.'),
		title: said('Kept out of your mixes ('),
		on: said('Songs you hid on Spotify or keep skipping stay out of your mixes. Put one back and it can play in your next mixes.'),
		off: said('These stay out of your record’s mixes. Put one back and it can play in them again.'),
		empty: said('Nothing right now: every song in your record can play in your mixes.'),
		show: said('Show the songs'),
		hide: said('Hide the songs'),
		more: said('Show more'),
		putBack: said('Put back'),
		putting: said('Putting back…'),
		label: said('Put back {s.title} by {s.artist}'),
		done: said('${s.title} can play in your next mixes'),
		failed: said('Couldn’t put it back just now. Try again.')
	},
	step: progressOf({ step: 'taste' }).line
});
console.log(`taste vectors: ${tastes.length} tastes, ${seedCases.length} seed cases, ${refreshed.length} refreshes, ${why.length} kept-out lists`);
