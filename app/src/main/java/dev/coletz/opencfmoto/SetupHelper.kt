package dev.coletz.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Readiness checks for the guided setup ([SetupActivity]).
 *
 * Some prerequisites are programmatically detectable (is Android Auto installed, are runtime
 * permissions granted); others are not (Android Auto "developer mode" + unknown head-unit
 * projection is buried in Gearhead's own settings with no public API), so for those we can only
 * deep-link the user to the right screen and explain what to toggle.
 */
object SetupHelper {
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

    fun isAndroidAutoInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(GEARHEAD_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    /** Runtime permissions the projection flow needs. Camera + location are required; notifications
     *  gate the foreground-service notification on Android 13+. Bluetooth is intentionally omitted
     *  (the BLE wake-up path is dormant). */
    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingPermissions(ctx: Context): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }

    fun permissionsGranted(ctx: Context): Boolean = missingPermissions(ctx).isEmpty()

    /** Everything we can verify is in place. Head-unit mode can't be verified, so it isn't gated. */
    fun coreReady(ctx: Context): Boolean =
        isAndroidAutoInstalled(ctx) && permissionsGranted(ctx)
}
