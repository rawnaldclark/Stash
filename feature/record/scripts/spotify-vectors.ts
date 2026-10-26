/**
 * Parity vectors for the app's Spotify import (feature/record `importer/`): small exports crafted around every rule of the web's
 * reader and merge (stash-player `src/lib/spotify/`), run through the web's own `openFiles`, `prepare` and `commit`, with what
 * they gave written beside them. The app's `SpotifyVectorsTest` reads the same files and must give the same: files, unreadable,
 * the preview and every archive row (id, words, start, time played, flags).
 *
 * Run from this repository's root (TZ=UTC: a time with no offset is local time to JavaScript, and the test reads it as UTC):
 *   PLAYER=../stash-web/player-spotify
 *   $PLAYER/node_modules/.bin/esbuild feature/record/scripts/spotify-vectors.ts --bundle --platform=node --format=esm \
 *     --alias:@player=$PLAYER/src/lib --outfile=build/spotify-vectors.mjs
 *   TZ=UTC node build/spotify-vectors.mjs feature/record/src/test/resources/spotify-export/vectors
 */
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { memoryArchive } from '@player/spotify/archive';
import { zipOf } from '@player/spotify/fixture';
import { commit, openFiles, prepare } from '@player/spotify/importer';

type F = { name: string; bytes: Uint8Array };
type Case = { name: string; before?: F[]; files: F[] };

const NOW = Date.parse('2026-10-23T12:00:00Z');
const enc = new TextEncoder();
const text = (name: string, s: string): F => ({ name, bytes: enc.encode(s) });
const json = (name: string, v: unknown): F => text(name, JSON.stringify(v, null, 1));
/** A JSON array whose elements are written as given (to keep duplicate keys and odd values JSON.stringify can't write). */
const rawArray = (name: string, parts: string[]): F => text(name, `[\n${parts.join(',\n')}\n]`);

const CRC = (() => {
	const t = new Uint32Array(256);
	for (let n = 0; n < 256; n++) {
		let c = n;
		for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		t[n] = c >>> 0;
	}
	return t;
})();
const crc32 = (u: Uint8Array) => {
	let c = 0xffffffff;
	for (let i = 0; i < u.length; i++) c = CRC[(c ^ u[i]!) & 0xff]! ^ (c >>> 8);
	return (c ^ 0xffffffff) >>> 0;
};
/** A zip of stored (method 0) entries: a zip inside one is read where it lies. */
function storedZip(files: readonly F[]): Uint8Array {
	const parts: Uint8Array[] = [];
	const central: Uint8Array[] = [];
	let offset = 0;
	for (const f of files) {
		const name = enc.encode(f.name);
		const crc = crc32(f.bytes);
		const local = new Uint8Array(30 + name.length);
		const lv = new DataView(local.buffer);
		lv.setUint32(0, 0x04034b50, true);
		lv.setUint16(4, 10, true);
		lv.setUint16(6, 0x0800, true);
		lv.setUint32(14, crc, true);
		lv.setUint32(18, f.bytes.length, true);
		lv.setUint32(22, f.bytes.length, true);
		lv.setUint16(26, name.length, true);
		local.set(name, 30);
		const cen = new Uint8Array(46 + name.length);
		const cv = new DataView(cen.buffer);
		cv.setUint32(0, 0x02014b50, true);
		cv.setUint16(4, 10, true);
		cv.setUint16(6, 10, true);
		cv.setUint16(8, 0x0800, true);
		cv.setUint32(16, crc, true);
		cv.setUint32(20, f.bytes.length, true);
		cv.setUint32(24, f.bytes.length, true);
		cv.setUint16(28, name.length, true);
		cv.setUint32(42, offset, true);
		cen.set(name, 46);
		parts.push(local, f.bytes);
		central.push(cen);
		offset += local.length + f.bytes.length;
	}
	const cdSize = central.reduce((n, c) => n + c.length, 0);
	const eocd = new Uint8Array(22);
	const ev = new DataView(eocd.buffer);
	ev.setUint32(0, 0x06054b50, true);
	ev.setUint16(8, files.length, true);
	ev.setUint16(10, files.length, true);
	ev.setUint32(12, cdSize, true);
	ev.setUint32(16, offset, true);
	const all = [...parts, ...central, eocd];
	const out = new Uint8Array(all.reduce((n, p) => n + p.length, 0));
	let at = 0;
	for (const p of all) out.set(p, at), (at += p.length);
	return out;
}

