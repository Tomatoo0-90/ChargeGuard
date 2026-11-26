package com.chargeguard.ui.alarm

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.domain.repository.SettingsRepository
import com.chargeguard.ui.theme.ChargeGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Alarm UI launched by the full-screen notification intent (over the lock screen when Android
 * permits it). It is NOT a fake lock screen: the user can still leave it; leaving it does not
 * stop the alarm. It closes itself once the alarm is over.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject lateinit var manager: ProtectionManager
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Back leaves the screen (moves it behind) but never stops the alarm.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        val themeFlow = settings.settings.map { it.theme }
        setContent {
            val theme by themeFlow.collectAsStateWithLifecycle(AppTheme.DARK)
            ChargeGuardTheme(theme) { AlarmRoute() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.status.collect { status ->
                    when (status.state) {
                        ProtectionState.ALARM_ACTIVE -> Unit
                        ProtectionState.RECONNECTED -> { delay(1_800); finishAndRemoveTask() }
                        else -> if (status.initialized) finishAndRemoveTask()
                    }
                }
            }
        }
    }

    /** While alarming, volume keys in this screen do nothing (they can't lower the siren here). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (manager.status.value.state == ProtectionState.ALARM_ACTIVE && keyCode.isVolumeKey()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (manager.status.value.state == ProtectionState.ALARM_ACTIVE && keyCode.isVolumeKey()) return true
        return super.onKeyUp(keyCode, event)
    }
}

fun Int.isVolumeKey(): Boolean =
    this == KeyEvent.KEYCODE_VOLUME_UP || this == KeyEvent.KEYCODE_VOLUME_DOWN || this == KeyEvent.KEYCODE_VOLUME_MUTE
