# Search Tab Overhaul — Performance Checklist

Verify on-device against §4.1 of the spec. Each row maps to a latency target.

- [ ]  Keystroke → skeleton rows visible          target p50 <100ms / p95 <200ms
       Trigger: adb logcat Perf:D | grep "Search skeleton"
- [ ]  Debounced query → first real results       target p50 <900ms / p95 <1500ms
       Trigger: adb logcat Perf:D | grep "Search first-results"
- [ ]  Tap artist → hero (name+avatar) visible    target p50 <50ms  / p95 <100ms
       Verify: first Perf log line "ArtistProfile hero first-frame"
- [ ]  Tap artist → full profile painted          target p50 <1s    / p95 <2s
       Trigger: adb logcat Perf:D | grep "ArtistProfile paint"
- [ ]  Tap artist (SWR-cached) → full profile     target p50 <50ms  / p95 <100ms
       Cold-open, tap same artist again within 6h; Perf log should read "status=Fresh"
- [ ]  Tap preview on Popular → audible           target p50 <500ms / p95 <3s
       adb logcat Perf:D | grep "Preview audible"
- [ ]  Scroll Albums/Singles row                  target 60 fps
       adb shell dumpsys gfxinfo com.stash.mp3apk framestats

Gate: if ANY target is missed twice in a row, the phase is failed — do NOT ship.

Enable Perf logs on-device:
    adb shell setprop log.tag.Perf DEBUG
