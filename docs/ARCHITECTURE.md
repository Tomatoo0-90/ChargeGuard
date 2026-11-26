# ChargeGuard architecture

## Layers

```
ui/ (Compose screens + Hilt ViewModels)
  │  observes StateFlow, sends user intents
  ▼
domain/
  model/        pure data: ProtectionState, ChargingSnapshot, settings, history
  repository/   interfaces: ChargingRepository, SettingsRepository, HistoryRepository,
                AlarmOutput, MonitoringServiceController, Clock
  protection/   ProtectionStateMachine (pure)  +  ProtectionManager (actor, single authority)
  ▲
  │ implements
data/           AndroidChargingRepository (sticky ACTION_BATTERY_CHANGED + receiver + poll)
                DataStoreSettingsRepository, RoomHistoryRepository, PinVerifier (+Keystore MAC)
platform        service/ProtectionService (foreground, mediaPlayback)
                receiver/ChargingReceiver (dynamic), receiver/BootReceiver
                alarm/AlarmController (MediaPlayer, USAGE_ALARM), VibrationController,
                AlarmCoordinator (AlarmOutput impl), core/notifications, core/common/WakeLocks
```

The domain layer has no Android imports, which is why the state machine and the manager are
covered by fast JVM unit tests.

## Single authoritative charging state

* **Authority:** `BatteryManager.EXTRA_PLUGGED` from the sticky `ACTION_BATTERY_CHANGED` intent,
  read with `registerReceiver(null, filter)`. Any non-zero value means *external power is
  physically connected* (AC, USB, wireless, dock or an unknown future flag).
* **Charge status** (`EXTRA_STATUS`: charging / full / not charging) is shown in the UI but is
  never used for protection decisions. A full battery or an 80 % charge limit is still "connected".
* **Event sources** (all funnel into one stream that the service collects):
  1. a dynamically registered `ChargingReceiver` for `ACTION_POWER_CONNECTED`,
     `ACTION_POWER_DISCONNECTED` and `ACTION_BATTERY_CHANGED`. Manifest receivers can't get these
     since Android 8; `BATTERY_CHANGED` can never be manifest-registered;
  2. a sticky re-read 400 ms after connect/disconnect broadcasts (they can race the sticky update);
  3. a 5 s safety poll of the sticky intent while monitoring.
* **De-duplication:** `ProtectionManager` only reacts when the boolean "connected" value changes.
  Level updates, repeated broadcasts and poll results that agree are ignored.

## Protection state machine

| From \ Event          | PowerChanged(true) | PowerChanged(false)                 | DisconnectConfirmed | Deactivate | Other |
|-----------------------|--------------------|-------------------------------------|---------------------|------------|-------|
| INACTIVE              | –                  | –                                   | –                   | –          | Activate → WAITING |
| WAITING_FOR_CHARGER   | → ARMED            | –                                   | –                   | → INACTIVE | MonitoringLost → UNAVAILABLE |
| ARMED                 | cancel pending     | schedule check (or alarm if 0 ms)   | → ALARM_ACTIVE      | → INACTIVE | MonitoringLost → UNAVAILABLE |
| ALARM_ACTIVE          | → RECONNECTED      | ignored (duplicate)                 | ignored             | **rejected** | OwnerStop (PIN) → INACTIVE |
| RECONNECTED           | –                  | behaves like ARMED                  | –                   | → INACTIVE | RearmTimer (2.5 s) → ARMED |
| MONITORING_UNAVAILABLE| –                  | –                                   | –                   | → INACTIVE | Activate → WAITING; MonitoringRestored → ARMED/WAITING |

Effects (`StartAlarm`, `StopAlarm`, timers, history records, stopping the service) are returned as
data and executed by the manager. `StartAlarm` is always the first effect so nothing delays the siren.

### Disconnect confirmation

When armed, a disconnect schedules a check after the configurable window (default 1 s, 0–3 s).
When the timer fires, the manager **re-reads the sticky intent** and only alarms if power is still
absent. A reconnect within the window cancels the check and logs "brief interruption ignored".

### Concurrency

`ProtectionManager` processes every input (UI taps, service lifecycle, charging readings, timer
expiries) through one `Channel` consumed by one coroutine. There is no shared mutable state touched
from two threads, and stale timers are discarded by token.

## Foreground service and lifecycle

* Started only by `MonitoringServiceController.start()` from a user tap while the app is visible
  (a permitted FGS start on Android 12+). Failure to start is reported, never swallowed.
* Type `mediaPlayback` (Android 14+ requires `FOREGROUND_SERVICE_MEDIA_PLAYBACK`), because the
  service owns the looping siren. Android 15's 6-hour FGS timeout applies to `dataSync` and
  `mediaProcessing`, not to `mediaPlayback`.
* `START_STICKY`. On a system restart (null intent) the manager decides from persisted state:
  protection still wanted → restore (and alarm if armed and now unplugged); otherwise stop.
* If the service is destroyed while monitoring, the manager goes to `MONITORING_UNAVAILABLE` and a
  "protection is OFF" notification is posted.
* On process start, if protection was enabled but no service is running, the app shows
  `MONITORING_UNAVAILABLE` and records one "monitoring interrupted" history event.
* After reboot or app update, `BootReceiver` only posts a "protection is OFF" notification.
  Android 15+ forbids starting a `mediaPlayback` FGS from `BOOT_COMPLETED`.

## Wake locks

Charging does not keep the CPU awake with the screen off. ChargeGuard takes:
* a 15 s partial wake lock in the receiver when a disconnect is seen (covers the confirmation timer);
* a partial wake lock for the alarm (released on stop, 2 h safety timeout), plus
  `MediaPlayer.setWakeMode` while the siren plays.

## Alarm engine

`AlarmCoordinator` implements `AlarmOutput`:
* `AlarmController`: single `MediaPlayer`, `USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION`, looping,
  transient audio focus (focus loss is ignored), bounded auto-restart on player error, optional
  opt-in "raise alarm volume" that restores the user's volume afterwards.
* `VibrationController`: repeating waveform with alarm usage attributes.
* Alarm notification: high-importance channel with no channel sound, `CATEGORY_ALARM`, ongoing,
  full-screen intent only when enabled and `canUseFullScreenIntent()` is true, re-posted if swiped.
  Tapping it opens the alarm screen and does not stop the alarm.
* The alarm overlay sits above the whole navigation graph, so settings screens can't dismiss it.
  Volume keys are consumed in ChargeGuard's own windows while alarming.

## Persistence

* DataStore (`chargeguard_settings`): settings and `PersistedProtection` (enabled, session start,
  last state, interruption recorded).
* Room (`chargeguard_history.db`): history events, pruned to 500.
* SharedPreferences (`owner_pin`): PIN salt, HMAC verifier and rate-limit counters. The HMAC key is
  a non-exportable Android Keystore key.
* Cloud backup and device transfer are disabled.
