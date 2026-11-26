package com.chargeguard.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargeguard.core.common.Formatting
import com.chargeguard.domain.model.ChargeStatus
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.ui.components.CgCard
import com.chargeguard.ui.components.IconBadge
import com.chargeguard.ui.components.PinDialog
import com.chargeguard.ui.components.SettingRow
import com.chargeguard.ui.components.ShieldLogo
import com.chargeguard.ui.components.StatusDot
import com.chargeguard.ui.theme.CgColors
import com.chargeguard.ui.theme.LocalCgPalette

@Composable
fun HomeRoute(
    onOpenAlarmSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPermissions: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val pinError by vm.pinError.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    var showNotifRationale by rememberSaveable { mutableStateOf(false) }
    var showPinPrompt by rememberSaveable { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refresh()
        vm.activate()
    }

    val activate = {
        val p = state.permissions
        if (p.notificationPermissionApplies && !p.notificationsGranted) showNotifRationale = true else vm.activate()
    }

    HomeScreen(
        state = state,
        onActivate = activate,
        onDeactivate = { if (vm.requestDeactivate()) showPinPrompt = true },
        onOpenAlarmSettings = onOpenAlarmSettings,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory,
        onOpenPermissions = onOpenPermissions,
    )

    if (showNotifRationale) {
        AlertDialog(
            onDismissRequest = { showNotifRationale = false },
            containerColor = LocalCgPalette.current.elevated,
            title = { Text("Allow notifications?") },
            text = {
                Text(
                    "ChargeGuard uses notifications to show that monitoring is running and to display the alarm " +
                        "over the lock screen. Without them the siren and vibration still work, but you won't see " +
                        "the alarm notification.",
                    color = CgColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotifRationale = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else vm.activate()
                }) { Text("Allow", color = CgColors.Green) }
            },
            dismissButton = {
                TextButton(onClick = { showNotifRationale = false; vm.activate() }) {
                    Text("Activate without", color = CgColors.TextSecondary)
                }
            },
        )
    }

    if (showPinPrompt) {
        PinDialog(
            title = "Turn off protection",
            message = "Enter your owner PIN to turn off charger protection.",
            confirmLabel = "Turn off",
            error = pinError,
            onConfirm = { pin -> if (vm.deactivateWithPin(pin)) showPinPrompt = false },
            onDismiss = { showPinPrompt = false; vm.clearPinError() },
        )
    }
}

private data class StateLook(val color: Color, val label: String, val title: String, val body: String, val action: String)

