// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * On-screen way to drive Android Auto without touching the dash: a "Navigate to…" box, an on-screen
 * D-pad + rotary knob (forwarded over the AAP INPUT channel via [AaVideoBridge.keySink]/[scrollSink]),
 * and the toggle + entry point for handlebar-button control ([MediaButtonBridge]/[ButtonMappingActivity]).
 */
class ControlsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_controls)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.controls_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        // Navigate-to box.
        val nav = findViewById<EditText>(R.id.et_nav)
        findViewById<MaterialButton>(R.id.btn_nav_go).setOnClickListener {
            val dest = nav.text?.toString()?.trim().orEmpty()
            if (dest.isEmpty()) {
                Toast.makeText(this, "Type a destination first", Toast.LENGTH_SHORT).show()
            } else {
                NavLauncher.navigate(this, dest, LogBus::log)
            }
        }

        // On-screen pad.
        findViewById<View>(R.id.btn_knob_back).setOnClickListener { scroll(-1) }
        findViewById<View>(R.id.btn_knob_fwd).setOnClickListener { scroll(+1) }
        findViewById<View>(R.id.btn_dpad_up).setOnClickListener { key(AaInput.KEY_UP) }
        findViewById<View>(R.id.btn_dpad_down).setOnClickListener { key(AaInput.KEY_DOWN) }
        findViewById<View>(R.id.btn_dpad_left).setOnClickListener { key(AaInput.KEY_LEFT) }
        findViewById<View>(R.id.btn_dpad_right).setOnClickListener { key(AaInput.KEY_RIGHT) }
        findViewById<View>(R.id.btn_select).setOnClickListener { key(AaInput.KEY_ENTER) }
        findViewById<View>(R.id.btn_back).setOnClickListener { key(AaInput.KEY_BACK) }
        findViewById<View>(R.id.btn_home).setOnClickListener { key(AaInput.KEY_HOME) }
        findViewById<View>(R.id.btn_assistant).setOnClickListener {
            ensureMicPermission()
            key(AaInput.KEY_ASSISTANT)
        }

        // Handlebar-button mode toggle (live-applied if the bridge is running).
        val sw = findViewById<MaterialSwitch>(R.id.switch_control_aa)
        sw.isChecked = ButtonMode.isControlAa(this)
        sw.setOnCheckedChangeListener { _, checked ->
            ButtonMode.set(this, checked)
            MediaButtonBridge.instance?.setCaptureActive(checked)
            LogBus.log("→ handlebar buttons ${if (checked) "control Android Auto" else "control media"}")
            if (checked) ensureMicPermission()
        }

        findViewById<MaterialButton>(R.id.btn_customize).setOnClickListener {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }
    }

    private fun key(code: Int) {
        val sink = AaVideoBridge.keySink
        if (sink == null) {
            Toast.makeText(this, "Start Android Auto first", Toast.LENGTH_SHORT).show()
        } else {
            sink(code)
        }
    }

    private fun scroll(delta: Int) {
        val sink = AaVideoBridge.scrollSink
        if (sink == null) {
            Toast.makeText(this, "Start Android Auto first", Toast.LENGTH_SHORT).show()
        } else {
            sink(delta)
        }
    }

    /** The Assistant needs the mic to hear you — ask for it the first time voice is used. */
    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    companion object {
        private const val REQ_MIC = 71
    }
}
