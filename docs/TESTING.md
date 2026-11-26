# Testing

Status as of **27 Nov 2026**. Every result below was actually executed. Nothing here is assumed.

Test device: **vivo iQOO I2220, Android 16 (API 36), OriginOS 7, security patch 2026-10-01**.
Build host: Windows, JDK 17 (Temurin), AGP 8.13.2, Gradle 8.14.3.

## 1. Automated tests

### JVM unit tests: `testDebugUnitTest`, **77 run, 0 failed, 0 skipped**

| Suite | Tests | Covers |
|---|---|---|
| `ProtectionStateMachineTest` | 23 | every listed transition; duplicate disconnects; confirmation window; deactivate rejected during alarm; owner stop; monitoring lost/restored |
| `ProtectionManagerTest` | 32 | activation (with/without charger), start failure, watchdog, confirmation window timing, duplicate broadcasts (alarm starts exactly once), cable wobble, fresh-reading confirmation, unstable cable, full battery / not-charging, USB↔AC switch, audio failure, siren time limit, reconnection & re-arm, repeated cycles, deactivation, process restart detection (logged once), system service restore (plugged → armed, unplugged → alarm), history ordering |
| `ChargingSnapshotTest` | 7 | AC/USB/wireless/dock/unknown flags, full battery, 80 % charge limit, PC connection, percent maths |
| `DataStoreSettingsRepositoryTest` | 5 | defaults, round-trip of every setting, clamping, protection state round-trip, isolation |
| `PinVerifierTest` | 10 | set/verify, no plaintext, format, change requires current, lockout after 5, exponential/capped lockout, clock rollback, lockout survives restart, removal |

The settings JVM test uses an in-memory DataStore, because DataStore's JVM file storage can't
replace files on a Windows host. Real on-disk persistence is covered by the instrumented test below.

### Instrumented tests on the phone: `connectedDebugAndroidTest`, **7 run, 0 failed**

| Test | Result |
|---|---|
| `HistoryDaoTest.newestFirst_andTypesRoundTrip` | passed |
| `HistoryDaoTest.prune_keepsNewest` | passed |
| `HistoryDaoTest.unknownTypesAreSkipped_notCrashing` | passed |
| `PlatformInstrumentedTest.stickyBatteryRead_matchesSystem` | passed |
| `PlatformInstrumentedTest.alarmController_singlePlayer_startStop` | passed |
| `PlatformInstrumentedTest.vibrationController_idempotent` | passed |
| `SettingsPersistenceInstrumentedTest.settingsSurviveANewDataStoreInstance` | passed |

### Static analysis
* `lintDebug`: **No issues found.** `lintRelease`: passed (abort-on-error enabled).
* Version-upgrade lint checks are disabled on purpose (pinned toolchain, see README).

## 2. Physical-device tests

**Method.** Driven over adb with UI-hierarchy taps, and verified through system state rather than
appearance:
* `dumpsys audio`: `MediaPlayer` with `USAGE_ALARM` in state *started*
* `dumpsys vibrator_manager`: current vibration, alarm usage
* `dumpsys notification`, `dumpsys activity services` (foreground type `0x2` = mediaPlayback)
* `dumpsys activity activities` and event logs

"Simulated unplug" = `adb shell dumpsys battery unplug`, which makes Android's BatteryService
report no power source and send the real `ACTION_BATTERY_CHANGED` / `ACTION_POWER_DISCONNECTED`
broadcasts. "Physical" = a person pulled the real cable.

