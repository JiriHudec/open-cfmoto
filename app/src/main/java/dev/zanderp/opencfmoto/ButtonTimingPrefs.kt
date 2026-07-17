// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * How long to wait before committing a single handlebar press as a tap (vs waiting for a double-tap).
 * Longer = more forgiving doubles, but singles feel laggier. Applied live by [MediaButtonBridge].
 */
enum class DoubleTapDelay(val ms: Long, val label: String) {
    FAST(200L, "200 ms — snappier singles"),
    NORMAL(300L, "300 ms — balanced (recommended)"),
    SLOW(450L, "450 ms — forgiving doubles"),
}

/**
 * How long Enter / ★ must be held to count as Select (hold) instead of a tap. Applied live.
 */
enum class LongPressDelay(val ms: Long, val label: String) {
    SHORT(500L, "500 ms — quicker hold"),
    NORMAL(600L, "600 ms — balanced (recommended)"),
    LONG(800L, "800 ms — deliberate hold"),
}

/**
 * Handlebar gesture timing. Global (not per-bike) — it's about how the rider taps, not the dash.
 */
object ButtonTimingPrefs {
    private const val PREFS = "opencfmoto_btn_timing"
    private const val KEY_DOUBLE = "double_tap_ms"
    private const val KEY_LONG = "long_press_ms"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun doubleTap(ctx: Context): DoubleTapDelay {
        val ms = prefs(ctx).getLong(KEY_DOUBLE, DoubleTapDelay.NORMAL.ms)
        return DoubleTapDelay.entries.firstOrNull { it.ms == ms } ?: DoubleTapDelay.NORMAL
    }

    fun setDoubleTap(ctx: Context, delay: DoubleTapDelay) {
        prefs(ctx).edit().putLong(KEY_DOUBLE, delay.ms).apply()
    }

    fun longPress(ctx: Context): LongPressDelay {
        val ms = prefs(ctx).getLong(KEY_LONG, LongPressDelay.NORMAL.ms)
        return LongPressDelay.entries.firstOrNull { it.ms == ms } ?: LongPressDelay.NORMAL
    }

    fun setLongPress(ctx: Context, delay: LongPressDelay) {
        prefs(ctx).edit().putLong(KEY_LONG, delay.ms).apply()
    }

    fun doubleTapMs(ctx: Context): Long = doubleTap(ctx).ms
    fun longPressMs(ctx: Context): Long = longPress(ctx).ms
}
