/**
 * App – Main dashboard component.
 *
 * Layout:
 *   Header  – title, connection badge, record/stop button
 *   Sidebar – metric cards (BPM, Steps, Phase, Session timer) + SessionPanel
 *   Content – Accelerometer chart, Gyroscope chart
 */

import { useState, useEffect, useRef } from "react";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts";
import { useWearableStream } from "./useWearableStream";
import { SessionPanel } from "./SessionPanel";
import "./index.css";

// ─── Helpers ───────────────────────────────────────────────────────────────

function elapsed(startMs) {
  if (!startMs) return "--:--";
  const secs = Math.floor((Date.now() - startMs) / 1000);
  const m = String(Math.floor(secs / 60)).padStart(2, "0");
  const s = String(secs % 60).padStart(2, "0");
  return `${m}:${s}`;
}

// ─── BPM Card with beat animation ─────────────────────────────────────────

function BpmCard({ bpm }) {
  const [beating, setBeating] = useState(false);
  const prevBpm = useRef(null);

  useEffect(() => {
    if (bpm !== null && bpm !== prevBpm.current) {
      prevBpm.current = bpm;
      setBeating(true);
      const t = setTimeout(() => setBeating(false), 200);
      return () => clearTimeout(t);
    }
  }, [bpm]);

  return (
    <div className={`card bpm-card${beating ? " beating" : ""}`}>
      <div className="card-label">❤ Heart Rate</div>
      <div className="card-value">
        {bpm !== null ? Math.round(bpm) : "--"}
        <span className="card-unit">BPM</span>
      </div>
      <div className="card-subtext">Updated every ~1 s</div>
    </div>
  );
}

// ─── Motion chart ──────────────────────────────────────────────────────────

