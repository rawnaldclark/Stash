// @ts-check
import { defineConfig, fontProviders } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// Stash promises no tracking, and that includes the tools that build its website:
// Astro's anonymous usage telemetry is off for everyone who builds this site.
process.env.ASTRO_TELEMETRY_DISABLED = '1';

export default defineConfig({
  site: 'https://stashfm.app',
  output: 'static',
  // Astro 7 defaults to JSX whitespace rules, which drop the space before a link
  // or <code> that starts on a new line. `true` keeps HTML's own rules, so copy
  // can be written and wrapped like normal HTML.
  compressHTML: true,
  // Pages build to /privacy.html and are served at /privacy (no trailing slash),
  // which is what Workers Static Assets does by default.
  build: { format: 'file' },
  trailingSlash: 'never',
  integrations: [
    sitemap({
      // The early-access pages (src/pages/gate/) are templates the Worker fills in; none has a URL of its own.
      filter: (page) => !page.endsWith('/404') && !page.includes('/gate/'),
    }),
  ],
  // The app's own fonts, self-hosted (see scripts/make-assets.py). <Font> in
  // BaseLayout.astro adds the @font-face rules and the preloads.
  fonts: [
    {
      provider: fontProviders.local(),
      name: 'Inter',
      cssVariable: '--font-inter',
      fallbacks: ['system-ui', 'sans-serif'],
      options: {
        variants: [
          { weight: 400, style: 'normal', src: ['./src/assets/fonts/inter-400.woff2'] },
          { weight: 500, style: 'normal', src: ['./src/assets/fonts/inter-500.woff2'] },
          { weight: 600, style: 'normal', src: ['./src/assets/fonts/inter-600.woff2'] },
        ],
      },
    },
    {
      provider: fontProviders.local(),
      name: 'Space Grotesk',
      cssVariable: '--font-space-grotesk',
      fallbacks: ['system-ui', 'sans-serif'],
      options: {
        variants: [
          { weight: 500, style: 'normal', src: ['./src/assets/fonts/space-grotesk-500.woff2'] },
          { weight: 700, style: 'normal', src: ['./src/assets/fonts/space-grotesk-700.woff2'] },
        ],
      },
    },
  ],
});
