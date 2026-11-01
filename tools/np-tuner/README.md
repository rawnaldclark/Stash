# Now Playing tuner

Tune Now Playing's text spacing on the real screen, from a browser. The page shows a live screenshot of the phone next to a slider for each value. When you move a slider, the debug app lays Now Playing out again with the new value, and the next screenshot (about 1 a second) shows the result.

This works with **debug builds only**. Release builds have no receiver and always use the production values (see `NpSpacingModule`).

## What you need

- Node 18 or newer. The tool needs nothing else installed: no `npm install`.
- `adb` on your PATH, or pass `--adb <path>`.
- A phone running the debug app (`com.stash.app.debug`), with Now Playing open and a song playing.

## The Pixel 5 recipe

1. Connect to the rig over Wi-Fi:

   ```
   adb connect 192.168.137.230:5555
   ```

2. Optional: make the Pixel 5 lay out like the Pixel 6. The Pixel 5 is 393 x 851 dp and the Pixel 6 is 411 x 914 dp. dp values carry over, but the art and the spare height follow the screen size. To give the Pixel 5 the Pixel 6's geometry:

   ```
   adb -s 192.168.137.230:5555 shell wm size 1080x2400
   adb -s 192.168.137.230:5555 shell wm density 420
   ```

   Undo it when you're done, so the phone goes back to its own size:

   ```
   adb -s 192.168.137.230:5555 shell wm size reset
   adb -s 192.168.137.230:5555 shell wm density reset
   ```

3. Start the tuner and open the page it prints (`http://localhost:5178`):

   ```
   node tools/np-tuner/serve.mjs --serial 192.168.137.230:5555
   ```

   Options: `--port 5178`, `--adb <path to adb>`, `--package com.stash.app.debug`. Add `--no-wake` to stop the tool from sending the wake key.

4. On the phone, open Now Playing with a song playing.
5. Move the sliders:
   - A value that differs from production turns amber.
   - A grey tick on each slider marks the production value. Click a value's name to put it back.
   - **Layout › Text in full art** sets where the text sits while full art is out: where the square-art layout puts it (as today), right under the art, or at the bottom. Turn full art on to see it.
6. When it looks right, click **Copy values**. You get the Kotlin `NpSpacingDefaults` block, already in your clipboard, and a list of what changed. Paste the block over `NpSpacingDefaults` in `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/NpSpacing.kt`. Then change the pinned numbers in `NpSpacingTest`, and the matching `default` entries in `fields.json`, to the new values (`NpTunerFieldsTest` checks that `fields.json` matches the Kotlin defaults). Check the final values once on the Pixel 6, on a release build.
7. Click **Reset** to send every value back to production, on the phone as well.

The page remembers your values across reloads, and sends them to the phone when it opens. The phone keeps them too, across app restarts, until you reset.

## Without the page

The page only sends these broadcasts, so you can send them yourself. `-p` matters: Android doesn't deliver a broadcast without a package to an app's manifest receiver.

```
adb shell am broadcast -a com.stash.app.debug.NP_SPACING -p com.stash.app.debug --es json '{"titleSize":24,"gapArt":30,"align":"start"}'
adb shell am broadcast -a com.stash.app.debug.NP_SPACING -p com.stash.app.debug --ez reset true
adb shell am broadcast -a com.stash.app.debug.NP_SPACING -p com.stash.app.debug
```

- The first applies the values on top of what is already set. You can send just one value.
- The second goes back to production.
- The third only logs what is in force: `adb logcat -s DebugNpSpacing`.
- Keys the app doesn't know are ignored, and each number is clamped to its range. `fields.json` lists every key with its range, unit and production value.

## Safety

- The server listens on `127.0.0.1` only.
- It checks every value against `fields.json` before calling adb. Numbers are clamped, and choices must come from their list, so nothing typed on the page can run as a command on the phone.
- The only key it sends is `KEYCODE_WAKEUP`, at most every 30 s while the page is taking screenshots. That key can wake the screen but never turns it off or locks the phone.
- The receiver is declared only in the debug manifest, behind the DUMP permission. adb has that permission; ordinary apps can't get it.
