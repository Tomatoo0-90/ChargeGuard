package com.chargeguard.ui.settings

import android.Manifest
import android.os.Build
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargeguard.BuildConfig
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.ui.components.CgCard
import com.chargeguard.ui.components.OptionChip
import com.chargeguard.ui.components.SectionLabel
import com.chargeguard.ui.components.SettingRow
import com.chargeguard.ui.components.SubScreen
import com.chargeguard.ui.components.SwitchRow
import com.chargeguard.ui.theme.CgColors
import com.chargeguard.ui.theme.LocalCgPalette

private enum class PinMode { SET, CHANGE, REMOVE }

@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenAlarmSettings: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    var pinMode by rememberSaveable { mutableStateOf<PinMode?>(null) }
    var infoDialog by rememberSaveable { mutableStateOf<String?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refresh()
        if (!granted) vm.checker.launch(context, vm.checker.appNotificationSettingsIntent())
    }
    val p = state.permissions

    SubScreen(title = "Settings", onBack = onBack) {
        SectionLabel("Appearance")
        CgCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Rounded.Palette, null, tint = CgColors.TextSecondary)
                Text("  Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionChip("Dark", state.settings.theme == AppTheme.DARK) { vm.setTheme(AppTheme.DARK) }
                    OptionChip("AMOLED", state.settings.theme == AppTheme.AMOLED) { vm.setTheme(AppTheme.AMOLED) }
                }
            }
        }

        SectionLabel("Alarm")
        CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            SettingRow(Icons.Rounded.Tune, "Sound and vibration", "Alarm sound, volume, vibration, duration", tint = CgColors.Red, onClick = onOpenAlarmSettings)
            SwitchRow(
                Icons.Rounded.Notifications, "Battery level in notification", "Show battery and power source in the monitoring notification",
                state.settings.showBatteryInNotification, onCheckedChange = vm::setShowBattery,
            )
        }

        SectionLabel("Security")
        CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            if (state.pinSet) {
                SettingRow(Icons.Rounded.Password, "Owner PIN is set", "Required to turn protection off and to stop an alarm without the charger", tint = CgColors.Green) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = CgColors.Green)
                }
                SettingRow(Icons.Rounded.Password, "Change PIN", null, onClick = { pinMode = PinMode.CHANGE })
                SettingRow(Icons.Rounded.Password, "Remove PIN", null, tint = CgColors.Red, onClick = { pinMode = PinMode.REMOVE })
            } else {
                SettingRow(Icons.Rounded.Password, "Set an owner PIN", "Optional. Stops someone with your unlocked phone from turning protection off", onClick = { pinMode = PinMode.SET })
            }
            Text(
                "A PIN can't stop Android's power button, a system shutdown or Force stop in system settings.",
                style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 2.dp),
            )
        }

        SectionLabel("Permissions")
        CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            if (p.notificationPermissionApplies) {
                PermissionRow(Icons.Rounded.Notifications, "Notifications", p.notificationsGranted,
                    if (p.notificationsGranted) "Allowed" else "Not allowed: the alarm notification can't be shown") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else vm.checker.launch(context, vm.checker.appNotificationSettingsIntent())
                }
            } else {
                PermissionRow(Icons.Rounded.Notifications, "Notifications", p.notificationsGranted, if (p.notificationsGranted) "Allowed" else "Blocked") {
                    vm.checker.launch(context, vm.checker.appNotificationSettingsIntent())
                }
            }
            PermissionRow(Icons.Rounded.Fullscreen, "Full-screen alarm", p.fullScreenAllowed,
                if (p.fullScreenAllowed) "Allowed" else "Not allowed: a heads-up notification is shown instead") {
                vm.checker.launch(context, vm.checker.fullScreenSettingsIntent())
            }
            PermissionRow(Icons.Rounded.BatterySaver, "Battery optimization", p.ignoringBatteryOptimizations,
                if (p.ignoringBatteryOptimizations) "ChargeGuard is exempt" else "Optimized: Android may restrict the app") {
                vm.checker.launch(context, vm.checker.batteryOptimizationSettingsIntent())
            }
        }

        SectionLabel("Reliability")
        CgCard(Modifier.fillMaxWidth()) {
            Text("Battery optimization guidance", style = MaterialTheme.typography.titleSmall)
            Text(
                "Monitoring runs as a foreground service, which Android normally keeps alive. Some manufacturers " +
                    "(for example Xiaomi, Oppo, Vivo, Realme, OnePlus, Huawei, Samsung) add extra battery savers that can still stop it.\n\n" +
                    "• Set ChargeGuard to \"Unrestricted\" / \"Don't optimize\" in battery settings.\n" +
                    "• On Xiaomi/Redmi/POCO also enable Autostart and lock the app in recents.\n" +
                    "• On Samsung remove it from \"Sleeping apps\".\n\n" +
                    "If monitoring is ever stopped, ChargeGuard shows \"Monitoring unavailable\" and never claims you are protected.",
                style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary, modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com".toUri())) }
            }) { Text("Device-specific steps (dontkillmyapp.com)", color = CgColors.Green) }
        }

        SectionLabel("About")
        CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            SettingRow(Icons.Rounded.Info, "About ChargeGuard", "Version ${BuildConfig.VERSION_NAME}", onClick = { infoDialog = "about" })
            SettingRow(Icons.Rounded.PrivacyTip, "Privacy", "What is stored and what isn't", onClick = { infoDialog = "privacy" })
        }
    }

    when (pinMode) {
        null -> Unit
        PinMode.SET, PinMode.CHANGE -> PinSetupDialog(
            requireCurrent = pinMode == PinMode.CHANGE,
            onSubmit = { new, confirm, current -> vm.setPin(new, confirm, current) },
            onDone = { pinMode = null },
        )
        PinMode.REMOVE -> PinSetupDialog(
            requireCurrent = true, removeOnly = true,
            onSubmit = { _, _, current -> vm.removePin(current.orEmpty()) },
            onDone = { pinMode = null },
        )
    }

    infoDialog?.let { which ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            containerColor = LocalCgPalette.current.elevated,
            title = { Text(if (which == "about") "About ChargeGuard" else "Privacy") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(if (which == "about") ABOUT_TEXT else PRIVACY_TEXT, color = CgColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { infoDialog = null }) { Text("Close", color = CgColors.Green) } },
        )
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, ok: Boolean, subtitle: String, onFix: () -> Unit) {
    SettingRow(icon, title, subtitle, tint = if (ok) CgColors.Green else CgColors.Amber, onClick = if (ok) null else onFix) {
        if (ok) Icon(Icons.Rounded.CheckCircle, null, tint = CgColors.Green)
        else Text("Fix", color = CgColors.Amber, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PinSetupDialog(
    requireCurrent: Boolean,
    removeOnly: Boolean = false,
    onSubmit: (new: String, confirm: String, current: String?) -> String?,
    onDone: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = (!requireCurrent || current.length >= 4) && (removeOnly || (new.length >= 4 && confirm.length >= 4))

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = LocalCgPalette.current.elevated,
        title = { Text(if (removeOnly) "Remove PIN" else if (requireCurrent) "Change PIN" else "Set owner PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!removeOnly) {
                    Text(
                        "4–8 digits. The PIN is never stored: only a salted, Keystore-protected verifier. After 5 wrong attempts entry is temporarily locked.",
                        style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary,
                    )
                }
                if (requireCurrent) PinField("Current PIN", current) { current = it }
                if (!removeOnly) {
                    PinField("New PIN", new) { new = it }
                    PinField("Confirm new PIN", confirm) { confirm = it }
                }
                error?.let { Text(it, color = CgColors.Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val result = onSubmit(new, confirm, if (requireCurrent) current else null)
                if (result == null) onDone() else error = result
            }) { Text(if (removeOnly) "Remove" else "Save", color = if (removeOnly) CgColors.Red else CgColors.Green) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = CgColors.TextSecondary) } },
    )
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 8 && v.all(Char::isDigit)) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

private const val ABOUT_TEXT =
    "ChargeGuard sounds an alarm if your phone is unplugged from its charger while protection is on.\n\n" +
        "How it decides: Android reports whether a power source (AC, USB, wireless or dock) is connected. " +
        "ChargeGuard alarms only when that changes from connected to not connected while armed, after a short, configurable " +
        "confirmation window. A full battery, or a phone that pauses charging at 80–85%, is still \"connected\" and does not alarm.\n\n" +
        "Limits: ChargeGuard cannot prevent the phone from being switched off with the power button, cannot keep running after " +
        "Force stop or a reboot, and may be stopped by aggressive manufacturer battery savers. In all of these cases the app " +
        "shows that you are not protected when you next open it, and after a reboot it posts a notification."

private const val PRIVACY_TEXT =
    "ChargeGuard works completely offline.\n\n" +
        "• No account, no backend, no ads, no analytics.\n" +
        "• No location, contacts, camera or microphone access.\n" +
        "• Stored on this device only: your settings, a local activity history (up to 500 events, clearable), and, if you set one, " +
        "a salted HMAC verifier of your PIN whose key lives in Android Keystore. The PIN itself is never stored.\n" +
        "• App data is excluded from cloud backup.\n\n" +
        "Permissions: notifications (status + alarm), foreground service (monitoring), full-screen intent (alarm over lock screen), " +
        "vibrate, wake lock (so a sleeping CPU can't delay the alarm), and boot completed (to tell you protection is off after a restart)."