// ── rows ────────────────────────────────────────────────────────────────────────────────────────────────────────────

const SID = '0123456789abcdefghijAB';
const ext = (o: Record<string, unknown> = {}) => ({
	ts: '2023-05-01T10:00:00Z',
	platform: 'Android OS 14',
	ms_played: 200000,
	conn_country: 'US',
	ip_addr: '198.51.100.23',
	master_metadata_track_name: 'Song',
	master_metadata_album_artist_name: 'Artist',
	master_metadata_album_album_name: 'Album',
	spotify_track_uri: `spotify:track:${SID}`,
	episode_name: null,
	reason_start: 'trackdone',
	reason_end: 'trackdone',
	shuffle: false,
	skipped: false,
	offline: false,
	incognito_mode: false,
	...o
});
let minute = 0;
/** A distinct time for each row, so each is its own play. */
const at = () => new Date(Date.UTC(2023, 4, 1, 10, 0, 0) + minute++ * 600_000).toISOString().replace(/\.000Z$/, 'Z');
const e = (o: Record<string, unknown> = {}) => JSON.stringify(ext({ ts: at(), ...o }));

const extendedRows: string[] = [
	e(),
	e({ master_metadata_track_name: null, episode_name: 'Episode 12' }), // a podcast: no song, not an error
	e({ master_metadata_album_artist_name: '   ' }), // no artist left after cleaning: no song
	e({ master_metadata_track_name: '\u0000\u001f\u007f' }), // only control characters: no song
	e({ incognito_mode: true }),
	e({ incognito_mode: 'true' }), // not true: kept
	e({ ts: 'not a time' }),
	e({ ts: '2005-12-31T23:59:59Z' }),
	e({ ts: '2006-01-01T00:00:00Z', ms_played: 0, master_metadata_track_name: 'First moment' }),
	e({ ts: '2026-10-25T12:00:00Z', master_metadata_track_name: 'Two days on' }),
	e({ ts: '2026-10-25T12:00:00.001Z' }),
	e({ ts: 1682935200000 }),
	e({ ts: '2023-05-01T12:00:00.5+02:00', master_metadata_track_name: 'Offset' }),
	e({ ts: '2023-05-01T12:00:00', master_metadata_track_name: 'No offset' }),
	e({ ts: '2023-05-02', master_metadata_track_name: 'A day' }),
	e({ ts: '2023-02-30T00:00:00Z', master_metadata_track_name: 'February 30' }),
	e({ ts: '2023-05-01T08:23:45.123456Z', master_metadata_track_name: 'Long fraction' }),
	e({ ts: '2023-05-01t08:23:45z', master_metadata_track_name: 'Lower case' }),
	e({ ts: '2023-05-01 08:24:45Z', master_metadata_track_name: 'A space' }),
	e({ ts: ' 2023-05-01T08:23:45Z' }),
	e({ ms_played: -1 }),
	e({ ms_played: 21600000, master_metadata_track_name: 'Six hours' }),
	e({ ms_played: 21600001 }),
	e({ ms_played: '200000' }),
	e({ ms_played: null }),
	e({ ms_played: 1234.5, master_metadata_track_name: 'Half up' }),
	e({ ms_played: -0.4, master_metadata_track_name: 'Minus nothing' }),
	e({ ms_played: 2.5e2, master_metadata_track_name: 'Exponent' }),
	e({ reason_start: 'clickrow', reason_end: 'fwdbtn', skipped: null, master_metadata_track_name: 'Clicked then next' }),
	e({ reason_start: 'playbtn', reason_end: 'endplay', skipped: 'true', master_metadata_track_name: 'Play button' }),
	e({ reason_start: '', reason_end: '', skipped: null, master_metadata_track_name: 'Empty reasons' }),
	e({ reason_start: undefined, reason_end: undefined, skipped: true, shuffle: 'yes', master_metadata_track_name: 'No reasons' }),
	e({ reason_start: 'remote', reason_end: 'trackdone', shuffle: true, master_metadata_track_name: 'Remote' }),
	e({ reason_start: 'clickrow', reason_end: undefined, skipped: false, master_metadata_track_name: 'Start only' }),
	e({ reason_start: 7, reason_end: 'logout', master_metadata_track_name: 'Odd reasons' }),
	e({ spotify_track_uri: `spotify:track:${SID}\n`, master_metadata_track_name: 'Uri newline' }),
	e({ spotify_track_uri: `https://open.spotify.com/track/${SID}?si=x`, master_metadata_track_name: 'Uri link' }),
	e({ spotify_track_uri: 'spotify:track:short', master_metadata_track_name: 'Uri short' }),
	e({ spotify_track_uri: `spotify:episode:${SID}`, master_metadata_track_name: 'Uri episode' }),
	e({ spotify_track_uri: `xopen.spotify.com/track/${SID}CDEF`, master_metadata_track_name: 'Uri long' }),
	e({ spotify_track_uri: null, master_metadata_track_name: 'Uri none' }),
	e({ master_metadata_track_name: '\u0000Tab\tbed\u007f\u0085 Song  ' }),
	e({ master_metadata_track_name: '   Spaced﻿ ', master_metadata_album_album_name: ' ' }),
	e({ master_metadata_track_name: 'x'.repeat(299) + '\u{1F600}' }),
	e({ master_metadata_track_name: 'a'.repeat(301) + '   ' }),
	e({ master_metadata_track_name: '  ' + 'b'.repeat(300) + ' ' }),
	e({ master_metadata_track_name: 'c'.repeat(299) + ' d' }),
	e({ master_metadata_album_artist_name: 'Artist (feat. Guest)', master_metadata_track_name: 'Featuring' }),
	e({ master_metadata_track_name: 'Café' }),
	e({ master_metadata_track_name: 'Café' }),
	e({ master_metadata_track_name: 'ΣΟΦΟΣ' }),
	e({ master_metadata_track_name: 'İstanbul' }),
	e({ master_metadata_track_name: 'ﬁve Ｓｏｎｇ' }),
	e({ master_metadata_album_album_name: '', master_metadata_track_name: 'No album' }),
	e({ master_metadata_album_album_name: null, master_metadata_track_name: 'Null album' }),
	e({ master_metadata_album_album_name: 5, master_metadata_track_name: 'Number album' }),
	e({ master_metadata_track_name: 5 }),
	// Duplicate keys: JSON.parse keeps the last
	`{"ts":"${at()}","ms_played":"bad","master_metadata_track_name":"First","master_metadata_album_artist_name":"Artist","master_metadata_track_name":"Second","ms_played":180000}`,
	`{"ts":"${at()}","ms_played":180000,"master_metadata_track_name":"Kept","master_metadata_album_artist_name":"Artist","ms_played":"bad"}`,
	// Escapes
	`{"ts":"${at()}","ms_played":180000,"master_metadata_track_name":"Esc\\u00e9 \\/ \\"q\\" \\\\ \\ud83d\\ude00 \\ud800","master_metadata_album_artist_name":"Artist"}`,
	// Not objects: null and scalars are unreadable, arrays no song
	'null',
	'5',
	'"x"',
	'true',
	'[]',
	'[1,2]',
	'{}',
	// The same row twice
	e({ ts: '2023-06-01T10:00:00Z', master_metadata_track_name: 'Twice' }),
	e({ ts: '2023-06-01T10:00:00Z', master_metadata_track_name: 'Twice' })
];

