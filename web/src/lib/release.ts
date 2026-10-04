/**
 * The latest Stash release, read from GitHub's API once per build.
 *
 * Visitors' browsers never call GitHub (the site makes no third-party requests),
 * so the version shown is as fresh as the last build. The Download button links
 * to /releases/latest, which is always right; only the number can lag.
 *
 * If GitHub can't be reached or answers with something unexpected, the build
 * still succeeds and the pages simply leave the version out.
 *
 * Set GITHUB_TOKEN in the build environment to avoid GitHub's limit of
 * 60 unauthenticated requests an hour per IP address (shared build machines can hit it).
 */

const API_URL = 'https://api.github.com/repos/rawnaldclark/Stash/releases/latest';
const TIMEOUT_MS = 8000;

export interface Release {
  /** For example "v0.9.110", or null when GitHub couldn't be read. */
  version: string | null;
}

let pending: Promise<Release> | undefined;

/** Every page shares one request per build (or per dev-server start). */
export function getLatestRelease(): Promise<Release> {
  pending ??= fetchLatestRelease();
  return pending;
}

async function fetchLatestRelease(): Promise<Release> {
  try {
    const headers: Record<string, string> = {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'stashfm-site-build',
      'X-GitHub-Api-Version': '2022-11-28',
    };
    const token = process.env.GITHUB_TOKEN;
    if (token) headers.Authorization = `Bearer ${token}`;

    const response = await fetch(API_URL, { headers, signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (!response.ok) throw new Error(`GitHub answered ${response.status}`);

    const { tag_name: tag } = (await response.json()) as { tag_name?: unknown };
    // Release tags look like "v0.9.110". Anything else is shown as no version at all.
    if (typeof tag !== 'string' || !/^v?\d+(\.\d+){1,3}$/.test(tag)) {
      throw new Error(`unexpected tag ${JSON.stringify(tag)}`);
    }
    const version = tag.startsWith('v') ? tag : `v${tag}`;
    console.info(`[release] Latest Stash release: ${version}`);
    return { version };
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    console.warn(`[release] Couldn't read the latest release from GitHub (${reason}). Building without a version number.`);
    return { version: null };
  }
}
