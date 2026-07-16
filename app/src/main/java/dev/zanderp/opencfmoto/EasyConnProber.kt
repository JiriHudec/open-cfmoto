// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * EasyConn / Carbit PXC client for CFMoto MotoPlay.
 *
 * Topology (verified in cfmoto-tcp-v5.log): the PHONE is the SERVER.
 *  1. Discover the bike (gateway 192.168.0.1, EasyConn mDNS advertises :10930).
 *  2. Open TCP servers on 10920, 10921, 10922 bound to our bike-network IP.
 *  3. Connect once to bike:10930 and send ECP_PXC_MDNS_RESPOND (cmd 0x70000010, JSON);
 *     bike replies {"status":true} and we close that socket.
 *  4. The bike then connects BACK to our listening ports and drives the PXC handshake
 *     (channel selects, CLIENT_INFO, SN check, heartbeats) — handled by [PxcHandshake].
 */
class EasyConnProber(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT_MEDIA_DATA = 10920   // MediaProjectService data
        const val PORT_MEDIA_CTRL = 10921   // MediaProjectService ctrl
        const val PORT_PXC_CTRL   = 10922   // PXCService ctrl (channel selects + CLIENT_INFO)
        const val BIKE_PROBE_PORT = 10930   // bike's EasyConn mDNS/probe endpoint
        const val SPOOFED_PACKAGE = "com.cfmoto.cfmotointernational"
        private val LISTEN_PORTS = intArrayOf(PORT_PXC_CTRL, PORT_MEDIA_CTRL, PORT_MEDIA_DATA)
        /** How many times to auto re-probe after a link drop before giving up (user taps Connect). */
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private val handshake = PxcHandshake(log)
    private val servers = ArrayList<ServerSocket>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var probed = false
    @Volatile private var video: VideoPipeline? = null
    @Volatile private var ownsVideo = false
    @Volatile private var negW = 800
    @Volatile private var negH = 384
    @Volatile private var framesSent = 0
    @Volatile private var touchMoves = 0
    @Volatile private var lastFrameAt = 0L

    // Live client sockets the bike has opened back to us, so the watchdog ([AndroidAutoService]) can
    // force a clean reconnect by dropping them (which trips the existing onAllConnectionsClosed path).
    private val activeClients = java.util.Collections.synchronizedList(ArrayList<Socket>())

    // Auto-reconnect: the phone keeps listening on all three ports for the whole session, so if the
    // dash drops the link while Wi-Fi is still up we just re-send the mDNS probe to invite it back —
    // no user Stop/Start. Retained connection params + a live-connection counter drive this.
    @Volatile private var myIp: Inet4Address? = null
    @Volatile private var bikeIp: Inet4Address? = null
    @Volatile private var network: Network? = null
    private val liveConns = AtomicInteger(0)
    @Volatile private var everConnected = false
    @Volatile private var reprobing = false
    @Volatile private var reconnectAttempts = 0

    fun start(network: Network?) {
        if (running) { log("already running"); return }
        probed = false
        framesSent = 0
        lastFrameAt = 0L
        everConnected = false
        reconnectAttempts = 0
        liveConns.set(0)
        this.network = network
        dumpEnvironment(network)

        val myIp = pickBikeInterfaceIp(network)
        if (myIp == null) { log("could not resolve our IPv4 on the bike network; aborting"); return }
        val bikeIp = resolveGateway(network)
        if (bikeIp == null) { log("could not resolve bike gateway IP; aborting"); return }
        this.myIp = myIp
        this.bikeIp = bikeIp
        log("our IP=${myIp.hostAddress}  bike IP=${bikeIp.hostAddress}")

        running = true
        acquireMulticastLock()

        // 1. Listen on all three ports BEFORE probing, so we're ready for the bike's call-back.
        //    SO_REUSEADDR (set before bind) lets us re-listen immediately after a Stop→Connect: the
        //    bike's just-closed call-back sockets leave these ports in TIME_WAIT for up to a couple
        //    minutes, and without reuse the rebind fails with EADDRINUSE — so no media servers open,
        //    the bike has nowhere to connect back to, and the dash shows an empty screen (no frames).
        var bindConflict = false
        for (port in LISTEN_PORTS) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(myIp, port), 50)
                servers.add(ss)
                spawnAccept(port, ss)
            } catch (e: Exception) {
                bindConflict = true
                log("bind :$port failed: ${e.message}")
            }
        }
        log("listening on ${myIp.hostAddress} ports ${LISTEN_PORTS.toList()} (${servers.count { !it.isClosed }} open)")

        // If a port is taken, the bike's mirroring link ports are held by another EasyConnect client —
        // almost always the official CFMoto app running in the background (it binds the same 10920-10922
        // and the bike connects back to IT, not us). Probing anyway is pointless: no media server means
        // no frames and a blank dash. Fail fast with an actionable message instead of failing silently.
        if (bindConflict) {
            log("!! link ports are held by another app (usually the official CFMoto/EasyConnect app). " +
                "Close it (force-stop) and reconnect — OpenCfMoto needs ports ${LISTEN_PORTS.toList()}.")
            ConnectionState.set(Phase.ERROR, "close the official CFMoto app, then reconnect")
            stop()
            return
        }

        // 2. Send the probe (gives the bike our IP → it connects back).
        thread(name = "ec-probe", isDaemon = true) {
            sendMdnsRespond(bikeIp, myIp, network)
        }
        startHeartbeatLog()
    }

    fun stop() {
        running = false
        probed = false
        everConnected = false
        reprobing = false
        // Only stop the pipeline if we created it; the shared Android Auto pipeline is owned
        // by AndroidAutoService and must outlive a bike disconnect.
        if (ownsVideo) video?.stop()
        video = null; ownsVideo = false
        heartbeatThread?.interrupt(); heartbeatThread = null
        synchronized(activeClients) {
            for (s in activeClients.toList()) try { s.close() } catch (_: Exception) {}
            activeClients.clear()
        }
        for (s in servers) try { s.close() } catch (_: IOException) {}
        servers.clear()
        multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        multicastLock = null
        log("stopped")
    }

    // ---- Watchdog surface (read/driven by AndroidAutoService's auto-recovery loop) ----

    /** True while the prober is live (started, not stopped). */
    val isRunning: Boolean get() = running

    /** True once at least one frame has been delivered to the dash this session. */
    val isStreaming: Boolean get() = running && framesSent > 0

    /** Milliseconds since the last frame was sent to the dash (Long.MAX_VALUE if none yet). */
    fun msSinceLastFrame(): Long =
        if (lastFrameAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastFrameAt

    /**
     * Force a clean reconnect: drop every live bike socket. Each read loop then ends and, once the
     * last one closes, [onAllConnectionsClosed] re-probes — reusing the proven reconnect path. Used
     * by the watchdog when frames stall while the sockets are (half-)open and won't close on their own.
     */
    fun forceReconnect() {
        val n = activeClients.size
        log("[watchdog] forcing reconnect — dropping $n live socket(s)")
        synchronized(activeClients) {
            for (s in activeClients.toList()) try { s.close() } catch (_: Exception) {}
        }
    }

    /**
     * Re-arm reconnection after the retry budget was exhausted ([Phase.ERROR]). Resets the attempt
     * counter and kicks a fresh probe so a bike that comes back into range links up on its own.
     */
    fun rearmFromError() {
        if (!running || liveConns.get() > 0) return
        log("[watchdog] re-arming reconnect after error")
        reconnectAttempts = 0
        onAllConnectionsClosed()
    }

    /** Step 3: phone→bike probe. cmd 0x70000010 + JSON; expect 0x70000011 {"status":true}. */
    private fun sendMdnsRespond(bikeIp: Inet4Address, myIp: Inet4Address, network: Network?) {
        var attempt = 0
        while (running && attempt < 5 && !probed) {
            attempt++
            try {
                log("[PROBE] connect #$attempt -> ${bikeIp.hostAddress}:$BIKE_PROBE_PORT")
                val sock = Socket()
                try { sock.bind(InetSocketAddress(myIp, 0)) } catch (_: Exception) {}
                network?.let { try { it.bindSocket(sock) } catch (_: Exception) {} }
                sock.connect(InetSocketAddress(bikeIp, BIKE_PROBE_PORT), 3000)
                sock.soTimeout = 5000

                val json = JSONProbe()
                log("[PROBE] -> MDNS_RESPOND (0x70000010) $json")
                PxcFrame(PxcFrame.CMD_MDNS_RESPOND, json.toByteArray(Charsets.UTF_8))
                    .write(sock.getOutputStream())

                val resp = PxcFrame.read(sock.getInputStream())
                if (resp == null) {
                    log("[PROBE] bike closed before responding")
                } else {
                    val body = String(resp.payload, Charsets.UTF_8)
                    log("[PROBE] <- cmd=0x${resp.cmd.toUInt().toString(16)} $body")
                    if (resp.cmd == PxcFrame.CMD_MDNS_RESPOND_ACK && body.contains("true")) {
                        log("[PROBE] *** accepted — bike should now connect back to our ports ***")
                        probed = true
                    } else {
                        log("[PROBE] !! not accepted: $body")
                    }
                }
                try { sock.close() } catch (_: IOException) {}
                if (probed) return
            } catch (e: Exception) {
                log("[PROBE] failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun JSONProbe(): String =
        "{\"phoneType\":\"Android\",\"packageName\":\"$SPOOFED_PACKAGE\"}"

    private fun spawnAccept(port: Int, server: ServerSocket) =
        thread(name = "ec-accept-$port", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) {
                    if (running) log("[:$port] accept ended: ${e.message}"); break
                }
                log("[:$port] <<< bike connected from ${client.remoteSocketAddress}")
                everConnected = true
                reconnectAttempts = 0            // a fresh connection resets the retry budget
                liveConns.incrementAndGet()
                activeClients.add(client)
                thread(name = "ec-conn-$port", isDaemon = true) {
                    try { readLoop(port, client) }
                    finally {
                        activeClients.remove(client)
                        if (liveConns.decrementAndGet() == 0) onAllConnectionsClosed()
                    }
                }
            }
        }

    /**
     * All bike sockets have closed. If we're still running and the link had connected at least once,
     * the dash likely dropped the session (a UI transition, brief Wi-Fi blip, etc.) while the phone
     * kept listening. Re-send the mDNS probe to invite it straight back — no user Stop/Start — with a
     * capped, backed-off retry so a genuinely-gone bike doesn't spin forever.
     */
    private fun onAllConnectionsClosed() {
        if (!running || !everConnected || reprobing) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log("[reconnect] gave up after $reconnectAttempts attempts — tap Connect to retry")
            ConnectionState.set(Phase.ERROR, "lost bike link")
            return
        }
        reprobing = true
        ConnectionState.set(Phase.RECONNECTING, "attempt ${reconnectAttempts + 1}")
        thread(name = "ec-reprobe", isDaemon = true) {
            try {
                while (running && liveConns.get() == 0 && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    val backoff = minOf(1000L * reconnectAttempts, 5000L)
                    log("[reconnect] link lost — re-probing (attempt $reconnectAttempts) in ${backoff}ms")
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { return@thread }
                    if (!running || liveConns.get() > 0) return@thread
                    val bi = bikeIp; val mi = myIp
                    if (bi == null || mi == null) { log("[reconnect] no cached IPs — abort"); return@thread }
                    probed = false
                    framesSent = 0   // so the first frame after reconnect re-signals STREAMING
                    sendMdnsRespond(bi, mi, network)
                    // Give the dash a moment to connect back before deciding to try again.
                    try { Thread.sleep(2500) } catch (_: InterruptedException) { return@thread }
                }
            } finally {
                reprobing = false
            }
        }
    }

    private fun readLoop(port: Int, socket: Socket) {
        val tag = ":$port"
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            // Framing is by port (consistent across every run):
            //   10922 = PXC control (16-byte CmdBaseHead); 10921/10920 = media (8-byte ReqBase).
            if (port == PORT_PXC_CTRL) {
                log("[$tag] framing=CmdBaseHead (PXC control)")
                while (running) {
                    val frame = try { PxcFrame.read(input) } catch (e: Exception) {
                        log("[$tag] frame error: ${e.message}"); return
                    } ?: run { log("[$tag] bike closed"); return }
                    try { handshake.handle(tag, frame, socket) }
                    catch (e: Exception) { log("[$tag] handler error: $e") }
                }
            } else {
                log("[$tag] framing=ReqBase (media plane) profile=${handshake.profile.name}")
                mediaLoop(tag, input, socket.getOutputStream())
            }
        } catch (e: IOException) {
            log("[$tag] read error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    // ---- Media plane: Protocol.ReqBase framing (8-byte LE header + body) ----
    // header: cmdType(s16) | cmdLen(s16) | token(i32); reply uses the same header.
    private fun mediaLoop(tag: String, input: java.io.InputStream, out: OutputStream) {
        val header = ByteArray(8)
        while (running) {
            if (!PxcFrame.readFully(input, header, 8)) { log("[$tag] media closed"); return }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmdType = b.getShort(0).toInt()
            val cmdLen = b.getShort(2).toInt() and 0xFFFF
            val token = b.getInt(4)
            val body = ByteArray(cmdLen)
            if (cmdLen > 0 && !PxcFrame.readFully(input, body, cmdLen)) { log("[$tag] media body short"); return }
            handleMediaReq(tag, cmdType, token, body, out)
        }
    }

    /** Frame reply on the data socket is written RAW (not ReqBase): [size i32 LE][access unit].
     *  Inferred from the partial-decompiled MediaProjectServerDataExecuteThread.reply*Data(). */
    private fun sendFrameRaw(out: OutputStream, frame: ByteArray) {
        val sz = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, frame.size).array()
        synchronized(out) {
            out.write(sz)
            out.write(frame)
            out.flush()
        }
    }

    private fun sendReqBase(out: OutputStream, cmdType: Int, body: ByteArray?) {
        val len = body?.size ?: 0
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(0, cmdType.toShort())
        h.putShort(2, len.toShort())
        h.putInt(4, 0)
        synchronized(out) {
            out.write(h.array())
            if (body != null && body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun handleMediaReq(tag: String, cmdType: Int, token: Int, body: ByteArray, out: OutputStream) {
        when (cmdType) {
            16 -> { // REQ_RV_CONFIG_CAPTURE
                val cfg = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val w = if (body.size >= 2) cfg.getShort(0).toInt() and 0xFFFF else 0
                val h = if (body.size >= 4) cfg.getShort(2).toInt() and 0xFFFF else 0
                val fps = if (body.size >= 8) cfg.getInt(4) else 0
                val wantEncoder = if (body.size >= 12) cfg.getInt(8) else 2
                val supportExtend = if (body.size >= 30) body[29] else 0
                log("[$tag] REQ_CONFIG_CAPTURE w=$w h=$h fps=$fps wantEncoder=$wantEncoder ext=$supportExtend len=${body.size}")
                // Learn this dash's shape so an unknown bike auto-picks the right AA orientation next time.
                if (w > 0 && h > 0) {
                    val ssid = BikeMemory.lastQr(context)?.ssid
                    DashMemory.observe(context, ssid, w, h)
                    val dashPortrait = h > w
                    val aaPortrait = BikeProfileHolder.aaVideo.height > BikeProfileHolder.aaVideo.width
                    if (dashPortrait != aaPortrait) {
                        log("[$tag] dash canvas is ${if (dashPortrait) "portrait" else "landscape"} (${w}x$h) " +
                            "but AA is ${if (aaPortrait) "portrait" else "landscape"} — reconnect once to auto-apply the right orientation")
                    }
                }
                val (rw, rh) = handshake.profile.roundCaptureDimensions(w, h)
                negW = rw
                negH = rh
                // If Android Auto is the source (shared pipeline), size its encoder + letterbox
                // compositor to this bike canvas now — before the bike starts pulling frames (112/114).
                AaVideoBridge.pipeline?.configureBikeCanvas(negW, negH)
                // RLY_RV_CONFIG_CAPTURE (17): encoder(i32) | width&~15(s16) | height&~15(s16) | ext(byte)
                val rly = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                rly.putInt(0, if (wantEncoder == 0) 2 else wantEncoder)
                rly.putShort(4, negW.toShort())
                rly.putShort(6, negH.toShort())
                rly.put(8, supportExtend)
                log("[$tag] → RLY_CONFIG_CAPTURE(17) encoder=${if (wantEncoder==0) 2 else wantEncoder} w=$negW h=$negH")
                sendReqBase(out, 17, rly.array())
            }
            48 -> { // REQ_GET_VERSION → 49 (two LE ints: version, subVersion=1) per ctrl thread
                log("[$tag] REQ_GET_VERSION → RLY 49")
                val v = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                v.putInt(0, 3); v.putInt(4, 1)
                sendReqBase(out, 49, v.array())
            }
            64 -> { // REQ_HEARTBEAT → 65
                sendReqBase(out, 65, null)
            }
            96 -> { // REQ_CONFIGCAPTUREREXTEND → 97 (JSON). Send state OK.
                log("[$tag] REQ_CONFIGCAPTUREREXTEND len=${body.size} ${String(body, Charsets.UTF_8).take(120)} → RLY 97")
                sendReqBase(out, 97, "{\"state\":0}".toByteArray(Charsets.UTF_8))
            }
            128 -> { // REQ_START_H264 → 129 (then bike expects frames on data socket)
                log("[$tag] *** REQ_START_H264 *** len=${body.size} → RLY 129 (no encoder yet — video stage TODO)")
                sendReqBase(out, 129, null)
            }
            112 -> { // REQ_RV_DATA_START → start encoder, then RLY_RV_DATA_START(113)
                if (video == null) {
                    val shared = AaVideoBridge.pipeline
                    if (shared != null) {
                        // Android Auto is running: pull encoded frames from its (already started)
                        // pipeline instead of creating our own Presentation/mirror source.
                        video = shared
                        ownsVideo = false
                        log("[$tag] REQ_RV_DATA_START(112): using shared Android Auto video pipeline")
                    } else {
                        log("[$tag] REQ_RV_DATA_START(112): starting video ${negW}x${negH}")
                        video = VideoPipeline(context, negW, negH, log).also { it.start() }
                        ownsVideo = true
                    }
                }
                // Ensure the first frame the bike pulls is a keyframe (SPS+PPS+IDR). Critical for the
                // Android Auto path, whose encoder has been running since REQ_CONFIG_CAPTURE — its
                // initial IDR is already gone from the queue, so without this the dash starts mid-GOP
                // on a P-frame and stays black. See VideoPipeline.onBikeDataStart().
                video?.onBikeDataStart()
                log("[$tag] → RLY 113")
                sendReqBase(out, 113, null)
            }
            114 -> { // REQ_RV_DATA_NEXT — bike pulling a frame (data socket); send one access unit raw
                val frame = video?.pollFrame(1500)
                if (frame == null) {
                    log("[$tag] REQ_RV_DATA_NEXT(114): no frame ready")
                } else {
                    sendFrameRaw(out, frame)
                    framesSent++
                    lastFrameAt = System.currentTimeMillis()
                    if (framesSent == 1) ConnectionState.set(Phase.STREAMING)
                    if (framesSent <= 5 || framesSent % 60 == 0)
                        log("[$tag] sent frame #$framesSent (${frame.size}b)")
                }
            }
            32 -> handleTouch(tag, body)
            else -> {
                val preview = BleProtocol.bytesToHex(body.copyOf(minOf(32, body.size)))
                log("[$tag] media cmdType=$cmdType len=${body.size} $preview")
            }
        }
    }

    /**
     * Dash touchscreen event (PXC media cmdType 32, 18-byte body, little-endian):
     *   action u16 @0 (2=DOWN, 3=MOVE, 1=UP) | x u16 @2 | y u16 @4 | pointerId u16 @6 | timestamp u32 @8 | …
     * Coordinates are in the bike canvas we negotiated ([negW]x[negH]). The CFDL26 dash reports
     * genuine two-finger multi-touch: the byte at offset 6 is the finger index (0/1). NOTE: Y is a
     * 16-bit field — reading it as u32 (the old bug) folded the pointerId into the high bits, so the
     * second finger got a bogus Y (~65 800), landed outside the canvas, and was silently dropped.
     * Forward both pointers to the Android Auto session via [AaVideoBridge.touchSink], which
     * letterbox-maps them into AA video space and sends them over the AAP INPUT channel as
     * multi-touch (enabling pinch-to-zoom). Actions are normalised to AaInput's 0=DOWN/1=UP/2=MOVE.
     */
    private fun handleTouch(tag: String, body: ByteArray) {
        if (body.size < 8) { log("[$tag] touch frame too short (${body.size}b)"); return }
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val rawAction = b.getShort(0).toInt() and 0xFFFF
        val x = b.getShort(2).toInt() and 0xFFFF
        val y = b.getShort(4).toInt() and 0xFFFF
        val pointerId = b.getShort(6).toInt() and 0xFFFF
        val action = when (rawAction) {
            2 -> 0   // DOWN
            1 -> 1   // UP
            3 -> 2   // MOVE
            else -> { log("[$tag] touch: unknown action=$rawAction x=$x y=$y"); return }
        }
        // Log DOWN/UP (and the first MOVE) so the coordinate mapping is verifiable without move spam.
        if (action != 2 || (touchMoves++ % 30) == 0) {
            log("[$tag] TOUCH ${if (action==0) "DOWN" else if (action==1) "UP" else "MOVE"} bike=($x,$y) p$pointerId canvas=${negW}x$negH")
        }
        val sink = AaVideoBridge.touchSink
        if (sink == null) {
            if (action != 2) log("[$tag] touch dropped — no AA session")
        } else {
            sink(action, pointerId, x, y)
        }
    }

    private fun resolveGateway(network: Network?): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val target = network ?: cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(target) ?: return null

        // 1. Explicit default-route gateway. Present on most phones — the DHCP server on the
        //    bike's Wi-Fi Direct group advertises itself as the router.
        for (r in lp.routes) {
            if (r.isDefaultRoute) {
                val gw = r.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) return gw
            }
        }
        // 2. Wi-Fi Direct fallback. Some phones (seen on Samsung / Android 16) install NO default
        //    route for the P2P group — only the on-link 192.168.49.0/24 subnet with a 0.0.0.0
        //    gateway. But the group owner (the bike) is always the ".1" host of our own /24 by
        //    Android's Wi-Fi Direct convention, so derive it from our address. Additive: only runs
        //    when step 1 found nothing (which would otherwise abort the whole link).
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                gatewayForSubnet(a, la.prefixLength)?.let {
                    log("no default route — assuming Wi-Fi Direct group owner ${it.hostAddress}")
                    return it
                }
            }
        }
        // 3. Last resort.
        return lp.dnsServers.filterIsInstance<Inet4Address>().firstOrNull()
    }

    /** The ".1" host of [addr]'s subnet (network address | 1) — the Wi-Fi Direct group-owner IP. */
    private fun gatewayForSubnet(addr: Inet4Address, prefix: Int): Inet4Address? {
        if (prefix !in 1..31) return null
        return try {
            val b = addr.address
            val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            val mask = -1 shl (32 - prefix)
            val gw = (ip and mask) or 1
            if (gw == ip) return null // we already are ".1"; there's no distinct gateway to reach
            val out = byteArrayOf(
                ((gw ushr 24) and 0xFF).toByte(),
                ((gw ushr 16) and 0xFF).toByte(),
                ((gw ushr 8) and 0xFF).toByte(),
                (gw and 0xFF).toByte(),
            )
            Inet4Address.getByAddress(out) as? Inet4Address
        } catch (_: Exception) {
            null
        }
    }

    private fun pickBikeInterfaceIp(network: Network?): Inet4Address? {
        if (network != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(network)?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.let { return it }
        }
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
            }
        }
        return null
    }

    private fun acquireMulticastLock() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("opencfmoto").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun startHeartbeatLog() {
        heartbeatThread = thread(name = "ec-hb", isDaemon = true) {
            var i = 0
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                i++
                log("hb#$i probed=$probed video=${video != null} framesSent=$framesSent openServers=${servers.count { !it.isClosed }}")
            }
        }
    }

    private fun dumpEnvironment(network: Network?) {
        log("---- environment ----")
        log("Build: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (network != null) {
            val lp = cm.getLinkProperties(network)
            log("linkProps iface=${lp?.interfaceName} addrs=${lp?.linkAddresses} routes=${lp?.routes}")
        }
        log("---------------------")
    }
}
