# MultiSense Wearable System

Real-time wearable telemetry pipeline for a Samsung Galaxy Watch running Wear OS, a FastAPI ingestion/broadcast backend, and a React dashboard for live visualization and session logging.

## What This Repo Does

This project streams live watch telemetry to a local backend and visualizes it in a browser.

Current end-to-end flow:

1. The Wear OS app collects accelerometer, gyroscope, heart rate, and step-counter data.
2. The watch batches motion samples into JSON packets and sends them over WebSocket.
3. The FastAPI backend receives those packets on `/ws/wearable`.
4. The backend optionally records packets to `sessions/session_<timestamp>.jsonl`.
5. The backend rebroadcasts live telemetry to dashboard clients on `/ws/dashboard`.
6. The React dashboard renders live charts, metric cards, connection status, and saved session logs.

There is also a Python simulator in `scratch/` that can generate synthetic telemetry without a physical watch.

## Current Pipeline

```text
Galaxy Watch / Simulator
        |
        |  WebSocket JSON packets
        v
FastAPI backend (port 8000)
  - /ws/wearable   ingest stream
  - /ws/dashboard  fan-out to UI
  - /api/sessions  list logs
  - /api/sessions/{file} download logs
        |
        v
React dashboard (port 5173)
  - live motion charts
  - heart-rate / steps cards
  - recording controls
  - session log browser
```

## What's Implemented

### Backend

`backend/main.py` currently provides:

- FastAPI app with permissive CORS for local development
- WebSocket ingestion endpoint at `/ws/wearable`
- WebSocket dashboard subscription endpoint at `/ws/dashboard`
- Live fan-out to multiple dashboard clients
- Session recording control via dashboard messages:
  - `start_recording`
  - `stop_recording`
- JSONL session persistence under `sessions/`
- REST endpoints to list and download session logs
- `/health` endpoint for a lightweight health check
- Server-side timestamp injection when `server_ts` is missing
- Malformed JSON frame rejection without killing the stream

### Dashboard

`dashboard/` is a Vite + React app using Recharts.

Implemented UI/features:

- Connection badge with reconnect state
- Record / stop toggle wired to backend control messages
- Heart-rate card
- Step-count card
- Activity phase card
- Session timer card
- Live accelerometer chart
- Live gyroscope chart
- Latest packet summary table
- Session log panel with refresh and download actions
- Automatic WebSocket reconnect handling in `src/useWearableStream.js`
- Cleanup-safe refresh/unmount behavior for the dashboard socket

### Watch App

`watch-app/` is a native Kotlin Wear OS app.

Implemented app behavior:

- Jetpack Compose UI for:
  - server IP
  - server port
  - device ID
  - Start / Stop streaming
- Foreground `SensorService`
- `PARTIAL_WAKE_LOCK` to keep sampling alive with screen off
- Accelerometer streaming
- Gyroscope streaming
- Heart-rate sensor integration using `TYPE_HEART_RATE`
- Step-counter integration using `TYPE_STEP_COUNTER`
- Session-relative step calculation from a baseline
- Motion batching at 20 samples per packet
- OkHttp WebSocket client with reconnect loop
- Runtime permission handling for:
  - `ACTIVITY_RECOGNITION`
  - `BODY_SENSORS`
- Manifest support for:
  - foreground data sync service
  - cleartext local-network traffic
  - body sensor access

### Simulator

`scratch/sensor_simulator.py` supports:

- synthetic motion generation at 20 Hz
- batched packet sends every second
- synthetic heart rate and step counts
- multiple activity phases:
  - `rest`
  - `walk`
  - `run`
- automatic reconnect on backend disconnect

## Repository Layout

```text
ODU internship - watch/
├── backend/
│   ├── main.py
│   └── requirements.txt
├── dashboard/
│   ├── package.json
│   └── src/
│       ├── App.jsx
│       ├── SessionPanel.jsx
│       ├── useWearableStream.js
│       ├── App.css
│       └── index.css
├── watch-app/
│   ├── DEPLOY.md
│   ├── build.gradle.kts
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       └── java/com/multisense/wearable/
│           ├── MainActivity.kt
│           ├── SensorService.kt
│           └── SocketClient.kt
├── scratch/
│   ├── sensor_simulator.py
│   └── test_ws.py
└── sessions/
```

## Data Shape

The stream payloads currently look like this:

```json
{
  "device_id": "galaxy_watch_4",
  "phase": "live",
  "heart_rate": 78.4,
  "steps": 125,
  "motion": [
    {
      "ts": 1716534958000,
      "accel": { "x": 0.12, "y": 9.81, "z": -0.45 },
      "gyro": { "x": -0.01, "y": 0.02, "z": 0.00 }
    }
  ]
}
```

When the backend receives a packet, it adds `server_ts` if the client did not send one.

## Setup

### 1. Backend

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Backend endpoints:

- `ws://localhost:8000/ws/wearable`
- `ws://localhost:8000/ws/dashboard`
- `http://localhost:8000/api/sessions`
- `http://localhost:8000/health`

### 2. Dashboard

```bash
cd dashboard
npm install
npm run dev
```

Open `http://localhost:5173`.

The dashboard auto-targets `127.0.0.1:8000` when running locally.

### 3. Simulator

Run this if you want to test the full pipeline without the watch:

```bash
cd scratch
python3 sensor_simulator.py --phase walk
```

Examples:

```bash
python3 sensor_simulator.py --phase rest
python3 sensor_simulator.py --phase walk
python3 sensor_simulator.py --phase run
```

### 4. Watch App

Detailed deployment steps are in [watch-app/DEPLOY.md](/Users/abhigyanshekhar/Desktop/ODU%20internship%20-%20watch/watch-app/DEPLOY.md).

Quick start:

1. Open `watch-app/` in Android Studio.
2. Connect the Galaxy Watch on the same network.
3. Run/install the debug build.
4. In the app, set:
   - backend IP
   - port `8000`
   - device ID
5. Tap `Start`.

## Versions and Stack

- Backend: FastAPI, Uvicorn, websockets
- Dashboard: React 19, Vite 8, Recharts
- Watch app: Kotlin, Jetpack Compose for Wear, OkHttp, Coroutines
- Android target: `compileSdk 34`, `targetSdk 34`, `minSdk 30`
- Java/Kotlin target: 17

## Recent Completed Work

The repository now reflects these completed items:

- real watch-side heart-rate streaming
- real watch-side step streaming
- runtime permission requests for motion/body sensors
- session log recording from the dashboard
- session log listing and download APIs
- safer dashboard WebSocket teardown on refresh/unmount
- defensive watch foreground-service startup
- `dataSync` foreground service type in the manifest
- cleartext LAN support for local backend connections

## Known Constraints

- The backend is configured for local-network development and allows broad CORS.
- The watch app currently sends directly to the backend over LAN WebSocket.
- Session logs are stored as JSONL on the backend filesystem.
- The dashboard expects the backend on port `8000` unless you change the code.
- The project does not yet implement phone-companion relay, cloud storage, or model inference in this repo.

## Validation Commands

Useful local checks:

```bash
cd backend && source .venv/bin/activate && uvicorn main:app --reload --host 0.0.0.0 --port 8000
cd dashboard && npm run build
cd watch-app && ./gradlew :app:assembleDebug
cd scratch && python3 sensor_simulator.py --phase walk
```

## Next Likely Enhancements

- configurable backend host/port for the dashboard
- richer session playback from saved JSONL logs
- stronger packet/schema validation
- phone companion transport for better watch battery life
- downstream inference pipeline on recorded/live sessions
