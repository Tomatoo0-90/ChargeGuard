package com.chargeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chargeguard.core.notifications.ProtectionNotifications
import com.chargeguard.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After a reboot or an app update, monitoring is NOT running. Android 15+ forbids starting a
 * mediaPlayback foreground service from BOOT_COMPLETED, and a restart must not silently claim
 * protection anyway. So this receiver only informs the user. (The interruption itself is recorded
 * in history when the app process starts and finds protection enabled but not running.)
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun settings(): SettingsRepository
        fun notifications(): ProtectionNotifications
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (deps.settings().currentProtection().enabled) {
                    val why = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "The phone restarted" else "ChargeGuard was updated"
                    deps.notifications().showProtectionOff("$why, so charger protection is OFF. Open ChargeGuard and turn it on again.")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
