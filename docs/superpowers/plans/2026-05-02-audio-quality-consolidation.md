# Audio Quality Section Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse three stacked Settings GlassCards (Audio Quality / Lossless toggle / squid.wtf captcha) into a single combined GlassCard with a `glassBorder` divider, an inline lossless toggle, and an "Advanced" expander hiding manual cookie paste + rate-limiter reset.

**Architecture:** Pure Compose UI refactor inside `SettingsScreen.kt`. No new files, no DataStore changes, no ViewModel changes. The existing `SettingsUiState` fields (`audioQuality`, `losslessEnabled`, `squidWtfCaptchaCookie`) and existing callbacks (`onQualityChanged`, `onLosslessEnabledChanged`, `onSquidWtfCaptchaCookieChanged`, `onResetLosslessRateLimiter`, `onNavigateToSquidWtfCaptcha`) feed the new card unchanged.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Stash's `GlassCard` + `extendedColors` extended-theme tokens.

**Spec:** `docs/superpowers/specs/2026-05-02-audio-quality-consolidation-design.md`

---

## Pre-flight

This work piggybacks on the in-progress `feat/squid-webview-captcha` branch (it consolidates UI for that branch's lossless + captcha work). **No worktree needed** — the user is already on the right branch. Single-file refactor, single commit.

- [ ] **Confirm clean-enough working state**

```bash
cd C:/Users/theno/Projects/MP3APK
git status --short feature/settings/
```

Expected: `feature/settings/SettingsScreen.kt` and `feature/settings/SettingsViewModel.kt` may already be `M` (in-progress captcha work) — that's fine, this task touches `SettingsScreen.kt` further. If anything else looks unexpected, stop and ask.

- [ ] **Confirm spec is committed**

```bash
cd C:/Users/theno/Projects/MP3APK && git log --oneline -5 docs/superpowers/specs/2026-05-02-audio-quality-consolidation-design.md
```

Expected: at least one commit referencing the spec (e.g. `58f5369` or `fa1dc80`).

---

## Task 1: Replace the three Audio Quality cards with one combined GlassCard

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt` (replaces lines ~362-528 — note line numbers shift as edits land; use the literal anchor strings below to find the right region)

**Single-edit task** — the three cards form one logical Settings section, and intermediate states (e.g. radio card landed but old lossless card still beside it) would visually thrash. Do the whole replacement in one Edit, build, then commit.

- [ ] **Step 1: Read the current region to confirm what's being replaced**

Use the Read tool on `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt` from line 360 to line 535 (or whatever the closing brace of the third card lands on). Verify:
- Line ~362: `// -- Audio Quality section --` comment
- Line ~363: `SectionHeader(title = "Audio Quality")`
- Line ~365-413: First `GlassCard` containing the `QualityTier` radio group
- Line ~417-452: Second `GlassCard` with the lossless toggle Row
- Line ~454-528: `if (uiState.losslessEnabled) { ... }` containing the third `GlassCard` with captcha controls (`Verify in browser`, cookie status, manual paste TextField, Reset button)

Confirm the **exact opening anchor** is the comment line `// -- Audio Quality section --------------------------------------------` and the **exact closing anchor** is the closing `}` of the captcha-conditional block (likely followed by another section like Audio Effects or similar).

- [ ] **Step 2: Replace the three-card block with one combined card**

Use Edit with the literal anchor strings to scope the replacement. Note: keep the `SectionHeader(title = "Audio Quality")` outside the new card (matches the existing pattern in the rest of `SettingsScreen.kt`).

The replacement uses these existing imports already present at the top of the file: `GlassCard`, `MaterialTheme`, `Switch`, `Text`, `Row`, `Column`, `Spacer`, `RadioButton`, `RadioButtonDefaults`, `OutlinedButton`, `OutlinedTextField`, `ButtonDefaults`, `selectable`, `selectableGroup`, `Role`, `role`, `semantics`, `Modifier`, `Alignment`. New imports needed:
- `androidx.compose.foundation.clickable` (already imported at line 3)
- `androidx.compose.material3.HorizontalDivider`
- `androidx.compose.material3.TextButton` (already used at line 518 via fully-qualified name; replace with proper import)
- `androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight` (already imported line 19)
- `androidx.compose.runtime.getValue`, `setValue`, `remember`, `mutableStateOf` (already imported)
- `androidx.compose.animation.AnimatedVisibility`
- `androidx.compose.animation.core.animateFloatAsState`
- `androidx.compose.ui.graphics.graphicsLayer`
- `androidx.compose.ui.semantics.contentDescription`
- `androidx.compose.ui.semantics.stateDescription`

Add the missing imports near their alphabetical position in the import block at the top of `SettingsScreen.kt`.

Then replace the three-card region. New code (paste after `SectionHeader(title = "Audio Quality")`, replacing the three existing GlassCards and their inter-card spacers):

```kotlin
GlassCard {
    var advancedExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (advancedExpanded) 90f else 0f,
        label = "advancedChevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        // -- Download quality (radio group) -------------------------
        Text(
            text = "Download quality",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))

        QualityTier.entries.forEach { tier ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = uiState.audioQuality == tier,
                        onClick = { onQualityChanged(tier) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = uiState.audioQuality == tier,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = tier.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${tier.bitrateKbps} kbps  ~${tier.sizeMbPerMinute} MB/min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // -- Divider between always-on quality + experimental lossless ---
        // glassBorder matches the existing in-card divider style used by
        // other Settings cards (Storage, Move library, AccountConnectionCard).
        // Do NOT use MaterialTheme.colorScheme.outlineVariant — it would
        // visually announce the consolidation as foreign vs the surrounding
        // GlassCard chrome.
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            thickness = 1.dp,
            color = extendedColors.glassBorder,
        )

        // -- Lossless toggle row -------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Lossless downloads (experimental)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (uiState.losslessEnabled) {
                        "Try Qobuz proxy first; FLAC ~10× larger"
                    } else {
                        "Off — uses YouTube/yt-dlp like before"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.losslessEnabled,
                onCheckedChange = onLosslessEnabledChanged,
                modifier = Modifier.semantics { role = Role.Switch },
            )
        }

        // -- Captcha sub-block + Advanced expander (only when lossless on) -
        AnimatedVisibility(visible = uiState.losslessEnabled) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(12.dp))

                // Verify button + verified status, single row to save vertical
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onNavigateToSquidWtfCaptcha,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Verify in browser")
                    }
                    if (uiState.squidWtfCaptchaCookie.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "✓ Verified",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics {
                                contentDescription = "captcha cookie verified"
                            },
                        )
                    }
                }

                // -- Advanced expander row (chevron + label) -----------
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .semantics {
                            role = Role.Button
                            // Spec §Accessibility: announce collapsed/expanded
                            // state to screen readers.
                            stateDescription = if (advancedExpanded) "expanded" else "collapsed"
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (advancedExpanded) "Collapse advanced" else "Expand advanced",
                        modifier = Modifier.graphicsLayer(rotationZ = chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Advanced",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(visible = advancedExpanded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Or paste the captcha_verified_at cookie value directly:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = uiState.squidWtfCaptchaCookie,
                            onValueChange = onSquidWtfCaptchaCookieChanged,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("captcha_verified_at value") },
                            singleLine = true,
                            placeholder = { Text("e.g. 1777687404951") },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = onResetLosslessRateLimiter,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Reset lossless attempts",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

Notes for the implementer:
- `extendedColors` is already in scope at this point in `SettingsContent` (it's pulled at line 203 via `val extendedColors = StashTheme.extendedColors`). If for some reason that local has been refactored away, re-derive it via `val extendedColors = StashTheme.extendedColors` near the top of the function.
- The existing `androidx.compose.material3.TextButton` reference at the old line ~518 was fully-qualified inline; replace it with a normal `import` at the top alongside the other Material3 imports.
- The existing two `Spacer(modifier = Modifier.height(8.dp))` calls between the old cards (around lines 415 and 461) MUST be deleted — they're now inside-card vertical rhythm, and the new card handles its own spacing.
- The card replacement starts immediately after `SectionHeader(title = "Audio Quality")` — do NOT introduce a `Spacer` before the new card.
- After the new card closes with `}`, the next code in the file should be whatever followed the third old card (likely another `Spacer` + the Audio Effects section). Do NOT delete that.

- [ ] **Step 3: Verify the file compiles**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :feature:settings:assembleDebug
```

