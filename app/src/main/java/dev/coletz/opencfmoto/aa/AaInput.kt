// OpenCfMoto (uses AGPLv3 code/protocol ported from headunit-revived). Injects touch input into the
// Android Auto session: the CFMoto dash is a touchscreen and reports touches over PXC (see
// EasyConnProber cmdType 32); this forwards them to Gearhead over the AAP INPUT channel so Maps/Waze
// can be driven from the bike.
package dev.coletz.opencfmoto.aa

import android.os.SystemClock
import dev.coletz.opencfmoto.aa.proto.Input

/**
 * Sends touch events to Android Auto over the INPUT channel (declared as a touchscreen in
 * [ServiceDiscoveryResponse], sized to the AA video). Coordinates must already be in AA video space
 * (0..width, 0..height) — the caller letterbox-maps from the bike canvas first.
 */
class AaInput(
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    /** Normalised actions from the bike decoder. */
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }

    /**
     * @param action one of [ACTION_DOWN]/[ACTION_UP]/[ACTION_MOVE]
     * @param x,y    pointer position in AA video coordinates
     */
    fun sendTouch(action: Int, x: Int, y: Int) {
        val pointerAction = when (action) {
            ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
            ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
            ACTION_MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
            else -> return
        }
        try {
            val touch = Input.TouchEvent.newBuilder()
                .addPointerData(
                    Input.TouchEvent.Pointer.newBuilder()
                        .setX(x).setY(y).setPointerId(0).build()
                )
                .setActionIndex(0)
                .setAction(pointerAction)
                .build()
            val report = Input.InputReport.newBuilder()
                // AAP input timestamps are a monotonic microsecond clock.
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setTouchEvent(touch)
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        } catch (e: Exception) {
            log("[AA] sendTouch failed: $e")
        }
    }
}
