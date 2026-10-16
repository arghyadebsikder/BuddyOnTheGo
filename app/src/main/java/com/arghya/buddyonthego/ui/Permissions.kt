package com.arghya.buddyonthego.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AS
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arghya.buddyonthego.ServiceLocator

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Small helpers so every screen reports permission results the same way. */
object Perms {
    private const val ASKED_NOTIF = "asked_notif_permission"
    private const val ASKED_LOC = "asked_loc_permission"

    fun notificationsOn(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun locationOn(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** True when the phone's own Location switch is on. */
    fun locationServicesOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }

    fun markAskedNotifications() = ServiceLocator.settings.put(ASKED_NOTIF, true)
    fun markAskedLocation() = ServiceLocator.settings.put(ASKED_LOC, true)

    /** Notifications were asked for before and Android will not show the question again, so Settings is the only way. */
    fun notificationsBlocked(ctx: Context): Boolean {
        if (notificationsOn(ctx)) return false
        if (Build.VERSION.SDK_INT < 33) return true // no runtime question exists on older Android
        val act = ctx.findActivity() ?: return false
        return ServiceLocator.settings.bool(ASKED_NOTIF, false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.POST_NOTIFICATIONS)
    }

    fun locationBlocked(ctx: Context): Boolean {
        if (locationOn(ctx)) return false
        val act = ctx.findActivity() ?: return false
        return ServiceLocator.settings.bool(ASKED_LOC, false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun go(ctx: Context, i: Intent) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ctx.startActivity(
                Intent(AS.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openNotificationSettings(ctx: Context) =
        go(ctx, Intent(AS.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AS.EXTRA_APP_PACKAGE, ctx.packageName))

    fun openAppSettings(ctx: Context) =
        go(ctx, Intent(AS.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    fun openLocationSettings(ctx: Context) = go(ctx, Intent(AS.ACTION_LOCATION_SOURCE_SETTINGS))
}
