package dev.coletz.opencfmoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One remembered bike: the exact scanned QR plus a friendly name for the UI. */
data class SavedBike(val raw: String, val name: String) {
    val qr: QrData? get() = QrData.parse(raw)
}

/**
 * Remembers the bikes the user has connected to so a returning rider can reconnect with one tap
 * instead of re-scanning the dash QR every time — and can keep more than one bike paired (e.g. two
 * motorcycles) and pick between them.
 *
 * We persist the **raw QR string** per bike (not the parsed fields): [QrData.parse] reconstructs the
 * exact same [QrData], and [BikeProfiles.selectByQr] picks the same profile, so a saved reconnect is
 * byte-identical to a fresh scan. The list is stored as JSON; a legacy single-bike entry (older app
 * versions) is migrated into the list on first read.
 */
object BikeMemory {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_LIST = "bikes_json"
    private const val KEY_SELECTED = "selected_raw"

    // Legacy single-bike keys (pre-multi-device); migrated then left in place harmlessly.
    private const val KEY_RAW = "last_qr_raw"
    private const val KEY_NAME = "last_bike_name"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All remembered bikes, most-recently-saved first. */
    fun devices(ctx: Context): List<SavedBike> {
        migrateIfNeeded(ctx)
        val arr = runCatching { JSONArray(prefs(ctx).getString(KEY_LIST, "[]")) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("raw"); val name = o.optString("name")
                if (raw.isNotBlank()) add(SavedBike(raw, name.ifBlank { raw }))
            }
        }
    }

    /** The bike the one-tap Connect will use: the explicitly selected one, else the most recent. */
    fun selected(ctx: Context): SavedBike? {
        val list = devices(ctx)
        if (list.isEmpty()) return null
        val sel = prefs(ctx).getString(KEY_SELECTED, null)
        return list.firstOrNull { it.raw == sel } ?: list.first()
    }

    /** Persist (or move-to-front) the QR we just connected with, and select it. */
    fun save(ctx: Context, raw: String, qr: QrData) {
        val name = displayName(qr)
        val existing = devices(ctx).filter { it.raw != raw }
        val list = buildList {
            add(SavedBike(raw, name))
            addAll(existing)
        }
        writeList(ctx, list)
        prefs(ctx).edit().putString(KEY_SELECTED, raw).apply()
    }

    fun select(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, raw).apply()
    }

    fun remove(ctx: Context, raw: String) {
        writeList(ctx, devices(ctx).filter { it.raw != raw })
        if (prefs(ctx).getString(KEY_SELECTED, null) == raw) {
            prefs(ctx).edit().remove(KEY_SELECTED).apply()
        }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_LIST).remove(KEY_SELECTED)
            .remove(KEY_RAW).remove(KEY_NAME)
            .apply()
    }

    // ---- convenience accessors used across the app (selected bike) ----
    fun lastRaw(ctx: Context): String? = selected(ctx)?.raw
    fun lastQr(ctx: Context): QrData? = selected(ctx)?.qr
    fun lastBikeName(ctx: Context): String? = selected(ctx)?.name
    fun hasSaved(ctx: Context): Boolean = selected(ctx) != null

    private fun writeList(ctx: Context, list: List<SavedBike>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("raw", b.raw).put("name", b.name))
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Fold a pre-multi-device single saved bike into the list, once. */
    private fun migrateIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains(KEY_LIST)) return
        val raw = p.getString(KEY_RAW, null)
        val arr = JSONArray()
        if (!raw.isNullOrBlank()) {
            val name = p.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: raw
            arr.put(JSONObject().put("raw", raw).put("name", name))
            p.edit().putString(KEY_SELECTED, raw).apply()
        }
        p.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun displayName(qr: QrData): String =
        qr.name?.takeIf { it.isNotBlank() } ?: qr.ssid
}
