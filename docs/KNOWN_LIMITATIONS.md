# Known limitations

ChargeGuard is an ordinary Android app. It uses only public, supported APIs and does not claim
abilities Android doesn't give it. This document lists what it **cannot** do, and how the app
behaves in each case.

## Things Android does not allow any normal app to prevent

| Situation | What happens | What ChargeGuard does |
|---|---|---|
| Phone switched off with the power button / power menu | The OS shuts down; apps can't block it. Some phones let you disable the power menu on the lock screen in system settings. | Nothing can run. After the next boot a notification says protection is OFF, and the app shows "Monitoring unavailable". |
| **Force stop** (Settings → Apps → ChargeGuard → Force stop) | All app components are killed and nothing restarts until the user opens the app. | Next launch shows "Monitoring unavailable" and records "Monitoring interrupted". |
| Device reboot | Services don't survive a reboot. Android 15+ forbids starting a `mediaPlayback` foreground service from `BOOT_COMPLETED`. | `BootReceiver` posts "Charger protection is OFF". Protection is never silently re-armed. |
| App update | The process is replaced. | Same as reboot (`MY_PACKAGE_REPLACED`). Verified on device. |
| Reboot notice timing | On encrypted phones (all modern ones), `BOOT_COMPLETED` is delivered only after the **first unlock** after boot. | The "protection is OFF" notification appears right after you first unlock. Verified on device. |
| Vibration and screen-off | Android cancels running app vibrations when the screen turns off, even with alarm usage (observed: `cancelled_by_screen_off`). | While alarming, ChargeGuard re-issues the vibration ~0.4 s after every screen-off. There is a short gap; the siren is unaffected. Verified on device. |
| Manufacturer task killers (Xiaomi/MIUI/HyperOS, Oppo/ColorOS, Vivo, Huawei, some Samsung modes) | Can kill even foreground services. | Battery-optimization status and guidance in Settings. If the system restarts the sticky service, monitoring is restored only if persisted state says protection is still wanted; otherwise the UI shows "Monitoring unavailable". |
| System volume controls | Android lets the user lower the alarm stream with the volume keys on the lock screen or in the volume panel. ChargeGuard consumes volume keys only inside its own windows. | The siren uses `USAGE_ALARM` and your alarm volume. An opt-in setting raises the alarm stream to maximum during an alarm and restores it afterwards. ChargeGuard does not fight later user volume changes. |
| Do Not Disturb set to block alarms | Alarm audio is suppressed by the OS. | Not bypassed (no DND policy access is requested). Vibration and notification behaviour then also follow DND. |
| Someone with the **unlocked** phone | They can open the app and turn protection off. | Optional owner PIN (Keystore-backed HMAC, rate limited) is required to deactivate and to stop an alarm. The monitoring notification deliberately has no "turn off" action. |
| Lock-screen alarm | Android 14+ grants `USE_FULL_SCREEN_INTENT` by default only to calling/alarm-clock apps. | Checks `canUseFullScreenIntent()`. If not allowed, a high-priority heads-up notification is shown and the user is guided to the system setting. |
| Notifications denied (Android 13+) | The foreground-service notification and alarm notification are hidden. | The siren and vibration still work. The dashboard and Settings show a warning with a fix button. |

## Charging-specific behaviour

* **What counts as "connected":** any non-zero `EXTRA_PLUGGED` (AC, USB, wireless, dock). A phone
  plugged into a computer counts as connected. If the computer sleeps and cuts USB power, that is a
  real disconnect and will alarm after the confirmation window.
* **Full battery / charge limiting (80–85 %):** still connected, so no alarm. Verified in unit
  tests. Behaviour on your specific phone must be confirmed manually (see TESTING.md).
* **Wireless pads:** shifting the phone can briefly break the connection. Use a 1–3 s confirmation
  window. "Instant" mode will alarm on any break.
* **Power banks / smart chargers** that pause output when the battery is full may report a
  disconnect, which ChargeGuard treats as a real disconnect.
* **Detection latency:** broadcast-driven. Measured on the test phone: **1.14 s** from a physical
  cable pull to the alarm with the default 1 s confirmation window, and **58 ms** from replug to stop.
  A 5 s poll acts as a safety net if a broadcast is ever missed.

## Product / distribution notes

* The foreground service type is `mediaPlayback`, as specified, because the service owns the siren.
  Google Play reviews foreground-service types. A Play release may need to justify this or move
  monitoring to a `specialUse` type with a written justification.
* UI strings in Compose are English and not yet extracted to resources (notification strings are).
* The Home "Monitoring unavailable" state after a system kill is only visible once the app process
  runs again (opening the app, or a system service restart). A dead process can't notify anyone.

## Verification status

See `docs/TESTING.md` for which behaviours were verified by automated tests, which on a physical
device, and which remain unverified.
