/**
 * useWearableStream – React hook that manages the WebSocket connection
 * to the FastAPI dashboard broadcast endpoint.
 *
 * Returns:
 *   status      – "connecting" | "connected" | "disconnected"
 *   latestFrame – the most recent parsed telemetry frame (or null)
 *   history     – rolling window of the last MAX_HISTORY frames
 *   recording   – whether the backend is currently writing a session log
 *   sessionFile – current session filename (or null)
 *   sendControl – function to send a control JSON object to the backend
 */

import { useState, useEffect, useRef, useCallback } from "react";

const WS_URL = "ws://localhost:8000/ws/dashboard";
const MAX_HISTORY = 100; // keep last 100 data points (~5 s at 20 Hz)
const RECONNECT_DELAY_MS = 2000;

export function useWearableStream() {
  const [status, setStatus] = useState("connecting");
  const [latestFrame, setLatestFrame] = useState(null);
  const [history, setHistory] = useState([]);
  const [recording, setRecording] = useState(false);
  const [sessionFile, setSessionFile] = useState(null);

  const wsRef = useRef(null);
  const reconnectTimer = useRef(null);
  const unmounted = useRef(false);

  const connect = useCallback(() => {
    if (unmounted.current) return;
    setStatus("connecting");

    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;

    ws.onopen = () => {
      if (unmounted.current) { ws.close(); return; }
      setStatus("connected");
    };

    ws.onmessage = (event) => {
      let data;
      try {
        data = JSON.parse(event.data);
      } catch {
        return;
      }

      // Meta control messages from the backend
      if (data.type === "meta") {
        setRecording(data.recording ?? false);
        setSessionFile(data.session_filename ?? null);
        return;
      }

      // Telemetry frame — flatten motion batch into individual history points
      const motionSamples = data.motion ?? [];
      const flatPoints = motionSamples.map((m) => ({
        ts: m.ts,
        ax: parseFloat(m.accel?.x?.toFixed(4) ?? 0),
        ay: parseFloat(m.accel?.y?.toFixed(4) ?? 0),
        az: parseFloat(m.accel?.z?.toFixed(4) ?? 0),
        gx: parseFloat(m.gyro?.x?.toFixed(4) ?? 0),
        gy: parseFloat(m.gyro?.y?.toFixed(4) ?? 0),
        gz: parseFloat(m.gyro?.z?.toFixed(4) ?? 0),
        heart_rate: data.heart_rate ?? null,
        steps: data.steps ?? null,
      }));

      setLatestFrame(data);
      setHistory((prev) => {
        const next = [...prev, ...flatPoints];
        return next.length > MAX_HISTORY ? next.slice(next.length - MAX_HISTORY) : next;
      });
    };

    ws.onclose = () => {
      if (unmounted.current) return;
      setStatus("disconnected");
      reconnectTimer.current = setTimeout(connect, RECONNECT_DELAY_MS);
    };

    ws.onerror = () => {
      ws.close();
    };
  }, []);

  useEffect(() => {
    connect();
    return () => {
      unmounted.current = true;
      clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
    };
  }, [connect]);

  const sendControl = useCallback((obj) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(obj));
    }
  }, []);

  return { status, latestFrame, history, recording, sessionFile, sendControl };
}
