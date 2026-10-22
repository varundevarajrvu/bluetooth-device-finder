# Locate — Design notes

## The hard constraint

A Bluetooth device has no GPS. Every "location" in Locate is **where the phone was** when it last saw or connected to the device. There's live signal strength while the device is in range, and no remote tracking. The UI says so wherever a location is shown.

## Architecture

There is a single `:app` module, MVVM + Hilt, Coroutines/Flow, Room and Compose.

```
AclReceiver (manifest) ─┐
BootReceiver ───────────┼─> ServiceStarter ─> MonitorService (FGS: connectedDevice|location)
WatchdogWorker (15 min) ┘                          │
                                                   ▼
                                         PresenceMonitor (orchestrator)
             ┌─────────────┬────────────┬──────────┴──────────┬──────────────────┐
         BleSource   LocationProvider  LeftBehindDetector   SightingRepository  AlertNotifier
     (Android|Fake)  (Fused)           (pure Kotlin)        (Room; interface)    (Notifications)
```

- `BleSource` is an interface. `FakeBleSource` backs the unit tests and emulator builds (`-Plocate.fakeBle=true`).
- `LeftBehindDetector` is a pure state machine with no Android or clock dependencies; every input carries its own time. All thresholds live in `AlertConfig`.
- `SightingRepository` is an interface, so a future crowdsourced source can sit behind it.

## Why the connection, not scanning

Most cheap earbuds and speakers are Bluetooth **Classic**, so a BLE scan usually can't see them. The alert therefore runs on the link itself, through `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED`, which fire for both Classic and LE links.

These broadcasts are on Android's implicit-broadcast exemption list, so the manifest receiver wakes Locate even when its process is dead. That doesn't apply after a force-stop. A runtime receiver inside the service delivers them too, and the monitor drops the duplicates (2 s window).

BLE scanning is secondary: it records "seen" history for devices that advertise, and cancels a pending alert when the device turns out to be nearby.

## Device identification across MAC rotation

1. **Bonded devices (the only kind that can be registered):** the key is `BluetoothDevice.address`.
   - Classic devices don't rotate their address.
   - For bonded LE devices, the Bluetooth stack resolves rotating private addresses using the IRK exchanged at bonding. ACL events and filtered scans therefore report the stable identity address.
2. **Dual-mode devices whose LE address differs:** at registration we store a fingerprint: advertised name, manufacturer company ID and service UUIDs. It's reserved for M3's live Find Nearby view, labelled "likely your device". It never writes stored sightings or triggers alerts, because two identical earbuds would collide.
3. **Unbonded devices with rotating addresses:** these are deliberately not trackable, and they can't be registered.

## Anti-stalking

- Only devices **paired with this phone** can be registered. Pairing needs physical access and usually a button press on the device.
- Unpaired devices nearby are listed only to explain why they can't be added.
- Background scans always filter by registered addresses, so nothing is logged about anyone else's devices.

## Battery

| Activity | When | Cost |
|---|---|---|
| BLE scan, low power, address-filtered | 10 s every 5 min | ~3% radio duty |
| BLE scan, low latency | 10 s every 30 s, only while an alert is pending | bounded to ≤10 min |
| Location | cached fix on connect/seen; one high-accuracy fix on disconnect; a fix every 20 s only while an alert is pending | GPS only after a disconnect |
| Watchdog | WorkManager, every 15 min | negligible |

There is no continuous location while everything is connected.

## Alert logic and false-positive strategy

A disconnect opens an *episode*. Its **anchor** is the phone's position at the disconnect: a fix taken at most 60 s before it, or the first usable fix after. The episode alerts only if every gate passes:

| # | Gate | Default |
|---|---|---|
| 1 | The link was up long enough (not connection flapping) | ≥ 30 s |
| 2 | Bluetooth wasn't switched off around the disconnect | ±10 s |
| 3 | No reconnect within the debounce window, and no sighting at any point | 60 s debounce; sighting ≥ −80 dBm |
| 4 | The **anchor** is outside every safe zone, accuracy-aware: an accuracy circle touching the zone counts as inside | — |
| 5 | Fixes are accurate enough (cell-tower-only fixes are ignored) | ≤ 75 m |
| 6 | Moved away: distance from the anchor minus both accuracy radii, on N consecutive fixes, never getting closer | ≥ 100 m, N = 2, 15 m tolerance |
| 7 | The episode gives up if no gate fails but the phone never moves away | 10 min |

Gate 4 checks where the device was left, not where the phone is now. Leave earbuds at the café downstairs and walk into your Work zone, and you still get the alert.

- **One alert per episode.** It's dismissed with **It's with me**, and it clears itself when the device reconnects or is seen nearby.
- **Every outcome and its reason** goes to the Alert log screen, so thresholds can be tuned from real data.

### Known gaps

- **Earbuds put in their case look exactly like earbuds left on a table.** No public API tells the two apart, so the copy says "may have" and dismissal is one tap.
- **A device that died long before you left (over 10 min) won't alert.** The v1.1 fix is a geofence-exit trigger.
- **Devices that only advertise and never connect** get "seen" history but no alert.

## Background execution and OEM risks

- **Foreground service:** `MonitorService`, types `connectedDevice|location`. If background location isn't granted, it falls back to `connectedDevice` only.
- **Restarts:** after boot or an app update, from the ACL receiver, and from the 15-minute watchdog. On Android 12+, a background start needs the app to be exempt from battery optimisation, which onboarding asks for. If Android refuses the start, the user gets a "tap to resume" notification instead of a silent failure.

| OEM | What kills background apps | Fix |
|---|---|---|
| Xiaomi / Redmi / POCO | Autostart off by default (blocks the boot and ACL receivers); battery saver | Autostart on; battery "No restrictions"; lock in recents |
| Samsung | Sleeping / deep sleeping apps | "Never sleeping apps"; battery "Unrestricted" |
| Vivo / iQOO | Background power limits | Allow high background power use and autostart |
| Oppo / Realme / OnePlus | Auto-launch and background activity off | Enable both; lock in recents |

An M4 screen will deep-link to each of these settings.
