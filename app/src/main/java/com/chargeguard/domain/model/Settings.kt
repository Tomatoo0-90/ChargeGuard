package com.chargeguard.domain.model

enum class AlarmSound { SIREN, PULSE, KLAXON }

enum class AppTheme { DARK, AMOLED }

data class AlarmSettings(
    val sound: AlarmSound = AlarmSound.SIREN,
    /** App-level gain applied on top of the system alarm volume, 0.1..1.0. */
    val volume: Float = 1.0f,
    /** Opt-in: raise the system ALARM stream to max while alarming, restore afterwards. */
    val raiseSystemAlarmVolume: Boolean = false,
    val vibrationEnabled: Boolean = true,
    /** 0 = sound until power is reconnected. Otherwise silence the siren after N minutes. */
    val soundDurationMinutes: Int = 0,
    /** Confirmation window for a disconnect, filters cable wobble. 0 = immediate. */
    val disconnectConfirmMillis: Long = DEFAULT_CONFIRM_MILLIS,
    val fullScreenAlarm: Boolean = true,
) {
    companion object {
        const val DEFAULT_CONFIRM_MILLIS = 1_000L
        val CONFIRM_OPTIONS = listOf(0L, 500L, 1_000L, 2_000L, 3_000L)
        val DURATION_OPTIONS = listOf(0, 1, 3, 5, 10)
    }
}

data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val theme: AppTheme = AppTheme.DARK,
    val showBatteryInNotification: Boolean = true,
    val alarm: AlarmSettings = AlarmSettings(),
)

/** What must survive process death so the app can tell the truth after a restart. */
data class PersistedProtection(
    /** The user turned protection on and has not turned it off. */
    val enabled: Boolean = false,
    val sessionStartedAtMillis: Long? = null,
    /** An interruption has already been recorded for the current enabled session. */
    val interruptionRecorded: Boolean = false,
    /** Last state while monitoring; used to decide what to do when monitoring is restored. */
    val lastState: ProtectionState = ProtectionState.INACTIVE,
)
