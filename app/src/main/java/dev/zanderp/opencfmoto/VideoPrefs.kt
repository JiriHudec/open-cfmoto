package dev.zanderp.opencfmoto

import android.content.Context

/**
 * User-selectable video quality. Applied as a multiplier on top of each [BikeProfile]'s tuned
 * bitrate (see [BikeProfile.videoBitrate]), so per-dash tuning and the user's preference compose:
 * a rider on a weak link can pick [SMOOTH] to cut data/latency, or [SHARP] for a crisper map.
 *
 * Only the bitrate is scaled — resolution, H.264 profile and frame rate are left to the bike
 * profile, since those are the values documented as fragile end-to-end (black-screen risk).
 */
enum class VideoQuality(val multiplier: Float, val label: String) {
    SMOOTH(0.7f, "Smoother — less data/latency"),
    BALANCED(1.0f, "Balanced (recommended)"),
    SHARP(1.6f, "Sharper — more data"),
}

/**
 * How the Android Auto video (a fixed 16:9-ish landscape resolution) is placed into the bike's
 * differently-shaped mirror canvas by [AaCompositor]. AA can't render at the dash's aspect ratio, so
 * the rider picks the trade-off:
 *  - [FILL]    zoom to cover the whole screen; the AA image's edges are cropped (no black bars).
 *  - [FIT]     letterbox: whole AA image visible, black bars fill the leftover space.
 *  - [STRETCH] scale to the exact canvas; fills completely but distorts the aspect ratio.
 */
enum class ScreenFit(val label: String) {
    FILL("Fill — edge to edge (crops a little)"),
    FIT("Fit — no cropping (black bars)"),
    STRETCH("Stretch — fills, slight distortion"),
}

/**
 * Caps how many frames per second the [AaCompositor] pushes into the H.264 encoder. Every capped
 * frame is one fewer GPU composite + hardware encode + Wi-Fi transmit, so lowering it directly cuts
 * heat and battery drain. Below the cap the map still looks fluid; the trade is a touch less
 * smoothness on fast pans. Independent of the idle keep-alive (which drops far lower still).
 *
 * [AUTO] hands frame rate AND bitrate to [AdaptiveVideoController]: it starts smooth and steps both
 * down as the phone heats up (thermal status) or the bike Wi-Fi link congests (dropped frames),
 * then recovers as conditions ease — so the dash stays connected and smooth-at-a-lower-rate instead
 * of stuttering or dropping. [fps] here is the *starting* cap the controller adapts from. The fixed
 * modes disable the controller entirely and pin the rate, exactly as before.
 */
enum class PowerMode(val fps: Int, val label: String) {
    AUTO(30, "Auto — adapts to heat & signal"),
    SMOOTH(30, "Smooth — 30 fps (most battery)"),
    BALANCED(24, "Balanced — 24 fps (recommended)"),
    SAVER(20, "Battery saver — 20 fps (coolest)"),
}

/**
 * Android Auto video resolution + orientation.
 *
 * [AUTO] uses the resolution/orientation the bike profile proved works — correct for recognized
 * CFMoto dashes (matched from the QR/CLIENT_INFO). But an unrecognized dash falls back to the legacy
 * landscape 800×480, which is wrong for a tall/portrait screen. The explicit options let the rider
 * force the shape + size for any bike (e.g. a portrait display we don't yet have a profile for). AA
 * only supports these fixed sizes; [MatchAspectMode] + AAP margins reflow AA into odd panel
 * aspects, otherwise the compositor letterboxes/crops per [ScreenFit]. HD sizes are crisper but
 * heavier and can black-screen on some embedded decoders — drop back to a smaller size or AUTO if
 * a bike rejects them.
 */
enum class ResolutionMode(val label: String, val spec: AaVideoSpec?) {
    AUTO("Auto — match your bike (recommended)", null),
    LANDSCAPE_SD("Landscape · 800×480", AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)),
    LANDSCAPE_HD("Landscape · 1280×720 (HD)", AaVideoSpec(AaResolution.LANDSCAPE_1280x720, dpi = 160)),
    PORTRAIT_SD("Portrait · 720×1280", AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)),
    PORTRAIT_HD("Portrait · 1080×1920 (HD)", AaVideoSpec(AaResolution.PORTRAIT_1080x1920, dpi = 240)),
}

/**
 * How [AaMargins] are chosen so Android Auto can reflow to the dash panel's real aspect ratio.
 *
 * - [AUTO] (default) — use the panel size learned from the bike (`REQ_CONFIG_CAPTURE` / [DashMemory])
 *   or the active profile's [BikeProfile.panelSize]. Margins are 0 when the AA coded size already
 *   matches, so normal landscape dashes stay unchanged.
 * - [OFF] — never advertise margins (old letterbox/crop behaviour).
 * - [MANUAL] — use the rider-entered panel W×H.
 */
enum class MatchAspectMode(val label: String) {
    AUTO("Auto — from bike screen"),
    OFF("Off"),
    MANUAL("Manual size"),
}

/**
 * Video/projection preferences. Each setting is **per bike** (scoped via [BikeScope] to the selected
 * bike in the garage): a portrait 1000 MT-X can keep Fit + portrait HD while a landscape 800MT keeps
 * Fill + SD, and switching the active bike switches its settings. When no bike is selected — or a bike
 * has never been customized — the previous single, global value is used as the default.
 */
object VideoPrefs {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_FIT = "screen_fit"
    private const val KEY_POWER = "power_mode"
    private const val KEY_RESOLUTION = "resolution_mode"

