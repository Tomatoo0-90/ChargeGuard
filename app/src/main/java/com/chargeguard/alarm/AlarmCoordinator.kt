package com.chargeguard.alarm

import com.chargeguard.core.common.WakeLocks
import com.chargeguard.core.notifications.ProtectionNotifications
import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.repository.AlarmOutput
import javax.inject.Inject
import javax.inject.Singleton

/** Combines siren, vibration, wake lock and alarm notification behind [AlarmOutput]. */
@Singleton
class AlarmCoordinator @Inject constructor(
    private val audio: AlarmController,
    private val vibration: VibrationController,
    private val notifications: ProtectionNotifications,
    private val wakeLocks: WakeLocks,
) : AlarmOutput {

    @Volatile
    private var active = false

    override fun start(settings: AlarmSettings): Boolean {
        if (active) return audio.isActive
        active = true
        wakeLocks.holdForAlarm()
        val audioOk = audio.start(settings.sound, settings.volume, settings.raiseSystemAlarmVolume)
        if (settings.vibrationEnabled) vibration.start()
        notifications.showAlarm(fullScreen = settings.fullScreenAlarm, sirenSilenced = false, audioFailed = !audioOk)
        return audioOk
    }

    override fun silence() {
        audio.stop()
        vibration.stop()
        if (active) notifications.showAlarm(fullScreen = false, sirenSilenced = true, audioFailed = false)
    }

    override fun stop() {
        active = false
        audio.stop()
        vibration.stop()
        notifications.cancelAlarm()
        wakeLocks.releaseAlarm()
    }
}
