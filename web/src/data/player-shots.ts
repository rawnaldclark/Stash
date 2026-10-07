/**
 * The web player's screenshots (play.stashfm.app), each in a dark and a light version, with its alt text.
 *
 * Files live in src/assets/player/{dark,light}/<name>.webp: the real player with its real sources, real
 * albums and covers, captured at 1280 × 800 at 2x, so 2560 × 1600. Astro resizes them at
 * build time. They're shown in a browser window (components/BrowserFrame.astro).
 */
import type { ImageMetadata } from 'astro';

type ImageModule = { default: ImageMetadata };

const dark = import.meta.glob<ImageModule>('../assets/player/dark/*.webp', { eager: true });
const light = import.meta.glob<ImageModule>('../assets/player/light/*.webp', { eager: true });

const ALT = {
  home: 'The Stash web player in a browser: Home, with Daily Discover, your likes and playlists, and the lyrics beside them',
  'now-playing': 'The web player’s Now Playing: the album cover beside big lyrics',
  search: 'Searching in the web player: the top result, songs and artists',
  album: 'An album page in the web player, with its songs',
} as const;

export type PlayerShotName = keyof typeof ALT;

export interface PlayerShot {
  dark: ImageMetadata;
  light: ImageMetadata;
  alt: string;
}

function load(files: Record<string, ImageModule>, theme: string, name: string): ImageMetadata {
  const file = files[`../assets/player/${theme}/${name}.webp`];
  if (!file) throw new Error(`Missing web player screenshot: src/assets/player/${theme}/${name}.webp`);
  return file.default;
}

export function playerShot(name: PlayerShotName): PlayerShot {
  return { dark: load(dark, 'dark', name), light: load(light, 'light', name), alt: ALT[name] };
}
