/**
 * Sessions' stations, the pure parts (spec docs/superpowers/specs/2026-11-20-sessions-design.md §3): cleaning what a
 * host sends, working out a schedule's windows in the host's time zone, and turning a stored row into what phones see.
 * No I/O: src/stations-api.js does the routes and the SQL.
 */

export const NAME_MAX = 40;
export const HOST_NAME_MAX = 40;
export const VIBE_MAX = 60;
export const MAX_WINDOWS = 7;
const AUTO_LIVE = new Set(["off", "schedule", "always"]);
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

const given = (o, k) => Object.hasOwn(o, k) && o[k] !== undefined;
/** Trimmed text of 1..max characters, else null. */
const text = (v, max) => (typeof v === "string" && v.trim() && v.trim().length <= max ? v.trim() : null);
/** An optional text field: null or blank clears it ({ok, value: null}); too long or not a string is refused. */
function optionalText(v, max) {
    if (v === null || (typeof v === "string" && !v.trim())) return { ok: true, value: null };
    const t = text(v, max);
    return t === null ? { ok: false } : { ok: true, value: t };
}

/** The time zone as Intl spells it, or null for one Intl doesn't know. */
function zone(tz) {
    if (typeof tz !== "string" || !tz || tz.length > 64) return null;
    try {
        return new Intl.DateTimeFormat("en-US", { timeZone: tz }).resolvedOptions().timeZone;
    } catch {
        return null;
    }
}

/**
 * `{tz, windows: [{day, start, end}]}`: an Intl time zone and up to 7 windows, day 1–7 (Monday = 1), times "HH:MM"
 * (00:00–23:59), start ≠ end; an end before its start runs past midnight. Only those keys are kept. Null when anything is off.
 */
export function cleanSchedule(v) {
    if (!v || typeof v !== "object" || !Array.isArray(v.windows) || v.windows.length > MAX_WINDOWS) return null;
    const tz = zone(v.tz);
    if (!tz) return null;
    const windows = [];
    for (const w of v.windows) {
        if (!w || typeof w !== "object" || !Number.isInteger(w.day) || w.day < 1 || w.day > 7) return null;
        if (typeof w.start !== "string" || typeof w.end !== "string" || !TIME.test(w.start) || !TIME.test(w.end) || w.start === w.end) return null;
        windows.push({ day: w.day, start: w.start, end: w.end });
    }
    return { tz, windows };
}

/**
 * What a host sends to create (`partial` false: a name is required) or change (`partial` true: at least one field)
 * a station: `{name?, hostName?, vibe?, autoLive?, schedule?}`, cleaned. hostName, vibe and schedule may be null to clear
 * them. Unknown keys are dropped; any field that's off refuses the whole request (null).
 */
export function cleanStationFields(input, { partial = false } = {}) {
    if (!input || typeof input !== "object" || Array.isArray(input)) return null;
    const out = {};
    if (given(input, "name") || !partial) {
        const name = text(input.name, NAME_MAX);
        if (!name) return null;
        out.name = name;
    }
    for (const [key, max] of [["hostName", HOST_NAME_MAX], ["vibe", VIBE_MAX]]) {
        if (!given(input, key)) continue;
        const r = optionalText(input[key], max);
        if (!r.ok) return null;
        // Creating, a blank optional field is just left out.
        if (r.value !== null || partial) out[key] = r.value;
    }
    if (given(input, "autoLive")) {
        if (!AUTO_LIVE.has(input.autoLive)) return null;
        out.autoLive = input.autoLive;
    }
    if (given(input, "schedule")) {
        if (input.schedule === null) {
            out.schedule = null;
        } else {
            const s = cleanSchedule(input.schedule);
            if (!s) return null;
            out.schedule = s;
        }
    }
    return Object.keys(out).length ? out : null;
}

const formats = new Map();
/** Local calendar parts at instant t in tz. */
function wallParts(t, tz) {
    let f = formats.get(tz);
    if (!f) {
        f = new Intl.DateTimeFormat("en-US", {
            timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
        });
        formats.set(tz, f);
    }
    const p = Object.fromEntries(f.formatToParts(new Date(t)).map((x) => [x.type, x.value]));
    return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour, mi: +p.minute };
}
/** Local wall time at instant t, written as the UTC timestamp with the same digits (whole minutes). */
const wallAt = (t, tz) => {
    const p = wallParts(t, tz);
    return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi);
};
const offsetAt = (t, tz) => wallAt(t, tz) - Math.floor(t / 60_000) * 60_000;

