// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.coletz.opencfmoto

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * One-time guided setup + persistent settings. Shows a live checklist of the prerequisites for
 * Android Auto projection (install, permissions, head-unit mode) and the display/battery options.
 * Each selector reflects the saved choice with a highlighted segment so the current setting is
 * always visible. Status is re-evaluated in [onResume] so returning from a permission prompt or the
 * Play Store re-ticks the steps.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var step1Title: TextView
    private lateinit var step1Btn: MaterialButton
    private lateinit var step2Title: TextView
    private lateinit var step2Btn: MaterialButton
    private lateinit var qualityDesc: TextView
    private lateinit var fitDesc: TextView
    private lateinit var powerDesc: TextView
    private lateinit var resDesc: TextView
    private lateinit var btStatus: TextView
    private lateinit var resumeBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Draw content clear of the status/navigation bars (edge-to-edge is enforced on newer Android).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        step1Title = findViewById(R.id.step1_title)
        step1Btn = findViewById(R.id.step1_btn)
        step2Title = findViewById(R.id.step2_title)
        step2Btn = findViewById(R.id.step2_btn)
        qualityDesc = findViewById(R.id.quality_desc)
        fitDesc = findViewById(R.id.fit_desc)
        powerDesc = findViewById(R.id.power_desc)
        resDesc = findViewById(R.id.res_desc)
        btStatus = findViewById(R.id.bt_status)
        resumeBtn = findViewById(R.id.resume_perm_btn)

        step1Btn.setOnClickListener { openAndroidAuto() }
        step2Btn.setOnClickListener { requestMissingPermissions() }
        findViewById<MaterialButton>(R.id.step2_settings_btn).setOnClickListener { openAppSettings() }
        findViewById<MaterialButton>(R.id.step3_btn).setOnClickListener { openAndroidAutoSettings() }

        findViewById<MaterialButton>(R.id.quality_smooth).setOnClickListener { setQuality(VideoQuality.SMOOTH) }
        findViewById<MaterialButton>(R.id.quality_balanced).setOnClickListener { setQuality(VideoQuality.BALANCED) }
        findViewById<MaterialButton>(R.id.quality_sharp).setOnClickListener { setQuality(VideoQuality.SHARP) }
        findViewById<MaterialButton>(R.id.fit_fill).setOnClickListener { setFit(ScreenFit.FILL) }
        findViewById<MaterialButton>(R.id.fit_fit).setOnClickListener { setFit(ScreenFit.FIT) }
        findViewById<MaterialButton>(R.id.fit_stretch).setOnClickListener { setFit(ScreenFit.STRETCH) }
        findViewById<MaterialButton>(R.id.power_smooth).setOnClickListener { setPower(PowerMode.SMOOTH) }
        findViewById<MaterialButton>(R.id.power_balanced).setOnClickListener { setPower(PowerMode.BALANCED) }
        findViewById<MaterialButton>(R.id.power_saver).setOnClickListener { setPower(PowerMode.SAVER) }
        findViewById<MaterialButton>(R.id.res_auto).setOnClickListener { setResolution(ResolutionMode.AUTO) }
        findViewById<MaterialButton>(R.id.res_land_sd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_SD) }
        findViewById<MaterialButton>(R.id.res_land_hd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_HD) }
        findViewById<MaterialButton>(R.id.res_port_sd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_SD) }
        findViewById<MaterialButton>(R.id.res_port_hd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_HD) }
        findViewById<MaterialButton>(R.id.autoconnect_on).setOnClickListener { setAutoConnect(true) }
        findViewById<MaterialButton>(R.id.autoconnect_off).setOnClickListener { setAutoConnect(false) }
        findViewById<MaterialButton>(R.id.recovery_on).setOnClickListener { setAutoRecovery(true) }
        findViewById<MaterialButton>(R.id.recovery_off).setOnClickListener { setAutoRecovery(false) }
        findViewById<MaterialButton>(R.id.logtrips_on).setOnClickListener { setLogTrips(true) }
        findViewById<MaterialButton>(R.id.logtrips_off).setOnClickListener { setLogTrips(false) }
        findViewById<MaterialButton>(R.id.bt_settings_btn).setOnClickListener { BluetoothHelper.openBluetoothSettings(this) }
        resumeBtn.setOnClickListener { requestOverlayPermission() }

        findViewById<MaterialButton>(R.id.setup_done_btn).setOnClickListener {
            markSeen(this)
            finish()
        }
    }

    private fun setQuality(q: VideoQuality) {
        VideoPrefs.set(this, q)
        refreshOptions()
        toast("Video quality: ${q.label}")
    }

    private fun setFit(f: ScreenFit) {
        VideoPrefs.setFit(this, f)
        refreshOptions()
        toast("Screen fit: ${f.label}")
    }

    private fun setPower(m: PowerMode) {
        VideoPrefs.setPower(this, m)
        refreshOptions()
        toast("Power mode: ${m.label}")
    }

    private fun setResolution(m: ResolutionMode) {
        VideoPrefs.setResolution(this, m)
        refreshOptions()
        toast("Resolution: ${m.label}")
    }

    private fun setAutoConnect(on: Boolean) {
        AppSettings.setAutoConnect(this, on)
        refreshOptions()
        Toast.makeText(this, "Auto-connect ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun setAutoRecovery(on: Boolean) {
        AppSettings.setAutoRecovery(this, on)
        refreshOptions()
        Toast.makeText(this, "Auto-recovery ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun setLogTrips(on: Boolean) {
        AppSettings.setLogTrips(this, on)
        refreshOptions()
        Toast.makeText(this, "Trip logging ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, "$msg (applies next connect)", Toast.LENGTH_SHORT).show()

    /** Update every selector's description + highlight the active segment. */
    private fun refreshOptions() {
        val quality = VideoPrefs.get(this)
        val fit = VideoPrefs.fit(this)
        val power = VideoPrefs.power(this)
        val res = VideoPrefs.resolution(this)
        qualityDesc.text = quality.label
        fitDesc.text = fit.label
        powerDesc.text = power.label
        resDesc.text = res.label

        highlight(quality,
            R.id.quality_smooth to VideoQuality.SMOOTH,
            R.id.quality_balanced to VideoQuality.BALANCED,
            R.id.quality_sharp to VideoQuality.SHARP)
        highlight(fit,
            R.id.fit_fill to ScreenFit.FILL,
            R.id.fit_fit to ScreenFit.FIT,
            R.id.fit_stretch to ScreenFit.STRETCH)
        highlight(power,
            R.id.power_smooth to PowerMode.SMOOTH,
            R.id.power_balanced to PowerMode.BALANCED,
            R.id.power_saver to PowerMode.SAVER)
        highlight(res,
            R.id.res_auto to ResolutionMode.AUTO,
            R.id.res_land_sd to ResolutionMode.LANDSCAPE_SD,
            R.id.res_land_hd to ResolutionMode.LANDSCAPE_HD,
            R.id.res_port_sd to ResolutionMode.PORTRAIT_SD,
            R.id.res_port_hd to ResolutionMode.PORTRAIT_HD)

        highlight(AppSettings.autoConnect(this),
            R.id.autoconnect_on to true,
            R.id.autoconnect_off to false)
        highlight(AppSettings.autoRecovery(this),
            R.id.recovery_on to true,
            R.id.recovery_off to false)
        highlight(AppSettings.logTrips(this),
            R.id.logtrips_on to true,
            R.id.logtrips_off to false)
    }

    /** Refresh the Bluetooth pairing status line shown in the helper card. */
    private fun refreshBluetooth() {
        btStatus.text = BluetoothHelper.status(this).describe()
    }

    /** Paint the segment matching [selected] in brand color; the rest stay neutral tonal. */
    private fun <T> highlight(selected: T, vararg pairs: Pair<Int, T>) {
        val onColor = ContextCompat.getColor(this, R.color.brand_orange)
        val onText = ContextCompat.getColor(this, R.color.on_brand)
        val offColor = ContextCompat.getColor(this, R.color.surface_high)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        for ((id, value) in pairs) {
            val btn = findViewById<MaterialButton>(id)
            val on = value == selected
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else offColor)
            btn.setTextColor(if (on) onText else offText)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshOptions()
        refreshBluetooth()
    }

    private fun refresh() {
        val aaOk = SetupHelper.isAndroidAutoInstalled(this)
        step1Title.text = tick(aaOk) + " 1. Android Auto"
        step1Btn.text = if (aaOk) "Open Android Auto" else "Install Android Auto"

        val permsOk = SetupHelper.permissionsGranted(this)
        step2Title.text = tick(permsOk) + " 2. Permissions"
        step2Btn.text = if (permsOk) "All granted" else "Grant permissions"
        step2Btn.isEnabled = !permsOk

        val resumeOk = SetupHelper.canAutoResume(this)
        resumeBtn.text = if (resumeOk) "\u2713 Seamless resume enabled" else "Enable seamless resume"
        resumeBtn.isEnabled = !resumeOk
    }

    /** Deep-link to the "Display over other apps" screen for this app (overlay = seamless auto-resume). */
    private fun requestOverlayPermission() {
        if (SetupHelper.canAutoResume(this)) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)))
            Toast.makeText(this, "Turn on \u201cDisplay over other apps\u201d for seamless resume",
                Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open the overlay permission screen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tick(ok: Boolean): String = if (ok) "\u2713" else "\u2022"

    private fun requestMissingPermissions() {
        val missing = SetupHelper.missingPermissions(this)
        if (missing.isEmpty()) { refresh(); return }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // If anything is still missing after the prompt, the user likely hit "Don't allow" — point
        // them at the system settings where it can still be granted.
        if (!SetupHelper.permissionsGranted(this)) {
            Toast.makeText(this,
                "Some permissions are still off — use \u201cApp settings\u201d to enable them.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open app settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAndroidAuto() {
        if (SetupHelper.isAndroidAutoInstalled(this)) {
            openAndroidAutoSettings()
            return
        }
        val market = Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${SetupHelper.GEARHEAD_PACKAGE}"))
        try {
            startActivity(market)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${SetupHelper.GEARHEAD_PACKAGE}")))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open the Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAndroidAutoSettings() {
        try {
            startActivity(Intent("android.settings.ANDROID_AUTO_SETTINGS")
                .setPackage(SetupHelper.GEARHEAD_PACKAGE))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).setClassName(
                    SetupHelper.GEARHEAD_PACKAGE,
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open Android Auto settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 42
        private const val PREFS = "opencfmoto_bike"
        private const val KEY_SEEN = "setup_seen"

        fun hasSeen(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        fun markSeen(ctx: android.content.Context) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
        }

        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, SetupActivity::class.java))
        }
    }
}
