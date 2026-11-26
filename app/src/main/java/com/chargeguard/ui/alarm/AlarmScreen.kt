package com.chargeguard.ui.alarm

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargeguard.core.common.Formatting
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.model.ProtectionStatus
import com.chargeguard.ui.components.PinDialog
import com.chargeguard.ui.components.StatusDot
import com.chargeguard.ui.theme.CgColors
import kotlinx.coroutines.delay

private val AlarmBackground = Color(0xFF140A0B)
private val AlarmSurface = Color(0xFF221214)

@Composable
fun AlarmRoute(vm: AlarmViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val pinError by vm.pinError.collectAsStateWithLifecycle()
    var showPin by rememberSaveable { mutableStateOf(false) }

    AlarmScreen(status = status, pinSet = vm.pinSet, onOwnerStop = { showPin = true })

    if (showPin && status.state == ProtectionState.ALARM_ACTIVE) {
        PinDialog(
            title = "Owner stop",
            message = "Enter your owner PIN to stop the alarm and turn protection off.",
            confirmLabel = "Stop alarm",
            error = pinError,
            onConfirm = { pin -> if (vm.ownerStop(pin)) showPin = false },
            onDismiss = { showPin = false; vm.clearPinError() },
        )
    }
}

/**
 * Alarm screen. There is intentionally no dismiss button: the alarm ends when external power is
 * reconnected, or by the owner PIN if one has been set.
 */
@Composable
fun AlarmScreen(status: ProtectionStatus, pinSet: Boolean, onOwnerStop: () -> Unit) {
    val reconnected = status.state == ProtectionState.RECONNECTED
    val accent = if (reconnected) CgColors.Green else CgColors.Red
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(if (reconnected) Color(0xFF0B1410) else AlarmBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        WarningBeacon(accent, reconnected)
        Spacer(Modifier.height(36.dp))
        Text(
            if (reconnected) "CHARGER RECONNECTED" else "CHARGER DISCONNECTED",
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 1.5.sp),
            color = accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (reconnected) "External power is back. The alarm has stopped and protection is re-arming."
            else "Your phone is no longer connected to external power.",
            style = MaterialTheme.typography.bodyLarge,
            color = CgColors.TextPrimary.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        if (!reconnected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AlarmSurface)
                    .padding(PaddingValues(20.dp)),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val started = status.alarmStartedAtMillis
                StatusLine(
                    CgColors.Red,
                    "Alarm active",
                    started?.let { Formatting.duration(now - it) },
                )
                StatusLine(
                    when {
                        status.alarmAudioFailed -> CgColors.Amber
                        status.sirenTimedOut -> CgColors.TextSecondary
                        else -> CgColors.Red
                    },
                    when {
                        status.alarmAudioFailed -> "Siren unavailable: vibration and notification only"
                        status.sirenTimedOut -> "Siren paused (time limit reached)"
                        else -> "Siren sounding"
                    },
                    null,
                )
                StatusLine(CgColors.Amber, "Waiting for charger reconnection", null, animated = true)
            }
        }

        Spacer(Modifier.weight(1f))

        if (!reconnected) {
            if (pinSet) {
                TextButton(onClick = onOwnerStop) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = CgColors.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Owner? Stop with PIN", color = CgColors.TextSecondary)
                }
            } else {
                Text(
                    "Reconnect the charger to stop the alarm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CgColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun WarningBeacon(color: Color, calm: Boolean) {
    val t = rememberInfiniteTransition(label = "beacon")
    val ring by t.animateFloat(0.85f, 1.25f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "ring")
    val ringAlpha by t.animateFloat(0.45f, 0f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "ringAlpha")
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        if (!calm) {
            Box(
                Modifier
                    .size(160.dp)
                    .scale(ring)
                    .alpha(ringAlpha)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Box(
            Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (calm) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(color: Color, text: String, trailing: String?, animated: Boolean = false) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotAlpha")
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, Modifier.alpha(if (animated) a else 1f))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CgColors.TextPrimary, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleSmall, color = CgColors.TextPrimary)
    }
}
