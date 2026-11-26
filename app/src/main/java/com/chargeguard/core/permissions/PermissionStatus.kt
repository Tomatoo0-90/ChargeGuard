package com.chargeguard.core.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.chargeguard.core.notifications.ProtectionNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PermissionSnapshot(
    val notificationsGranted: Boolean = true,
    val notificationPermissionApplies: Boolean = false,
    val fullScreenAllowed: Boolean = true,
    val fullScreenPermissionApplies: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
)

/** Reads permission state (refreshed on every resume) and opens the right system screens. */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: ProtectionNotifications,
) {
    fun snapshot(): PermissionSnapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return PermissionSnapshot(
            notificationsGranted = notifications.canPostNotifications(),
            notificationPermissionApplies = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            fullScreenAllowed = notifications.canUseFullScreenIntent(),
            fullScreenPermissionApplies = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun fullScreenSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())
        } else appNotificationSettingsIntent()

    /**
     * Opens the system battery-optimization list. ChargeGuard does not use
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; the user decides in system settings.
     */
    fun batteryOptimizationSettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    /** Launches [intent], falling back to the app details screen if the device lacks it. */
    fun launch(activityContext: Context, intent: Intent) {
        try {
            activityContext.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { activityContext.startActivity(appDetailsIntent()) }
        } catch (_: SecurityException) {
            runCatching { activityContext.startActivity(appDetailsIntent()) }
        }
    }
}
