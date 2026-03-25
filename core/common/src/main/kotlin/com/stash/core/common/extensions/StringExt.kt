package com.stash.core.common.extensions

import java.text.Normalizer
import java.util.Locale

fun String.toCanonical(): String {
    var result = this.lowercase(Locale.ENGLISH)
    result = Normalizer.normalize(result, Normalizer.Form.NFD)
        .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
    result = result.replace(Regex("\\([^)]*\\)"), "")
    result = result.replace(Regex("\\[[^]]*]"), "")
    result = result.replace(Regex("\\b(feat\\.?|ft\\.?|featuring)\\b"), "")
    result = result.replace(Regex("\\s+"), " ").trim()
    return result
}

fun String.toSlug(): String {
    var result = this.toCanonical()
    result = result.replace(Regex("[^a-z0-9\\s-]"), "")
    result = result.replace(Regex("\\s+"), "-")
    result = result.trim('-')
    return result.take(80)
}
