/**
 * All D1 access. Every function takes the D1 binding (or test/fake-d1.js) and plain
 * values; no ORM, no runtime migrations (see migrations/). Times are unix seconds.
 * Day and hour keys are UTC strings, so every cap resets at midnight UTC.
 */
export const dayKey = (nowSec) => new Date(nowSec * 1000).toISOString().slice(0, 10);  // "2026-09-01"
export const hourKey = (nowSec) => new Date(nowSec * 1000).toISOString().slice(0, 13); // "2026-09-01T17"

/** Serve a cached mint only while its URL has at least this long left (spec §6.3). */
export const CACHE_MIN_LEFT_S = 900;

export async function getCached(db, trackId, formatId, nowSec) {
    return db.prepare(
        "SELECT url, got_format_id, bit_depth, sample_rate, etsp FROM mints WHERE track_id = ?1 AND format_id = ?2 AND etsp - ?3 >= ?4",
    ).bind(trackId, formatId, nowSec, CACHE_MIN_LEFT_S).first();
}

/** Statement (not executed) so the caller can batch it with the quota bumps. */
export function putCachedStmt(db, trackId, formatId, m) {
    return db.prepare(
        "INSERT OR REPLACE INTO mints (track_id, format_id, url, got_format_id, bit_depth, sample_rate, etsp) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
    ).bind(trackId, formatId, m.url, m.formatId, m.bitDepth, m.sampleRateHz, m.etsp);
}

export async function readQuota(db, day, key) {
    const row = await db.prepare("SELECT n FROM quota WHERE day = ?1 AND key = ?2").bind(day, key).first();
    return row ? row.n : 0;
}

export function bumpQuotaStmt(db, day, key) {
    return db.prepare("INSERT INTO quota (day, key, n) VALUES (?1, ?2, 1) ON CONFLICT(day, key) DO UPDATE SET n = n + 1").bind(day, key);
}

/**
 * Spec §5.3 in one statement: pick the live, un-cooled account with the oldest
 * last_used_at that is under both caps, and stamp its counters in the same write.
 * SET expressions read the pre-update row, so `hour_key = ?2` on the right-hand
 * side compares the OLD key. Atomic under concurrent invocations because D1 runs
 * one statement at a time. Returns the label, or null when nothing is eligible.
 */
export async function selectAccount(db, nowSec, caps) {
    const row = await db.prepare(`
UPDATE accounts SET
  hour_n = CASE WHEN hour_key = ?2 THEN hour_n + 1 ELSE 1 END, hour_key = ?2,
  day_n  = CASE WHEN day_key  = ?3 THEN day_n  + 1 ELSE 1 END, day_key  = ?3,
  last_used_at = ?1
WHERE label = (
  SELECT label FROM accounts
  WHERE state = 'live' AND cooling_until <= ?1
    AND (hour_key != ?2 OR hour_n < ?4)
    AND (day_key  != ?3 OR day_n  < ?5)
  ORDER BY last_used_at ASC, label ASC LIMIT 1)
RETURNING label`).bind(nowSec, hourKey(nowSec), dayKey(nowSec), caps.hourly, caps.daily).first();
    return row ? row.label : null;
}

/** Give every label in the secret a state row. INSERT OR IGNORE writes nothing for a known label. */
export async function ensureAccounts(db, labels) {
    if (labels.length === 0) return;
    await db.batch(labels.map((l) => db.prepare("INSERT OR IGNORE INTO accounts (label) VALUES (?1)").bind(l)));
}

export async function coolAccount(db, label, untilSec) {
    await db.prepare("UPDATE accounts SET cooling_until = ?2 WHERE label = ?1").bind(label, untilSec).run();
}

export async function killAccount(db, label, reason) {
    await db.prepare("UPDATE accounts SET state = 'dead', dead_reason = ?2 WHERE label = ?1").bind(label, reason).run();
}

/** A dead account is re-tried after this long: a misclassified kill self-heals, a real one costs one failed mint per window. */
export const DEAD_RETRY_S = 6 * 3600;

/** Hourly cron: drop mints past their etsp, quota rows older than yesterday, and give stale dead accounts another chance. */
export async function prune(db, nowSec) {
    await db.batch([
        db.prepare("DELETE FROM mints WHERE etsp < ?1").bind(nowSec),
        db.prepare("DELETE FROM quota WHERE day < ?1").bind(dayKey(nowSec - 86400)),
        db.prepare("UPDATE accounts SET state = 'live', dead_reason = '' WHERE state = 'dead' AND last_used_at < ?1").bind(nowSec - DEAD_RETRY_S),
    ]);
}