private fun look(state: HomeUiState): StateLook {
    val s = state.status
    return when (s.state) {
        ProtectionState.INACTIVE -> StateLook(
            CgColors.TextSecondary, "PROTECTION OFF", "Not protected",
            "Connect your charger, then activate protection.", "Tap to activate",
        )
        ProtectionState.WAITING_FOR_CHARGER -> StateLook(
            CgColors.Amber, "WAITING FOR CHARGER", "Connect your charger",
            "Protection arms automatically as soon as external power is detected.", "Tap to turn off",
        )
        ProtectionState.ARMED -> StateLook(
            CgColors.Green, if (s.disconnectPending) "CHECKING CONNECTION" else "PROTECTION ACTIVE", "Protected",
            "If the charger is disconnected, the alarm sounds until power is reconnected. Lock your phone now.",
            "Tap to turn off",
        )
        ProtectionState.ALARM_ACTIVE -> StateLook(
            CgColors.Red, "ALARM TRIGGERED", "Charger disconnected",
            "Reconnect external power to stop the alarm.", "Alarm active",
        )
        ProtectionState.RECONNECTED -> StateLook(
            CgColors.Green, "CHARGER RECONNECTED", "Alarm stopped",
            "Re-arming protection…", "Re-arming",
        )
        ProtectionState.MONITORING_UNAVAILABLE -> StateLook(
            CgColors.Red, "MONITORING UNAVAILABLE", "Not protected",
            s.unavailableReason ?: "Monitoring is not running.", "Tap to turn on again",
        )
    }
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onOpenAlarmSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val palette = LocalCgPalette.current
    val l = look(state)
    val ps = state.status.state

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShieldLogo(28.dp)
            Spacer(Modifier.width(10.dp))
            Text("ChargeGuard", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenHistory) { Icon(Icons.Rounded.History, "Activity history", tint = CgColors.TextSecondary) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = CgColors.TextSecondary) }
        }

        // Status hero
        CgCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 28.dp, horizontal = 20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(l.color.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(l.color)
                    Spacer(Modifier.width(8.dp))
                    Text(l.label, style = MaterialTheme.typography.labelMedium, color = l.color)
                }
                Spacer(Modifier.height(24.dp))
                ProtectionToggle(
                    state = ps,
                    color = l.color,
                    contentDescription = l.action,
                    onClick = {
                        when (ps) {
                            ProtectionState.INACTIVE, ProtectionState.MONITORING_UNAVAILABLE -> onActivate()
                            ProtectionState.WAITING_FOR_CHARGER, ProtectionState.ARMED, ProtectionState.RECONNECTED -> onDeactivate()
                            ProtectionState.ALARM_ACTIVE -> Unit // no ordinary dismiss
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(l.action, style = MaterialTheme.typography.labelMedium, color = CgColors.TextSecondary)
                Spacer(Modifier.height(18.dp))
                Text(l.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(l.body, style = MaterialTheme.typography.bodyMedium, color = CgColors.TextSecondary, textAlign = TextAlign.Center)
                val started = state.status.sessionStartedAtMillis
                if (started != null && ps.isMonitoring) {
                    Spacer(Modifier.height(16.dp))
                    Text("Session ${Formatting.duration(state.now - started)}", style = MaterialTheme.typography.titleMedium, color = CgColors.TextPrimary)
                }
                if (ps == ProtectionState.MONITORING_UNAVAILABLE) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDeactivate) { Text("Turn protection off", color = CgColors.TextSecondary) }
                }
            }
        }

        // Power + battery
        val c = state.charging
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = if (c?.isPowerConnected == true) Icons.Rounded.Power else Icons.Rounded.PowerOff,
                tint = if (c?.isPowerConnected == true) CgColors.Green else CgColors.TextSecondary,
                label = "External power",
                value = when {
                    c == null -> "Unknown"
                    c.isPowerConnected -> "Connected"
                    else -> "Not connected"
                },
                caption = c?.let { if (it.isPowerConnected) Formatting.source(it.source) else "On battery" } ?: "–",
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = if (c?.chargeStatus == ChargeStatus.CHARGING) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                tint = CgColors.TextPrimary,
                label = "Battery",
                value = c?.batteryPercent?.let { "$it%" } ?: "–",
                caption = c?.let { Formatting.chargeStatus(it.chargeStatus) } ?: "–",
            )
        }

        // Warnings that weaken protection
        val p = state.permissions
        if (p.notificationPermissionApplies && !p.notificationsGranted) {
            WarningCard(Icons.Rounded.NotificationsOff, "Notifications are off", "The siren still works, but the alarm notification and lock-screen alarm can't be shown.", onOpenPermissions)
        }
        if (p.notificationsGranted && p.fullScreenPermissionApplies && !p.fullScreenAllowed) {
            WarningCard(Icons.Rounded.Fullscreen, "Full-screen alarm not allowed", "Android shows the alarm as a heads-up notification instead. You can allow full-screen alarms in settings.", onOpenPermissions)
        }
        if (!p.ignoringBatteryOptimizations) {
            WarningCard(Icons.Rounded.BatterySaver, "Battery optimization is on", "Some phones stop background apps aggressively. Exempting ChargeGuard makes monitoring more reliable.", onOpenPermissions, color = CgColors.Amber)
        }

        // Shortcuts
        CgCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            SettingRow(Icons.Rounded.Tune, "Alarm settings", "Sound, volume, vibration, timing", tint = CgColors.Red, onClick = onOpenAlarmSettings)
            SettingRow(Icons.Rounded.History, "Activity history", "Protection and alarm events", tint = CgColors.Green, onClick = onOpenHistory)
            SettingRow(Icons.Rounded.Settings, "Settings", "Theme, permissions, PIN, about", onClick = onOpenSettings)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProtectionToggle(state: ProtectionState, color: Color, contentDescription: String, onClick: () -> Unit) {
    val animatedColor by animateColorAsState(color, tween(400), label = "toggleColor")
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "pulseScale",
    )
    val active = state == ProtectionState.ARMED || state == ProtectionState.ALARM_ACTIVE
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(188.dp)) {
        Box(
            Modifier
                .size(176.dp)
                .scale(if (active) pulse else 1f)
                .clip(CircleShape)
                .background(animatedColor.copy(alpha = 0.07f))
        )
        Box(
            Modifier
                .size(148.dp)
                .clip(CircleShape)
                .background(LocalCgPalette.current.elevated)
                .border(3.dp, animatedColor.copy(alpha = if (state == ProtectionState.INACTIVE) 0.35f else 0.9f), CircleShape)
                .clickable(enabled = state != ProtectionState.ALARM_ACTIVE, role = Role.Switch, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state == ProtectionState.MONITORING_UNAVAILABLE) Icons.Rounded.ErrorOutline else Icons.Rounded.Shield,
                contentDescription = null,
                tint = animatedColor,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, icon: ImageVector, tint: Color, label: String, value: String, caption: String) {
    CgCard(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        IconBadge(icon, tint, size = 36.dp)
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = CgColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun WarningCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit, color: Color = CgColors.Red) {
    CgCard(Modifier.fillMaxWidth(), border = color.copy(alpha = 0.35f), onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(icon, color, size = 36.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary)
            }
        }
    }
}
