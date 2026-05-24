# Phase 2: Wear OS App – Deployment Guide

## What was built

A native Kotlin Wear OS app for the Galaxy Watch 4 that:

- Runs a **foreground `SensorService`** that holds a `PARTIAL_WAKE_LOCK` so the CPU keeps running in screen-off / ambient mode
- Samples **Accelerometer + Gyroscope at 20 Hz** (50 000 µs period via `SensorManager`)
- **Batches 20 motion samples** (≈ 1 s) into a single JSON packet and sends it over WebSocket
- **Auto-reconnects** to the FastAPI backend within 2 s on any network failure (via `SocketClient`)
- Has a minimal **Jetpack Compose for Wear** UI with editable server IP, port, device ID, and Start/Stop button

Phase 3 stubs (heart rate, steps) are present in the payload as `null` fields — ready to be wired up via Wear Health Services.

---

## Prerequisites

1. **Android Studio** installed (you already have it: `Android Studio.app`)
2. **Android SDK** installed inside Android Studio with:
   - `Android 11 (API 30)` platform — **required** (Galaxy Watch 4 runs Wear OS 3.0 = API 30)
   - `Wear OS` system images (optional, for emulator)
3. **Galaxy Watch 4** with **Developer Options → ADB debugging** enabled (Settings → About Watch → tap "Software version" 7 times → Developer options → ADB debugging ON)

---

## Setup Steps

### 1. Open the project in Android Studio

```
File → Open → select the `watch-app/` directory
```

Android Studio will sync Gradle on first open (downloads dependencies — ~200 MB).

### 2. Set SDK path (if prompted)

`local.properties` already has:
```
sdk.dir=/Users/abhigyanshekhar/Library/Android/sdk
```

If Android Studio installed the SDK to a different path, update this file.

### 3. Install API 30 platform (if not already)

```
Tools → SDK Manager → Android 11 (API 30) → ✓ Install
```

### 4. Connect the Galaxy Watch 4 via ADB over Wi-Fi

On the watch:
```
Settings → Developer Options → ADB debugging ON
Settings → Developer Options → Wi-Fi debugging ON
```

On your Mac:
```bash
~/Library/Android/sdk/platform-tools/adb connect <WATCH_IP>:5555
~/Library/Android/sdk/platform-tools/adb devices   # should show the watch
```

### 5. Run the app

Select the watch in the device dropdown (top toolbar) and click ▶ **Run**.

Or from terminal:
```bash
cd watch-app
./gradlew installDebug
```

---

## Usage on the watch

1. **Start the FastAPI backend first** (see README.md in project root)
2. Open the MultiSense app on the watch
3. Enter your Mac's local IP address (e.g. `192.168.1.X`) — same network as the watch
4. Port: `8000`, Device ID: your choice
5. Tap **Start** → the foreground notification appears: "Streaming ● galaxy_watch_4 → server"
6. The React dashboard at `http://localhost:5173` will show the live motion charts

---

## Phase 2 Feasibility Checks

When testing on the real device, verify these **before** moving to Phase 3:

| Check | Expected |
|-------|----------|
| Accelerometer data appears on dashboard | Within 3 s of tapping Start |
| Data continues with screen off (ambient) | WakeLock holds, packets keep arriving |
| 30 min continuous recording | No dropouts in session JSONL log |
| Reconnect after toggling Wi-Fi | Socket reconnects within 2 s |
| Battery drain over 30 min | < 8% (walk phase, Wi-Fi active) |

---

## Known Wear OS Caveats (Phase 2)

- **Battery optimisation**: Some Wear OS builds aggressively kill background services even with `PARTIAL_WAKE_LOCK`. If this happens, you may need to add the app to the "Always allowed" battery optimiser list on the watch.
- **Sensor rate**: The actual delivered sample rate may differ from the requested 20 Hz. Log the inter-sample timestamps in `SensorService` to measure actual rate on-device.
- **OkHttp on Wear**: `OkHttpClient` is included via the app's own classpath — no system library dependency issues expected.