/**
 * The instant a local wall time happens in tz; [wall] is that time written as a UTC timestamp with the same digits.
 * An ambiguous time (fall back) is the first one; a skipped time (spring forward) lands just after the gap.
 */
export function instantOf(wall, tz) {
    const first = wall - offsetAt(wall, tz);
    const second = wall - offsetAt(first, tz);
    return wallAt(second, tz) === wall ? second : first;
}

const minutes = (hhmm) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3));

/**
 * `{start, end}` (ms) of the window in progress at [now], else of the next one to start; null without windows.
 * Looks from yesterday (a window across midnight may still be running) to a week ahead.
 */
export function nextWindow(schedule, now) {
    if (!schedule?.windows?.length) return null;
    const today = wallParts(now, schedule.tz);
    let best = null;
    for (let k = -1; k <= 7; k++) {
        const date = Date.UTC(today.y, today.mo - 1, today.d + k);
        const day = ((new Date(date).getUTCDay() + 6) % 7) + 1; // Monday = 1 … Sunday = 7
        for (const w of schedule.windows) {
            if (w.day !== day) continue;
            const s = minutes(w.start);
            const e = minutes(w.end);
            const start = instantOf(date + s * 60_000, schedule.tz);
            const end = instantOf(date + (e > s ? e : e + 1440) * 60_000, schedule.tz);
            if (end <= now) continue;
            if (!best || start < best.start) best = { start, end };
        }
    }
    return best;
}

/**
 * A `stations` row (with `followers`, and `my_vote`, `heard`, `following` from the caller's joins) → the StationSummary
 * phones see. [me] is the caller's poster id, or null without a key; `myVote`, `mine`, `heard` and `following` appear only
 * with it. The owner's id never leaves. Your own station counts as heard (you can't vote on it anyway).
 */
export function summaryOfStation(row, me) {
    return {
        id: row.id, name: row.name, hostName: row.host_name ?? null, vibe: row.vibe ?? null,
        up: row.up, down: row.down, followers: row.followers ?? 0,
        ...(me ? {
            myVote: row.my_vote ?? 0, mine: row.owner === me, heard: row.owner === me || Boolean(row.heard),
            following: Boolean(row.following),
        } : {}),
    };
}

const DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
/** "09:00" → "9:00": the hour without its leading zero, on a 24-hour clock. */
const clock = (hhmm) => `${Number(hhmm.slice(0, 2))}:${hhmm.slice(3)}`;

/** Sorted ISO days as people say them: "Every day", "Weekdays", "Weekends", "Mon–Wed, Fri", "Mon, Tue". */
function daysOf(days) {
    const key = days.join("");
    if (key === "1234567") return "Every day";
    if (key === "12345") return "Weekdays";
    if (key === "67") return "Weekends";
    const runs = [];
    for (const d of days) {
        const run = runs.at(-1);
        if (run && d === run.at(-1) + 1) run.push(d);
        else runs.push([d]);
    }
    return runs.flatMap((r) => (r.length >= 3 ? [`${DAY_NAMES[r[0] - 1]}–${DAY_NAMES[r.at(-1) - 1]}`] : r.map((d) => DAY_NAMES[d - 1])))
        .join(", ");
}

/** An IANA zone as people say it: "New York time", "Buenos Aires time", "UTC". */
function zoneName(tz) {
    if (/^(Etc\/)?(UTC|GMT|Universal|Zulu)$/.test(tz)) return "UTC";
    return `${tz.split("/").at(-1).replaceAll("_", " ")} time`;
}

/**
 * A clean schedule as one line for the station page, in the station's own time zone: "Weekdays 9:00–17:00 (New York
 * time)". Days with the same hours are grouped; groups go in week order, then by start. Null without windows.
 */
export function hoursOf(schedule) {
    if (!schedule?.windows?.length) return null;
    const groups = new Map(); // "09:00-17:00" (zero-padded, so it sorts) → its days
    for (const w of schedule.windows) {
        const key = `${w.start}-${w.end}`;
        groups.set(key, [...(groups.get(key) ?? []), w.day]);
    }
    const lines = [...groups].map(([key, days]) => {
        const sorted = [...new Set(days)].sort((a, b) => a - b);
        const [start, end] = key.split("-");
        return { first: sorted[0], key, text: `${daysOf(sorted)} ${clock(start)}–${clock(end)}` };
    });
    lines.sort((a, b) => a.first - b.first || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
    return `${lines.map((l) => l.text).join("; ")} (${zoneName(schedule.tz)})`;
}
