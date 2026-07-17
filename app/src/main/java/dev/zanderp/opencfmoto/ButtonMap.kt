// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Handlebar gesture → action mapping, ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Something a handlebar gesture can be made to do. Everything here is reachable with a live Android
 * Auto session and no touchscreen.
 */
enum class ButtonAction(val id: String, val label: String) {
    NONE("none", "Do nothing"),
    KNOB_FORWARD("knobFwd", "Knob forward (next item)"),
    KNOB_BACK("knobBack", "Knob back (previous item)"),
    SELECT("select", "Select / OK"),
    BACK("back", "Back"),
    HOME("home", "Home (app list)"),
    ASSISTANT("assistant", "Assistant (voice)"),
    DPAD_UP("up", "D-pad up"),
    DPAD_DOWN("down", "D-pad down"),
    DPAD_LEFT("left", "D-pad left"),
    DPAD_RIGHT("right", "D-pad right"),
    NAV_1("nav1", "Navigate to saved place 1"),
    NAV_2("nav2", "Navigate to saved place 2"),
    NAV_3("nav3", "Navigate to saved place 3");

    /** Nav actions read better as the place's own name once one is saved. */
    fun displayLabel(context: Context): String = when (this) {
        NAV_1 -> SavedPlaces.actionLabel(context, 0)
        NAV_2 -> SavedPlaces.actionLabel(context, 1)
        NAV_3 -> SavedPlaces.actionLabel(context, 2)
        else -> label
    }

    companion object {
        fun byId(id: String?): ButtonAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The **semantic** gestures a CFMoto handlebar can produce, regardless of which physical buttons a
 * given bike uses. Different dashes send navigation on different buttons, but they all collapse to a
 * "previous / next / select" trio, so we map by meaning and let [MediaButtonBridge] route whatever
 * the bike sends into the matching gesture — no per-bike layout config needed:
 *   • **Backward / Forward** — the CFDL16 dashes (450SR etc.) send these as ▲/▼ *volume* writes;
 *     the 800MT's 5-way sends them as ◀/▶ *previous-/next-track*. Either way ◀/▲ = backward and
 *     ▶/▼ = forward.
 *   • **Backward/Forward ×2** — a double-tap. On the CFDL16 volume dashes it is read from one
 *     coalesced volume write with a bigger jump, or two quick writes inside a short window; on the
 *     800MT's discrete track keys it is a second key event inside that same window (see
 *     [MediaButtonBridge]). Either way, two quick presses = the ×2 gesture.
 *   • **Select / Select ×2 / Select (hold)** — the OK / ★ (start) button, which every dash sends as
 *     an AVRCP play/pause. A quick tap (after the double-tap window) is [SELECT_PRESS]; a second
 *     press within the window is [SELECT_DOUBLE]; holding past the long-press threshold is
 *     [SELECT_LONG]. Hold timing needs a distinct key-up, so it only works on dashes that send a
 *     release (the ▲/▼ volume path has no release event).
 *
 * [label] is the semantic name (with the physical buttons that trigger it); [hint] explains it.
 */
enum class ButtonGesture(
    val id: String,
    val label: String,
    val hint: String,
    val default: ButtonAction,
) {
    NAV_BACK("navBack", "Backward  ◀ / ▲", "◀ left, or the ▲ volume press on non-touch dashes", ButtonAction.KNOB_BACK),
    NAV_FWD("navFwd", "Forward  ▶ / ▼", "▶ right, or the ▼ volume press on non-touch dashes", ButtonAction.KNOB_FORWARD),
    SELECT_PRESS("selectPress", "Select  Enter / ★", "a quick tap of the OK / ★ start button", ButtonAction.SELECT),
    SELECT_LONG("selectLong", "Select (hold)  Enter / ★", "press and hold the OK / ★ button", ButtonAction.ASSISTANT),
    NAV_BACK_DOUBLE("navBackDouble", "Backward ×2", "double-tap backward within a short window", ButtonAction.HOME),
    NAV_FWD_DOUBLE("navFwdDouble", "Forward ×2", "double-tap forward within a short window", ButtonAction.BACK),
    SELECT_DOUBLE("selectDouble", "Select ×2", "double-tap the OK / ★ button", ButtonAction.ASSISTANT),
}

/**
 * What each handlebar gesture does. Unset gestures fall back to [ButtonGesture.default], so
 * "reset to defaults" is just clearing the store.
 */
object ButtonMap {
    private const val PREF = "button_map"

    fun get(context: Context, gesture: ButtonGesture): ButtonAction =
        ButtonAction.byId(BikeScope.getString(prefs(context), context, gesture.id, null)) ?: gesture.default

    fun set(context: Context, gesture: ButtonGesture, action: ButtonAction) {
        BikeScope.putString(prefs(context), context, gesture.id, action.id)
    }

    /** Reset the **selected bike's** mapping to defaults (other bikes keep theirs). */
    fun resetAll(context: Context) {
        val p = prefs(context)
        for (g in ButtonGesture.entries) BikeScope.remove(p, context, g.id)
    }

    /** True when every gesture is still on its default — used to enable/disable the reset button. */
    fun isAllDefault(context: Context): Boolean =
        ButtonGesture.entries.all { get(context, it) == it.default }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
