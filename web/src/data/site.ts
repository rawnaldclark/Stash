/**
 * Site-wide facts and every outbound link, in one place.
 *
 * Links here must match the repo's README.md. If one changes there, change it here.
 */

export const SITE = {
  name: 'Stash',
  url: 'https://stashfm.app',
  /** The README's tagline. */
  tagline: 'Your Spotify + YouTube Music library',
  /** Used for the home page's <meta name="description"> and social cards. */
  description:
    'Stash mirrors your Spotify and YouTube Music libraries to your Android phone, to play offline or stream. Free and open source, with no account and no ads.',
  /** README › Requirements. */
  minAndroid: 'Android 8.0+',
  /** The social card every page uses: public/social-preview.jpg (2560 × 1280). */
  socialImage: '/social-preview.jpg',
  socialImageAlt:
    'Stash on three phones: Home with Daily Discover, Now Playing with a lossless FLAC track, and word-synced lyrics',
} as const;

const REPO = 'https://github.com/rawnaldclark/Stash';

export const LINKS = {
  /** Always the newest release, so the button never needs a rebuild to stay right. */
  download: `${REPO}/releases/latest`,
  releases: `${REPO}/releases`,
  github: REPO,
  issues: `${REPO}/issues`,
  readme: `${REPO}#readme`,
  /** README › First-time setup, which holds the Discord Rich Presence callout. */
  firstTimeSetup: `${REPO}#first-time-setup`,
  contributing: `${REPO}/blob/master/CONTRIBUTING.md`,
  license: `${REPO}/blob/master/LICENSE`,
  security: `${REPO}/blob/master/SECURITY.md`,
  securityAdvisory: `${REPO}/security/advisories/new`,
  discord: 'https://discord.gg/vcbjEby5PC',
  kofi: 'https://ko-fi.com/rawnald',
  paypal: 'https://www.paypal.com/paypalme/Paraliyzedevo',
  sponsors: 'https://github.com/sponsors/rawnaldclark',
  crowdin: 'https://crowdin.com/project/stash-music-player',
  obtainium: 'https://obtainium.imranr.dev/',
  dontKillMyApp: 'https://dontkillmyapp.com/',
  tink: 'https://developers.google.com/tink',
} as const;
