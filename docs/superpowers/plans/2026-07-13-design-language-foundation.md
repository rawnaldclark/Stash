# Premium Crisp — Design Language Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the reusable, data-agnostic foundation of the "Premium Crisp" design language in `core:ui` (motion/elevation tokens + presentation primitives) and apply the frosted bottom nav — so the Home redesign (Plan 2) has a standard to inherit.

**Architecture:** Everything here is presentation-only and takes plain params (no ViewModels, no data sources). New tokens go in `core:ui/theme`; new composables in `core:ui/components`; the frosted nav + edge-to-edge change is in `app/.../StashScaffold.kt`. Nothing in this plan depends on the Home data reshape — it ships and is verifiable on its own.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), the existing `StashTheme`/`StashExtendedColors`/`StashTypography`. Tests: JUnit + Truth for the pure token/logic bits; composables are compile-verified + on-device smoke (this repo has no Compose UI-test harness, and Plan-2/device is where visual correctness is judged — same approach used for the shipped About section).

**Spec:** `docs/superpowers/specs/2026-07-13-design-language-and-home-redesign.md` (§3 design language, §3.3 elevation, §3.5 motion).

**Scope boundary:** data-agnostic primitives + tokens + frosted nav ONLY. The chip-*derivation* logic, cold-start, data sections, screen rewrite, and Library port are **Plan 2**. Now Playing shared-element + mini-player chrome are **sub-project 2**.

**Repo notes:** Gradle **daemon** (never `--no-daemon`); always pass `--tests`. Module = `:core:ui`. `core:ui` test classpath: confirm Truth is present (add `testImplementation(libs.truth)` if not — mirror what Plan-1 Task 1 does).

---

## File Structure

| File | Responsibility | Task |
| --- | --- | --- |
| `core/ui/build.gradle.kts` (modify) | ensure Truth on test classpath | 1 |
| `core/ui/.../theme/Motion.kt` (new) | `StashMotion` durations/easings/press-scale/stagger constants | 1 |
| `core/ui/.../theme/Elevation.kt` (new) | named shadow/elevation dp constants (§3.3) | 1 |
| `core/ui/.../components/motion/PressScale.kt` (new) | `Modifier.pressScale()` | 2 |
| `core/ui/.../components/CrispChipRow.kt` (new) | data-agnostic filter-chip row | 3 |
| `core/ui/.../components/RankedAlbumRow.kt` (new) | ranked/numbered album list item + list | 4 |
| `core/ui/.../components/DiscoverHeroCard.kt` (new) | hero card (art-gradient, title, play) | 5 |
| `core/ui/.../components/AlbumSquareCard.kt` (modify) | optional FLAC badge overlay + press-scale | 6 |
| `app/.../navigation/StashScaffold.kt` (modify) | frosted bottom nav + edge-to-edge content padding | 7 |

---

## Task 1: Motion + elevation tokens

