# Locate

An Android app that tells you when you've **left a Bluetooth device behind**: earbuds, speakers, watches. It's built for the cheap devices that Find Hub and SmartThings Find don't cover.

When a device you've registered disconnects and your phone is moving away from where it disconnected, outside your safe zones, you get an alert: **"You may have left your earbuds behind."**

> Bluetooth devices have no GPS. Locate shows where **your phone** was when it last saw or connected to a device. It is not live tracking.

**Status:** M1, the left-behind alert, is built. Next come M2 (map), M3 (Find Nearby) and M4 (OEM keep-alive guidance, plus the full README and roadmap).

## Build

You need JDK 17+ and the Android SDK (platform 37). Point `local.properties` at the SDK with `sdk.dir=...`.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug
./gradlew installDebug -Plocate.fakeBle=true   # emulator: fake Bluetooth with simulate buttons
```

Stack: Kotlin, Jetpack Compose, Hilt, Coroutines/Flow, Room, WorkManager and the fused location provider. minSdk is 26. There is no backend, and all data stays on the phone.

## Docs

- [docs/DESIGN.md](docs/DESIGN.md): the architecture, device identification across MAC rotation, battery strategy, alert gates, and OEM risks.
- [docs/TESTING.md](docs/TESTING.md): the automated tests, emulator use, and **a step-by-step real-phone test script**.

## Known limitations (so far)

- **Earbuds put in their case look the same as earbuds left behind**, so expect the occasional false alarm. Dismissing one takes a tap.
- **Phone makers' battery killers** (Xiaomi, Vivo, Oppo, Realme, Samsung) can stop background monitoring. Onboarding asks for a battery-optimisation exemption; per-OEM guidance arrives in M4.
- **A force-stopped app receives nothing until it's opened again.** That's an Android rule.
- **Only paired devices can be added**, by design: it keeps the app from being used to track other people's devices.