const a = (o: Record<string, unknown>) => JSON.stringify({ endTime: '2024-11-05 10:15', artistName: 'Artist', trackName: 'Track', msPlayed: 200000, ...o });
const accountRows: string[] = [
	a({ trackName: 'Plain' }),
	a({ endTime: '2024-11-05T10:16:30', trackName: 'Seconds and T' }),
	a({ endTime: '2024-11-05 10:17 extra junk', trackName: 'Junk after' }),
	a({ endTime: '2024-13-01 10:00', trackName: 'Month 13' }),
	a({ endTime: '2024-02-30 10:00', trackName: 'February 30' }),
	a({ endTime: '2024-11-32 10:00', trackName: 'Day 32' }),
	a({ endTime: '2024-11-05 25:00', trackName: 'Hour 25' }),
	a({ endTime: '2024-11-05 10:99', trackName: 'Minute 99' }),
	a({ endTime: '0099-01-01 00:00', trackName: 'Year 99' }),
	a({ endTime: '2024-11-05', trackName: 'No time' }),
	a({ endTime: 1730800000000, trackName: 'A number' }),
	a({ endTime: '２０２４-11-05 10:00', trackName: 'Wide digits' }),
	a({ trackName: '', artistName: 'Artist' }),
	a({ trackName: 'No artist', artistName: null }),
	a({ msPlayed: 1.5, trackName: 'Half' }),
	a({ msPlayed: '5', trackName: 'String ms' }),
	a({ msPlayed: 21600001, trackName: 'Too long' }),
	a({ endTime: '2024-11-06 09:00', trackName: 'Same twice' }),
	a({ endTime: '2024-11-06 09:00', trackName: 'Same twice' }),
	'null',
	'[]',
	'"row"'
];

