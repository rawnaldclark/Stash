package com.stash.core.model

enum class QualityTier(
    val label: String,
    val bitrateKbps: Int,
    val sizeMbPerMinute: Float,
) {
    BEST(label = "Best", bitrateKbps = 256, sizeMbPerMinute = 1.9f),
    HIGH(label = "High", bitrateKbps = 160, sizeMbPerMinute = 1.2f),
    NORMAL(label = "Normal", bitrateKbps = 96, sizeMbPerMinute = 0.72f),
    LOW(label = "Low", bitrateKbps = 64, sizeMbPerMinute = 0.48f),
}