**Files:**
- Modify: `core/ui/build.gradle.kts` (add Truth if missing)
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/theme/Motion.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/theme/Elevation.kt`
- Test: `core/ui/src/test/kotlin/com/stash/core/ui/theme/MotionTokensTest.kt`

Spec §3.5 pins: `durShort=180ms`, `durMed=260ms`, `easeEnter=FastOutSlowIn`, `easeExit=FastOutLinearIn`, `pressScale=0.97f`, `sectionStagger=40ms`. §3.3 pins shadows: `heroShadow=8dp`, `chromeShadow=6dp`, `pressElevation=2dp`.

- [ ] **Step 0: Confirm Truth on `core:ui` test classpath (already present — verify, don't duplicate)**
`core/ui/build.gradle.kts` already has `testImplementation(libs.truth)` (~line 22). This is a verify-only step; do NOT add a second line.

- [ ] **Step 1: Write the failing test** (Truth-native assertions only — no Guava `Range`; nothing in this repo imports `com.google.common.collect.*` and we won't start)
```kotlin
package com.stash.core.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MotionTokensTest {
    @Test fun `durations are ordered and sane`() {
        assertThat(StashMotion.DUR_SHORT_MS).isLessThan(StashMotion.DUR_MED_MS)
        assertThat(StashMotion.DUR_SHORT_MS).isGreaterThan(0)
    }
    @Test fun `press scale is a subtle shrink`() {
        assertThat(StashMotion.PRESS_SCALE).isGreaterThan(0.9f)
        assertThat(StashMotion.PRESS_SCALE).isLessThan(1f)
    }
    @Test fun `stagger is small`() {
        assertThat(StashMotion.SECTION_STAGGER_MS).isAtLeast(10)
        assertThat(StashMotion.SECTION_STAGGER_MS).isAtMost(80)
    }
}
```

- [ ] **Step 2: Run — verify fail**
Run: `./gradlew :core:ui:testDebugUnitTest --tests "*MotionTokensTest*"` → FAIL (`StashMotion` unresolved).

- [ ] **Step 3: Implement `Motion.kt`**
```kotlin
package com.stash.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/** Premium Crisp motion tokens (spec §3.5). Named so screens never use ad-hoc values. */
object StashMotion {
    const val DUR_SHORT_MS = 180
    const val DUR_MED_MS = 260
    const val PRESS_SCALE = 0.97f
    const val SECTION_STAGGER_MS = 40
    // Material standard curves.
    val EaseEnter: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)  // FastOutSlowIn (decelerate)
    val EaseExit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)   // FastOutLinearIn (accelerate)
}
```
And `Elevation.kt`:
```kotlin
package com.stash.core.ui.theme

import androidx.compose.ui.unit.dp

/** Premium Crisp elevation cues (spec §3.3). The only depth in the system. */
object StashElevation {
    val Hero = 8.dp
    val Chrome = 6.dp
    val Press = 2.dp
}
```

- [ ] **Step 4: Run — verify pass**
Run: `./gradlew :core:ui:testDebugUnitTest --tests "*MotionTokensTest*"` → PASS (3).

- [ ] **Step 5: Commit**
```bash
git add core/ui/build.gradle.kts core/ui/src/main/kotlin/com/stash/core/ui/theme/Motion.kt \
        core/ui/src/main/kotlin/com/stash/core/ui/theme/Elevation.kt \
        core/ui/src/test/kotlin/com/stash/core/ui/theme/MotionTokensTest.kt
git commit -m "feat(ui): Premium Crisp motion + elevation tokens"
```

---

## Task 2: Press-scale modifier

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/motion/PressScale.kt`

