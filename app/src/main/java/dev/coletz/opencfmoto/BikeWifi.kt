package dev.coletz.opencfmoto

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock

/**
 * Joins the bike's hotspot using WifiNetworkSpecifier (no system Wi-Fi config required).
 *
 * The system shows a dialog asking the user to accept the network. After acceptance, the
 * resulting Network object is process-bound so our TCP sockets and mDNS lookups go through
 * the bike's interface (which has no internet — that's fine).
 *
 * QR for this bike:
 *   ssid = CFMOTO-f46457
 *   pwd  = 59a9cddc94
 *   auth = wpa2-psk
 */
object BikeWifi {
    private var callback: ConnectivityManager.NetworkCallback? = null
    var currentNetwork: Network? = null
        private set

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        log: (String) -> Unit,
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let {            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                cm.bindProcessToNetwork(network)
                log("Wi-Fi joined: $ssid (network=$network, bound)")
                onAvailable(network)
            }

            override fun onLost(network: Network) {
                log("Wi-Fi lost: $network")
                currentNetwork = null
                onLost()
            }

            override fun onUnavailable() {
                log("Wi-Fi join unavailable (user declined or out of range)")
            }
        }
        callback = cb
        log("requesting Wi-Fi join: $ssid …")
        cm.requestNetwork(request, cb)
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
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        cm.bindProcessToNetwork(null)
        currentNetwork = null
        log("Wi-Fi released")
    }
}
