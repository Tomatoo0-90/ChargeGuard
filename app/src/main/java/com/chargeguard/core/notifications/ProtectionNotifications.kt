package com.chargeguard.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chargeguard.MainActivity
import com.chargeguard.R
import com.chargeguard.domain.model.ChargeStatus
import com.chargeguard.domain.model.PowerSource
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.model.ProtectionStatus
import com.chargeguard.service.ProtectionService
import com.chargeguard.ui.alarm.AlarmActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProtectionNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val monitoring = NotificationChannel(
            CHANNEL_MONITORING, context.getString(R.string.channel_monitoring), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_monitoring_desc)
            setShowBadge(false)
        }
        val alarm = NotificationChannel(
            CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            // The siren and vibration are produced by the app on the ALARM stream; a channel
            // sound would only duplicate (or be blocked by) notification volume.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val status = NotificationChannel(
            CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.channel_status_desc) }
        nm.createNotificationChannels(listOf(monitoring, alarm, status))
    }

    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun monitoringNotification(status: ProtectionStatus, showBattery: Boolean): Notification {
        val (title, text) = when (status.state) {
            ProtectionState.WAITING_FOR_CHARGER -> context.getString(R.string.notif_waiting_title) to context.getString(R.string.notif_waiting_text)
            ProtectionState.ARMED -> context.getString(R.string.notif_armed_title) to
                if (status.disconnectPending) context.getString(R.string.notif_checking_text) else context.getString(R.string.notif_armed_text)
            ProtectionState.ALARM_ACTIVE -> context.getString(R.string.notif_alarm_title) to context.getString(R.string.notif_alarm_text)
            ProtectionState.RECONNECTED -> context.getString(R.string.notif_reconnected_title) to context.getString(R.string.notif_reconnected_text)
            else -> context.getString(R.string.notif_starting_title) to context.getString(R.string.notif_starting_text)
        }
        val detail = status.charging?.takeIf { showBattery }?.let { c ->
            val pct = c.batteryPercent?.let { "$it%" } ?: "–"
            "$text · $pct · ${sourceLabel(c.source, c.chargeStatus)}"
        } ?: text
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(context, R.color.cg_green))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Deliberately no "turn off" action: it would let anyone disarm from the lock screen.
            .setContentIntent(mainActivityIntent())
            .build()
    }

    @SuppressLint("MissingPermission") // checked via canPostNotifications()
    fun updateMonitoring(status: ProtectionStatus, showBattery: Boolean) {
        if (!canPostNotifications()) return
        runCatching { manager.notify(ID_MONITORING, monitoringNotification(status, showBattery)) }
    }

    @SuppressLint("MissingPermission")
    fun showAlarm(fullScreen: Boolean, sirenSilenced: Boolean, audioFailed: Boolean) {
        if (!canPostNotifications()) return
        val text = when {
            audioFailed -> context.getString(R.string.notif_alarm_audio_failed)
            sirenSilenced -> context.getString(R.string.notif_alarm_silenced)
            else -> context.getString(R.string.notif_alarm_body)
        }
        val alarmIntent = PendingIntent.getActivity(
            context, REQ_ALARM,
            Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val repost = PendingIntent.getService(
            context, REQ_REPOST,
            Intent(context, ProtectionService::class.java).setAction(ProtectionService.ACTION_REPOST_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.notif_alarm_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(context, R.color.cg_red))
            .setColorized(true)
            // Tapping opens the alarm screen; it does NOT stop the alarm.
            .setContentIntent(alarmIntent)
            // If the user swipes it away while alarming, the service re-posts it.
            .setDeleteIntent(repost)
        if (fullScreen && canUseFullScreenIntent()) builder.setFullScreenIntent(alarmIntent, true)
        runCatching { manager.notify(ID_ALARM, builder.build()) }
    }

    fun cancelAlarm() {
        manager.cancel(ID_ALARM)
    }

    /** Honest "you are not protected" notice after reboot, update or a monitoring failure. */
    @SuppressLint("MissingPermission")
    fun showProtectionOff(reason: String) {
        if (!canPostNotifications()) return
        val n = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.notif_off_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.cg_red))
            .setContentIntent(mainActivityIntent())
            .build()
        runCatching { manager.notify(ID_STATUS, n) }
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        context, REQ_MAIN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun sourceLabel(source: PowerSource, status: ChargeStatus): String = when (source) {
        PowerSource.NONE -> "On battery"
        PowerSource.AC -> "AC charger"
        PowerSource.USB -> "USB"
        PowerSource.WIRELESS -> "Wireless"
        PowerSource.DOCK -> "Dock"
        PowerSource.UNKNOWN -> "External power"
    } + if (status == ChargeStatus.FULL) " · Full" else ""

    companion object {
        const val CHANNEL_MONITORING = "monitoring"
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_STATUS = "status"
        const val ID_MONITORING = 1001
        const val ID_ALARM = 1002
        const val ID_STATUS = 1003
        private const val REQ_MAIN = 10
        private const val REQ_ALARM = 11
        private const val REQ_REPOST = 12
    }
}