function MotionChart({ title, data, lines, legendItems }) {
  return (
    <div className="chart-section">
      <div className="chart-section-header">
        <span className="chart-title">{title}</span>
        <div className="chart-legend">
          {legendItems.map((l) => (
            <span key={l.label} className="legend-item">
              <span className="legend-dot" style={{ background: l.color }} />
              {l.label}
            </span>
          ))}
        </div>
      </div>
      <ResponsiveContainer width="100%" height={200}>
        <LineChart data={data} margin={{ top: 4, right: 12, left: -20, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#1f2535" />
          <XAxis dataKey="ts" hide />
          <YAxis
            domain={["auto", "auto"]}
            tick={{ fill: "#475569", fontSize: 10 }}
            width={50}
          />
          <Tooltip
            contentStyle={{
              background: "#141720",
              border: "1px solid #1f2535",
              borderRadius: 6,
              fontSize: 11,
              color: "#f1f5f9",
            }}
            itemStyle={{ color: "#94a3b8" }}
            labelFormatter={() => ""}
          />
          {lines.map((l) => (
            <Line
              key={l.key}
              type="monotone"
              dataKey={l.key}
              stroke={l.color}
              dot={false}
              strokeWidth={1.5}
              isAnimationActive={false}
              name={l.label}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

// ─── App ───────────────────────────────────────────────────────────────────

export default function App() {
  const { status, latestFrame, history, recording, sessionFile, sendControl } =
    useWearableStream();

  const [sessionStartMs, setSessionStartMs] = useState(null);
  const [elapsedStr, setElapsedStr] = useState("--:--");

  // Update elapsed timer every second
  useEffect(() => {
    if (!recording) {
      setSessionStartMs(null);
      setElapsedStr("--:--");
      return;
    }
    if (sessionStartMs === null) setSessionStartMs(Date.now());
    const id = setInterval(() => setElapsedStr(elapsed(sessionStartMs)), 1000);
    return () => clearInterval(id);
  }, [recording, sessionStartMs]);

  const handleRecordToggle = () => {
    if (recording) {
      sendControl({ action: "stop_recording" });
    } else {
      sendControl({ action: "start_recording" });
    }
  };

  const bpm = latestFrame?.heart_rate ?? null;
  const steps = latestFrame?.steps ?? null;
  const phase = latestFrame?.phase ?? null;
  const deviceId = latestFrame?.device_id ?? null;

  const ACCEL_LINES = [
    { key: "ax", color: "#3b82f6", label: "X" },
    { key: "ay", color: "#22c55e", label: "Y" },
    { key: "az", color: "#a855f7", label: "Z" },
  ];

  const GYRO_LINES = [
    { key: "gx", color: "#f59e0b", label: "X" },
    { key: "gy", color: "#06b6d4", label: "Y" },
    { key: "gz", color: "#ef4444", label: "Z" },
  ];

  return (
    <div className="layout">
      {/* ─── Header ─── */}
      <header className="header">
        <div className="header-title">
          <span className={`dot${status !== "connected" ? " offline" : ""}`} />
          MultiSense Dashboard
          {deviceId && (
            <span
              style={{
                fontSize: 11,
                color: "var(--text-muted)",
                fontWeight: 400,
                fontFamily: "var(--font-mono)",
              }}
            >
              / {deviceId}
            </span>
          )}
        </div>

        <div className="header-actions">
          <span
            className={`badge ${status === "connected" ? "connected" : "disconnected"}`}
            id="connection-badge"
          >
            <span className="badge-dot" />
            {status === "connected"
              ? "Connected"
              : status === "connecting"
              ? "Connecting…"
              : "Disconnected – retrying"}
          </span>

          <button
            id="record-toggle-btn"
            className={`btn ${recording ? "btn-danger" : "btn-primary"}`}
            onClick={handleRecordToggle}
            disabled={status !== "connected"}
          >
            {recording ? "⬛ Stop Recording" : "● Record Session"}
          </button>
        </div>
      </header>

      {/* ─── Main ─── */}
      <div className="main">
        {/* Sidebar */}
        <aside className="sidebar">
          <BpmCard bpm={bpm} />

          <div className="card">
            <div className="card-label">👟 Steps</div>
            <div className="card-value">
              {steps !== null ? steps.toLocaleString() : "--"}
            </div>
            <div className="card-subtext">Cumulative this session</div>
          </div>

          <div className="card">
            <div className="card-label">⚡ Activity Phase</div>
            <div
              className="card-value"
              style={{ fontSize: 20, textTransform: "capitalize", color: "var(--accent-cyan)" }}
            >
              {phase ?? "—"}
            </div>
          </div>

          <div className="card">
            <div className="card-label">⏱ Session Timer</div>
            <div
              className="card-value"
              style={{ fontSize: 26, fontFamily: "var(--font-mono)" }}
            >
              {recording ? elapsedStr : "--:--"}
            </div>
            <div className="card-subtext">
              {recording ? (
                <span className="badge recording" style={{ fontSize: 10 }}>
                  <span className="badge-dot" /> Recording
                </span>
              ) : (
                "Not recording"
              )}
            </div>
          </div>

          <SessionPanel recording={recording} sessionFile={sessionFile} />
        </aside>

        {/* Charts */}
        <main className="content">
          {history.length === 0 && (
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                height: "100%",
                color: "var(--text-muted)",
                fontSize: 13,
                flexDirection: "column",
                gap: 8,
              }}
            >
              <span style={{ fontSize: 32 }}>📡</span>
              <span>Waiting for sensor data…</span>
              <span style={{ fontSize: 11 }}>
                Start the simulator:{" "}
                <code
                  style={{
                    background: "var(--bg-card)",
                    padding: "2px 6px",
                    borderRadius: 4,
                    fontFamily: "var(--font-mono)",
                  }}
                >
                  python scratch/sensor_simulator.py --phase walk
                </code>
              </span>
            </div>
          )}

          {history.length > 0 && (
            <>
              <MotionChart
                title="Accelerometer (g)"
                data={history}
                lines={ACCEL_LINES}
                legendItems={ACCEL_LINES.map((l) => ({ label: `Accel ${l.label}`, color: l.color }))}
              />
              <MotionChart
                title="Gyroscope (rad/s)"
                data={history}
                lines={GYRO_LINES}
                legendItems={GYRO_LINES.map((l) => ({ label: `Gyro ${l.label}`, color: l.color }))}
              />

              {/* Last-frame summary table */}
              <div className="card">
                <div className="card-label">Latest Packet Summary</div>
                <table
                  style={{
                    width: "100%",
                    borderCollapse: "collapse",
                    fontFamily: "var(--font-mono)",
                    fontSize: 12,
                  }}
                >
                  <tbody>
                    {[
                      ["Heart Rate", bpm !== null ? `${bpm} BPM` : "—"],
                      ["Steps", steps !== null ? steps : "—"],
                      ["Phase", phase ?? "—"],
                      ["Device", deviceId ?? "—"],
                      [
                        "Last Accel",
                        history.length
                          ? `X ${history.at(-1).ax}  Y ${history.at(-1).ay}  Z ${history.at(-1).az}`
                          : "—",
                      ],
                      [
                        "Last Gyro",
                        history.length
                          ? `X ${history.at(-1).gx}  Y ${history.at(-1).gy}  Z ${history.at(-1).gz}`
                          : "—",
                      ],
                    ].map(([label, value]) => (
                      <tr key={label} style={{ borderBottom: "1px solid var(--border)" }}>
                        <td
                          style={{
                            padding: "5px 0",
                            color: "var(--text-muted)",
                            width: 110,
                            fontSize: 11,
                          }}
                        >
                          {label}
                        </td>
                        <td style={{ padding: "5px 0", color: "var(--text-primary)" }}>
                          {value}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </main>
      </div>
    </div>
  );
}
