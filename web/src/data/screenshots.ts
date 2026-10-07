/**
 * The app screenshots, each in a dark and a light version, with its alt text.
 *
 * Files live in src/assets/screenshots/{dark,light}/<name>.webp (1080 × 2340,
 * transparent rounded corners). Astro resizes them at build time, so drop in
 * full-size originals. To add a screen: put both files in place, then add its
 * name and alt text below. Alt text says what the screen shows, in the
 * README's words where the README has them.
 */
import type { ImageMetadata } from 'astro';

type ImageModule = { default: ImageMetadata };

const dark = import.meta.glob<ImageModule>('../assets/screenshots/dark/*.webp', { eager: true });
const light = import.meta.glob<ImageModule>('../assets/screenshots/light/*.webp', { eager: true });

const ALT = {
  home: 'Home: Daily Discover, your mixes and radios',
  'now-playing': 'Now Playing: the colour wash follows the album art',
  lyrics: 'Lyrics that light up word by word',
  library: "Library: songs from Spotify and YouTube, with each one's quality",
  playlists: "The Library's Playlists tab",
  playlist: 'A playlist page',
  artist: 'An artist page',
  discography: "An artist's albums, singles and EPs",
  album: 'An album page',
  search: 'Search: an artist and their songs',
  queue: "The queue, with each song's FLAC quality",
  sync: 'Sync: your library at a glance',
  settings: 'Settings, from Playback and Audio & Quality to Accounts & Sync and Appearance',
  appearance: 'Appearance: dark, light or follow system, and pure black for the dark theme',
} as const;

export type ScreenName = keyof typeof ALT;

export interface Screen {
  dark: ImageMetadata;
  light: ImageMetadata;
  alt: string;
}

function load(files: Record<string, ImageModule>, theme: string, name: string): ImageMetadata {
  const file = files[`../assets/screenshots/${theme}/${name}.webp`];
  if (!file) throw new Error(`Missing screenshot: src/assets/screenshots/${theme}/${name}.webp`);
  return file.default;
}

export function screen(name: ScreenName): Screen {
  return { dark: load(dark, 'dark', name), light: load(light, 'light', name), alt: ALT[name] };
}
