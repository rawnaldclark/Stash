#!/usr/bin/env node
// Refreshes Your record's bundled page (feature/record/src/main/assets/) from a build of the fork, stash-on-record's
// `stash/app-host` (npm run build there first). See ../README.md for what goes in and why.
//
//   node feature/record/scripts/record-assets.mjs [--fork <stash-on-record checkout>] [--fonts <stash-player static/fonts>]
//
// What it copies:
//   record/embed.html and record/assets/*  from <fork>/dist, minus the landing's chunks (index-*) and the demo's (load-*.js),
//                                          every .woff (the woff2
//                                          is used everywhere) and Instrument Serif's upright 400 (the embed sets it italic only);
//   record-fonts/                          Inter and Space Grotesk (theme.css loads them from the site root, /fonts/) and their
//                                          OFL licences, from the web player's static/fonts.
// The landing page and the site's files (index.html, index-*, og.png, icons, robots.txt, sitemap.xml, _headers) never go in.
// Both folders are emptied first, so a chunk the new build dropped doesn't linger. It fails, and copies nothing, when the
// build's embed.html names a file that isn't there.
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > 0 && process.argv[i + 1] ? resolve(process.argv[i + 1]) : fallback;
};
const fork = arg('--fork', resolve(here, '../../../../stash-on-record'));
const fonts = arg('--fonts', resolve(here, '../../../../stash-web/player-upi/static/fonts'));
const out = resolve(here, '../src/main/assets');
const dist = join(fork, 'dist');

const skip = (name) =>
  /^index-/.test(name) || // the landing page's own chunk and styles
  /^load-.*\.js$/.test(name) || // the demo's seeded sample and the landing's loader: never in the app
  /\.woff$/.test(name) || // woff2 everywhere the embed's CSS names a face
  /^instrument-serif-.*-400-normal.*\.woff2$/.test(name); // only its italic is used by the embed
const FONT_FILES = ['inter-var.woff2', 'space-grotesk-var.woff2', 'OFL-Inter.txt', 'OFL-SpaceGrotesk.txt'];

if (!existsSync(join(dist, 'embed.html'))) throw new Error(`no build at ${dist} (npm run build in the fork first)`);
const html = readFileSync(join(dist, 'embed.html'), 'utf8');
const assets = readdirSync(join(dist, 'assets')).filter((n) => !skip(n));
for (const ref of html.matchAll(/(?:src|href)="\.\/assets\/([^"]+)"/g)) {
  if (!assets.includes(ref[1])) throw new Error(`embed.html loads assets/${ref[1]}, which the build doesn't have`);
}
for (const f of FONT_FILES) if (!existsSync(join(fonts, f))) throw new Error(`missing ${join(fonts, f)}`);

let commit = 'unknown';
try {
  commit = execFileSync('git', ['-C', fork, 'log', '-1', '--format=%h %s'], { encoding: 'utf8' }).trim();
} catch {
  /* not a checkout: the commit isn't known */
}

for (const d of ['record', 'record-fonts']) rmSync(join(out, d), { recursive: true, force: true });
mkdirSync(join(out, 'record', 'assets'), { recursive: true });
mkdirSync(join(out, 'record-fonts'), { recursive: true });
copyFileSync(join(dist, 'embed.html'), join(out, 'record', 'embed.html'));
let bytes = statSync(join(dist, 'embed.html')).size;
for (const n of assets) {
  copyFileSync(join(dist, 'assets', n), join(out, 'record', 'assets', n));
  bytes += statSync(join(dist, 'assets', n)).size;
}
let fontBytes = 0;
for (const f of FONT_FILES) {
  copyFileSync(join(fonts, f), join(out, 'record-fonts', f));
  fontBytes += statSync(join(fonts, f)).size;
}

// Chunks that name a file left out (the demo's loader is expected: only ?demo=1 imports it)
for (const n of assets.filter((a) => a.endsWith('.js'))) {
  const js = readFileSync(join(dist, 'assets', n), 'utf8');
  for (const m of js.matchAll(/["'`]\.\/([A-Za-z0-9_.-]+\.(?:js|css))["'`]/g)) {
    if (!assets.includes(m[1]) && !/^load-/.test(m[1])) console.warn(`warning: ${n} names ${m[1]}, which isn't bundled`);
  }
}

console.log(`fork: ${commit}`);
console.log(`record: embed.html + ${assets.length} assets, ${bytes.toLocaleString('en-US')} bytes`);
console.log(`fonts: ${FONT_FILES.length} files, ${fontBytes.toLocaleString('en-US')} bytes`);
console.log('Record the fork commit in feature/record/README.md.');