    // Match panel aspect (AA margins): Auto uses DashMemory / profile panel size for every bike.
    private const val KEY_MATCH_MODE = "match_aspect_mode"
    /** Legacy boolean from PR #5 — migrated once into [KEY_MATCH_MODE]. */
    private const val KEY_MATCH_ASPECT = "match_aspect_on"
    private const val KEY_ASPECT_W = "match_aspect_w"
    private const val KEY_ASPECT_H = "match_aspect_h"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): VideoQuality {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_QUALITY, VideoQuality.BALANCED.name)
        return runCatching { VideoQuality.valueOf(name!!) }.getOrDefault(VideoQuality.BALANCED)
    }

    fun set(ctx: Context, quality: VideoQuality) {
        BikeScope.putString(prefs(ctx), ctx, KEY_QUALITY, quality.name)
    }

    /** Effective bitrate for [profile] under the current quality preference. */
    fun bitrateFor(ctx: Context, profile: BikeProfile): Int =
        (profile.videoBitrate * get(ctx).multiplier).toInt()

    fun fit(ctx: Context): ScreenFit {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_FIT, ScreenFit.FILL.name)
        return runCatching { ScreenFit.valueOf(name!!) }.getOrDefault(ScreenFit.FILL)
    }

    fun setFit(ctx: Context, fit: ScreenFit) {
        BikeScope.putString(prefs(ctx), ctx, KEY_FIT, fit.name)
    }

    fun power(ctx: Context): PowerMode {
        // Default stays BALANCED until AUTO has more on-bike soak; riders can opt into Auto in Setup.
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_POWER, PowerMode.BALANCED.name)
        return runCatching { PowerMode.valueOf(name!!) }.getOrDefault(PowerMode.BALANCED)
    }

    fun setPower(ctx: Context, mode: PowerMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_POWER, mode.name)
    }

    fun resolution(ctx: Context): ResolutionMode {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_RESOLUTION, ResolutionMode.AUTO.name)
        return runCatching { ResolutionMode.valueOf(name!!) }.getOrDefault(ResolutionMode.AUTO)
    }

    fun setResolution(ctx: Context, mode: ResolutionMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_RESOLUTION, mode.name)
    }

    fun matchAspectMode(ctx: Context): MatchAspectMode {
        val p = prefs(ctx)
        val named = BikeScope.getString(p, ctx, KEY_MATCH_MODE, null)
        if (named != null) {
            return runCatching { MatchAspectMode.valueOf(named) }.getOrDefault(MatchAspectMode.AUTO)
        }
        // Migrate PR #5 boolean: explicit On → Manual (kept their typed size); else Auto.
        return if (BikeScope.getBoolean(p, ctx, KEY_MATCH_ASPECT, false)) {
            MatchAspectMode.MANUAL
        } else {
            MatchAspectMode.AUTO
        }
    }

    /** Manual target W×H when mode is [MatchAspectMode.MANUAL]. */
    fun aspectTarget(ctx: Context): Pair<Int, Int> {
        val detected = detectedPanelSize(ctx)
        return Pair(
            BikeScope.getInt(prefs(ctx), ctx, KEY_ASPECT_W, detected?.first ?: 800),
            BikeScope.getInt(prefs(ctx), ctx, KEY_ASPECT_H, detected?.second ?: 480),
        )
    }

    /**
     * Panel size for Auto match-aspect: last measured canvas for this bike's SSID, else the
     * profile's known [BikeProfile.panelSize]. Null when we have not seen the dash yet.
     */
    fun detectedPanelSize(ctx: Context): Pair<Int, Int>? {
        val qr = BikeMemory.lastQr(ctx)
        DashMemory.get(ctx, qr?.ssid)?.let { return it }
        BikeProfileHolder.active.panelSize?.let { return it }
        return BikeProfiles.selectByQr(qr, ctx).panelSize
    }

    fun setMatchAspect(ctx: Context, mode: MatchAspectMode, w: Int, h: Int) {
        BikeScope.putString(prefs(ctx), ctx, KEY_MATCH_MODE, mode.name)
        BikeScope.putInt(prefs(ctx), ctx, KEY_ASPECT_W, w.coerceIn(16, 8192))
        BikeScope.putInt(prefs(ctx), ctx, KEY_ASPECT_H, h.coerceIn(16, 8192))
    }

    /** Margins to advertise for the current AA [spec], or [AaMargins.NONE]. */
    fun aaMarginsFor(ctx: Context, spec: AaVideoSpec): AaMargins {
        val panel = when (matchAspectMode(ctx)) {
            MatchAspectMode.OFF -> return AaMargins.NONE
            MatchAspectMode.MANUAL -> aspectTarget(ctx)
            MatchAspectMode.AUTO -> detectedPanelSize(ctx) ?: return AaMargins.NONE
        }
        return AaMargins.forAspect(spec, panel.first, panel.second)
    }

    /**
     * The Android Auto video override for [profile] under the current [ResolutionMode], or null to
     * use the profile's own proven resolution ([ResolutionMode.AUTO], or when the explicit choice
     * already equals the profile's spec).
     */
    fun resolutionOverride(ctx: Context, profile: BikeProfile): AaVideoSpec? {
        val spec = resolution(ctx).spec ?: return null
        val base = profile.aaVideo
        if (spec.width == base.width && spec.height == base.height && spec.dpi == base.dpi) return null
        return spec
    }
}