// ── Account against Extended ────────────────────────────────────────────────────────────────────────────────────────

const twinsExt = [
	ext({ ts: '2024-11-10T10:00:30Z', master_metadata_track_name: 'Twin', master_metadata_album_artist_name: 'Album Artist', ms_played: 200000 }),
	ext({ ts: '2024-11-10T11:00:00Z', master_metadata_track_name: 'Lonely', master_metadata_album_artist_name: 'Album Artist', ms_played: 120000 }),
	ext({ ts: '2024-11-10T12:00:00Z', master_metadata_track_name: 'Repeat', ms_played: 100000 }),
	ext({ ts: '2024-11-10T12:00:00Z', master_metadata_track_name: 'Repeat', ms_played: 100000 })
];
const twinsAcc = [
	{ endTime: '2024-11-10 10:00', artistName: 'Song Artist', trackName: 'Twin', msPlayed: 200000 }, // same minute: one play
	{ endTime: '2024-11-10 10:01', artistName: 'Song Artist', trackName: 'Twin', msPlayed: 200000 }, // the next minute: one play
	{ endTime: '2024-11-10 09:59', artistName: 'Other', trackName: 'TWIN!', msPlayed: 200000 }, // the minute before, folds the same
	{ endTime: '2024-11-10 10:02', artistName: 'Song Artist', trackName: 'Twin', msPlayed: 200000 }, // two minutes on: its own play
	{ endTime: '2024-11-10 13:00', artistName: 'Song Artist', trackName: 'Only here', msPlayed: 150000 },
	{ endTime: '2024-11-10 13:00', artistName: 'Song Artist', trackName: 'Only here', msPlayed: 150000 }, // the same row twice
	{ endTime: '2024-11-10 14:00', artistName: 'Song Artist', trackName: 'Short', msPlayed: 10000 } // a skip: kept, not a listen
];

// ── zips ────────────────────────────────────────────────────────────────────────────────────────────────────────────

