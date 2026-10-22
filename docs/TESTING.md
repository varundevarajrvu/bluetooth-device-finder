# Testing Locate

## Automated (no phone needed)

```bash
./gradlew testDebugUnitTest   # 75 JVM tests: detector, safe zones, geo, permissions, sighting repo, monitor end-to-end
./gradlew lintDebug
```

`PresenceMonitorTest` runs the real monitor, detector and Room database against `FakeBleSource` and a fake location provider on virtual time. It covers walk-away → alert, safe zone, reconnect, still-advertising, Bluetooth off, alerts disabled, unregistered devices and dismiss.

## Emulator (UI only)

Emulators have no Bluetooth, so build against the fake:

```bash
./gradlew installDebug -Plocate.fakeBle=true
```

1. Add "Fake Buds". The device detail screen then shows **Fake Bluetooth** buttons.
2. Set a location in the emulator's Extended controls → Location.
3. Tap **Connect**, wait 30 s or more, then tap **Disconnect**.
4. Within 10 min, move the emulator more than 150 m. The easiest way is to play a route from Extended controls → Location → Routes.

## M1 on a real phone

You need:
- an Android phone
- a USB cable
- `adb`: Android Studio, or Google's "SDK Platform-Tools"
- a Bluetooth device you've **already paired** in system settings. A speaker is the easiest first test because it stays on when you put it down.

### Install

1. On the phone, enable Developer options and USB debugging.
2. Install the app:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   You can also download `locate-debug-apk` from the PR's CI run.
3. Keep a log running during the tests:
   ```bash
   adb logcat -s Locate
   ```
   Every disconnect logs a line like `Episode 1: ALERTED - Phone moved 132 m away`.

The **Log** screen in the app (top bar) shows the same thing: every disconnect, and why Locate did or didn't alert.

### Test 0: Onboarding (5 min)

1. Open Locate and allow everything:
   - Location: **Precise**
   - Background location: **Allow all the time**
   - Battery: **Allow**
   - Expect: the home screen shows no red or amber cards.
2. Denial path. Run `adb shell pm clear app.locate`, open the app, and tap **Not now** or **Don't allow** on everything. On a permission's second denial, expect **Open settings** instead of **Try again**.
   - Expect on home: red cards for Nearby devices, Location and Notifications, and an amber card for Battery.
   - Tap **Fix**. Only the missing steps should be shown.
3. Grant everything again before continuing.

### Test 1: Registration

1. Tap **Add device**.
   - Expect: your paired device is listed. The dBm value appears only if it's advertising; many earbuds don't advertise while connected.
   - Expect: unpaired devices nearby are listed greyed out, with "Pair it first", and can't be tapped.
2. Add the device with a nickname.
   - Expect: a persistent "Watching 1 device" notification.
3. Turn the device off and on.
   - Expect: its card shows "Disconnected…" then "Connected…", with "phone location ±N m".

### Test 2: Safe zone, at home

1. Go to **Safe zones** → **Add zone** → **Use my current location**, name it "Home", set 150 m, and save.
2. Connect the device for at least 1 min, then switch it off or put it in its case.
3. Expect: no alert. Within about 20 s, the Log shows "No alert · Left inside safe zone "Home"".

### Test 3: Left behind, the real thing (outdoors, ~10 min)

1. Go somewhere outside every safe zone: a college ground, a park, a café.
2. Connect the device for at least 1 min.
3. Leave it **switched on** on a bench or table. Walk away at a normal pace, with the phone in your pocket and the screen off.
4. Expect: about **1.5–3 min** after you walk off, roughly 150–250 m away, a heads-up notification: **"You may have left <nickname> behind"**. It has two buttons, **It's with me** and **Show where**.
5. Tap **Show where**.
   - Expect: your maps app opens at where your phone was when the device disconnected.
6. Walk back.
   - Expect: when the device reconnects, the alert disappears.
7. Check the Log.
   - Expect: "Alerted · Phone moved N m away".

### Test 4: Debounce (false-alarm guard)

1. Outside a safe zone, while walking, switch the device off and back on within about 30 s.
2. Expect: no alert. The Log says "Came back · Reconnected after Ns".

### Test 5: Bluetooth switched off

1. Outside a safe zone, connect the device for at least 1 min.
2. Turn Bluetooth off from quick settings, then walk 300 m.
3. Expect: no alert. The Log says "Bluetooth was turned off".

### Test 6: The known false alarm

Do this so you know what it looks like.

1. Outside a safe zone, put the earbuds in their case, put the case in your pocket, and walk 200 m.
2. Expect: **an alert**. Locate can't tell "in the case" from "on the table".
3. Tap **It's with me**.
   - Expect: the Log says "Dismissed".

Tell me how often this happens in normal use. It's the number we're tuning against.

### Test 7: Survival (the OEM-killer test)

1. **Swipe Locate away from recents**, then repeat Test 3.
   - Expect: it still alerts.
   - If it doesn't, look at the Log for that disconnect. No row means the phone maker killed Locate. Fix the phone's autostart and battery settings, then tell me the phone model.
2. **Reboot** the phone and don't open Locate.
   - Expect: the "Watching" notification is back within about a minute of unlocking. Repeat Test 3.
3. **Force stop:** run `adb shell am force-stop app.locate`.
   - Expect: Locate does nothing until you open it again. That's an Android rule, not a bug. Opening Locate resumes monitoring.

### What to send back

- Phone make, model and Android version; device make and model.
- Pass or fail for each test.
- For each failure: a screenshot of the Log screen, and
  ```bash
  adb logcat -d -s Locate > locate.log
  ```
