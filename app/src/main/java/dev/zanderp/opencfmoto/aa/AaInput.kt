// OpenCfMoto (uses AGPLv3 code/protocol ported from headunit-revived). Injects touch input into the
// Android Auto session: the CFMoto dash is a touchscreen and reports touches over PXC (see
// EasyConnProber cmdType 32); this forwards them to Gearhead over the AAP INPUT channel so Maps/Waze
// can be driven from the bike.
package dev.zanderp.opencfmoto.aa

import android.os.SystemClock
import dev.zanderp.opencfmoto.aa.proto.Input

/**
 * Sends touch events to Android Auto over the INPUT channel (declared as a touchscreen in
 * [ServiceDiscoveryResponse], sized to the AA video). Coordinates must already be in AA video space
 * (0..width, 0..height) — the caller letterbox-maps from the bike canvas first.
 *
 * The CFDL26 dash reports two-finger multi-touch (see [dev.zanderp.opencfmoto.EasyConnProber]), so
 * this tracks the set of currently-down pointers and emits the AAP multi-touch protocol: every event
 * carries all active pointers, with [Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN]/
 * `_POINTER_UP` for the 2nd+ finger and an `actionIndex` naming which pointer changed. That lets
 * Google Maps recognise pinch-to-zoom. Single-finger use is unchanged (plain DOWN/MOVE/UP).
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

    /** Insertion-ordered set of down pointers → last known AA-space position. */
    private val pointers = LinkedHashMap<Int, Pair<Int, Int>>()

    /**
     * @param action    one of [ACTION_DOWN]/[ACTION_UP]/[ACTION_MOVE]
     * @param pointerId finger index reported by the dash (0/1)
     * @param x,y       pointer position in AA video coordinates
     */
    @Synchronized
    fun sendTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val pointerAction = when (action) {
            ACTION_DOWN -> {
                val first = pointers.isEmpty()
                pointers[pointerId] = x to y
                if (first) Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
            }
            ACTION_MOVE -> {
                // A MOVE for an untracked pointer promotes it to down (defensive against a lost DOWN).
                val promote = !pointers.containsKey(pointerId)
                pointers[pointerId] = x to y
                if (promote) {
                    if (pointers.size == 1) Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                    else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
                } else {
                    Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
                }
            }
            ACTION_UP -> {
                if (!pointers.containsKey(pointerId)) return
                pointers[pointerId] = x to y
                if (pointers.size == 1) Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
            }
            else -> return
        }

        val actionIndex = pointers.keys.indexOf(pointerId).coerceAtLeast(0)
        try {
            val touch = Input.TouchEvent.newBuilder()
            for ((id, pos) in pointers) {
                touch.addPointerData(
                    Input.TouchEvent.Pointer.newBuilder()
                        .setX(pos.first).setY(pos.second).setPointerId(id).build()
                )
            }
            touch.setActionIndex(actionIndex).setAction(pointerAction)
            val report = Input.InputReport.newBuilder()
                // AAP input timestamps are a monotonic microsecond clock.
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setTouchEvent(touch.build())
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        } catch (e: Exception) {
            log("[AA] sendTouch failed: $e")
        } finally {
            // Remove a lifted finger only after it has been reported in this (POINTER_)UP event.
            if (action == ACTION_UP) pointers.remove(pointerId)
        }
    }
}
