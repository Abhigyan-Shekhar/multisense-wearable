# MultiSense Wearable System

A real-time sensor streaming system for **Samsung Galaxy Watch 4** (Wear OS 3) with a live web dashboard. This repository contains the source code for the Wear OS watch application (`watch-app`), the FastAPI WebSocket ingestion server (`backend`), the Vite+React visualization dashboard (`dashboard`), and a synthetic telemetry generator (`scratch/sensor_simulator.py`).

---

## System Architecture & Phased Roadmap

To ensure reliable, battery-efficient, and continuous operation, the system is designed around a multi-phase engineering plan:

1. **Phase 1 (Complete):** Local simulation framework. FastAPI WebSocket server, React dashboard, and synthetic Python client generating 20 Hz IMU and 1 Hz health data.
2. **Phase 2 (Complete):** Native Wear OS 3 application. Implements a foreground `SensorService` with a CPU wake lock, sampling raw Accelerometer and Gyroscope at 20 Hz, batching into 20-sample packets (1 Hz packet transmission), and streaming directly over WebSocket with auto-reconnect.
3. **Phase 3 (Planned):** Health services integration. Replacing synthetic heart rate and step stubs in the Wear OS payload with real-time data from Wear Health Services.
4. **Phase 4 (Planned - Target Production Architecture):** Watch-to-Phone Companion architecture. Offloading network traffic by transmitting data from Watch → Phone via the **Wearable Data Layer API** (Bluetooth), with the companion phone app forwarding telemetry to the server. This reduces watch battery drain and increases connection robustness.

---

## Repository Layout

```
multisense-wearable/
├── backend/                  # FastAPI WebSocket Server
│   ├── main.py               # WS endpoints & session logger
│   └── requirements.txt      # Python dependencies (fastapi, uvicorn, websockets)
├── dashboard/                # Vite + React Visualization Dashboard
│   ├── src/
│   │   ├── App.jsx           # UI layout, charts, and BPM cards
│   │   ├── useWearableStream.js # WS connection hook with auto-reconnect
│   │   ├── SessionPanel.jsx  # Logs management panel
│   │   └── index.css         # Dark-themed styling
│   └── package.json
├── watch-app/                # Native Wear OS 3 App (Kotlin)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml # Permissions (BODY_SENSORS, FOREGROUND_SERVICE, WAKE_LOCK, INTERNET)
│   │   │   └── java/com/multisense/wearable/
│   │   │       ├── MainActivity.kt # Compose UI for server configuration & control
│   │   │       ├── SensorService.kt # Foreground service for 20 Hz sensor sampling
│   │   │       └── SocketClient.kt # OkHttp WebSocket client with 2s reconnection
│   └── build.gradle.kts
├── scratch/
│   └── sensor_simulator.py   # 20 Hz Accel/Gyro synthetic generator
└── sessions/                 # Directory auto-created by backend to store JSONL logs
```

---

## Measurable Engineering Specifications

The system is developed and benchmarked against the following strict parameters:

| Metric | Target Specification | Status |
|--------|----------------------|--------|
| **UI End-to-End Latency** | ≤ 250 ms | Verified (Phase 1 & 2) |
| **IMU Sample Rate** | 20 Hz (50 ms inter-sample period) | Verified (Phase 1 & 2) |
| **Packet Batching** | 20 samples per packet (1 packet/sec) | Verified (Phase 1 & 2) |
| **Socket Reconnection** | Auto-reconnect within 2.0 seconds | Verified (Phase 1 & 2) |
| **Continuous Recording** | ≥ 30 minutes without dropouts | Verified (Phase 1 & 2 Simulator) |
| **Session Format** | JSON Lines (JSONL), one JSON per line | Verified (Phase 1 & 2) |
| **Watch Battery Drain** | < 8% per 30 minutes (Active Wi-Fi, 20 Hz) | Target for Phase 2 device validation |

---

## Setup & Startup Instructions

### 1. Ingestion Backend
1. Initialize the Python virtual environment and install dependencies:
   ```bash
   cd backend
   python3 -m venv .venv
   source .venv/bin/activate
   pip install -r requirements.txt
   ```
2. Start the Uvicorn server:
   ```bash
   uvicorn main:app --reload --host 0.0.0.0 --port 8000
   ```
   *The backend is live at `http://localhost:8000`. WebSocket endpoints are located at `/ws/wearable` (ingestion) and `/ws/dashboard` (broadcast).*

### 2. React Web Dashboard
1. Install Node modules and run the development server:
   ```bash
   cd dashboard
   npm install
   npm run dev
   ```
2. Open `http://localhost:5173` in your browser to view the real-time plots, BPM cards, and session log history.

### 3. Running the Simulator (Alternative to Real Watch)
If you do not have a physical watch connected, run the simulator script to stream high-fidelity synthetic data:
```bash
python scratch/sensor_simulator.py --phase walk
```
*Supported phases: `rest` | `walk` | `run` (adjusts Accel/Gyro frequencies and average BPM).*

---

## Phase 2: Wear OS App Deployment Guide

The native Android app targets Wear OS 3.0 (API 30) to support Samsung Galaxy Watch 4 and newer.

### Prerequisites
- **Android Studio** (2023.x or newer recommended).
- **Android SDK Platform 30** installed via SDK Manager (`Tools → SDK Manager`).
- **Galaxy Watch 4** connected to the same Wi-Fi network as your workstation.

### Step-by-Step Deployment
1. **Enable Developer Options on Watch:**
   - On the watch, navigate to `Settings → About Watch → Software Info`.
   - Tap `Software Version` 7 times until a toast reads "Developer mode turned on".
   - Go back to `Settings → Developer Options`.
   - Enable **ADB Debugging** and **Wireless Debugging**.
   
2. **Establish ADB Wi-Fi Connection:**
   - Under `Settings → Developer Options → Wireless Debugging`, note the IP address and Port (e.g., `192.168.1.50:5555`).
   - Open a terminal on your workstation and connect using adb (located in `~/Library/Android/sdk/platform-tools/` on macOS by default):
     ```bash
     adb connect <WATCH_IP>:<PORT>
     adb devices
     ```
   - Approve the workstation connection prompt on your watch face.

3. **Build and Install:**
   - Open the `/watch-app` subdirectory in Android Studio.
   - Wait for the Gradle sync to complete.
   - Select the connected watch from the device dropdown list and click **Run (▶)**.
   - Alternatively, build and install via CLI:
     ```bash
     cd watch-app
     ./gradlew installDebug
     ```

4. **App Configuration & Streaming:**
   - Open the **MultiSense** app on the watch.
   - Input your workstation's local IP address (e.g., `192.168.1.10`), Port `8000`, and a custom Device ID.
   - Tap **Start** to begin streaming. A persistent foreground notification indicating "Streaming ● <device_id> → server" will appear.
   - Raw motion data will stream immediately to the dashboard.

---

## Session Logs & Data Verification
- **Log Location:** All streaming sessions are saved under `sessions/session_<timestamp>.jsonl`.
- **Formatting:** Each line is a valid JSON object matching the format:
  ```json
  {
    "device_id": "galaxy_watch_4",
    "timestamp": 1716534958123,
    "sensors": {
      "accelerometer": [{"x": 0.12, "y": 9.81, "z": -0.45, "t": 1716534958000}],
      "gyroscope": [{"x": -0.01, "y": 0.02, "z": 0.00, "t": 1716534958000}],
      "heart_rate": 78,
      "steps": 1420
    }
  }
  ```
- Logs can be managed and downloaded directly through the dashboard UI.
