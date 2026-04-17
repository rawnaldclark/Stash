package com.stash.core.common.perf

import android.util.Log

/**
 * Wrapper around [Log.d] that only emits when the "Perf" tag is enabled
 * via `adb shell setprop log.tag.Perf DEBUG`. Use this for latency
 * instrumentation so release builds don't pay the formatting cost.
 *
 * Using [Log.isLoggable] avoids per-module `BuildConfig.DEBUG` wiring;
 * R8's log-stripping rules still drop these calls in release builds.
 *
 * Prefer the lazy-lambda overload ([d]) at call sites that interpolate
 * values — the string is only built when the tag is enabled.
 */
object PerfLog {
    // @PublishedApi so the inline overload can reference TAG without
    // forcing the constant to be public API.
    @PublishedApi
    internal const val TAG = "Perf"

    fun d(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    inline fun d(lazyMessage: () -> String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, lazyMessage())
    }
}
