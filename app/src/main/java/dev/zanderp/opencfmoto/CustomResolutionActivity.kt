// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Match-panel-aspect page: make Android Auto render at the dash panel's aspect ratio by advertising
 * unused margins (AAP marginWidth/marginHeight), which the compositor then crops to. Applies on the
 * next connect. See [VideoPrefs] (KEY_MATCH_ASPECT/ASPECT_*) and [AaMargins].
 */
class CustomResolutionActivity : AppCompatActivity() {

    private var matchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_resolution)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.custom_res_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val (mw, mh) = VideoPrefs.aspectTarget(this)
        findViewById<EditText>(R.id.match_w).setText(mw.toString())
        findViewById<EditText>(R.id.match_h).setText(mh.toString())
        matchOn = VideoPrefs.matchAspect(this)

        findViewById<MaterialButton>(R.id.match_on).setOnClickListener { matchOn = true; refresh() }
        findViewById<MaterialButton>(R.id.match_off).setOnClickListener { matchOn = false; refresh() }
        findViewById<MaterialButton>(R.id.btn_custom_res_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btn_custom_res_done).setOnClickListener { save(); finish() }

        listOf(R.id.match_w, R.id.match_h).forEach { id ->
            findViewById<EditText>(id).addTextChangedListener(SimpleWatcher { refreshMatchNote() })
        }
        refresh()
    }

    private fun readInt(id: Int, def: Int): Int =
        findViewById<EditText>(id).text.toString().trim().toIntOrNull() ?: def

    private fun save() {
        val (mw0, mh0) = VideoPrefs.aspectTarget(this)
        VideoPrefs.setMatchAspect(this, matchOn, readInt(R.id.match_w, mw0), readInt(R.id.match_h, mh0))
        Toast.makeText(this, "Saved (applies next connect)", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        highlight(matchOn, R.id.match_on to true, R.id.match_off to false)
        refreshMatchNote()
    }

    /** Show the margins + usable content size that match-aspect will produce for the current AA size. */
    private fun refreshMatchNote() {
        val (mw0, mh0) = VideoPrefs.aspectTarget(this)
        val tw = readInt(R.id.match_w, mw0)
        val th = readInt(R.id.match_h, mh0)
        val coded = VideoPrefs.resolution(this).spec ?: BikeProfileHolder.aaVideo
        val m = AaMargins.forAspect(coded, tw, th)
        val uw = coded.width - m.marginW
        val uh = coded.height - m.marginH
        findViewById<TextView>(R.id.match_note).text =
            "AA coded ${coded.width}×${coded.height} → margins ${m.marginW}×${m.marginH}, " +
            "content ${uw}×${uh} (aspect ${"%.3f".format(uw.toDouble() / uh)}). Fills the panel; Screen fit no longer matters."
    }

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

    private class SimpleWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = onChange()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, CustomResolutionActivity::class.java))
        }
    }
}
