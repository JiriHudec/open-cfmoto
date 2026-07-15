package dev.coletz.opencfmoto

import android.content.Context

/**
 * Small on/off app preferences (kept apart from the video-tuning [VideoPrefs]).
 *
 *  - [autoConnect]  — on launch, if a bike is paired and its Wi-Fi is in range, start connecting
 *    automatically instead of waiting for a tap. Default on; the rider can turn it off.
 *  - [autoRecovery] — the watchdog ([AndroidAutoService]) restarts the bike link if projection
 *    stalls or the dash drops, without a manual Stop/Start. Default on.
 */
object AppSettings {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_AUTO_RECOVERY = "auto_recovery"
    private const val KEY_LOG_TRIPS = "log_trips"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoConnect(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_CONNECT, true)
    fun setAutoConnect(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_CONNECT, on).apply()

    fun autoRecovery(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_RECOVERY, true)
    fun setAutoRecovery(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_RECOVERY, on).apply()

    fun logTrips(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOG_TRIPS, true)
    fun setLogTrips(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_LOG_TRIPS, on).apply()
}
