import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import Sparkline from "../components/Sparkline";
import FlashValue from "../components/FlashValue";

type Reading = {
  recordedAt: string | null;
  heartRate: number | null;
  temperature: number | null;
  accelX: number | null;
  accelY: number | null;
  accelZ: number | null;
  batteryV: number | null;
  batteryPct: number | null;
  fingerDetected: number | null;
};

function num(v: any): number | null {
  return typeof v === "number" && !Number.isNaN(v) ? v : null;
}
function accelMag(r: Reading | null): number | null {
  if (!r) return null;
  const x = num(r.accelX), y = num(r.accelY), z = num(r.accelZ);
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

function Metric({ label, value, digits = 0, suffix = "" }: {
  label: string; value: number | null; digits?: number; suffix?: string;
}) {
  return (
    <div className="card lift anim-up live-metric">
      <div className="label">{label}</div>
      <div className="value">
        <FlashValue value={value} digits={digits} suffix={suffix} />
      </div>
    </div>
  );
}

function HeartRate({ bpm }: { bpm: number | null }) {
  const beat = bpm && bpm > 0 ? 60 / bpm : 1;
  return (
    <div className="card lift anim-up live-metric live-hr">
      <div className="label">Heart rate</div>
      <div className="live-hr-row">
        <svg
          className="hr-heart"
          viewBox="0 0 24 24"
          style={{ animationDuration: `${beat}s` }}
          aria-hidden="true"
        >
          <path d="M12 21C8 17.5 3 14 3 9.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 9 2.5C21 14 16 17.5 12 21Z" fill="currentColor" />
        </svg>
        <div className="value">
          <FlashValue value={bpm} suffix=" bpm" />
        </div>
      </div>
    </div>
  );
}

function EcgTrace() {
  const beat = "M0 20 H18 L21 20 L24 9 L27 31 L30 13 L33 20 H48 L50 16 L52 24 L54 20 H100";
  return (
    <div className="card ecg-card anim-up" style={{ animationDelay: "120ms" }}>
      <div className="label">ECG trace · live</div>
      <svg className="ecg" viewBox="0 0 400 40" preserveAspectRatio="none" aria-hidden="true">
        <g className="ecg-track">
          {[0, 100, 200, 300].map((x) => (
            <path key={x} d={beat} transform={`translate(${x} 0)`} />
          ))}
        </g>
      </svg>
    </div>
  );
}

export default function Live() {
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const alive = useRef(true);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => {
    alive.current = true;
    const tick = async () => {
      if (document.hidden) {
        timer.current = window.setTimeout(tick, 2000);
        return;
      }
      try {
        const d = await api.recent(60);
        if (alive.current) {
          setData(d);
          setError(null);
        }
      } catch (e: any) {
        if (alive.current) setError(e.message);
      } finally {
        if (alive.current) {
          setLoading(false);
          timer.current = window.setTimeout(tick, 2000);
        }
      }
    };
    tick();
    return () => {
      alive.current = false;
      if (timer.current) clearTimeout(timer.current);
    };
  }, []);

  const readings: Reading[] = (data?.readings ?? []) as Reading[];
  const latest: Reading | null = data?.latest ?? null;
  const age = ageSeconds(latest?.recordedAt ?? null);
  const status = !latest ? "waiting" : age !== null && age <= 20 ? "live" : "stale";
  const statusLabel = status === "live" ? "Live" : status === "stale" ? "No signal" : "Waiting for device";

  // Sparklines expect chronological order; the API returns newest first.
  const chrono = readings.slice().reverse();
  const hrSeries = chrono.map((r) => num(r.heartRate));
  const tempSeries = chrono.map((r) => num(r.temperature));

  let perMin = 0;
  if (readings.length >= 2) {
    const newest = new Date(readings[0].recordedAt ?? "").getTime();
    const oldest = new Date(readings[readings.length - 1].recordedAt ?? "").getTime();
    const span = (newest - oldest) / 1000;
    if (span > 0) perMin = ((readings.length - 1) / span) * 60;
  }

  return (
    <>
      <h2 className="section-title">
        Live <span className="hl">ingestion</span>
      </h2>

      <div className="card live-head-card" style={{ marginBottom: 16 }}>
        <div className="live-head">
          <span className={`live-pill ${status}`}>
            <span className={`live-dot ${status}`} />
            <span className="live-status">{statusLabel}</span>
          </span>
          <div className="spacer" />
          <span className="muted">
            {latest ? `last reading ${ago(latest.recordedAt)}` : "no readings in the last 10 min"}
          </span>
        </div>
        {error && <div className="error">{error}</div>}
      </div>

      {loading ? (
        <div className="grid cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div className="card anim-up" key={i} style={{ animationDelay: `${i * 50}ms` }}>
              <div className="skel line" style={{ width: "45%" }} />
              <div className="skel big" />
            </div>
          ))}
        </div>
      ) : !latest ? (
        <div className="empty">
          Waiting for the device… power it on and keep the UDP bridge running.
        </div>
      ) : (
        <>
          <div className="grid cols-4">
            <HeartRate bpm={num(latest.heartRate)} />
            <Metric label="Temperature" value={num(latest.temperature)} digits={1} suffix=" °C" />
            <Metric label="Battery" value={num(latest.batteryPct)} suffix=" %" />
            <Metric label="Accel magnitude" value={accelMag(latest)} digits={2} suffix=" g" />
          </div>

          <EcgTrace />

          <div className="grid cols-2" style={{ marginTop: 16 }}>
            <div className="card">
              <div className="label">Heart rate · last {readings.length} readings</div>
              <Sparkline values={hrSeries} lime />
            </div>
            <div className="card">
              <div className="label">Temperature · last {readings.length} readings</div>
              <Sparkline values={tempSeries} />
            </div>
          </div>

          <div className="card" style={{ marginTop: 16 }}>
            <div className="label">
              Recent readings · {Math.round(perMin)}/min · {num(latest.batteryV)?.toFixed(2) ?? "—"} V
            </div>
            <div className="live-table-wrap">
              <table className="dev" style={{ marginTop: 6 }}>
                <thead>
                  <tr>
                    <th>Time</th>
                    <th>HR</th>
                    <th>Temp</th>
                    <th>Battery</th>
                    <th>Finger</th>
                  </tr>
                </thead>
                <tbody>
                  {readings.slice(0, 6).map((r, i) => (
                    <tr key={r.recordedAt ?? i} className={i === 0 ? "row-new" : undefined}>
                      <td>{r.recordedAt ? new Date(r.recordedAt).toLocaleTimeString() : "—"}</td>
                      <td>{num(r.heartRate) ?? "—"}</td>
                      <td>{num(r.temperature)?.toFixed(1) ?? "—"}</td>
                      <td>{num(r.batteryPct) ?? "—"}%</td>
                      <td>{r.fingerDetected ? "yes" : "no"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </>
  );
}
