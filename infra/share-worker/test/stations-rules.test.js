import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanSchedule, cleanStationFields, instantOf, nextWindow, summaryOfStation } from "../src/stations.js";

const NY = "America/New_York";
const at = (iso) => Date.parse(iso);
const win = (day, start, end) => ({ day, start, end });
const sched = (tz, ...windows) => ({ tz, windows });

test("fields: a name is trimmed, 1–40 characters", () => {
    assert.deepEqual(cleanStationFields({ name: "  Rawn's Basement " }), { name: "Rawn's Basement" });
    assert.equal(cleanStationFields({ name: "   " }), null);
    assert.equal(cleanStationFields({ name: "x".repeat(41) }), null);
    assert.deepEqual(cleanStationFields({ name: "x".repeat(40) }), { name: "x".repeat(40) });
    assert.equal(cleanStationFields({}), null, "a new station needs a name");
    assert.equal(cleanStationFields(null), null);
    assert.equal(cleanStationFields("Rawn"), null);
});

test("fields: host name up to 40, vibe up to 60, both optional and clearable", () => {
    assert.deepEqual(cleanStationFields({ name: "A", hostName: " Rawn ", vibe: " 90s house, requests open " }),
        { name: "A", hostName: "Rawn", vibe: "90s house, requests open" });
    assert.equal(cleanStationFields({ name: "A", hostName: "x".repeat(41) }), null);
    assert.equal(cleanStationFields({ name: "A", vibe: "x".repeat(61) }), null);
    assert.deepEqual(cleanStationFields({ name: "A", vibe: "x".repeat(60) }), { name: "A", vibe: "x".repeat(60) });
    assert.deepEqual(cleanStationFields({ vibe: "" }, { partial: true }), { vibe: null });
    assert.deepEqual(cleanStationFields({ hostName: null }, { partial: true }), { hostName: null });
    assert.equal(cleanStationFields({ name: "A", vibe: 7 }), null);
});

test("fields: autoLive is off, schedule or always", () => {
    assert.deepEqual(cleanStationFields({ autoLive: "always" }, { partial: true }), { autoLive: "always" });
    assert.equal(cleanStationFields({ autoLive: "sometimes" }, { partial: true }), null);
});

test("fields: a partial change needs something to change; unknown keys are dropped", () => {
    assert.equal(cleanStationFields({}, { partial: true }), null);
    assert.equal(cleanStationFields({ owner: "x" }, { partial: true }), null);
    assert.deepEqual(cleanStationFields({ name: "B", owner: "x", up: 99 }), { name: "B" });
    assert.equal(cleanStationFields({ name: "" }, { partial: true }), null, "a name can't be cleared");
});

test("schedule: a time zone and up to 7 windows of day, start and end", () => {
    assert.deepEqual(cleanSchedule(sched(NY, win(1, "09:00", "17:00"))), sched(NY, win(1, "09:00", "17:00")));
    assert.deepEqual(cleanSchedule({ tz: NY, windows: [{ day: 5, start: "21:00", end: "01:00", extra: true }], also: 1 }),
        sched(NY, win(5, "21:00", "01:00")), "extra keys are dropped");
    assert.deepEqual(cleanSchedule(sched(NY)), sched(NY), "no windows is allowed (nothing upcoming)");
    assert.deepEqual(cleanStationFields({ schedule: null }, { partial: true }), { schedule: null });
});

test("schedule: refused when anything is off", () => {
    assert.equal(cleanSchedule(sched("Mars/Olympus_Mons", win(1, "09:00", "17:00"))), null);
    assert.equal(cleanSchedule(sched(NY, ...Array.from({ length: 8 }, () => win(1, "09:00", "10:00")))), null);
    for (const bad of [win(0, "09:00", "10:00"), win(8, "09:00", "10:00"), win(1.5, "09:00", "10:00"), win(1, "9:00", "10:00"),
        win(1, "24:00", "10:00"), win(1, "09:60", "10:00"), win(1, "09:00", "09:00"), { day: 1, start: "09:00" }]) {
        assert.equal(cleanSchedule(sched(NY, bad)), null, JSON.stringify(bad));
    }
    assert.equal(cleanSchedule({ tz: NY }), null);
    assert.equal(cleanSchedule({ windows: [] }), null);
    assert.equal(cleanStationFields({ schedule: { tz: "nowhere", windows: [] } }, { partial: true }), null);
});

test("schedule: the time zone is stored the way Intl spells it", () => {
    assert.equal(cleanSchedule(sched("america/new_york"))?.tz, NY);
});

