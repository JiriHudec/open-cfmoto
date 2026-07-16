// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.coletz.opencfmoto

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Joins the bike's hotspot using WifiNetworkSpecifier (no system Wi-Fi config required).
 *
 * The system shows a dialog asking the user to accept the network. After acceptance, the
 * resulting Network object is process-bound so our TCP sockets and mDNS lookups go through
 * the bike's interface (which has no internet — that's fine). Once the SSID has been approved,
 * Android re-satisfies the request without prompting again, so re-joins are silent.
 *
 * ## Auto-rejoin on loss (bike power-cycle)
 * A [WifiNetworkSpecifier] request is *terminal* on loss: when the bike is switched off its hotspot
 * disappears and the framework won't re-satisfy the old request on its own. So if the network drops
 * while a session is still active, we re-issue the request (with backoff) until the bike's AP comes
 * back. The FIRST acquisition drives the normal start-up; every subsequent one is a re-acquire that
 * restarts the bike link on the fresh network via [BikeLink.onWifiReacquired] (fresh IP + rebound
 * server sockets + fresh probe). Without this, stopping and restarting the bike left the app probing
 * a dead interface and needed several manual Stop→Connect cycles to recover.
 */
object BikeWifi {
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var request: NetworkRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    var currentNetwork: Network? = null
        private set

    @Volatile private var active = false
    @Volatile private var firstDelivered = false
    private var rejoinAttempts = 0
    private var ssid: String = ""
    private var onAvailableCb: ((Network) -> Unit)? = null
    private var onLostCb: (() -> Unit)? = null
    private var logCb: ((String) -> Unit)? = null

    // Watch for the bike's AP until the rider taps Stop (leave()): a stopped bike may be off for a
    // while, and re-issuing the (approved, silent) request is cheap. Backoff grows then caps so we
    // don't hammer the framework while parked.
    //
    // The FIRST re-request after a drop is near-instant: at home the phone races to re-join a saved
    // network the moment the bike's AP blips, and if we don't re-grab the bike AP fast the dash's
    // EasyConn times out ("device is not on the network") and needs a manual restart. Grabbing it back
    // immediately keeps us on the bike's network long enough for the dash to reconnect on its own.
    private const val REJOIN_FAST_MS = 300L
    private const val REJOIN_BASE_MS = 2500L
    private const val REJOIN_MAX_MS = 15000L

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        log: (String) -> Unit,
    ) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Tear down any previous session's callback first.
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }

        this.cm = cm
        this.ssid = ssid
        this.onAvailableCb = onAvailable
        this.onLostCb = onLost
        this.logCb = log
        this.active = true
        this.firstDelivered = false
        this.rejoinAttempts = 0

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()
        request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        log("requesting Wi-Fi join: $ssid …")
        registerCallback()
    }

    private fun registerCallback() {
        val cm = cm ?: return
        val req = request ?: return
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                cm.bindProcessToNetwork(network)
                rejoinAttempts = 0
                if (!firstDelivered) {
                    firstDelivered = true
                    logCb?.invoke("Wi-Fi joined: $ssid (network=$network, bound)")
                    onAvailableCb?.invoke(network)
                } else {
                    logCb?.invoke("Wi-Fi re-acquired: $ssid — restarting bike link on fresh network")
                    BikeLink.onWifiReacquired(network)
                }
            }

            override fun onLost(network: Network) {
                logCb?.invoke("Wi-Fi lost: $network")
                currentNetwork = null
                onLostCb?.invoke()
                if (active) scheduleRejoin()
            }

            override fun onUnavailable() {
                logCb?.invoke("Wi-Fi join unavailable (bike off / out of range / declined)")
                if (active) scheduleRejoin()
            }
        }
        callback = cb
        cm.requestNetwork(req, cb)
    }

    private fun scheduleRejoin() {
        if (!active) return
        rejoinAttempts++
        // First attempt is near-instant to beat the phone settling on a saved (home) network and the
        // dash timing out; later attempts back off and cap so we don't hammer the framework.
        val delay = if (rejoinAttempts <= 1) REJOIN_FAST_MS
        else minOf(REJOIN_BASE_MS * (rejoinAttempts - 1), REJOIN_MAX_MS)
        handler.postDelayed({
            if (!active) return@postDelayed
            logCb?.invoke("re-requesting bike Wi-Fi (attempt $rejoinAttempts) …")
            // Don't stomp the WAITING_FOR_BIKE state the service sets once AA is parked.
            if (ConnectionState.phase != Phase.WAITING_FOR_BIKE) {
                ConnectionState.set(Phase.RECONNECTING, "waiting for bike Wi-Fi")
            }
            registerCallback()
        }, delay)
    }

    /**
     * Best-effort "is the bike's hotspot nearby?" check for auto-connect. Deliberately biased toward
     * `null` ("unknown → try anyway"): a false "not in range" is worse than a wasted connect attempt.
     *  - `true`  : the SSID is in a fresh Wi-Fi scan (or we're already on it) → connect.
     *  - `false` : we have FRESH scan results and the SSID is not among them → the bike really is away.
     *  - `null`  : we can't be sure (Wi-Fi off, no location permission, empty/stale scan cache) →
     *              the caller should attempt anyway.
     *
     * Reads cached scan results and only trusts "absent" when at least one result is recent (Android
     * throttles active scans, so a stale cache must not be read as "bike gone"). Also fires a
     * best-effort [WifiManager.startScan] to refresh the cache for the next check.
     */
    fun isSsidInRange(context: Context, ssid: String): Boolean? {
        if (ssid.isBlank()) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        if (!wm.isWifiEnabled) return null
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return null

        // Already associated with the bike's AP counts as in range.
        val connected = wm.connectionInfo?.ssid?.trim('"')
        if (connected != null && connected.equals(ssid, ignoreCase = true)) return true

        val results = try { wm.scanResults } catch (_: SecurityException) { null } ?: return null
        // Refresh for next time (throttled/deprecated — failures are fine).
        try { wm.startScan() } catch (_: Exception) {}
        if (results.isEmpty()) return null

        // ScanResult.timestamp is microseconds since boot when the AP was last seen.
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val fresh = results.filter { it.timestamp > 0 && nowUs - it.timestamp < FRESH_SCAN_US }
        if (fresh.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        if (results.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        // Not found: only a confident "no" if we actually have fresh evidence; else unknown.
        return if (fresh.isNotEmpty()) false else null
    }

    private const val FRESH_SCAN_US = 30_000_000L  // 30s

    fun leave(context: Context, log: (String) -> Unit) {
        active = false
        handler.removeCallbacksAndMessages(null)
        val cm = this.cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        firstDelivered = false
        cm.bindProcessToNetwork(null)
        currentNetwork = null
        log("Wi-Fi released")
    }
}
