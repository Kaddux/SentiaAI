import FlashValue from "./FlashValue";
import HrTrace from "./HrTrace";

function num(v: any): number | null {
  return typeof v === "number" && !Number.isNaN(v) ? v : null;
}
function accelMag(r: any): number | null {
  const x = num(r?.accelX), y = num(r?.accelY), z = num(r?.accelZ);
  return x !== null && y !== null && z !== null ? Math.sqrt(x * x + y * y + z * z) : null;
}
function ageSeconds(iso: string | null): number | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? null : Math.max(0, (Date.now() - t) / 1000);
}
function ago(iso: string | null): string {
  const s = ageSeconds(iso);
  if (s === null) return "no reading";
  if (s < 60) return `${Math.round(s)}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  return `${Math.round(s / 3600)}h ago`;
}

export default function LiveNow({
  latest,
  readings,
  steps,
  stepsMoving = false,
}: {
  latest: any;
  readings: any[];
  steps: number | null;
  stepsMoving?: boolean;
}) {
  const age = ageSeconds(latest?.recordedAt ?? null);
  const status = !latest ? "waiting" : age !== null && age <= 20 ? "live" : "stale";
  const statusLabel = status === "live" ? "Live" : status === "stale" ? "No signal" : "Waiting for device";
  const bpm = num(latest?.heartRate);
  const fingerOff = latest?.fingerDetected === 0;
  const liveHr = status === "live" && bpm !== null && !fingerOff;
  const chrono = readings.slice().reverse();
  const values = chrono.map((r) => num(r.heartRate));

  return (
    <div className="card live-now-card">
      <div className="live-head">
        <span className={`live-pill ${status}`}>
          <span className={`live-dot ${status}`} />
          <span className="live-status">{statusLabel}</span>
        </span>
        <div className="spacer" />
        <span className="muted">{latest ? `updated ${ago(latest.recordedAt)}` : "raw device readings"}</span>
      </div>

      {!latest ? (
        <div className="live-now-empty">
          Waiting for the device — live values appear here immediately.
        </div>
      ) : (
        <>
          <div className="live-now-grid">
            <div>
              <div className="label">Heart rate</div>
              <div className="live-now-value"><FlashValue value={bpm} suffix=" bpm" /></div>
            </div>
            <div>
              <div className="label">Temperature</div>
              <div className="live-now-value">
                <FlashValue value={num(latest.temperature)} digits={1} suffix=" °C" />
              </div>
            </div>
            <div>
              <div className="label">Battery</div>
              <div className="live-now-value"><FlashValue value={num(latest.batteryPct)} suffix=" %" /></div>
            </div>
            <div>
              <div className="label">Accel</div>
              <div className="live-now-value"><FlashValue value={accelMag(latest)} digits={2} suffix=" g" /></div>
            </div>
            <div>
              <div className="label">Steps today</div>
              <div className="live-now-value">
                <FlashValue value={steps} />
                {stepsMoving && <span className="move-dot" title="Moving" />}
              </div>
            </div>
          </div>
          <HrTrace values={values} live={liveHr} />
          <div className="live-now-note">
            {status !== "live"
              ? "No signal — the latest reading is older than 20 seconds."
              : fingerOff
                ? "Finger not detected — HR is not being measured. Steps still update live."
                : bpm === null
                  ? "No live HR in the latest reading. Steps still update live."
                  : "Live HR and steps update every 2 s; daily cards update after each 5-min window."}
          </div>
        </>
      )}
    </div>
  );
}
