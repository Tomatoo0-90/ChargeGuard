package com.chargeguard

import android.app.Application
import com.chargeguard.core.notifications.ProtectionNotifications
import com.chargeguard.domain.protection.ProtectionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ChargeGuardApp : Application() {

    @Inject lateinit var notifications: ProtectionNotifications

    // Injected eagerly so the manager initialises at process start and can detect, before any
    // UI is shown, that protection was enabled but monitoring is no longer running.
    @Inject lateinit var protectionManager: ProtectionManager

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
    }
}
