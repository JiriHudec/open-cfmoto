// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * A full-screen, in-app view of the Android Auto dash. Mirrors the live AA video (a second render
 * target on [AaCompositor]) onto a phone [SurfaceView] so the rider can watch it bigger and — on
 * touch dashes — drive it directly with their fingers before pocketing the phone. A bottom control
 * bar (knob / D-pad / OK / Back / Home / voice) drives AA over the AAP INPUT channel and works on
 * every dash, including non-touch ones where finger touch isn't delivered.
 *
 * Coordinates are sent in AA source space via [AaVideoBridge.previewTouchSink]; the letterbox rect is
 * computed the same way [AaCompositor.setPreview] fits the source into the surface, so a tap on the
 * preview lands on the same spot the dash shows.
 */
class HudViewActivity : AppCompatActivity() {

    private lateinit var surface: SurfaceView
    private lateinit var hint: TextView
    private var attached = false
    private var noSessionToastAt = 0L
    private var fullscreen = false
    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud_view)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = findViewById(R.id.hud_surface)
        hint = findViewById(R.id.hud_hint)

        insetsController = WindowInsetsControllerCompat(window, findViewById(R.id.hud_root)).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Keep the top bar buttons out from under the status bar / camera cutout. In fullscreen the
        // bars are hidden so the inset is 0 (and the bar itself is gone anyway) — no wasted space.
        val topbar = findViewById<View>(R.id.hud_topbar)
        val baseTopPad = topbar.paddingTop
        val baseLeftPad = topbar.paddingLeft
        val baseRightPad = topbar.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(topbar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(baseLeftPad + bars.left, baseTopPad + bars.top, baseRightPad + bars.right, v.paddingBottom)
            insets
        }
        // Back exits fullscreen first (so the rider isn't stuck with no visible controls), then closes.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val pipe = AaVideoBridge.pipeline
                if (pipe == null) {
                    LogBus.log("[HUD] no AA pipeline — preview will show once Android Auto is live")
                    return
                }
                if (!attached) {
                    pipe.setPreviewSurface(holder.surface, width, height)
                    attached = true
                    LogBus.log("[HUD] preview surface attached ${width}x$height")
                } else {
                    pipe.updatePreviewSize(width, height)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (attached) {
                    AaVideoBridge.pipeline?.clearPreviewSurface()
                    attached = false
                    LogBus.log("[HUD] preview surface detached")
                }
            }
        })

        surface.setOnTouchListener { _, e -> onSurfaceTouch(e); true }

        findViewById<View>(R.id.hud_close).setOnClickListener { finish() }
        findViewById<View>(R.id.hud_toggle_controls).setOnClickListener { toggleControls() }
        findViewById<View>(R.id.hud_fullscreen).setOnClickListener { setFullscreen(!fullscreen) }

        findViewById<View>(R.id.hud_knob_back).setOnClickListener { scroll(-1) }
        findViewById<View>(R.id.hud_knob_fwd).setOnClickListener { scroll(+1) }
        findViewById<View>(R.id.hud_left).setOnClickListener { key(AaInput.KEY_LEFT) }
        findViewById<View>(R.id.hud_right).setOnClickListener { key(AaInput.KEY_RIGHT) }
        findViewById<View>(R.id.hud_up).setOnClickListener { key(AaInput.KEY_UP) }
        findViewById<View>(R.id.hud_down).setOnClickListener { key(AaInput.KEY_DOWN) }
        findViewById<View>(R.id.hud_ok).setOnClickListener { key(AaInput.KEY_ENTER) }
        findViewById<View>(R.id.hud_back).setOnClickListener { key(AaInput.KEY_BACK) }
        findViewById<View>(R.id.hud_home).setOnClickListener { key(AaInput.KEY_HOME) }
        findViewById<View>(R.id.hud_voice).setOnClickListener {
            ensureMicPermission()
            key(AaInput.KEY_ASSISTANT)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHint()
    }

    private fun refreshHint() {
        val live = AaVideoBridge.pipeline != null
        hint.visibility = if (live) View.GONE else View.VISIBLE
    }

    private fun toggleControls() {
        val bar = findViewById<View>(R.id.hud_controls)
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /**
     * Fullscreen = pure dash. Hides the app's top/control bars and the phone's status/nav bars for an
     * unobstructed view. Back (or another swipe-down + the reappearing bar) exits.
     */
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        findViewById<View>(R.id.hud_topbar).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.hud_controls).visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Fullscreen — press Back to exit", Toast.LENGTH_SHORT).show()
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Forward preview touches (multi-finger) into AA source space, letterbox-aware. */
    private fun onSurfaceTouch(e: MotionEvent) {
        val sink = AaVideoBridge.previewTouchSink
        if (sink == null) {
            val now = System.currentTimeMillis()
            if (now - noSessionToastAt > 3000) {
                noSessionToastAt = now
                Toast.makeText(this, "Connect to the bike first", Toast.LENGTH_SHORT).show()
            }
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_DOWN)
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_MOVE)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_UP)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_UP)
        }
    }

    private fun forward(
        sink: (Int, Int, Int, Int) -> Unit,
        e: MotionEvent,
        index: Int,
        action: Int,
    ) {
        val src = mapToSource(e.getX(index), e.getY(index)) ?: return
        sink(action, e.getPointerId(index), src.first, src.second)
    }

    /**
     * Map a touch in the SurfaceView to AA source coordinates, mirroring [AaCompositor]'s aspect-fit.
     * Returns null for touches in the black letterbox bars.
     */
    private fun mapToSource(vx: Float, vy: Float): Pair<Int, Int>? {
        val vw = surface.width
        val vh = surface.height
        val spec = BikeProfileHolder.aaVideo
        val sw = spec.width
        val sh = spec.height
        if (vw == 0 || vh == 0 || sw == 0 || sh == 0) return null

        val srcAspect = sw.toFloat() / sh
        val viewAspect = vw.toFloat() / vh
        val rectW: Int
        val rectH: Int
        if (srcAspect < viewAspect) {
            rectH = vh; rectW = Math.round(vh * srcAspect)
        } else {
            rectW = vw; rectH = Math.round(vw / srcAspect)
        }
        val rectX = (vw - rectW) / 2
        val rectY = (vh - rectH) / 2

        val rx = vx - rectX
        val ry = vy - rectY
        if (rx < 0 || ry < 0 || rx >= rectW || ry >= rectH) return null
        val sx = (rx * sw / rectW).toInt().coerceIn(0, sw - 1)
        val sy = (ry * sh / rectH).toInt().coerceIn(0, sh - 1)
        return sx to sy
    }

    private fun key(code: Int) {
        val sink = AaVideoBridge.keySink
        if (sink == null) {
            Toast.makeText(this, "Connect to the bike first", Toast.LENGTH_SHORT).show()
        } else {
            sink(code)
        }
    }

    private fun scroll(delta: Int) {
        val sink = AaVideoBridge.scrollSink
        if (sink == null) {
            Toast.makeText(this, "Connect to the bike first", Toast.LENGTH_SHORT).show()
        } else {
            sink(delta)
        }
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    companion object {
        private const val REQ_MIC = 72
    }
}
