package dev.coletz.opencfmoto

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * H.264 video pipeline for MotoPlay mirroring.
 *
 *   VirtualDisplay  ──hosts──▶  Presentation ("Hello World", live clock)
 *        │ output Surface = encoder input
 *        ▼
 *   MediaCodec (video/avc, 800x384)  ──encoded access units──▶  frameQueue
 *
 * The data socket pulls one frame per REQ_RV_DATA_NEXT(114) via [pollFrame].
 * Frames are Annex-B; SPS/PPS (codec config) is prepended to the first keyframe so the
 * decoder on the bike can start.
 *
 * No MediaProjection needed: we render our OWN content onto a private VirtualDisplay
 * (the same approach as MotoPlay's SplitScreenPresentation).
 */
class VideoPipeline(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    private val frameQueue = LinkedBlockingDeque<ByteArray>(8)
    @Volatile private var codecConfig: ByteArray? = null   // SPS/PPS

    fun start() {
        if (running) return
        running = true
        try {
            fun baseFormat() = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // frequent keyframes for late joiners
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Prefer Baseline (embedded HU decoders often require it); fall back if the encoder rejects it.
            try {
                val fmt = baseFormat().apply {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                log("[VIDEO] configured Baseline@3.1")
            } catch (e: Exception) {
                log("[VIDEO] baseline configure failed ($e) — retrying default profile")
                c.reset()
                c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = c.createInputSurface()
            c.start()
            codec = c
            log("[VIDEO] encoder started ${width}x${height} h264 30fps")

            main.post { setupDisplayAndPresentation() }
            drainThread = thread(name = "video-drain", isDaemon = true) { drainLoop() }
        } catch (e: Exception) {
            log("[VIDEO] start failed: $e")
            stop()
        }
    }

    private fun setupDisplayAndPresentation() {
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val vd = dm.createVirtualDisplay("OpenCfMoto", width, height, 160, inputSurface, flags)
            virtualDisplay = vd
            val display = vd?.display ?: run { log("[VIDEO] virtualDisplay.display null"); return }

            val pres = Presentation(context, display)
            val root = LinearLayout(pres.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0D47A1"))
            }
            val title = TextView(pres.context).apply {
                text = "Hello from OpenCfMoto"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            val clock = TextView(pres.context).apply {
                setTextColor(Color.parseColor("#80D8FF"))
                textSize = 20f
                gravity = Gravity.CENTER
            }
            root.addView(title)
            root.addView(clock)
            pres.setContentView(root)
            pres.show()
            presentation = pres
            log("[VIDEO] presentation shown on virtual display")

            // Animate so the stream is visibly live (forces continuous frames).
            val ticker = object : Runnable {
                var n = 0
                override fun run() {
                    if (!running) return
                    clock.text = "frame tick ${n++}"
                    main.postDelayed(this, 100)
                }
            }
            main.post(ticker)
        } catch (e: Exception) {
            log("[VIDEO] display/presentation failed: $e")
        }
    }

    private fun drainLoop() {
        val codec = this.codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) {
                log("[VIDEO] dequeue failed: $e"); break
            }
            if (idx < 0) continue
            val buf = try { codec.getOutputBuffer(idx) } catch (e: Exception) { null }
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codecConfig = bytes   // SPS/PPS — hold, prepend to next keyframe
                    log("[VIDEO] got codec config (SPS/PPS) ${bytes.size}b")
                } else {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val out = if (isKey && codecConfig != null) codecConfig!! + bytes else bytes
                    // Keep the queue fresh: if full, drop oldest so we never lag far behind.
                    if (!frameQueue.offerLast(out)) {
                        frameQueue.pollFirst()
                        frameQueue.offerLast(out)
                    }
                }
            }
            try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
        }
    }

    /** Called by the data socket on each REQ_RV_DATA_NEXT(114). Returns one access unit. */
    fun pollFrame(timeoutMs: Long): ByteArray? =
        try { frameQueue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }

    fun stop() {
        running = false
        drainThread?.interrupt(); drainThread = null
        main.post {
            try { presentation?.dismiss() } catch (_: Exception) {}
            presentation = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        frameQueue.clear()
        codecConfig = null
    }
}
