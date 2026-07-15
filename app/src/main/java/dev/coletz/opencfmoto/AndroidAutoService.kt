package dev.coletz.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.coletz.opencfmoto.aa.AaReceiver

/**
 * Foreground service that hosts the Android Auto receiver end-to-end (M4):
 *   VideoPipeline (H.264 encoder, external-source mode) + AaReceiver (loopback AAP receiver).
 *
 * Running as a foreground service with a partial wake lock keeps the whole decode→encode→PXC
 * chain alive while the phone is backgrounded or the screen is locked. The encoder's input
 * Surface is published to [AaVideoBridge] so [EasyConnProber] streams the re-encoded Android
 * Auto video to the bike dash.
 */
class AndroidAutoService : Service() {

    private var pipeline: VideoPipeline? = null
    private var receiver: AaReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastRecoveryAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startAsForeground()
        startReceiver()
        startWatchdog()
        isRunning = true
        return START_STICKY
    }

    /**
     * Auto-recovery watchdog. Every [WATCHDOG_TICK_MS] it checks the bike link and, if enabled:
     *  - STREAMING but no frames for [STALL_MS] → the dash stopped pulling (half-open link / stall):
     *    force a clean reconnect (drops sockets → the prober re-probes).
     *  - ERROR (reconnect budget spent) → periodically re-arm so a bike back in range re-links itself.
     * A cooldown between actions stops it from thrashing. The rider can disable it in Setup.
     */
    private fun startWatchdog() {
        watchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                try { tickWatchdog() } catch (_: Exception) {}
                try { tickTripLogging() } catch (_: Exception) {}
                watchdogHandler.postDelayed(this, WATCHDOG_TICK_MS)
            }
        }, WATCHDOG_TICK_MS)
    }

    /**
     * Auto-log the ride: once we're actually streaming to the dash, start the shared [TripRecorder]
     * (if the rider enabled trip logging and location is granted). The recorder is stopped and saved
     * when the session ends (user Exit → [onAaSessionEnded], or service teardown → [onDestroy]); a
     * transient RECONNECTING blip is left alone so a single ride isn't split by a brief drop.
     */
    private fun tickTripLogging() {
        val rec = TripLogger.current
        if (!AppSettings.logTrips(this)) {
            if (rec != null && rec.recording) rec.stopAndSave()
            return
        }
        if (ConnectionState.phase == Phase.STREAMING) {
            val r = TripLogger.get(this)
            if (!r.recording && r.start()) LogBus.log("[trip] auto-logging this ride")
        }
    }

    private fun tickWatchdog() {
        if (!AppSettings.autoRecovery(this)) return
        val prober = BikeLink.prober ?: return
        if (!prober.isRunning) return
        val now = System.currentTimeMillis()
        when {
            ConnectionState.phase == Phase.STREAMING &&
                prober.msSinceLastFrame() > STALL_MS &&
                now - lastRecoveryAt > ACTION_COOLDOWN_MS -> {
                LogBus.log("[watchdog] streaming stalled (${prober.msSinceLastFrame()}ms, no frames) — recovering")
                lastRecoveryAt = now
                prober.forceReconnect()
            }
            ConnectionState.phase == Phase.ERROR &&
                now - lastRecoveryAt > ERROR_COOLDOWN_MS -> {
                lastRecoveryAt = now
                prober.rearmFromError()
            }
        }
    }

    private fun startAsForeground() {
        val channelId = "opencfmoto_androidauto"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Android Auto receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("OpenCfMoto — Android Auto")
            .setContentText("Receiving Android Auto for the bike dash — tap to open")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openApp)
            .build()

        // Include the location FGS type only when fine location is granted (auto-logging rides while
        // backgrounded needs it) — declaring it without the permission would crash on Android 14+.
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasLocation) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, fgType)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCfMoto:AndroidAuto").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L /* 4h safety cap */)
        }
        LogBus.log("[AA] foreground service up (wake lock held)")
    }

    private fun startReceiver() {
        if (receiver != null) { LogBus.log("[AA] receiver already started"); return }
        try {
            // Compositor mode: the AA decoder renders into the compositor's input surface; the
            // compositor letterboxes it into the bike's canvas (encoder created later, sized to the
            // bike's REQ_CONFIG_CAPTURE dims — see EasyConnProber / VideoPipeline.configureBikeCanvas).
            val vp = VideoPipeline(applicationContext, 0, 0, LogBus::log, compositor = true)
            vp.start()
            val surface = vp.decoderInputSurface()
            if (surface == null) {
                LogBus.log("[AA] compositor input surface null — cannot start receiver")
                vp.stop()
                stopSelf()
                return
            }
            pipeline = vp
            AaVideoBridge.pipeline = vp
            receiver = AaReceiver(applicationContext, surface, LogBus::log).also {
                it.onSessionEnded = { userExit -> onAaSessionEnded(userExit) }
                it.start()
            }
        } catch (e: Exception) {
            LogBus.log("[AA] receiver start failed: $e")
            stopSelf()
        }
    }

    /**
     * The Android Auto session ended. If the rider tapped Exit in AA ([userExit]), fully stop
     * projection — otherwise the bike keeps pulling the compositor's last frame and the dash freezes
     * on a stale image while the app still looks "connected". A transient drop is left alone so the
     * receiver can accept a reconnect. Runs on the main thread to avoid tearing down from the AAP
     * poll thread that just fired this callback.
     */
    private fun onAaSessionEnded(userExit: Boolean) {
        if (!userExit) {
            LogBus.log("[AA] session dropped — receiver still listening for reconnect")
            return
        }
        LogBus.log("[AA] user exited Android Auto — stopping projection to the dash")
        watchdogHandler.post { fullTeardown() }
    }

    /**
     * Drop the whole projection: stop the bike link (dash stops pulling the compositor's last frame,
     * so no frozen image), leave the bike Wi-Fi, mark STOPPED, and stop the service (which tears down
     * the receiver, pipeline, and wake lock in [onDestroy]). Shared by the AA-exit path and app
     * close/kill ([onTaskRemoved]).
     */
    private fun fullTeardown() {
        AaVideoBridge.onSteadyVideo = null
        try { TripLogger.current?.stopAndSave() } catch (_: Exception) {}
        try { BikeLink.prober?.stop() } catch (_: Exception) {}
        try { BikeWifi.leave(applicationContext, LogBus::log) } catch (_: Exception) {}
        ConnectionState.set(Phase.STOPPED, "")
        stopSelf()   // onDestroy tears down the receiver, pipeline, and wake lock
    }

    /**
     * The user swiped the app away from recents (or the task was killed). A foreground service
     * survives task removal, which would leave Android Auto projecting a frozen dash. Tear the whole
     * thing down so closing/killing the app also closes AA + the projected maps on the HUD.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogBus.log("[AA] app closed/killed — stopping projection to the dash")
        try { fullTeardown() } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        watchdogHandler.removeCallbacksAndMessages(null)
        try { TripLogger.current?.stopAndSave() } catch (_: Exception) {}
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop() } catch (_: Exception) {}
        pipeline = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        LogBus.log("[AA] foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2

        // Watchdog cadence / thresholds.
        private const val WATCHDOG_TICK_MS = 5_000L
        private const val STALL_MS = 8_000L            // no frames this long while STREAMING = stalled
        private const val ACTION_COOLDOWN_MS = 20_000L // min gap between forced reconnects
        private const val ERROR_COOLDOWN_MS = 30_000L  // min gap between re-arm attempts after ERROR

        @Volatile var isRunning = false
            private set

        const val ACTION_STOP = "dev.coletz.opencfmoto.ACTION_STOP_AA"

        fun start(ctx: Context) {
            val i = Intent(ctx, AndroidAutoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AndroidAutoService::class.java))
        }
    }
}