async function cases(): Promise<Case[]> {
	const dir = 'Spotify Extended Streaming History';
	const deep = await zipOf([
		json('endsong_1.json', [ext({ ts: '2022-01-01T10:00:00Z', master_metadata_track_name: 'Three deep' })]),
		{ name: 'D.zip', bytes: await zipOf([json('endsong_2.json', [ext({ ts: '2022-01-02T10:00:00Z', master_metadata_track_name: 'Four deep' })])]) }
	]);
	const nestedB = storedZip([{ name: 'C.zip', bytes: deep }, json('Streaming_History_Audio_2022_1.json', [ext({ ts: '2022-01-03T10:00:00Z', master_metadata_track_name: 'Two deep, stored' })])]);
	const nestedA = await zipOf([{ name: 'B.zip', bytes: nestedB }, json('StreamingHistory_music_7.json', [{ endTime: '2022-01-04 10:00', artistName: 'Artist', trackName: 'One deep', msPlayed: 99000 }])]);
	const picked = await zipOf([
		{ name: 'A.zip', bytes: nestedA },
		{ name: 'bad.zip', bytes: enc.encode('not a zip at all, but its name says it is') },
		{ name: `${dir}/`, bytes: new Uint8Array() },
		json(`${dir}\\Streaming_History_Audio_2021.json`, [ext({ ts: '2021-03-01T10:00:00Z', master_metadata_track_name: 'Backslash' })]),
		text(`${dir}/ReadMeFirst_ExtendedStreamingHistory.pdf`, '%PDF-1.4 not really'),
		json('Spotify Account Data/Userdata.json', { username: 'example' }),
		json('Spotify Account Data/StreamingHistory_podcast_0.json', [{ endTime: '2024-01-01 10:00', podcastName: 'P', episodeName: 'E', msPlayed: 1 }]),
		json(`${dir}/Streaming_History_Video_2022.json`, [ext({ ts: '2022-02-01T10:00:00Z', master_metadata_track_name: 'A video' })]),
		json(`${dir}/STREAMING_HISTORY_AUDIO_2020.JSON`, [ext({ ts: '2020-02-01T10:00:00Z', master_metadata_track_name: 'Upper case name' })]),
		json(`${dir}/Endsong_9.json`, [ext({ ts: '2020-02-02T10:00:00Z', master_metadata_track_name: 'Endsong upper' })]),
		json(`${dir}/StreamingHistory12.json`, [{ endTime: '2020-02-03 10:00', artistName: 'Artist', trackName: 'Old account name', msPlayed: 99000 }])
	]);

	const valid = (y: number, title: string) => json(`Streaming_History_Audio_${y}.json`, [ext({ ts: `${y}-04-01T10:00:00Z`, master_metadata_track_name: title })]);
	const truncated = text('Streaming_History_Audio_2021_9.json', `[\n${e({ master_metadata_track_name: 'Lost with its file' })},\n${e({ ts: 'bad' })},\n{"ts":`);
	const playlists = text(
		'Playlist1.json',
		JSON.stringify({
			playlists: [
				{
					name: 'Mixed',
					items: [
						{ track: { trackName: 'One', artistName: 'A', albumName: 'X', trackUri: `spotify:track:${SID}` }, episode: null, localTrack: null },
						{ track: null, episode: { episodeName: 'E' }, localTrack: null },
						{ track: null, localTrack: { uri: 'spotify:local:My+Band:Demo:Garage+Song:184' } },
						{ track: null, localTrack: { uri: 'spotify:local:My+Band:Demo:%E0%A4%A:184' } },
						{ track: null, localTrack: { uri: 'spotify:local::Demo:No+artist:184' } },
						{ track: null, localTrack: { uri: 'spotify:local:Artist%20Name:Album:Title%F0%9F%98%80:184' } },
						{ track: null, localTrack: { uri: 'spotify:local:Two:Parts' } },
						{ track: 'a string', localTrack: { uri: 'spotify:local:A:B:C:1' } },
						{ track: 0, localTrack: { uri: 'spotify:local:A:B:Zero:1' } },
						{ track: [], localTrack: { uri: 'spotify:local:A:B:Array:1' } },
						{ track: { trackName: 'No artist' } },
						{ track: { trackName: 'Two', artistName: 'B' }, addedDate: 'not a date' },
						null,
						'item'
					]
				},
				{ name: '   ', items: [] },
				{ items: [{ track: { trackName: 'Orphan', artistName: 'A' } }] },
				null,
				{ name: 'No items' },
				{ name: 'Items not a list', items: { track: 1 } },
				{ name: 'Mixed', items: [{ track: { trackName: 'Again', artistName: 'C' } }] }
			]
		})
	);
	const library = text(
		'YourLibrary.json',
		`{"tracks":[{"artist":"A","track":"Liked"}],"bannedTracks":[{"artist":"A","track":"Hidden"},{"artist":"","track":"No artist"},null,[],"x"],"tracks":{"artist":"A","track":"Not a list"}}`
	);
	const later = (o: Record<string, unknown>) => ext({ ...o });
	const older = [later({ ts: '2021-01-01T10:00:00Z', master_metadata_track_name: 'Old one' }), later({ ts: '2021-01-02T10:00:00Z', master_metadata_track_name: 'Old two' })];
	const newer = [...older, later({ ts: '2021-01-03T10:00:00Z', master_metadata_track_name: 'New three' }), later({ ts: '2021-01-04T10:00:00Z', master_metadata_track_name: 'New four', ms_played: 5000 })];

	return [
		{ name: 'extended-rows', files: [rawArray('Streaming_History_Audio_2023.json', extendedRows)] },
		{ name: 'account-rows', files: [rawArray('StreamingHistory_music_0.json', accountRows)] },
		{ name: 'account-and-extended', files: [json('StreamingHistory_music_0.json', twinsAcc), json('Streaming_History_Audio_2024.json', twinsExt)] },
		{ name: 'account-then-extended', before: [json('StreamingHistory_music_0.json', twinsAcc)], files: [json('Streaming_History_Audio_2024.json', twinsExt)] },
		{ name: 'extended-then-account', before: [json('Streaming_History_Audio_2024.json', twinsExt)], files: [json('StreamingHistory_music_0.json', twinsAcc)] },
		{ name: 'nested-zips', files: [{ name: 'my_spotify_data.zip', bytes: picked }] },
		{
			name: 'broken-files',
			files: [
				valid(2019, 'Kept beside the broken'),
				truncated,
				json('Streaming_History_Audio_2020.json', { not: 'a list' }),
				text('StreamingHistory_music_1.json', 'null'),
				text('Streaming_History_Audio_2018.json', '[] trailing'),
				text('Streaming_History_Audio_2017.json', '﻿[' + e({ master_metadata_track_name: 'After a BOM' }) + ']'),
				text('Streaming_History_Audio_2016.json', '[' + e({ master_metadata_track_name: 'Comma after' }) + ',]'),
				playlists,
				library,
				{ name: 'broken.zip', bytes: enc.encode('PK not really') },
				text('notes.txt', 'not looked at')
			]
		},
		{ name: 'not-an-export', files: [{ name: 'my_spotify_data.zip', bytes: await zipOf([text('ReadMe.pdf', '%PDF'), json('Userdata.json', { a: 1 })]) }] },
		{ name: 'only-broken', files: [text('Streaming_History_Audio_2023.json', '[{')] },
		{ name: 'a-newer-export', before: [json('endsong_0.json', older)], files: [json('endsong_0.json', newer)] },
		{ name: 'the-same-again', before: [json('endsong_0.json', newer)], files: [json('endsong_0.json', newer)] }
	];
}

