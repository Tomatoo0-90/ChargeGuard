package com.chargeguard.domain.model

/** Everything the UI and the notification need to render the current situation. */
data class ProtectionStatus(
    val state: ProtectionState = ProtectionState.INACTIVE,
    val charging: ChargingSnapshot? = null,
    val sessionStartedAtMillis: Long? = null,
    /** A disconnect was seen and is inside the confirmation window. */
    val disconnectPending: Boolean = false,
    /** Siren is currently audible. False during an alarm if audio failed or timed out. */
    val sirenPlaying: Boolean = false,
    val alarmAudioFailed: Boolean = false,
    val sirenTimedOut: Boolean = false,
    val alarmStartedAtMillis: Long? = null,
    /** Human-readable reason for MONITORING_UNAVAILABLE. */
    val unavailableReason: String? = null,
    /** False until persisted state has been read at startup. */
    val initialized: Boolean = false,
)
