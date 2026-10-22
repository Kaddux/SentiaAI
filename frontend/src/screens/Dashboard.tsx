import { useEffect, useState } from "react";
import { api } from "../api";
import Sparkline from "../components/Sparkline";
import CountUp from "../components/CountUp";
import SleepLogCard from "../components/SleepLogCard";

function num(v: any): number | null {
  return typeof v === "number" && !Number.isNaN(v) ? v : null;
}
function pick(obj: any, key: string): any {
  return obj && typeof obj === "object" ? obj[key] : undefined;
}
function trendSeries(trend: any): (number | null)[] {
  const vals = trend?.values;
  if (!Array.isArray(vals)) return [];
  return vals.map((v: any) => {
    const n = parseFloat(v?.value);
    return Number.isNaN(n) ? null : n;
  });
}

export default function Dashboard() {
  const [today, setToday] = useState<any>(null);
  const [wellness, setWellness] = useState<any>(null);
  const [tHr, setTHr] = useState<any>(null);
  const [tSteps, setTSteps] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [liveSteps, setLiveSteps] = useState<number | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const [td, wl, hr, st] = await Promise.all([
          api.today(), api.wellness(), api.trends("heart_rate", 7), api.trends("steps", 7),
        ]);
        setToday(td);
        setWellness(wl);
        setTHr(hr);
        setTSteps(st);
      } catch (e: any) {
        setError(e.message);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  // Poll the estimated step count every 5 s so the card updates while walking.
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const s = await api.steps();
        if (alive) setLiveSteps(typeof s?.steps === "number" ? s.steps : null);
      } catch {
        /* keep the previous value */
      }
    };
    tick();
    const id = window.setInterval(tick, 5000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  if (loading)
    return (
      <>
        <h2 className="section-title">
          Today at a <span className="hl">glance</span>
        </h2>
        <div className="grid cols-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <div className="card anim-up" key={i} style={{ animationDelay: `${i * 50}ms` }}>
              <div className="skel line" style={{ width: "42%" }} />
              <div className="skel big" />
              <div className="skel" style={{ height: 40, marginTop: 10 }} />
            </div>
          ))}
        </div>
      </>
    );
  if (error) return <div className="empty">{error}</div>;

  const hr = pick(today, "heart_rate") || {};
  const act = pick(today, "activity") || {};
  const temp = pick(today, "temperature") || {};

  const metrics = [
    { label: "Resting HR", value: num(hr.resting), digits: 0, suffix: " bpm", spark: trendSeries(tHr) },
    { label: "Avg HR", value: num(hr.average), digits: 0, suffix: " bpm", spark: trendSeries(tHr) },
    { label: "Steps", value: liveSteps ?? num(act.steps), digits: 0, suffix: "", spark: trendSeries(tSteps) },
    { label: "Active min", value: num(act.active_minutes), digits: 0, suffix: " min", spark: trendSeries(tSteps) },
    { label: "Temp", value: num(temp.average), digits: 1, suffix: " °C", spark: [] },
  ];

  return (
    <>
      <h2 className="section-title">
        Today at a <span className="hl">glance</span>
      </h2>

      <div className="grid cols-3">
        {metrics.map((m, i) => (
          <div className="card lift anim-up" key={m.label} style={{ animationDelay: `${i * 60}ms` }}>
            <div className="label">{m.label}</div>
            <div className="value">
              <CountUp value={m.value} digits={m.digits} suffix={m.suffix} />
            </div>
            <Sparkline values={m.spark} lime={m.label === "Steps"} />
          </div>
        ))}
      </div>

      <div className="grid cols-3" style={{ marginTop: 16 }}>
        <div className="card lift anim-up" style={{ gridColumn: "span 3", animationDelay: "120ms" }}>
          <div className="label">Wellness</div>
          <div className="value" style={{ fontSize: 22 }}>
            <CountUp
              value={num(wellness?.wellness_score)}
              suffix={num(wellness?.wellness_score) !== null ? " / 100" : ""}
            />
          </div>
          <div className="meter">
            <span
              style={{
                width: `${Math.max(0, Math.min(100, num(wellness?.wellness_score) ?? 0))}%`,
              }}
            />
          </div>
          <div className="meta">
            {wellness?.wellness === "no data"
              ? "no assessment yet"
              : `stress ${wellness?.stress_level ?? "—"} · confidence ${wellness?.confidence ?? "—"}`}
          </div>
        </div>
      </div>

      <SleepLogCard onOpenDetail={() => {}} />
    </>
  );
}
