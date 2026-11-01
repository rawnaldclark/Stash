package com.stash.feature.nowplaying.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Now Playing's text block, as numbers: the production values. Every one is read through
 * [LocalNpSpacing], so a debug build can tune them live from a browser (tools/np-tuner); a release
 * build only ever sees these.
 *
 * The tuner's "Copy values" prints this whole object with the tuned numbers: paste it over this
 * one (the comments inside are the tuner's groups, so nothing is lost). NpSpacingTest pins the
 * values, and NpTunerFieldsTest checks tools/np-tuner/fields.json against them.
 *
 * - Title and artist set their own line height and tracking. Left unset, both inherited
 *   bodyLarge's 24 sp line and +0.5 sp tracking: loose for a 22 sp title, and about 4.5 sp of
 *   invisible leading above and below the 15 sp artist line, which made the title-to-artist and
 *   artist-to-quality steps uneven. 28 / 0 is Material's own pair for a 22 sp title; 20 / 0 keeps
 *   the stack's height as it was (28 + 20 = 24 + 24).
 * - Around the art: the three flexible gaps (top bar to art, art to title, controls to the
 *   bottom), at their full size; they shrink to their floors on a short screen.
 * - Stack: the fixed steps from the title down to the Up next line.
 * - Up next: the line's height (taken whether or not it shows) and its side padding.
 * - Sides: the page's padding, a centred title's inset (room for the side button and the heart),
 *   and the gap before each of those 26 dp buttons beside a start-aligned title.
 * - Layout: [NpAlign.SETTING] has the title, artist, quality and Up next lines follow Settings ›
 *   Appearance together; [FullArtTextPosition.LAYOUT] keeps the text where the square-art layout
 *   puts it while full art is out.
 */
internal object NpSpacingDefaults {
    // Title
    const val TITLE_SIZE_SP = 22f
    const val TITLE_LINE_HEIGHT_SP = 28f
    const val TITLE_LETTER_SPACING_SP = 0f

    // Artist · album
    const val ARTIST_SIZE_SP = 15f
    const val ARTIST_LINE_HEIGHT_SP = 20f
    const val ARTIST_LETTER_SPACING_SP = 0f

    // Around the art
    const val GAP_TOP_DP = 20f
    const val GAP_ART_DP = 36f
    const val GAP_BOTTOM_DP = 28f

    // Stack
    const val TITLE_TO_ARTIST_DP = 4f
    const val ARTIST_TO_QUALITY_DP = 6f
    const val INFO_TO_PROGRESS_DP = 16f
    const val PROGRESS_TO_CONTROLS_DP = 12f
    const val CONTROLS_TO_UP_NEXT_DP = 2f

    // Up next
    const val UP_NEXT_HEIGHT_DP = 40f
    const val UP_NEXT_SIDE_PADDING_DP = 12f

    // Sides
    const val SIDE_PADDING_DP = 24f
    const val CENTERED_TITLE_INSET_DP = 40f
    const val SIDE_BUTTON_GAP_DP = 12f

    // Layout
    val ALIGN = NpAlign.SETTING
    val FULL_ART_TEXT = FullArtTextPosition.LAYOUT
}

/** How the text block's lines align. [SETTING] follows Settings › Appearance (title alignment). */
internal enum class NpAlign(val key: String) {
    SETTING("setting"),
    START("start"),
    CENTER("center"),
    ;

    /** True for start-aligned lines, given whether the Settings choice is start. */
    fun alignStart(settingStart: Boolean): Boolean = when (this) {
        SETTING -> settingStart
        START -> true
        CENTER -> false
    }

    companion object {
        fun fromKey(key: String): NpAlign? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

/**
 * Where the text block sits while full art is out. [LAYOUT]: where the square-art layout puts it
 * (today). [UNDER_ART]: right under full art's box, one art gap down. [BOTTOM]: at the bottom,
 * all the spare height above it.
 */
internal enum class FullArtTextPosition(val key: String) {
    LAYOUT("layout"),
    UNDER_ART("under_art"),
    BOTTOM("bottom"),
    ;

    companion object {
        fun fromKey(key: String): FullArtTextPosition? =
            entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

/** Now Playing's text-block spacing; sp and dp as plain numbers. Defaults: [NpSpacingDefaults]. */
@Immutable
internal data class NpSpacing(
    val titleSize: Float = NpSpacingDefaults.TITLE_SIZE_SP,
    val titleLineHeight: Float = NpSpacingDefaults.TITLE_LINE_HEIGHT_SP,
    val titleLetterSpacing: Float = NpSpacingDefaults.TITLE_LETTER_SPACING_SP,
    val artistSize: Float = NpSpacingDefaults.ARTIST_SIZE_SP,
    val artistLineHeight: Float = NpSpacingDefaults.ARTIST_LINE_HEIGHT_SP,
    val artistLetterSpacing: Float = NpSpacingDefaults.ARTIST_LETTER_SPACING_SP,
    val gapTop: Float = NpSpacingDefaults.GAP_TOP_DP,
    val gapArt: Float = NpSpacingDefaults.GAP_ART_DP,
    val gapBottom: Float = NpSpacingDefaults.GAP_BOTTOM_DP,
    val titleToArtist: Float = NpSpacingDefaults.TITLE_TO_ARTIST_DP,
    val artistToQuality: Float = NpSpacingDefaults.ARTIST_TO_QUALITY_DP,
    val infoToProgress: Float = NpSpacingDefaults.INFO_TO_PROGRESS_DP,
    val progressToControls: Float = NpSpacingDefaults.PROGRESS_TO_CONTROLS_DP,
    val controlsToUpNext: Float = NpSpacingDefaults.CONTROLS_TO_UP_NEXT_DP,
    val upNextHeight: Float = NpSpacingDefaults.UP_NEXT_HEIGHT_DP,
    val upNextSidePadding: Float = NpSpacingDefaults.UP_NEXT_SIDE_PADDING_DP,
    val sidePadding: Float = NpSpacingDefaults.SIDE_PADDING_DP,
    val centeredTitleInset: Float = NpSpacingDefaults.CENTERED_TITLE_INSET_DP,
    val sideButtonGap: Float = NpSpacingDefaults.SIDE_BUTTON_GAP_DP,
    val align: NpAlign = NpSpacingDefaults.ALIGN,
    val fullArtText: FullArtTextPosition = NpSpacingDefaults.FULL_ART_TEXT,
)

/** The spacing Now Playing lays out with. Provided once, by the player sheet (production values elsewhere). */
internal val LocalNpSpacing = staticCompositionLocalOf { NpSpacing() }

/**
 * One tunable number: its JSON key (the tuner's slider), the [NpSpacingDefaults] constant it
 * prints as, its unit, the range a value is clamped to, and where it lives in [NpSpacing].
 */
internal class NpSpacingField(
    val key: String,
    val constName: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val get: (NpSpacing) -> Float,
    val set: (NpSpacing, Float) -> NpSpacing,
)

/**
 * The tuner's protocol: JSON in (from adb), [NpSpacing] out. Every number is clamped to its
 * field's range; keys it doesn't know, values of the wrong type and malformed JSON are ignored,
 * so whatever arrives, Now Playing still lays out.
 */
internal object NpSpacingJson {
    const val ALIGN_KEY = "align"
    const val FULL_ART_TEXT_KEY = "fullArtText"

    val fields: List<NpSpacingField> = listOf(
        NpSpacingField("titleSize", "TITLE_SIZE_SP", "sp", 14f, 36f, { it.titleSize }, { s, v -> s.copy(titleSize = v) }),
        NpSpacingField("titleLineHeight", "TITLE_LINE_HEIGHT_SP", "sp", 14f, 48f, { it.titleLineHeight }, { s, v -> s.copy(titleLineHeight = v) }),
        NpSpacingField("titleLetterSpacing", "TITLE_LETTER_SPACING_SP", "sp", -1f, 2f, { it.titleLetterSpacing }, { s, v -> s.copy(titleLetterSpacing = v) }),
        NpSpacingField("artistSize", "ARTIST_SIZE_SP", "sp", 10f, 24f, { it.artistSize }, { s, v -> s.copy(artistSize = v) }),
        NpSpacingField("artistLineHeight", "ARTIST_LINE_HEIGHT_SP", "sp", 10f, 36f, { it.artistLineHeight }, { s, v -> s.copy(artistLineHeight = v) }),
        NpSpacingField("artistLetterSpacing", "ARTIST_LETTER_SPACING_SP", "sp", -1f, 2f, { it.artistLetterSpacing }, { s, v -> s.copy(artistLetterSpacing = v) }),
        NpSpacingField("gapTop", "GAP_TOP_DP", "dp", 0f, 80f, { it.gapTop }, { s, v -> s.copy(gapTop = v) }),
        NpSpacingField("gapArt", "GAP_ART_DP", "dp", 0f, 96f, { it.gapArt }, { s, v -> s.copy(gapArt = v) }),
        NpSpacingField("gapBottom", "GAP_BOTTOM_DP", "dp", 0f, 96f, { it.gapBottom }, { s, v -> s.copy(gapBottom = v) }),
        NpSpacingField("titleToArtist", "TITLE_TO_ARTIST_DP", "dp", 0f, 32f, { it.titleToArtist }, { s, v -> s.copy(titleToArtist = v) }),
        NpSpacingField("artistToQuality", "ARTIST_TO_QUALITY_DP", "dp", 0f, 32f, { it.artistToQuality }, { s, v -> s.copy(artistToQuality = v) }),
        NpSpacingField("infoToProgress", "INFO_TO_PROGRESS_DP", "dp", 0f, 48f, { it.infoToProgress }, { s, v -> s.copy(infoToProgress = v) }),
        NpSpacingField("progressToControls", "PROGRESS_TO_CONTROLS_DP", "dp", 0f, 48f, { it.progressToControls }, { s, v -> s.copy(progressToControls = v) }),
        NpSpacingField("controlsToUpNext", "CONTROLS_TO_UP_NEXT_DP", "dp", 0f, 32f, { it.controlsToUpNext }, { s, v -> s.copy(controlsToUpNext = v) }),
        NpSpacingField("upNextHeight", "UP_NEXT_HEIGHT_DP", "dp", 24f, 64f, { it.upNextHeight }, { s, v -> s.copy(upNextHeight = v) }),
        NpSpacingField("upNextSidePadding", "UP_NEXT_SIDE_PADDING_DP", "dp", 0f, 32f, { it.upNextSidePadding }, { s, v -> s.copy(upNextSidePadding = v) }),
        NpSpacingField("sidePadding", "SIDE_PADDING_DP", "dp", 0f, 48f, { it.sidePadding }, { s, v -> s.copy(sidePadding = v) }),
        NpSpacingField("centeredTitleInset", "CENTERED_TITLE_INSET_DP", "dp", 0f, 80f, { it.centeredTitleInset }, { s, v -> s.copy(centeredTitleInset = v) }),
        NpSpacingField("sideButtonGap", "SIDE_BUTTON_GAP_DP", "dp", 0f, 32f, { it.sideButtonGap }, { s, v -> s.copy(sideButtonGap = v) }),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [text] applied over [base]: each known key takes its (clamped) value, everything else
     * keeps [base]'s. Null, blank or malformed [text] gives [base] unchanged.
     */
    fun parse(text: String?, base: NpSpacing = NpSpacing()): NpSpacing {
        if (text.isNullOrBlank()) return base
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null // malformed: the tuner sent something broken, keep what's laid out
        } ?: return base
        var out = base
        for (field in fields) {
            val value = (obj[field.key] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.floatOrNull
                ?.takeIf { it.isFinite() }
                ?: continue
            out = field.set(out, value.coerceIn(field.min, field.max))
        }
        (obj[ALIGN_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(NpAlign::fromKey)?.let { out = out.copy(align = it) }
        (obj[FULL_ART_TEXT_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(FullArtTextPosition::fromKey)?.let { out = out.copy(fullArtText = it) }
        return out
    }

    /** [spacing] as the tuner's JSON: every key, so storing it and parsing it back is lossless. */
    fun write(spacing: NpSpacing): String = buildJsonObject {
        fields.forEach { put(it.key, it.get(spacing)) }
        put(ALIGN_KEY, spacing.align.key)
        put(FULL_ART_TEXT_KEY, spacing.fullArtText.key)
    }.toString()
}

/** Where Now Playing's spacing comes from: the production values, or a debug build's live overrides. */
interface NpSpacingSource {
    /** The current overrides as the tuner's JSON; null for none (the production values). */
    val overridesJson: Flow<String?>

    /** Release builds: the production values, and nothing is ever read. */
    object Production : NpSpacingSource {
        override val overridesJson: Flow<String?> = flowOf(null)
    }

    companion object {
        /**
         * The BuildConfig gate (the app binds `select(BuildConfig.DEBUG) { overrides }`): a debug
         * build reads the adb-set overrides; a release build gets [Production] and never even
         * creates the overrides' store.
         */
        fun select(debug: Boolean, overrides: () -> NpSpacingSource): NpSpacingSource =
            if (debug) overrides() else Production
    }
}

/**
 * Where the text block's top goes, in the body's coordinates. [LAYOUT][FullArtTextPosition.LAYOUT]
 * is [layoutTop]; [BOTTOM][FullArtTextPosition.BOTTOM] is [bottomTop] (the lowest it can sit);
 * [UNDER_ART][FullArtTextPosition.UNDER_ART] is [underArtTop] kept between the top and [bottomTop]
 * (the layout's own place while it isn't known yet). The block moves with full art's [grow] (0 in
 * the square, 1 out), so it glides as the art grows rather than jumping.
 */
internal fun npTextTop(
    position: FullArtTextPosition,
    layoutTop: Int,
    bottomTop: Int,
    underArtTop: Int?,
    grow: Float,
): Int {
    val target = when (position) {
        FullArtTextPosition.LAYOUT -> return layoutTop
        FullArtTextPosition.BOTTOM -> bottomTop
        FullArtTextPosition.UNDER_ART -> (underArtTop ?: layoutTop).coerceIn(0, bottomTop.coerceAtLeast(0))
    }
    return (layoutTop + (target - layoutTop) * grow.coerceIn(0f, 1f)).roundToInt()
}

/**
 * Full art's box as the text block sees it: [root] is the layout full art fills (Now Playing's
 * root), so the box's bottom is [fullArtBox]'s; [grow] is how far out the art is.
 */
internal class FullArtTextAnchor(
    private val root: () -> LayoutCoordinates?,
    private val zoom: () -> Float,
    val grow: () -> Float,
) {
    /** Full art's bottom edge in [body]'s coordinates, in px; null until both are placed. */
    fun artBottomIn(body: LayoutCoordinates?): Int? {
        val r = root() ?: return null
        if (body == null || !r.isAttached || !body.isAttached) return null
        val (box, _) = fullArtBox(r.size.width.toFloat(), r.size.height.toFloat(), zoom())
        val bodyTop = r.localPositionOf(body, Offset.Zero).y
        return (box.bottom - bodyTop).roundToInt()
    }
}
