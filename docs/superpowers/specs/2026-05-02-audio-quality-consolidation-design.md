# Audio Quality Section — Compact Consolidation Design

**Date:** 2026-05-02
**Status:** Design
**Branch:** `feat/squid-webview-captcha` (extends the in-progress lossless + captcha work)

## Problem

The Settings screen currently renders the lossless-related controls as **three separate `GlassCard`s** stacked vertically (`SettingsScreen.kt:362-528`):

1. **Audio Quality** — radio group of `QualityTier` options
2. **Lossless downloads (experimental)** — toggle + 2-line subtitle
3. **squid.wtf captcha** (only when lossless on) — verbose paragraph + "Verify in browser" button + cookie-set status sentence + manual paste `OutlinedTextField` + "Reset lossless attempts" button

Each card has its own padding, header, and surrounding spacing; the captcha card always shows all four sub-controls when lossless is on, regardless of whether the user has touched the failsafe paths. The result is roughly 3× the vertical real estate the controls actually need.

## Goals

- Collapse the three cards into a single `GlassCard` that visually integrates the lossless toggle and captcha controls with the existing download-quality radios
- Hide the rarely-used manual paste + reset controls behind an "Advanced" expander
- Tighten verbose copy without losing meaning
- Zero data-model changes — `QualityTier` enum and `LosslessSourcePreferences` stay as-is
- No regression for users who leave lossless OFF (the default path is unchanged)

## Non-goals

- Changing the underlying `QualityTier` model or making lossless a 5th radio option (rejected as approach C — too much migration risk for a beta feature)
- Redesigning the captcha WebView screen itself (`SquidWtfCaptchaScreen.kt`) — only the entry point in Settings
- Touching the `LosslessSourcePreferences` DataStore or the rate-limiter logic
- Modifying `SettingsViewModel` or `SettingsUiState` — the existing flow + state already supports everything the new layout needs

## Design

### Single combined `GlassCard`

The card replaces the existing three cards at the position of the current first card (`SectionHeader(title = "Audio Quality")` followed by the radio group, around `SettingsScreen.kt:362`). Internal layout:

```
┌─ Audio Quality (GlassCard) ────────────────────┐
│ Download quality                               │
│   ○ Low      96 kbps   ~0.7 MB/min             │
│   ○ Medium   ...                               │
│   ● High     320 kbps  ~2.4 MB/min             │
│   ─────── HorizontalDivider ───────            │
│ Lossless downloads (experimental)     [ON ⬤]  │
│  Try Qobuz proxy first; FLAC ~10× larger       │
│                                                │
│  ── (only when losslessEnabled) ─────          │
│  [ Verify in browser ]   ✓ Verified            │
│                                                │
│  ▸ Advanced                                    │
│  ── (only when advancedExpanded) ─────         │
│  [ captcha_verified_at TextField ]             │
│  [ Reset lossless attempts ] (TextButton)      │
└────────────────────────────────────────────────┘
```

The `HorizontalDivider` uses `MaterialTheme.colorScheme.outlineVariant` (matches the existing Settings divider style elsewhere). It separates the always-on "Download quality" radios from the conditional/experimental lossless block but keeps both inside one card boundary.

### Behaviour

- **Lossless OFF (default):** card shows the radio group + the lossless toggle row + its subtitle. Below the toggle: nothing. The captcha sub-block + the Advanced expander are not rendered.
- **Lossless ON, no cookie yet:** captcha sub-block appears below the toggle, showing the "Verify in browser" `OutlinedButton`. No status text. Advanced expander rendered but collapsed.
- **Lossless ON, cookie present:** Same as above, with "✓ Verified" inline at the end of the same `Row` as the Verify button (so the row is `[Verify in browser]   ✓ Verified` — single line, no wasted vertical).
- **Advanced expanded:** under the chevron row, render the manual `captcha_verified_at` `OutlinedTextField` and the "Reset lossless attempts" `TextButton`. Both behave identically to today.
- **Toggling lossless off resets `advancedExpanded` to `false`** (so re-enabling lossless shows the clean default state, not whatever the user left expanded last time).

### State

Two local UI states:
- `var advancedExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }` — keyed on `losslessEnabled` so it auto-resets when lossless is turned off.

Everything else flows from the existing `SettingsUiState` (`audioQuality`, `losslessEnabled`, `squidWtfCaptchaCookie`) and the existing callbacks (`onQualityChanged`, `onLosslessEnabledChanged`, `onSquidWtfCaptchaCookieChanged`, `onResetLosslessRateLimiter`, `onNavigateToSquidWtfCaptcha`).

