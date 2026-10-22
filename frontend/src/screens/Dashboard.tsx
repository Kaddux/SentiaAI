import { useEffect, useState } from "react";
import type { CSSProperties } from "react";
import { api } from "../api";
import Sparkline from "../components/Sparkline";
import CountUp from "../components/CountUp";
import SleepLogCard from "../components/SleepLogCard";
import SleepTile from "../components/SleepTile";
import SleepDetailModal from "../components/SleepDetailModal";
import WellnessRing from "../components/WellnessRing";

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
function deltaOf(series: (number | null)[]): number | null {
  const nums = series.filter((v): v is number => typeof v === "number");
  if (nums.length < 2) return null;
  return nums[nums.length - 1] - nums[nums.length - 2];
}
function rise(i: number) {
  return { ["--i" as any]: i } as CSSProperties;
}

export default function Dashboard() {
  const [today, setToday] = useState<any>(null);
  const [wellness, setWellness] = useState<any>(null);
  const [tHr, setTHr] = useState<any>(null);
  const [tSteps, setTSteps] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [liveSteps, setLiveSteps] = useState<number | null>(null);
  const [sleep, setSleep] = useState<any>(null);
  const [sleepModalOpen, setSleepModalOpen] = useState(false);
  const [sleepModalMode, setSleepModalMode] = useState<"past" | "detail">("detail");

  useEffect(() => {
    (async () => {
      try {
        const [td, wl, hr, st, sl] = await Promise.all([
          api.today(), api.wellness(), api.trends("heart_rate", 7), api.trends("steps", 7),
          api.sleepSummary(7),
        ]);
        setToday(td);
        setWellness(wl);
        setTHr(hr);
        setTSteps(st);
        setSleep(sl);
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

  async function refreshSleep() {
    try {
      setSleep(await api.sleepSummary(7));
    } catch {
      /* keep the previous summary */
    }
  }

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

  const hrSeries = trendSeries(tHr);
  const stepSeries = trendSeries(tSteps);
  const hrDelta = deltaOf(hrSeries);
  const stepDelta = deltaOf(stepSeries);
  const score = num(wellness?.wellness_score);

  const metrics = [
    { label: "Resting HR", value: num(hr.resting), digits: 0, suffix: " bpm", spark: hrSeries, delta: hrDelta, unit: " bpm" },
    { label: "Avg HR", value: num(hr.average), digits: 0, suffix: " bpm", spark: hrSeries, delta: null, unit: " bpm" },
    { label: "Steps", value: liveSteps ?? num(act.steps), digits: 0, suffix: "", spark: stepSeries, delta: stepDelta, unit: "" },
    { label: "Active min", value: num(act.active_minutes), digits: 0, suffix: " min", spark: stepSeries, delta: null, unit: " min" },
    { label: "Temp", value: num(temp.average), digits: 1, suffix: " °C", spark: [], delta: null, unit: " °C" },
  ];

  return (
    <>
      <h2 className="section-title">
        Today at a <span className="hl">glance</span>
      </h2>

      <div className="grid cols-3">
        {metrics.map((m, i) => (
          <div className="card lift glance-rise" key={m.label} style={rise(i)}>
            <div className="label">{m.label}</div>
            <div className="value">
              <CountUp value={m.value} digits={m.digits} suffix={m.suffix} />
            </div>
            {m.delta !== null && (
              <span className={`chip ${m.delta >= 0 ? "up" : "down"}`}>
                {m.delta >= 0 ? "↑" : "↓"} {Math.abs(m.delta).toFixed(0)}
                {m.unit}
              </span>
            )}
            <Sparkline values={m.spark} lime={m.label === "Steps"} delay={i * 0.07} />
          </div>
        ))}
        <SleepTile
          summary={sleep}
          onOpen={() => {
            setSleepModalMode("detail");
            setSleepModalOpen(true);
          }}
        />
      </div>

      <div className="grid cols-3" style={{ marginTop: 16 }}>
        <div className="card lift glance-rise" style={{ gridColumn: "span 3", ...rise(6) }}>
          <WellnessRing score={score} stress={wellness?.stress_level} confidence={wellness?.confidence} />
        </div>
      </div>

      <SleepLogCard
        onOpenDetail={(mode) => {
          setSleepModalMode(mode === "past" ? "past" : "detail");
          setSleepModalOpen(true);
        }}
      />
      <SleepDetailModal
        open={sleepModalOpen}
        mode={sleepModalMode}
        onClose={() => setSleepModalOpen(false)}
        onChanged={refreshSleep}
      />
    </>
  );
}
