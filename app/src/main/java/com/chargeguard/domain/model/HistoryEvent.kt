package com.chargeguard.domain.model

enum class HistoryEventType {
    PROTECTION_ACTIVATED,
    PROTECTION_ARMED,
    CHARGING_DISCONNECTED,
    BRIEF_INTERRUPTION_IGNORED,
    ALARM_STARTED,
    ALARM_AUDIO_FAILED,
    ALARM_SOUND_TIMED_OUT,
    CHARGER_RECONNECTED,
    ALARM_STOPPED,
    ALARM_STOPPED_BY_OWNER,
    PROTECTION_DEACTIVATED,
    MONITORING_INTERRUPTED,
    MONITORING_RESTORED,
    MONITORING_START_FAILED,
}

data class HistoryEvent(
    val id: Long = 0,
    val type: HistoryEventType,
    val timestampMillis: Long,
    val detail: String? = null,
)
