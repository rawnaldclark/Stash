package com.stash.data.download.files

/**
 * Filesystem-safe slug helpers lifted out of [FileOrganizer].
 *
 * v0.9.36: the lyrics-sidecar writer (`:data:lyrics`) needs to derive
 * the same `<artist>/<album>/<title>` directory layout as the download
 * pipeline when writing a `.lrc` next to a SAF-tree audio file. Rather
 * than duplicate the slug semantics (which the original `slugify`
 * encoded as a `private` member of [FileOrganizer]), lift the helper
 * into an object that cross-module callers in `:data` can share — one
 * definition, no drift.
 *
 * Visibility is `public` (the default) rather than `internal` because
 * Kotlin's `internal` is scoped to the containing Gradle module, and
 * `:data:lyrics` is a sibling module that needs to call this. There
 * is no public API surface exposure cost: the only callers are other
 * `:data:*` modules wiring downloads + sidecars, and the object is
 * intentionally narrow — one pure helper, no state.
 *
 * Download, migration, and sidecar paths share this definition so filename
 * behavior cannot drift between storage destinations.
 */
object FileOrganizerSlugs {

    /**
     * Converts a human-readable string into a filesystem-safe slug.
     *
     * Lowercases, preserves Unicode letters and numbers, strips other characters
     * (except spaces and hyphens), collapses whitespace into single hyphens, and
     * truncates to 60 Unicode code points.
     */
    fun slugify(input: String): String =
        input.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .takeCodePoints(60)

    private fun String.takeCodePoints(count: Int): String =
        substring(0, offsetByCodePoints(0, minOf(count, codePointCount(0, length))))
}