Reusable interaction: a card scales to `StashMotion.PRESS_SCALE` while pressed, animating with `EaseEnter/Exit`. Composable modifier — compile-verified (Compose modifiers aren't unit-testable without a UI harness this repo lacks).

- [ ] **Step 1: Implement**
```kotlin
package com.stash.core.ui.components.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.composed
import com.stash.core.ui.theme.StashMotion

/** Scales content to StashMotion.PRESS_SCALE while pressed via [interactionSource]. */
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) StashMotion.PRESS_SCALE else 1f,
        animationSpec = tween(StashMotion.DUR_SHORT_MS, easing = StashMotion.EaseEnter),
        label = "pressScale",
    )
    this.scale(scale)
}
```

- [ ] **Step 2: Compile**
Run: `./gradlew :core:ui:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/motion/PressScale.kt
git commit -m "feat(ui): pressScale interaction modifier"
```

---

## Task 3: `CrispChipRow` (data-agnostic filter chips)

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/CrispChipRow.kt`

Renders a horizontal, scrollable row of filter chips. **Data-agnostic:** takes `chips: List<String>`, `selected: String`, `onSelect: (String) -> Unit`. Active chip = `MaterialTheme.colorScheme.primary` fill / `onPrimary` text; inactive = `StashTheme.extendedColors.elevatedSurface` fill / `MaterialTheme.colorScheme.onSurfaceVariant` text (note: `onSurfaceVariant` *is* the secondary-text color in both schemes — `extendedColors` has no `textSecondary` field). 11sp Inter SemiBold, 16dp radius, 6px gaps (spec §3, mock C). No selection *logic* here (Plan 2 supplies the chip list + handles filtering).

- [ ] **Step 1: Implement** — a `Row` inside `horizontalScroll`, each chip a rounded `Surface`/`Box` with `clickable`, styled per above, using `MaterialTheme.colorScheme` + `StashTheme.extendedColors`. Follow the styling of existing `SearchFilterBar.kt` for consistency (read it first).
- [ ] **Step 2: Compile** — `./gradlew :core:ui:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Commit**
```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/CrispChipRow.kt
git commit -m "feat(ui): CrispChipRow filter-chip component"
```

---

## Task 4: `RankedAlbumRow` (numbered chart list)

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/RankedAlbumRow.kt`

The Crisp signature list (spec §5 "Top albums"). Define a small UI model in this file and a row + list composable. Data-agnostic.
```kotlin
data class RankedAlbumUi(
    val rank: Int,
    val title: String,
    val artist: String,
    val artUrl: String?,
    val movement: Int?, // >0 up, 0 or null flat/none
)
```
Row layout: rank numeral (Space Grotesk Bold, `titleMedium`, `extendedColors.textTertiary`, 16dp wide) · 42dp art (Coil `AsyncImage`, 6dp radius) · title (`bodyMedium`, `onSurface`) + artist (`bodySmall`, `MaterialTheme.colorScheme.onSurfaceVariant`) · trailing movement (`▲ n` in `extendedColors.cyan` when `movement>0`, `—` in `extendedColors.textTertiary` otherwise). A `RankedAlbumList(items, onClick)` maps rows in a `Column`.

- [ ] **Step 1: Implement** (Coil `AsyncImage` via `coil3.compose` per existing usage in `AboutSection.kt`/`AlbumSquareCard.kt`; use `ArtUrlUpgrader` on the art URL as those do).
- [ ] **Step 2: Compile** → SUCCESS.
- [ ] **Step 3: Commit**
```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/RankedAlbumRow.kt
git commit -m "feat(ui): RankedAlbumRow chart-list component"
```

---

## Task 5: `DiscoverHeroCard`

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/DiscoverHeroCard.kt`

The single bold Home moment (spec §4/§5). Data-agnostic params: `label: String` ("Daily discovery"), `title: String`, `subtitle: String`, `artUrl: String?`, `onPlay: () -> Unit`, optional `loading: Boolean`. Layout (mock): 150dp full-width rounded (16dp) card; art-derived gradient background (for now, a `Brush.linearGradient` of `StashPurpleDark → StashBackground`, or sample art color later); a light radial `sheen`; bottom-left label (9sp cyan uppercase) + title (`headlineSmall` Space Grotesk, white) + subtitle; a round 38dp white play FAB bottom-right calling `onPlay`; `StashElevation.Hero` shadow. When `loading`, render a shimmer block (reuse `ShimmerPlaceholder`).

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Compile** → SUCCESS.
- [ ] **Step 3: Commit**
```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/DiscoverHeroCard.kt
git commit -m "feat(ui): DiscoverHeroCard component"
```

---

## Task 6: `AlbumSquareCard` — FLAC badge + press-scale

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/AlbumSquareCard.kt`

Read the current file first (reuse — do not rewrite). Add two optional params, defaulted so existing callers are unaffected: `isLossless: Boolean = false` and wire an `interactionSource` + `Modifier.pressScale(...)` on the card root. **FlacBadge bridge:** `FlacBadge` gates on `fileFormat: String?` (via `isLossless(fileFormat)`), NOT a Boolean — so overlay it as `if (isLossless) FlacBadge(fileFormat = "flac")` (or `FlacBadge(fileFormat = if (isLossless) "flac" else null)`, which self-hides on null). Do not pass a Boolean into its String param. Confirm no existing caller breaks (defaults).

- [ ] **Step 1: Implement (additive).**
- [ ] **Step 2: Compile the module that uses it** — `./gradlew :core:ui:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Commit**
```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/AlbumSquareCard.kt
git commit -m "feat(ui): AlbumSquareCard FLAC badge + press-scale (additive)"
```

---

## Task 7: Frosted bottom nav (translucent only — additive, non-breaking)

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt` (`StashBottomBar` ~line 182; `NavigationBar` `containerColor = surface` at ~line 189)

Spec §3.3: the nav is the one depth cue. **Scope note (from Plan-1 review):** the *edge-to-edge* change (dropping the Scaffold bottom inset so content scrolls behind the nav) is **deliberately NOT in this plan** — today `StashNavHost` gets `.padding(innerPadding)` (line 174) which is the ONLY thing keeping content above the nav on **all five** tabs. Dropping it globally here would clip the last item on Library/Search/Sync/Settings (and the not-yet-rewritten Home), with no compensating `contentPadding` until Plan 2. So Plan 1 does the **purely additive translucent frosting** only, keeping Task-1–7 genuinely non-breaking (consistent with Task 8's "nothing changes on existing screens yet"). The edge-to-edge + true blur move to **Plan 2, scoped to Home** (Home consumes the bottom inset via its own `LazyColumn` `contentPadding`; the other four tabs keep the Scaffold `innerPadding` untouched). Spec §3.3 N3 sanctions exactly this: "downgrade the claim to translucent-surface-only" here.

**This task (additive):** in `StashBottomBar`, change the `NavigationBar` `containerColor` from opaque `MaterialTheme.colorScheme.surface` to a translucent surface (`MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)`) and add a subtle top hairline border + `StashElevation.Chrome` shadow on the bar. Because content is still inset above the (now translucent) nav, nothing scrolls behind it yet — so this reads as a subtle surface refinement, not a broken overlap. No inset changes.

- [ ] **Step 1: Implement the translucent frosting (no inset changes).**
- [ ] **Step 2: Compile app** — `./gradlew :app:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Device smoke (human/agent via adb):** install, open each tab — nav reads as a subtly translucent frosted surface, and **every tab still lays out exactly as before** (no clipped content, no gaps — this task changes only the nav's fill/border/shadow).
- [ ] **Step 4: Commit**
```bash
git add app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt
git commit -m "feat(ui): translucent frosted bottom nav (additive)"
```

---

## Task 8: Foundation build + smoke

- [ ] **Step 1: Assemble** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (all new components compile into the app; existing callers of `AlbumSquareCard` unaffected).
- [ ] **Step 2: Unit** — `./gradlew :core:ui:testDebugUnitTest --tests "*MotionTokensTest*"` → PASS.
- [ ] **Step 3: Device smoke (human):** frosted nav correct on every tab; no visual regressions to existing screens (this plan only *adds* components + reskins the nav — nothing should change on Home/Library/etc. yet). New components aren't wired into any screen until Plan 2, so their visual QA happens there.

---

## Out of scope (Plan 2 / later)

- Chip *derivation* (populated-section logic), cold-start, the 3 data sections, `HomeUiState`/`HomeViewModel` reshape, `HomeScreen` rewrite, nav callbacks + `SeeAllRoute`, the Library port — **Plan 2**.
- **Edge-to-edge under the nav + true backdrop blur — Plan 2, scoped to Home** (per Plan-1 review): Home's `LazyColumn` consumes the bottom inset via `contentPadding` so content scrolls behind the translucent nav; the other four tabs keep the Scaffold `innerPadding` untouched. Only then does `Modifier.blur`/`RenderEffect` behind the bar earn its cost. This is intentionally NOT in Plan 1 because a global inset drop would clip the four unrewritten tabs.
- True `SharedTransitionLayout` mini-player ⇄ Now Playing + mini-player frosted chrome — **sub-project 2**.
