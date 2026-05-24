/**
 * SessionPanel – sidebar panel listing saved session log files.
 * Allows downloading any session as a JSONL file.
 */

import { useState, useEffect } from "react";

const getApiBase = () => {
  let host = typeof window !== "undefined" ? window.location.hostname : "127.0.0.1";
  if (!host || host === "localhost") {
    host = "127.0.0.1";
  }
  return `http://${host}:8000`;
};

export function SessionPanel({ recording, sessionFile }) {
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading] = useState(false);

  const fetchSessions = async () => {
    setLoading(true);
    try {
      const r = await fetch(`${getApiBase()}/api/sessions`);
      setSessions(await r.json());
    } catch {
      // backend unreachable
    } finally {
      setLoading(false);
    }
  };

  // Refresh list whenever recording state changes (session just closed)
  useEffect(() => {
    if (!recording) fetchSessions();
  }, [recording]);

  const download = (filename) => {
    window.open(`${getApiBase()}/api/sessions/${filename}`, "_blank");
  };

  const formatSize = (bytes) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
  };

  return (
    <div className="card" style={{ flex: 1 }}>
      <div
        className="card-label"
        style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}
      >
        <span>Session Logs</span>
        <button
          className="btn btn-ghost"
          style={{ padding: "2px 8px", fontSize: 11 }}
          onClick={fetchSessions}
          disabled={loading}
        >
          {loading ? "…" : "↻"}
        </button>
      </div>

      {sessionFile && (
        <div
          className="badge recording"
          style={{ marginBottom: 8, fontSize: 10 }}
        >
          <span className="badge-dot" />
          Recording: {sessionFile}
        </div>
      )}

      <div className="session-list">
        {sessions.length === 0 && (
          <p style={{ fontSize: 11, color: "var(--text-muted)", textAlign: "center", padding: "12px 0" }}>
            No sessions yet
          </p>
        )}
        {sessions.map((s) => (
          <div key={s.filename} className="session-item">
            <div>
              <div className="session-item-name">{s.filename}</div>
              <div style={{ fontSize: 10, color: "var(--text-muted)", marginTop: 2 }}>
                {formatSize(s.size_bytes)}
              </div>
            </div>
            <button
              className="btn btn-ghost"
              style={{ padding: "3px 8px", fontSize: 11 }}
              onClick={() => download(s.filename)}
            >
              ↓
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