async function run(c: Case) {
	const archive = memoryArchive();
	if (c.before) await commit(await prepare(await openFiles(c.before), archive, { now: NOW }), archive, { now: NOW });
	try {
		const prep = await prepare(await openFiles(c.files), archive, { now: NOW });
		await commit(prep, archive, { now: NOW });
		const rows = (await archive.all()).sort((x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0)).map((r) => [r.id, r.t, r.a, r.al ?? null, r.sid ?? null, r.s, r.ms, r.f]);
		const { phone: _phone, ...preview } = prep.preview;
		return { files: prep.bundle.files, unreadable: prep.bundle.unreadable, preview, rows };
	} catch (e) {
		return { error: (e as Error).name };
	}
}

const out = process.argv[2];
if (!out) throw new Error('usage: node spotify-vectors.mjs <out dir>');
rmSync(out, { recursive: true, force: true });
const index: unknown[] = [];
for (const c of await cases()) {
	const dir = join(out, c.name);
	mkdirSync(dir, { recursive: true });
	const names = (fs: F[] | undefined, sub: string) => {
		if (!fs) return undefined;
		mkdirSync(join(dir, sub), { recursive: true });
		for (const f of fs) writeFileSync(join(dir, sub, f.name), f.bytes);
		return fs.map((f) => f.name);
	};
	index.push({ name: c.name, before: names(c.before, 'before'), files: names(c.files, 'files'), expected: await run(c) });
}
writeFileSync(join(out, 'cases.json'), JSON.stringify({ now: NOW, cases: index }, null, 1) + '\n');
console.log(`${index.length} cases written to ${out}`);
