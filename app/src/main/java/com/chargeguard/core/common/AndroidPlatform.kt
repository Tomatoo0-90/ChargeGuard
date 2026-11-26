package com.chargeguard.core.common

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.chargeguard.domain.repository.Clock
import com.chargeguard.domain.repository.MonitoringServiceController
import com.chargeguard.service.ProtectionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

class WallClock @Inject constructor() : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

@Singleton
class AndroidMonitoringServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : MonitoringServiceController {

    /**
     * Starts the foreground service. Called only from a user tap while the app is visible, which
     * is a permitted foreground-service start on Android 12+. Any refusal (for example
     * ForegroundServiceStartNotAllowedException) is returned as a failure, never swallowed.
     */
    override fun start(): Result<Unit> = runCatching {
        ContextCompat.startForegroundService(
            context, Intent(context, ProtectionService::class.java).setAction(ProtectionService.ACTION_START)
        )
        Unit
    }

    override fun stop() {
        runCatching { context.stopService(Intent(context, ProtectionService::class.java)) }
    }
}
