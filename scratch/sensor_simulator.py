"""
MultiSense Telemetry Simulator
================================
Generates synthetic sensor data mimicking a Galaxy Watch 4 and streams it
to the FastAPI backend at ws://localhost:8000/ws/wearable.

Motion Sensors  : 20 Hz sampling rate, batched to 20 samples per packet
                  → 1 packet sent every 1000 ms
Biometrics      : Heart rate + steps at 1 Hz, sent in the same packet

Usage:
    python sensor_simulator.py [--host HOST] [--port PORT] [--phase PHASE]

Phases:
    rest    - low HR (55–70 BPM), minimal motion
    walk    - moderate HR (80–110 BPM), periodic step motion
    run     - high HR (120–160 BPM), high-frequency vigorous motion
"""

import argparse
import asyncio
import json
import math
import random
import time
import sys

try:
    import websockets
except ImportError:
    sys.exit("Install websockets:  pip install websockets")


# ---------------------------------------------------------------------------
# Signal generation helpers
# ---------------------------------------------------------------------------
SAMPLE_RATE_HZ = 20
BATCH_SIZE = 20
SEND_INTERVAL_S = BATCH_SIZE / SAMPLE_RATE_HZ  # 1.0 s


def _noise(scale: float = 0.005) -> float:
    return random.gauss(0, scale)


def _accel_sample(t: float, phase: str) -> dict:
    """Simulated accelerometer (g units, approximate)."""
    if phase == "rest":
        return {
            "x": 0.02 + _noise(0.003),
            "y": 0.98 + _noise(0.003),   # gravity mostly on Y
            "z": 0.05 + _noise(0.003),
        }
    elif phase == "walk":
        freq = 1.8  # Hz – typical walking cadence (~108 steps/min / 60)
        return {
            "x": 0.15 * math.sin(2 * math.pi * freq * t) + _noise(0.01),
            "y": 0.98 + 0.10 * math.sin(2 * math.pi * freq * t + 1.2) + _noise(0.01),
            "z": 0.08 * math.cos(2 * math.pi * freq * t) + _noise(0.01),
        }
    else:  # run
        freq = 2.8  # Hz – typical running cadence (~168 steps/min / 60)
        return {
            "x": 0.45 * math.sin(2 * math.pi * freq * t) + _noise(0.02),
            "y": 0.98 + 0.35 * math.sin(2 * math.pi * freq * t + 0.9) + _noise(0.02),
            "z": 0.20 * math.cos(2 * math.pi * freq * t) + _noise(0.02),
        }


def _gyro_sample(t: float, phase: str) -> dict:
    """Simulated gyroscope (rad/s)."""
    if phase == "rest":
        return {"x": _noise(0.002), "y": _noise(0.002), "z": _noise(0.002)}
    elif phase == "walk":
        freq = 1.8
        return {
            "x": 0.30 * math.sin(2 * math.pi * freq * t + 0.3) + _noise(0.01),
            "y": 0.10 * math.cos(2 * math.pi * freq * t) + _noise(0.01),
            "z": 0.15 * math.sin(2 * math.pi * freq * t + 0.8) + _noise(0.01),
        }
    else:  # run
        freq = 2.8
        return {
            "x": 0.80 * math.sin(2 * math.pi * freq * t + 0.3) + _noise(0.02),
            "y": 0.35 * math.cos(2 * math.pi * freq * t) + _noise(0.02),
            "z": 0.50 * math.sin(2 * math.pi * freq * t + 0.8) + _noise(0.02),
        }


class BiometricState:
    """Slowly drifting heart rate and escalating step count."""

    HR_TARGETS = {"rest": 62, "walk": 95, "run": 142}
    HR_NOISE = {"rest": 2, "walk": 4, "run": 6}

    def __init__(self, phase: str) -> None:
        self.hr = float(self.HR_TARGETS[phase])
        self.steps = 0
        self.phase = phase

    def tick(self) -> None:
        target = self.HR_TARGETS[self.phase]
        noise = self.HR_NOISE[self.phase]
        # First-order lag towards target + noise
        self.hr += 0.05 * (target - self.hr) + random.gauss(0, noise * 0.1)
        self.hr = max(40.0, min(200.0, self.hr))

        if self.phase == "walk":
            self.steps += random.randint(1, 3)
        elif self.phase == "run":
            self.steps += random.randint(3, 6)

    @property
    def heart_rate(self) -> float:
        return round(self.hr, 1)


# ---------------------------------------------------------------------------
# Main streaming loop
# ---------------------------------------------------------------------------
async def stream(host: str, port: int, phase: str) -> None:
    uri = f"ws://{host}:{port}/ws/wearable"
    print(f"Connecting to {uri}  [phase={phase}]")

    reconnect_delay = 2  # seconds
    while True:
        try:
            async with websockets.connect(uri, ping_interval=20, ping_timeout=10) as ws:
                print(f"✓ Connected to {uri}")
                bio = BiometricState(phase)
                sample_interval = 1.0 / SAMPLE_RATE_HZ
                t = 0.0

                while True:
                    batch_start = time.monotonic()
                    motion_batch = []

                    # Collect BATCH_SIZE motion samples at SAMPLE_RATE_HZ
                    for _ in range(BATCH_SIZE):
                        ts_ms = int(time.time() * 1000)
                        motion_batch.append(
                            {
                                "ts": ts_ms,
                                "accel": _accel_sample(t, phase),
                                "gyro": _gyro_sample(t, phase),
                            }
                        )
                        t += sample_interval
                        await asyncio.sleep(sample_interval)

                    # Advance biometrics once per packet (1 Hz)
                    bio.tick()

                    payload = {
                        "device_id": "simulator_01",
                        "phase": phase,
                        "heart_rate": bio.heart_rate,
                        "steps": bio.steps,
                        "motion": motion_batch,
                    }

                    await ws.send(json.dumps(payload))

                    elapsed = time.monotonic() - batch_start
                    drift = SEND_INTERVAL_S - elapsed
                    if drift > 0:
                        await asyncio.sleep(drift)
                    else:
                        print(f"⚠ Timing overrun by {-drift*1000:.1f} ms")

        except (OSError, websockets.exceptions.ConnectionClosedError) as exc:
            print(f"✗ Connection lost ({exc}). Retrying in {reconnect_delay}s…")
            await asyncio.sleep(reconnect_delay)
        except KeyboardInterrupt:
            print("Simulator stopped.")
            break


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MultiSense Telemetry Simulator")
    parser.add_argument("--host", default="localhost", help="Backend host")
    parser.add_argument("--port", type=int, default=8000, help="Backend port")
    parser.add_argument(
        "--phase",
        choices=["rest", "walk", "run"],
        default="walk",
        help="Activity phase to simulate",
    )
    args = parser.parse_args()
    asyncio.run(stream(args.host, args.port, args.phase))