### Copy compactions

| Position | Today | New |
|---|---|---|
| Lossless subtitle (when ON) | "Tries Qobuz (via squid.wtf) first; FLAC files are ~10× larger" | "Try Qobuz proxy first; FLAC ~10× larger" |
| Lossless subtitle (when OFF) | "Off — uses YouTube/yt-dlp like before" | (unchanged) |
| Captcha card description | "Lossless downloads need a captcha solve every ~30 min. Tap below to open the verifier — solve once and the cookie auto-saves." | Removed entirely (button label "Verify in browser" is self-explanatory) |
| Cookie-set status | "Cookie set — squid.wtf downloads should work for ~30 min." | "✓ Verified" inline next to the Verify button |
| Manual paste prompt | "Or paste the captcha_verified_at cookie value directly:" | (kept inside Advanced expander; current text is fine since it's already opt-in detail) |
| Advanced expander label | (didn't exist) | "▸ Advanced" → "▾ Advanced" when expanded (`Icons.AutoMirrored.Filled.KeyboardArrowRight` rotated 90° on expand) |

### Accessibility

- The lossless toggle row keeps its existing `Modifier.semantics { role = Role.Switch }`
- The Advanced expander row uses `Modifier.clickable { ... }.semantics { role = Role.Button }` and announces collapsed/expanded state via `stateDescription`
- The cookie-set status `"✓ Verified"` is given a `contentDescription = "captcha cookie verified"` so screen readers don't read the literal check character

## Touch points

| File | Change |
|---|---|
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt` | Replace lines 362-528 (three `GlassCard`s + spacers) with the new single combined `GlassCard`. ~80 LOC net deletion |

No new files, no preference changes, no ViewModel changes, no test changes. Existing UI tests (if any) keep passing because the callbacks and state contracts are unchanged.

## Testing

### Unit tests

None. The pure UI reorganisation has no logic to unit-test (state and callbacks are pass-through to existing ViewModel methods that are already tested).

### Manual acceptance

1. **Lossless OFF (default):** Settings → Audio Quality. See the radio group + lossless toggle row + subtitle. No Verify button, no Advanced expander, no captcha controls.
2. **Toggle lossless ON, no cookie:** Verify button appears below the divider. Advanced expander renders collapsed. No "✓ Verified" status.
3. **Solve captcha (in WebView):** return to Settings. The "✓ Verified" indicator appears next to the Verify button. Vertical space delta vs (2): zero (the indicator is on the same row as the button).
4. **Tap "▸ Advanced":** TextField + Reset button appear below the chevron. Chevron rotates to "▾".
5. **Paste a cookie into the manual TextField:** `onSquidWtfCaptchaCookieChanged` fires the same way it does today.
6. **Tap "Reset lossless attempts":** `onResetLosslessRateLimiter` fires.
7. **Tap Advanced again to collapse:** TextField + Reset button hidden. Chevron rotates back.
8. **Toggle lossless OFF:** captcha sub-block + Advanced expander disappear. Vertical space matches state (1).
9. **Toggle lossless ON again:** Advanced expander rendered collapsed (auto-reset state).
10. **Compare total card height** in the "lossless ON, cookie set, advanced collapsed" state to the existing version: should be at least 30% shorter due to the removed paragraph copy + collapsed advanced section.
11. **Visual consistency check:** the divider style (`outlineVariant`) and the card chrome should match other Settings sections. No "freestyled generic Material3" — the GlassCard wrapping and typography stay aligned with `EqualizerSection.kt` and the existing pattern.

## Risks & rollback

- **Risk: divider visual stands out as foreign vs current settings cards.** Mitigation: use the same `outlineVariant` colour as elsewhere in Settings; sample one of the existing dividers (search Settings for `HorizontalDivider`) and match its `Modifier.padding(vertical = ...)`.
- **Risk: users miss the manual paste / reset controls now that they're behind the expander.** Mitigation: the in-app WebView captures the cookie automatically — the manual paste was already a fallback. Power users who need it will discover the expander on second look.
- **Risk: `remember(uiState.losslessEnabled) { mutableStateOf(false) }` doesn't behave as expected if Compose recomposes too aggressively.** Mitigation: easily verified in manual test (step 8 → 9).
- **Rollback:** revert the single commit. Three cards return; user data unaffected.

## Out of scope

- The captcha WebView screen itself (`SquidWtfCaptchaScreen.kt`)
- `LosslessSourcePreferences` data model
- The download pipeline / circuit breaker logic
- Adding new options to `QualityTier`
- Visual redesign of the rest of Settings
