package com.chargeguard.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.AlarmSound
import com.chargeguard.ui.components.CgCard
import com.chargeguard.ui.components.IconBadge
import com.chargeguard.ui.components.OptionChip
import com.chargeguard.ui.components.SectionLabel
import com.chargeguard.ui.components.SettingRow
import com.chargeguard.ui.components.SubScreen
import com.chargeguard.ui.components.SwitchRow
import com.chargeguard.ui.theme.CgColors
import androidx.compose.foundation.layout.Column

@Composable
fun AlarmSettingsRoute(onBack: () -> Unit, vm: AlarmSettingsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.stopPreview() }
    AlarmSettingsScreen(
        state = state,
        onBack = onBack,
        onSound = vm::setSound,
        onPreview = vm::preview,
        onVolume = vm::setVolume,
        onRaiseVolume = vm::setRaiseVolume,
        onVibration = vm::setVibration,
        onDuration = vm::setDuration,
        onConfirm = vm::setConfirm,
        onFullScreen = vm::setFullScreen,
        onOpenFullScreenSettings = { vm.openFullScreenSettings(context) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmSettingsScreen(
    state: AlarmSettingsUiState,
    onBack: () -> Unit,
    onSound: (AlarmSound) -> Unit,
    onPreview: (AlarmSound) -> Unit,
    onVolume: (Float) -> Unit,
    onRaiseVolume: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onDuration: (Int) -> Unit,
    onConfirm: (Long) -> Unit,
    onFullScreen: (Boolean) -> Unit,
    onOpenFullScreenSettings: () -> Unit,
) {
    val s = state.settings
    SubScreen(title = "Alarm settings", onBack = onBack) {
        if (state.alarmActive) {
            CgCard(Modifier.fillMaxWidth(), border = CgColors.Red.copy(alpha = 0.4f), contentPadding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Lock, CgColors.Red, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "An alarm is active. Changes here apply to the next alarm and cannot silence the current one. Preview is disabled.",
                        style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary,
                    )
                }
            }
        }

        SectionLabel("Alarm sound")
        CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            AlarmSound.entries.forEach { sound ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { onSound(sound) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = s.sound == sound, onClick = { onSound(sound) },
                        colors = RadioButtonDefaults.colors(selectedColor = CgColors.Green, unselectedColor = CgColors.TextSecondary),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(soundName(sound), style = MaterialTheme.typography.titleSmall)
                        Text(soundDescription(sound), style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary)
                    }
                    IconButton(onClick = { onPreview(sound) }, enabled = !state.alarmActive) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Preview ${soundName(sound)}", tint = if (state.alarmActive) CgColors.TextSecondary.copy(alpha = 0.4f) else CgColors.Green)
                    }
                }
            }
        }

        SectionLabel("Volume")
        CgCard(Modifier.fillMaxWidth()) {
            var local by remember(s.volume) { mutableFloatStateOf(s.volume) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = CgColors.TextSecondary)
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onVolume(local) },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = CgColors.Green, activeTrackColor = CgColors.Green, inactiveTrackColor = CgColors.Elevated),
                )
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = CgColors.TextSecondary)
            }
            Text(
                "Siren level ${(local * 100).toInt()}% of your phone's alarm volume (currently ${(state.systemAlarmVolume * 100).toInt()}%).",
                style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary,
            )
            if (state.systemAlarmVolume < 0.5f && !s.raiseSystemAlarmVolume) {
                Text(
                    "Your alarm volume is low. Raise it with the volume buttons or enable the option below.",
                    style = MaterialTheme.typography.bodySmall, color = CgColors.Amber, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            SwitchRow(
                Icons.AutoMirrored.Rounded.VolumeUp, "Raise alarm volume during alarm",
                "Sets the system alarm volume to maximum while alarming and restores it afterwards. Android may still let anyone lower it with the volume buttons.",
                s.raiseSystemAlarmVolume, onCheckedChange = onRaiseVolume,
            )
            SwitchRow(Icons.Rounded.Vibration, "Vibration", "Vibrate continuously while the alarm is active", s.vibrationEnabled, onCheckedChange = onVibration)
        }

        SectionLabel("Alarm duration")
        CgCard(Modifier.fillMaxWidth()) {
            Text("Siren keeps sounding", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmSettings.DURATION_OPTIONS.forEach { m ->
                    OptionChip(if (m == 0) "Until reconnected" else "$m min", selected = s.soundDurationMinutes == m) { onDuration(m) }
                }
            }
            Text(
                "With a time limit the siren stops after that time, but the alarm screen and notification remain until the charger is reconnected.",
                style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary, modifier = Modifier.padding(top = 10.dp),
            )
        }

        SectionLabel("Disconnect confirmation")
        CgCard(Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmSettings.CONFIRM_OPTIONS.forEach { ms ->
                    OptionChip(if (ms == 0L) "Instant" else "%.1f s".format(ms / 1000f), selected = s.disconnectConfirmMillis == ms) { onConfirm(ms) }
                }
            }
            Text(
                "A short window filters a loose cable or a phone shifting on a wireless pad. If power is back within the window, no alarm is raised.",
                style = MaterialTheme.typography.bodySmall, color = CgColors.TextSecondary, modifier = Modifier.padding(top = 10.dp),
            )
        }

        SectionLabel("Notifications")
        CgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            SwitchRow(
                Icons.Rounded.Fullscreen, "Full-screen alarm",
                if (state.fullScreenAllowed) "Show the alarm screen over the lock screen" else "Android currently doesn't allow full-screen alarms for ChargeGuard",
                s.fullScreenAlarm, onCheckedChange = onFullScreen,
            )
            if (!state.fullScreenAllowed) {
                SettingRow(Icons.Rounded.Lock, "Allow full-screen alarms", "Opens system settings", tint = CgColors.Amber, onClick = onOpenFullScreenSettings)
            }
        }
    }
}

private fun soundName(s: AlarmSound) = when (s) {
    AlarmSound.SIREN -> "Siren"
    AlarmSound.PULSE -> "Pulse"
    AlarmSound.KLAXON -> "Klaxon"
}

private fun soundDescription(s: AlarmSound) = when (s) {
    AlarmSound.SIREN -> "Rising and falling wail"
    AlarmSound.PULSE -> "Fast high-pitched beeps"
    AlarmSound.KLAXON -> "Two-tone horn"
}
