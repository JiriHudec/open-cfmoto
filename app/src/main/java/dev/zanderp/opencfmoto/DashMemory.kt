package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Remembers the canvas size each dash asks for (its `REQ_CONFIG_CAPTURE` width×height), keyed by
 * bike SSID, so the app can auto-pick the right Android Auto orientation for bikes we don't have a
 * built-in profile for.
 *
 * The dash only reveals its shape *during* a session — after Android Auto's resolution is already
 * fixed — so the first connection to an unknown portrait dash may be letterboxed. We record the
 * observed shape here; on the next connect [specFor] uses it to flip the AA orientation to match.
 */
object DashMemory {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_PREFIX = "dash_geo_"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Record the dash's requested canvas for [ssid] (ignored if degenerate). */
    fun observe(ctx: Context, ssid: String?, w: Int, h: Int) {
        if (ssid.isNullOrBlank() || w <= 0 || h <= 0) return
        prefs(ctx).edit().putString(KEY_PREFIX + ssid, "${w}x${h}").apply()
    }

    /** The last-seen canvas (w,h) for [ssid], or null if we've never connected to it. */
    fun get(ctx: Context, ssid: String?): Pair<Int, Int>? {
        if (ssid.isNullOrBlank()) return null
        val v = prefs(ctx).getString(KEY_PREFIX + ssid, null) ?: return null
        val parts = v.split("x")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return w to h
    }

    /**
     * An Android Auto spec derived from the remembered dash shape for [ssid], or null when we have no
     * observation or the [profile] already targets the correct orientation (so its proven resolution
     * is kept). Only flips orientation — using the conservative proven SD sizes, since HD can
     * black-screen on some dashes; the rider can bump to HD manually in Setup.
     */
    fun specFor(ctx: Context, ssid: String?, profile: BikeProfile): AaVideoSpec? {
        val (w, h) = get(ctx, ssid) ?: return null
        val dashPortrait = h > w
        val profilePortrait = profile.aaVideo.height > profile.aaVideo.width
        if (dashPortrait == profilePortrait) return null  // profile orientation already matches
        return if (dashPortrait) AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)
        else AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)
    }
}