Expected: BUILD SUCCESSFUL.

If you hit unresolved references for `extendedColors` or one of the icons / animation symbols, double-check the imports list in the file's import block (top ~70 lines).

- [ ] **Step 4: Build the full app to catch any wiring regressions**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. (The captcha branch's existing in-progress changes may produce warnings — that's fine; only fail on **errors**.)

- [ ] **Step 5: Run any existing settings unit tests for regression check**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :feature:settings:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. If `:feature:settings` has no test source set, gradle will say `NO-SOURCE` — that's fine. The change is pure UI; nothing should regress in unit tests.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt && \
git commit -m "$(cat <<'EOF'
feat(settings): consolidate Audio Quality + Lossless + Captcha into one card

Replaces three stacked GlassCards (Audio Quality / Lossless toggle /
squid.wtf captcha) with a single combined card. Internal layout:
download-quality radios, glassBorder divider, inline lossless toggle,
captcha controls (only when lossless on), and an Advanced expander
hiding the manual cookie paste TextField + Reset button.

Compactions:
- 3 cards → 1 card (eliminates 2 inter-card spacers + 2 sets of card padding)
- Lossless subtitle 2 lines → 1 line
- Captcha description paragraph removed (button label is self-explanatory)
- Cookie-set status reduced to "✓ Verified" inline next to the Verify button
- Manual paste + Reset hidden behind Advanced expander

