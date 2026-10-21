/**
 * Site-wide facts and every outbound link, in one place.
 *
 * Links here must match the repo's README.md. If one changes there, change it here.
 */

export const SITE = {
  name: 'Stash',
  url: 'https://stashfm.app',
  /** The README's tagline. */
  tagline: 'Your music, your way.',
  /**
   * Used for the home page's <meta name="description"> and social cards. It
   * avoids "no ads" / "no subscription", which beside the tagline read as a
   * pitch to replace those services' paid plans.
   */
  description:
    'Stash mirrors your Spotify and YouTube Music libraries to your Android phone, to play offline or stream, and now plays in your browser too, in early access. Free and open source, with no analytics.',
  /** README › Requirements. */
  minAndroid: 'Android 8.0+',
  /** The social card every page uses: public/social-preview.jpg (1200 × 630). */
  socialImage: '/social-preview.jpg',
  socialImageAlt:
    'Stash on three phones: Home with Daily Discover, Now Playing, and word-synced lyrics',
} as const;

const REPO = 'https://github.com/rawnaldclark/Stash';

export const LINKS = {
  /** Always the newest release, so the button never needs a rebuild to stay right. */
  // The newest release's APK. The release workflow names it Stash.apk (#550); older
  // releases used Stash-v*.apk, so this link needs a release made after #550.
  // The newest release's APK: the release workflow names it Stash.apk (since v0.9.111, #550).
  download: `${REPO}/releases/latest/download/Stash.apk`,
  latestRelease: `${REPO}/releases/latest`,
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
  /** rawn (owner) gets Ko-fi, Sponsors and his PayPal; Evo (co-developer) gets his PayPal. */
  paypalRawn: 'https://www.paypal.me/rawnaldclark',
  paypalEvo: 'https://www.paypal.com/paypalme/Paraliyzedevo',
  githubRawn: 'https://github.com/rawnaldclark',
  githubEvo: 'https://github.com/ParaliyzedEvo',
  sponsors: 'https://github.com/sponsors/rawnaldclark',
  /** rawn's Stripe Payment Link in INR with UPI on, for supporters in India whose cards get declined. */
  upi: 'https://buy.stripe.com/bJe4gz5uP8GP7z454D1gs00',
  crowdin: 'https://crowdin.com/project/stash-music-player',
  obtainium: 'https://obtainium.imranr.dev/',
  dontKillMyApp: 'https://dontkillmyapp.com/',
  tink: 'https://developers.google.com/tink',
} as const;
