package dev.coletz.opencfmoto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            log("QR parse FAILED — missing ssid/pwd?")
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}) modelId=${qr.modelId} sn=${qr.sn}"
        )
        joinAndStart(qr)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logView.movementMethod = ScrollingMovementMethod()

        prober = EasyConnProber(applicationContext, ::log)

        findViewById<Button>(R.id.btn_scan_connect).setOnClickListener {
            ensureLocationPermission()
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            prober.stop()
            bleWakeUp?.stop()
            bleWakeUp = null
            BikeWifi.leave(this, ::log)
        }
        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            logView.text = ""
        }

        log("ready. tap Scan QR")
    }

    override fun onDestroy() {
        prober.stop()
        bleWakeUp?.stop()
        bleWakeUp = null
        BikeWifi.leave(this, ::log)
        super.onDestroy()
    }

    private fun joinAndStart(qr: QrData) {
        BikeWifi.join(
            context = this,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = {
                // BLE wake-up is NOT required for projection (confirmed via TCP capture) — go
                // straight to the PXC flow. runBleWakeUpThenProber() remains available if needed.
                log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onLost = { log("bike network lost") },
            log = ::log,
        )
    }

    private fun runBleWakeUpThenProber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ), 2,
            )
            // The user will need to tap Scan again after granting; keeping it simple for PoC.
            return
        }
        bleWakeUp?.stop()
        bleWakeUp = BleWakeUp(
            context = this,
            log = ::log,
            onUnlocked = {
                log("→ BLE wake-up OK; starting EasyConn prober …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason ->
                log("BLE wake-up failed: $reason — TCP probe likely useless, starting anyway")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
        ).also { it.start() }
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    private fun shareLog() {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(logView.text.toString())
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("share failed: $e")
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("${ts.format(Date())}  $msg\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
