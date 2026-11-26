package com.chargeguard.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.ui.alarm.AlarmRoute
import com.chargeguard.ui.navigation.AppNavHost
import com.chargeguard.ui.theme.ChargeGuardTheme
import com.chargeguard.ui.theme.LocalCgPalette

@Composable
fun ChargeGuardRoot(vm: MainViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val activity = LocalActivity.current

    ChargeGuardTheme(settings?.theme ?: AppTheme.DARK) {
        Box(Modifier.fillMaxSize().background(LocalCgPalette.current.background)) {
            AppNavHost(nav, settings, ready = status.initialized)

            // The alarm overlays every screen (including settings), so no screen can hide or
            // dismiss it. It disappears only when the protection state leaves the alarm.
            val alarmVisible = status.state == ProtectionState.ALARM_ACTIVE || status.state == ProtectionState.RECONNECTED
            AnimatedVisibility(visible = alarmVisible, enter = fadeIn(), exit = fadeOut()) {
                BackHandler { activity?.moveTaskToBack(true) }
                AlarmRoute()
            }
        }
    }
}