| # | Checklist item | How | Result |
|---|---|---|---|
| 1 | Connect charger, activate protection | UI | **Pass**: ARMED, FGS type mediaPlayback, monitoring notification posted |
| 2 | Lock screen, disconnect cable | simulated, screen off | **Pass**: screen woke, `AlarmActivity` over lock screen, siren + alarm vibration + alarm notification |
| 3 | Verify alarm | dumpsys | **Pass**: one player only, `USAGE_ALARM`, started |
| 4 | Reconnect, verify alarm stops | simulated | **Pass**: siren, vibration and alarm notification gone. "Charger reconnected" shown, alarm screen closed itself |
| 5 | Verify monitoring resumes | UI | **Pass**: back to PROTECTION ACTIVE after ~2.5 s |
| 6 | Repeat several times | 5 cycles, locked | **Pass**: siren 1.3–1.7 s after unplug each time (incl. 1 s confirm window + adb polling); stopped each time |
| 7 | **Physical cable pull, locked** | user, release build | **Pass**: unplug 00:21:14.226 → alarm 00:21:15.363 (1.14 s); power button pressed mid-alarm → vibration restarted in 0.4 s; replug 00:21:20.755 → stopped 00:21:20.813 (58 ms); re-armed; no crash |
| 8 | Fully charged phone | simulated level 100 / status FULL, still plugged | **Pass**: no alarm |
| 9 | Plugged but not charging (charge limit) | simulated status NOT_CHARGING | **Pass**: no alarm |
| 10 | Unstable cable | 300 ms unplug/replug | **Pass**: no alarm (1 s window) |
| 11 | Duplicate broadcasts | second `unplug` during alarm | **Pass**: still exactly one player |
| 12 | Volume buttons in alarm screen | 3× VOLUME_DOWN | **Pass**: alarm stream stayed 15/15 |
| 13 | App minimized (unlocked, launcher on top) | simulated | **Pass**: siren plays; Android shows heads-up (no full-screen takeover while unlocked, as designed by Android) |
| 14 | Tap the alarm notification | UI | **Pass**: opens alarm screen, siren continues |
| 15 | Back press on alarm screen | UI | **Pass**: screen leaves, siren continues |
| 16 | Swipe the alarm notification | UI | **Pass**: notification stays posted, siren continues |
| 17 | Screen turned off during alarm | power button / SLEEP | **Pass after fix** (see §3): vibration re-issued after each screen-off |
| 18 | Notification permission denied | `pm revoke`, "Activate without" | **Pass**: rationale dialog shown, warning card on home, siren + vibration work, no notification posted |
| 19 | App process termination (SIGKILL while armed) | `run-as kill -9` | **Pass**: Android restarted the sticky service within 2 s; history "Monitoring interrupted" → "Monitoring restored"; a later unplug alarmed |
| 20 | Force stop | `am force-stop` | **Pass** (honest failure): nothing runs (no alarm on unplug, by Android design); next launch shows **MONITORING UNAVAILABLE / Not protected** |
| 21 | App update while armed | `adb install -r` | **Pass**: notification "ChargeGuard was updated, so charger protection is OFF"; home shows Monitoring unavailable |
| 22 | Device reboot while armed | `adb reboot` | **Pass**: after first unlock, `BootReceiver` posted "protection is OFF"; history "Monitoring interrupted"; protection resumed only when the user turned it on |
| 23 | Owner PIN | UI | **Pass**: stored as salt + Keystore HMAC only; wrong PIN rejected; correct PIN turns off; "Stop with PIN" during alarm stops siren and service |
| 24 | Siren time limit (1 min) | simulated, locked | **Pass**: siren and vibration stopped at 60 s; alarm screen ("Siren paused") and notification kept until reconnect |
| 25 | Release (R8-minified, signed) build | install + simulated + physical | **Pass**: onboarding, activation, alarm, reconnect; no crashes in the crash buffer |

## 3. Defects found on the device and fixed

| Defect | Found by | Fix | Re-verified |
|---|---|---|---|
| Text with no explicit colour rendered black on the dark background | screenshot of onboarding | `LocalContentColor` = white provided by the theme | yes, screenshots |
| History listed "Alarm started" before "Charging disconnected" | history screen | alarm history entries are deferred until after the transition's own records (siren still starts first) | yes, history screen + new unit test |
| **Android cancels the alarm vibration when the screen turns off** (`cancelled_by_screen_off`, even with alarm usage) | `dumpsys vibrator_manager` | `VibrationController` listens for `ACTION_SCREEN_OFF` while alarming and re-issues the vibration after 400 ms | yes, simulated and physical (power button during the real cable pull) |

## 4. Not verified / remaining manual tests

| Item | Why not verified | How to test |
|---|---|---|
| Wall (AC) charger and wireless charger | Only the PC's USB port was available. vivo reports it as "AC". | Repeat test 7 with a wall charger and, if available, a wireless pad (try 1–2 s confirm window). |
| Long-duration monitoring (hours / overnight) | Not run | Arm overnight while charging, check the next morning that the home screen still shows PROTECTION ACTIVE and history has no interruption. |
| vivo background restrictions over time | Battery optimization was left **on** ("Optimized") during all tests. No kill was observed, but vivo/OriginOS may still stop apps after long idle periods. | Run the overnight test with and without "Unrestricted" battery mode. |
| Do Not Disturb set to block alarms | Not tested | Expected: siren muted by the OS (documented limitation). |
| Android 8–13 devices | Only an Android 16 device was available | Install on an older phone; the code paths are version-guarded. |
| Full-screen intent when *not* granted | On this vivo the full-screen alarm worked by default | On a Pixel with Android 14+, verify the heads-up fallback and the "Allow full-screen alarms" guidance. |
| Physical cable pull with the **screen on and unlocked** | Only the locked case was done physically; unlocked was simulated | Pull the cable with the app open. |

## 5. Screenshots

`docs/screenshots/` contains screens captured on the test device during these runs (onboarding,
home inactive/armed/unavailable, alarm over app, alarm over lock screen, reconnected, history,
settings, alarm settings).
