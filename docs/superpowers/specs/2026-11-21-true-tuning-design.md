# True tuning: each song measured, then brought to your pitch

**Status:** approved by the owner 2026-11-21 ("lets build"). Step 1 is built; its accuracy report is §8.

**The owner's ask (2026-11-21):** "hz monitoring needs to be true and not a generic downpitch on every track. we aren't actively monitoring and validating the natural freq of each track with 100% accuracy."

## 1. What Stash does today

Tuning (#872) retunes "everything Stash plays" to a chosen concert pitch: the frequency of the A above middle C, 440 Hz by standard, 432 Hz for the setting most people pick.

- It multiplies every song's pitch by one number: the setting ÷ 440. At 432 that is 31.8 cents down, for every song.
- It never looks at the song. `TuningMath.factor` is the whole rule, and `TuningController` hands that one factor to every stream.
- So it lands on 432 only for a recording that was at exactly 440:
  - a song recorded at A = 442 comes out near 434;
  - a song already at 432 comes out near 424, further from the target than it started.
- The app still labels every song "A = 432 Hz".
- What is verified: the pitch engine moves tones by the right ratio, within 0.5 Hz (CI's "Native pitch test"). Nothing checks where a real song ends up.

## 2. What "true" can mean, and what it can't

**A song's own tuning** is the concert pitch its notes sit on. Recordings differ: orchestras tune to 441–443, old tape transfers run fast or slow, some artists record at 432 on purpose.

It can be measured from the audio: find the pitched content, see how far the notes sit from the standard 12-note grid, and read that offset as a concert pitch. A song whose notes sit 8 cents above the grid was recorded at about A = 442.

Three limits are worth stating before anything is built:

- **It is a measurement, so it has an error.** On music with clear pitched content, estimators of this kind are typically within a few cents (under 1 Hz at A). No method gives 100% on every recording, and this design doesn't claim it. It measures the error on a test set and publishes it (§5), and it tells you per song how sure it is.
- **Some songs have no tuning to find:** drum-only tracks, speech, noise, songs whose speed drifts, and music outside the 12-note system. Those must say "not measured", never a made-up number.
- **The offset is only known within a semitone.** A song transposed by a whole semitone is indistinguishable from one at 440, which is fine: its notes sit on the 440 grid, and that is the thing being corrected. A measured tuning is therefore always between about 427.5 and 452.9 Hz.

## 3. The design

The app already does this for loudness: each downloaded file is measured once in the background, the result is stored on the song, and playback applies a per-song gain (`LoudnessMeasurer`, `LoudnessBackfillWorker`, the `loudness_*` columns, `LoudnessState.currentTrackGainDb`). Tuning follows the same path.

### 3.1 Measure

- **The estimator** reads decoded audio and returns the song's offset from the 440 grid in cents, plus a confidence between 0 and 1.
  - It takes spectral peaks through the song, keeps the stable ones, and builds a histogram of how far each sits from the nearest note. The histogram's peak is the offset; how sharp that peak is, and how much pitched content there was, give the confidence.
  - It is plain Kotlin with no Android in it, so its accuracy is unit-tested on the build machine and in CI.
- **Downloaded songs** are measured in the background, as loudness is: when a song is downloaded or imported, and by a backfill worker for the songs already in the library (charging and idle only).
- **Streamed songs** are measured from the audio as it plays, and the result is kept for next time. See decision 2 for what happens on the first play.
- **Stored per song:** the offset in cents, the confidence, and when it was measured. That is a database change (version 80 to 81, three new columns, nothing existing touched).

### 3.2 Apply

- The factor for a song becomes **target ÷ the song's own tuning**, not target ÷ 440.
  - A song at 442 with the target at 432 is shifted by 432/442 (39.6 cents down, not the fixed 31.8).
  - A song already at 432 is left alone.
- **Always the smallest shift that lands on your pitch.** Because a tuning is only known within a semitone (§2), there are always two shifts that put a song's notes on your grid, one up and one down, adding to a semitone. The app takes the smaller, so no song moves more than half a semitone. For the usual case, a song within a few Hz of 440, that is the shift you'd expect.
- The factor is set at the start of each song. The pitch engine already changes pitch live between two retuned values, so a song boundary needs no gap.
- A song under the confidence bar counts as not measured: it is shifted as a 440 recording would be (decision 1).
- At 440 with every song measured, songs that aren't at 440 can be corrected too. That is a new behaviour, so it's its own switch (decision 3).

### 3.3 Show

- **Now Playing's tuning sheet** gains one line about the song that's playing:
  - "This song: recorded at A = 441.3 Hz. Shifted 36.9 cents lower."
  - "This song is already at A = 432 Hz. Left as recorded."
  - "This song's tuning couldn't be measured. Shifted as a standard recording."
  - "Measuring this song…" on a stream's first play.
- **The label stops over-claiming.** "A = 432 Hz" shows only for a song that was measured. An unmeasured song says "432 Hz, assuming a standard recording".
- **Settings › Tuning** says in one sentence that Stash measures each song first.

### 3.4 Monitor

This is the "actively monitoring and validating" part of the ask.

- The same estimator listens to what comes **out** of the pitch stage while a song plays, on a background thread, from a copy of the audio.
- After enough of the song has played, the signal path sheet shows it: "Measured at the output: A = 432.1 Hz", with a check mark when it's within tolerance of the target.
- A song that lands outside tolerance is logged with its numbers (target, measured in, measured out), so a wrong measurement can be found and the song re-measured.
- It costs a little CPU while playing, so it has a switch, on by default while Tuning isn't 440.

## 4. Decisions (owner, 2026-11-21)

1. **A song that can't be measured is treated as a standard 440 recording and shifted,** with "not measured" on its line. The owner's goal is that every track is pitched to the chosen tuning; the first draft's "leave it as recorded" was dropped for that reason. Proposed to the owner and built on at "lets build".
2. **A streamed song's first play starts at once,** is measured over its first seconds, then eases to the right pitch over about a second. The result is kept, so later plays are right from the first note. ("first play makes sense")
3. **At 440, songs that aren't at 440 are left alone by default,** with a switch to bring every song to standard pitch. Proposed with decision 1; the owner hasn't spoken to it directly.
4. **"Measure again"** on the song's line. ("yes to 4")

## 5. Proving the accuracy

The numbers below are the bars this design sets. The real figures come from the tests in step 1 and are reported before anything ships.

- **Made audio with a known answer:** tones, chords and full mixes rendered at 428, 432, 436, 438, 440, 442, 444.5 and 452 Hz, then with drums, noise, vibrato, reverb and lossy encoding added. The answer is known exactly, so the error is too.
  - One render at 415 Hz (Baroque pitch, a semitone below 440) checks the limit in §2: it must read as 439.7, on the 440 grid.
  - Bar: within 2 cents on clean material, within 5 cents with the additions.
  - Bar: unpitched material (drums, speech, noise) must come back under the confidence bar, every time.
- **Real songs:** a fixed list from the test phone's library, including recordings known to sit off 440. Each is measured, shifted, and measured again at the output.
  - Bar: the output within 5 cents of the target for at least 95% of the songs the estimator was confident about.
  - Every miss is listed with its numbers in the report.
- **Round trip in CI:** the estimator's tests run with the unit tests; the native pitch test gains a case that shifts by a measured, per-song factor.
- **On the phone:** the monitor's line (§3.4) is the same check, live, on whatever is playing.

## 6. Build order

Each step is its own PR and leaves the app working.

1. **The estimator, its test audio and its accuracy report.** Nothing in the app changes. This step decides the confidence bar, and whether the bars in §5 hold. If they don't, the design comes back here before anything else is built.
2. **Measure and store:** the columns and migration, measuring on download and import, the backfill worker.
3. **Apply per song,** with decision 1's rule for unmeasured songs, and the song's line in Now Playing.
4. **The monitor** and the signal path line.
5. **Streams:** measure while playing, per decision 2.

## 7. What stays the same

- Speed never changes; only pitch.
- Tuning stays local to this phone. In a Listen Together room or a Session, each phone applies its own.
- A retuned stream still can't be bit-perfect, and the signal path keeps saying so.
- The chosen target, its presets and the custom field are untouched.

## 8. Step 1's accuracy report (2026-11-21)

`TuningEstimator` in `core/model`, branch `feat/tuning-estimator`. Nothing in the app uses it yet.

**What changed while building it.** The first version counted every spectral peak. It passed every made-audio test and then failed on real songs: drums and voices buried the tuning, and 8 of the first 10 songs came back "not measured". Now a peak counts only if it holds its pitch for about half a second, which a played note does and a drum, a spoken word or a sung slide doesn't. The confidence is how far the histogram's peak stands above its median, less a rival peak.

**Made audio, where the answer is known exactly** (`TuningEstimatorTest`, in CI):

| Audio | Tunings | Largest error | Bar |
|---|---|---|---|
| A plain tone | 8, from 428 to 452 Hz | 0.27 cents | 2 |
| Chords, bass and melody | 8 | 0.19 cents | 2 |
| With drums and noise on top | 4 | 0.17 cents | 5 |
| Melody with a wide vibrato | 4 | 0.21 cents | 5 |
| Piano-like stretched partials | 4 | 1.18 cents | 5 |
| With echo | 4 | 0.21 cents | 5 |

- At 48 and 96 kHz the reading is the same (432.01 Hz for 432).
- A song a semitone down (415.3 Hz) reads 440.03, on the grid, as §2 says it must.
- Noise, drums, drums with noise, a pitch that never rests, and notes on no grid: all "not measured" (confidence 0.00 to 0.08 against a bar of 0.35).

**Real songs** (`TuningCorpusReport`; 19 files from the test phone, run on the dev PC, not in the repository). A real song's own tuning isn't known in advance, so each was also resampled by two exact amounts, -31.77 and +11.76 cents, and the reading had to move by exactly that.

- 18 music tracks: 17 measured, all at full or near-full confidence. Across their 34 shifted copies the largest error was 0.81 cents, and most were under 0.5.
- The one not measured, a 1966 Hendrix studio take, has two tunings in it about 16 cents apart, and the estimator says so rather than pick one.
- 27 minutes of stand-up comedy: not measured (confidence 0.00).
- What they read, which is the case for doing this at all:
  - six sit within 2 cents of 440 (four of them synth or keyboard productions at 440.0 to 440.2, which is the best outside check of the absolute reading these songs allow);
  - the rest are spread from 428.7 to 447.9 Hz: sample-based tracks pitched up 25 to 31 cents, one acoustic song 45 cents down, a band 22 cents up.
  - Today's fixed shift would leave those up to 45 cents off the target.
- Speed: about 7 ms for each second of audio on the dev PC (a 4-minute song in under 2 seconds). The phone will be slower; step 2 measures it there.

**Limits found, and still open:**

- Every voice with a wide vibrato at once, as in the first made-audio vibrato test, read 14 cents off at full confidence. Real music with steady instruments under the voice is fine (the test now has that shape), but a solo voice or solo strings needs a real recording in the corpus before step 3.
- The absolute reading on real songs is checked only by made audio and by the 440 productions above. The corpus has no real recording whose tuning is documented.
- The corpus is 18 songs from one phone: mostly hip-hop and rock, no classical, no solo instrument.
