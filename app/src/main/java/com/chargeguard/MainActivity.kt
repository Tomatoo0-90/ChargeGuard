package com.chargeguard

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.ui.ChargeGuardRoot
import com.chargeguard.ui.alarm.isVolumeKey
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var manager: ProtectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChargeGuardRoot() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (manager.status.value.state == ProtectionState.ALARM_ACTIVE && keyCode.isVolumeKey()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (manager.status.value.state == ProtectionState.ALARM_ACTIVE && keyCode.isVolumeKey()) return true
        return super.onKeyUp(keyCode, event)
    }
}
