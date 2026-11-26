package com.chargeguard.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShieldMoon
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargeguard.core.common.Formatting
import com.chargeguard.domain.model.HistoryEvent
import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.ui.components.CgCard
import com.chargeguard.ui.components.IconBadge
import com.chargeguard.ui.components.SectionLabel
import com.chargeguard.ui.components.SubScreen
import com.chargeguard.ui.theme.CgColors
import com.chargeguard.ui.theme.LocalCgPalette

@Composable
fun HistoryRoute(onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val events by vm.events.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    SubScreen(
        title = "Activity history",
        onBack = onBack,
        action = {
            if (!events.isNullOrEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear history", tint = CgColors.TextSecondary) }
            }
        },
    ) {
        val list = events
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CgColors.Green)
            }
            list.isEmpty() -> CgCard(Modifier.fillMaxWidth()) {
                IconBadge(Icons.Rounded.History, CgColors.TextSecondary)
                Spacer(Modifier.height(12.dp))
                Text("No activity yet", style = MaterialTheme.typography.titleMedium)
                Text("Protection and alarm events will appear here with local timestamps.", style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary)
            }
            else -> list.groupBy { Formatting.day(it.timestampMillis) }.forEach { (day, dayEvents) ->
                SectionLabel(day)
                CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    dayEvents.forEach { EventRow(it) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = LocalCgPalette.current.elevated,
            title = { Text("Clear history?") },
            text = { Text("All recorded events will be permanently deleted from this device.", color = CgColors.TextSecondary) },
            confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text("Clear", color = CgColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = CgColors.TextSecondary) } },
        )
    }
}

@Composable
private fun EventRow(e: HistoryEvent) {
    val (icon, tint, title) = describe(e.type)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        IconBadge(icon, tint, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary) }
        }
        Text(Formatting.time(e.timestampMillis), style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary)
    }
}

private fun describe(t: HistoryEventType): Triple<ImageVector, Color, String> = when (t) {
    HistoryEventType.PROTECTION_ACTIVATED -> Triple(Icons.Rounded.Shield, CgColors.Green, "Protection activated")
    HistoryEventType.PROTECTION_ARMED -> Triple(Icons.Rounded.Power, CgColors.Green, "Charger detected, protection armed")
    HistoryEventType.CHARGING_DISCONNECTED -> Triple(Icons.Rounded.PowerOff, CgColors.Red, "Charging disconnected")
    HistoryEventType.BRIEF_INTERRUPTION_IGNORED -> Triple(Icons.Rounded.FlashOn, CgColors.Amber, "Brief power interruption ignored")
    HistoryEventType.ALARM_STARTED -> Triple(Icons.Rounded.NotificationsActive, CgColors.Red, "Alarm started")
    HistoryEventType.ALARM_AUDIO_FAILED -> Triple(Icons.AutoMirrored.Rounded.VolumeOff, CgColors.Amber, "Alarm audio failed")
    HistoryEventType.ALARM_SOUND_TIMED_OUT -> Triple(Icons.Rounded.Timer, CgColors.Amber, "Siren time limit reached")
    HistoryEventType.CHARGER_RECONNECTED -> Triple(Icons.Rounded.Power, CgColors.Green, "Charger reconnected")
    HistoryEventType.ALARM_STOPPED -> Triple(Icons.Rounded.CheckCircle, CgColors.Green, "Alarm stopped")
    HistoryEventType.ALARM_STOPPED_BY_OWNER -> Triple(Icons.Rounded.Password, CgColors.Amber, "Alarm stopped with owner PIN")
    HistoryEventType.PROTECTION_DEACTIVATED -> Triple(Icons.Rounded.ShieldMoon, CgColors.TextSecondary, "Protection deactivated")
    HistoryEventType.MONITORING_INTERRUPTED -> Triple(Icons.Rounded.NotificationsOff, CgColors.Red, "Monitoring interrupted")
    HistoryEventType.MONITORING_RESTORED -> Triple(Icons.Rounded.Sync, CgColors.Green, "Monitoring restored")
    HistoryEventType.MONITORING_START_FAILED -> Triple(Icons.Rounded.ErrorOutline, CgColors.Red, "Monitoring failed to start")
}
