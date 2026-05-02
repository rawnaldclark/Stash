package com.stash.core.model

enum class QualityTier(
    val label: String,
    val bitrateKbps: Int,
    val sizeMbPerMinute: Float,
) {
    /**
     * Experimental tier that prefers YouTube format 141 (AAC LC 256 kbps,
     * gated behind YouTube Music auth) and falls back to format 251 (Opus
     * 160) and then 140 (AAC 128). Also queries the music-specific
     * InnerTube clients which historically expose 141 to free authenticated
     * accounts more reliably than the default `web` client. Yield depends
     * on Google's current gating — Library Health surfaces the actual
     * format breakdown so you can see what you're getting.
     */
    MAX(label = "Max", bitrateKbps = 256, sizeMbPerMinute = 1.92f),
    BEST(label = "Best", bitrateKbps = 256, sizeMbPerMinute = 1.9f),
    HIGH(label = "High", bitrateKbps = 160, sizeMbPerMinute = 1.2f),
    NORMAL(label = "Normal", bitrateKbps = 96, sizeMbPerMinute = 0.72f),
    LOW(label = "Low", bitrateKbps = 64, sizeMbPerMinute = 0.48f),
}