test("instantOf: a skipped time (spring forward) starts after the gap; an ambiguous one (fall back) is the first", () => {
    assert.equal(instantOf(Date.UTC(2026, 2, 8, 2, 30), NY), at("2026-03-08T07:30:00Z"), "02:30 doesn't exist: 03:30 EDT");
    assert.equal(instantOf(Date.UTC(2026, 10, 1, 1, 30), NY), at("2026-11-01T05:30:00Z"), "the first 01:30 (EDT)");
    assert.equal(instantOf(Date.UTC(2026, 10, 16, 9, 0), NY), at("2026-11-16T14:00:00Z"));
});

test("nextWindow: in progress, else the next to start", () => {
    const s = sched(NY, win(1, "09:00", "17:00"));
    // Mon 16 Nov 2026, 10:00 EST
    assert.deepEqual(nextWindow(s, at("2026-11-16T15:00:00Z")), { start: at("2026-11-16T14:00:00Z"), end: at("2026-11-16T22:00:00Z") });
    // Mon 18:00: next Monday
    assert.deepEqual(nextWindow(s, at("2026-11-16T23:00:00Z")), { start: at("2026-11-23T14:00:00Z"), end: at("2026-11-23T22:00:00Z") });
    // exactly at the end: that one is over
    assert.equal(nextWindow(s, at("2026-11-16T22:00:00Z")).start, at("2026-11-23T14:00:00Z"));
});

test("nextWindow: a window across midnight is still in progress after it", () => {
    const s = sched(NY, win(5, "21:00", "01:00")); // Fridays 21:00–01:00
    // Sat 21 Nov 00:30 EST
    assert.deepEqual(nextWindow(s, at("2026-11-21T05:30:00Z")), { start: at("2026-11-21T02:00:00Z"), end: at("2026-11-21T06:00:00Z") });
});

test("nextWindow: the soonest of several windows", () => {
    const s = sched(NY, win(3, "08:30", "10:00"), win(1, "09:00", "17:00"));
    // Sun 15 Nov noon: Monday comes before Wednesday
    assert.equal(nextWindow(s, at("2026-11-15T17:00:00Z")).start, at("2026-11-16T14:00:00Z"));
});

test("nextWindow: across the DST weeks", () => {
    // Sundays 02:30–04:00, asked the Saturday before spring forward: 03:30 EDT to 04:00 EDT
    assert.deepEqual(nextWindow(sched(NY, win(7, "02:30", "04:00")), at("2026-03-07T17:00:00Z")),
        { start: at("2026-03-08T07:30:00Z"), end: at("2026-03-08T08:00:00Z") });
    // Sundays 01:30–03:00, asked the Saturday before fall back: the first 01:30 (EDT) to 03:00 EST
    assert.deepEqual(nextWindow(sched(NY, win(7, "01:30", "03:00")), at("2026-10-31T16:00:00Z")),
        { start: at("2026-11-01T05:30:00Z"), end: at("2026-11-01T08:00:00Z") });
});

test("nextWindow: half-hour zones and a half-hour DST", () => {
    // Kolkata, +05:30: Monday 16 Nov 09:00 is 03:30 UTC
    assert.equal(nextWindow(sched("Asia/Kolkata", win(1, "09:00", "10:00")), at("2026-11-15T12:00:00Z")).start, at("2026-11-16T03:30:00Z"));
    // Lord Howe Island: +11 in its summer, +10:30 in its winter
    const lh = sched("Australia/Lord_Howe", win(1, "09:00", "10:00"));
    assert.equal(nextWindow(lh, at("2026-01-11T12:00:00Z")).start, at("2026-01-11T22:00:00Z"));
    assert.equal(nextWindow(lh, at("2026-06-14T12:00:00Z")).start, at("2026-06-14T22:30:00Z"));
});

test("nextWindow: nothing without windows", () => {
    assert.equal(nextWindow(sched(NY), Date.now()), null);
    assert.equal(nextWindow(null, Date.now()), null);
});

test("a station as phones see it: never its owner", () => {
    const row = { id: "STATION1", owner: "o".repeat(64), name: "Rawn's Basement", host_name: "Rawn", vibe: null, up: 5, down: 1, followers: 3, my_vote: 1, heard: 1, following: 0 };
    assert.deepEqual(summaryOfStation(row, null), { id: "STATION1", name: "Rawn's Basement", hostName: "Rawn", vibe: null, up: 5, down: 1, followers: 3 });
    assert.deepEqual(summaryOfStation(row, "x".repeat(64)),
        { id: "STATION1", name: "Rawn's Basement", hostName: "Rawn", vibe: null, up: 5, down: 1, followers: 3, myVote: 1, mine: false, heard: true, following: false });
    const mine = summaryOfStation({ ...row, heard: 0, my_vote: null }, "o".repeat(64));
    assert.equal(mine.mine, true);
    assert.equal(mine.heard, true, "your own station counts as heard");
    assert.equal(mine.myVote, 0);
    assert.ok(!JSON.stringify(mine).includes("o".repeat(64)));
});