No data-model changes; SettingsUiState + SettingsViewModel + the
LosslessSourcePreferences DataStore are all unchanged.

Spec: docs/superpowers/specs/2026-05-02-audio-quality-consolidation-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Device acceptance

Memory `feedback_install_after_fix.md`: compile-pass isn't enough — install and verify on device.

- [ ] **Step 1: Install on device**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :app:installDebug
```

Expected: `Installed on 1 device.`

- [ ] **Step 2: Run the manual acceptance flow**

Open Settings → scroll to "Audio Quality". Verify each scenario from spec §Testing → Manual acceptance:

1. **Lossless OFF (default state):** Single card visible with the radio group + the lossless toggle row + its subtitle. **No** Verify button, **no** Advanced expander, **no** captcha controls.
2. **Toggle lossless ON, no cookie yet:** A "Verify in browser" button appears below the divider. The Advanced expander row appears below the button (collapsed, chevron pointing right). **No** "✓ Verified" status.
3. **Solve captcha** (tap Verify → solve in WebView → return): the "✓ Verified" indicator appears inline at the end of the row containing the Verify button. **Vertical card height should not grow** because the indicator shares the row.
4. **Tap "▸ Advanced":** chevron rotates 90° → "▾". The captcha_verified_at TextField + the Reset button appear below.
5. **Paste a cookie into the manual TextField:** reflects through to `onSquidWtfCaptchaCookieChanged` (verify by checking that toggling the field shows the change in the "✓ Verified" indicator state — if the value is non-empty, the indicator shows).
6. **Tap "Reset lossless attempts":** confirm via logcat (`adb logcat | grep -i lossless`) that the rate-limiter reset log line fires.
7. **Tap Advanced again to collapse:** TextField + Reset button hidden. Chevron rotates back to "▸".
8. **Toggle lossless OFF:** captcha sub-block + Advanced expander disappear entirely. Card height matches state (1).
9. **Toggle lossless ON again:** Advanced expander rendered collapsed (auto-reset behavior — verifies the `remember(uiState.losslessEnabled)` keying works).
10. **Visual consistency check (both themes):** the divider colour matches other in-card dividers (compare to the Storage card's internal divider). Test in BOTH light and dark mode — `extendedColors.glassBorder` is theme-aware (different colours in `Color.kt:60` vs `:75`), so a regression that wired in `outlineVariant` instead would be more obvious in one theme than the other. No "foreign" Material default outlineVariant tint in either theme.
11. **Card-height comparison:** estimate the "lossless ON, cookie set, advanced collapsed" card height vs. the previous version (you may want to install the previous version to compare side-by-side, OR just trust the reduction is real — the spec table covers what was removed).

- [ ] **Step 3: If anything sounds glitchy or visually wrong**

Run `adb logcat -d -v time | grep -iE "stash|compose|settings" | tail -100` and inspect. If the issue is layout-related (e.g. card too narrow, divider misaligned), iterate on the new card's `Modifier` chain. If the issue is state (e.g. expander doesn't auto-reset on lossless OFF), the `remember(uiState.losslessEnabled)` key parameter is the place to check.

- [ ] **Step 4: Empty commit for the record (optional)**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git commit --allow-empty -m "test: device acceptance — Audio Quality consolidation"
```

Skip this if you'd rather keep the history flat. It's only useful as a checkpoint marker if you want to see in `git log` that manual acceptance ran.

---

## Skills reference

- @superpowers:verification-before-completion — before claiming done; do not skip the device install + manual acceptance in Task 2

## Risks / rollback

- **Rollback:** revert the single Task 1 commit. The three original cards return; user data, preferences, and download pipeline are unaffected (this was a pure UI refactor).
- **Risk: divider colour wrong.** If the card visually announces itself as foreign vs. the rest of Settings, the `glassBorder` token might have been routed wrong. Compare against the Storage card's internal divider at `SettingsScreen.kt` (search for the existing `HorizontalDivider` usages) and copy its `color` arg + `Modifier.padding(vertical = ...)` exactly.
- **Risk: Advanced expander state survives across lossless toggles.** Mitigation: the `remember(uiState.losslessEnabled)` keying re-creates the state. If this fails (Compose recomposes too aggressively), an explicit `LaunchedEffect(uiState.losslessEnabled) { advancedExpanded = false }` is the fallback pattern.
- **Risk: removing the verbose captcha description leaves new users confused.** Mitigation: the in-app WebView ("Verify in browser") opens with its own header and instructions; this card only points at it. If a user reports confusion, restore a one-liner like `"Required ~every 30 min"` between the toggle subtitle and the Verify button row.
