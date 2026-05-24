"""
MultiSense FastAPI WebSocket Backend
=====================================
Endpoints:
  WS  /ws/wearable   - Ingestion: watch / simulator pushes sensor frames here
  WS  /ws/dashboard  - Broadcast: web dashboard clients subscribe here
  GET /api/sessions  - List recorded session log files
  GET /api/sessions/{filename} - Download a specific session log
"""

import asyncio
import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SESSIONS_DIR = Path("sessions")
SESSIONS_DIR.mkdir(exist_ok=True)

# Maximum number of dashboard subscribers kept in memory
MAX_DASHBOARD_CLIENTS = 50

# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------
app = FastAPI(title="MultiSense Backend", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------
# Each subscriber is an asyncio.Queue that receives raw JSON strings
_dashboard_queues: list[asyncio.Queue] = []

# Active session log file handle (None when not recording)
_session_file: Optional[object] = None
_session_filename: Optional[str] = None
_recording: bool = False  # toggled via control messages from dashboard


def _current_session_path() -> Path:
    """Return the path for a new timestamped session log file."""
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return SESSIONS_DIR / f"session_{ts}.jsonl"


def _open_session() -> None:
    global _session_file, _session_filename, _recording
    if _session_file is not None:
        return  # already open
    path = _current_session_path()
    _session_file = open(path, "a", encoding="utf-8")  # noqa: WPS515
    _session_filename = path.name
    _recording = True
    log.info("Session started: %s", path)


def _close_session() -> None:
    global _session_file, _session_filename, _recording
    if _session_file is None:
        return
    _session_file.close()
    log.info("Session closed: %s", _session_filename)
    _session_file = None
    _session_filename = None
    _recording = False


async def _broadcast(payload: str) -> None:
    """Push a raw JSON string to every connected dashboard client."""
    dead: list[asyncio.Queue] = []
    for q in _dashboard_queues:
        try:
            q.put_nowait(payload)
        except asyncio.QueueFull:
            dead.append(q)
    for q in dead:
        _dashboard_queues.remove(q)


# ---------------------------------------------------------------------------
# WebSocket: Wearable ingestion
# ---------------------------------------------------------------------------
@app.websocket("/ws/wearable")
async def ws_wearable(ws: WebSocket) -> None:
    await ws.accept()
    log.info("Wearable client connected: %s", ws.client)
    try:
        while True:
            raw = await ws.receive_text()

            # Validate JSON (drop malformed frames, keep streaming)
            try:
                frame = json.loads(raw)
            except json.JSONDecodeError:
                log.warning("Malformed frame dropped")
                continue

            # Add server-side receive timestamp (ms epoch) if absent
            if "server_ts" not in frame:
                frame["server_ts"] = int(
                    datetime.now(timezone.utc).timestamp() * 1000
                )
                raw = json.dumps(frame)

            # Write to session log if recording
            if _recording and _session_file is not None:
                _session_file.write(raw + "\n")
                _session_file.flush()

            # Broadcast to all dashboard subscribers
            await _broadcast(raw)

    except WebSocketDisconnect:
        log.info("Wearable client disconnected: %s", ws.client)


# ---------------------------------------------------------------------------
# WebSocket: Dashboard broadcast
# ---------------------------------------------------------------------------
@app.websocket("/ws/dashboard")
async def ws_dashboard(ws: WebSocket) -> None:
    await ws.accept()
    log.info("Dashboard client connected: %s", ws.client)

    q: asyncio.Queue = asyncio.Queue(maxsize=200)
    _dashboard_queues.append(q)

    # Send current recording state immediately so UI can sync
    await ws.send_text(
        json.dumps(
            {
                "type": "meta",
                "recording": _recording,
                "session_filename": _session_filename,
            }
        )
    )

    try:
        while True:
            # Wait for a new telemetry frame or a control message from the client
            recv_task = asyncio.create_task(ws.receive_text())
            send_task = asyncio.create_task(q.get())

            done, pending = await asyncio.wait(
                {recv_task, send_task},
                return_when=asyncio.FIRST_COMPLETED,
            )

            for t in pending:
                t.cancel()

            if send_task in done:
                payload = send_task.result()
                await ws.send_text(payload)

            if recv_task in done:
                try:
                    ctrl = json.loads(recv_task.result())
                except json.JSONDecodeError:
                    continue

                action = ctrl.get("action")
                if action == "start_recording":
                    _open_session()
                    await ws.send_text(
                        json.dumps(
                            {
                                "type": "meta",
                                "recording": _recording,
                                "session_filename": _session_filename,
                            }
                        )
                    )
                elif action == "stop_recording":
                    _close_session()
                    await ws.send_text(
                        json.dumps(
                            {
                                "type": "meta",
                                "recording": _recording,
                                "session_filename": None,
                            }
                        )
                    )

    except WebSocketDisconnect:
        log.info("Dashboard client disconnected: %s", ws.client)
    finally:
        if q in _dashboard_queues:
            _dashboard_queues.remove(q)


# ---------------------------------------------------------------------------
# REST: Session management
# ---------------------------------------------------------------------------
@app.get("/api/sessions")
async def list_sessions() -> JSONResponse:
    files = sorted(SESSIONS_DIR.glob("session_*.jsonl"), reverse=True)
    return JSONResponse(
        [
            {
                "filename": f.name,
                "size_bytes": f.stat().st_size,
                "modified": datetime.fromtimestamp(
                    f.stat().st_mtime, tz=timezone.utc
                ).isoformat(),
            }
            for f in files
        ]
    )


@app.get("/api/sessions/{filename}")
async def download_session(filename: str) -> FileResponse:
    path = SESSIONS_DIR / filename
    if not path.exists() or not path.name.startswith("session_"):
        return JSONResponse({"error": "Not found"}, status_code=404)
    return FileResponse(
        path,
        media_type="application/x-ndjson",
        filename=filename,
    )


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse({"status": "ok", "recording": _recording})
