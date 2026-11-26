package com.chargeguard.domain.model

/** The six user-visible protection states. See docs/ARCHITECTURE.md for the transition table. */
enum class ProtectionState {
    /** Protection is off. */
    INACTIVE,
    /** Protection is on, but no external power is connected yet. Unplugging here never alarms. */
    WAITING_FOR_CHARGER,
    /** External power is connected and monitored. A confirmed disconnect triggers the alarm. */
    ARMED,
    /** External power was removed while armed. Siren, vibration and alarm notification are active. */
    ALARM_ACTIVE,
    /** Power came back during an alarm. Alarm stopped; returns to ARMED after a short moment. */
    RECONNECTED,
    /**
     * The user wanted protection, but monitoring is not running (service failed to start,
     * process was killed, device rebooted, app updated). The UI must never claim protection here.
     */
    MONITORING_UNAVAILABLE;

    /** True when the foreground monitoring service should be running. */
    val isMonitoring: Boolean
        get() = this == WAITING_FOR_CHARGER || this == ARMED || this == ALARM_ACTIVE || this == RECONNECTED
}
