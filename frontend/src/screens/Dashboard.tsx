import { useEffect, useState } from "react";
import type { CSSProperties } from "react";
import { api } from "../api";
import Sparkline from "../components/Sparkline";
import CountUp from "../components/CountUp";
import SleepLogCard from "../components/SleepLogCard";
import SleepTile from "../components/SleepTile";
import SleepDetailModal from "../components/SleepDetailModal";
import WellnessRing from "../components/WellnessRing";
import LiveNow from "../components/LiveNow";
import { useRecentSensors } from "../hooks/useRecentSensors";

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
  const [stepsInfo, setStepsInfo] = useState<any>(null);
  const [sleep, setSleep] = useState<any>(null);
  const [sleepModalOpen, setSleepModalOpen] = useState(false);
  const [sleepModalMode, setSleepModalMode] = useState<"past" | "detail">("detail");

  const live = useRecentSensors(60, 2000);

  useEffect(() => {
    (async () => {
      const results = await Promise.allSettled([
        api.today(),
        api.wellness(),
        api.trends("heart_rate", 7),
        api.trends("steps", 7),
        api.sleepSummary(7),
      ]);
      const failed: string[] = [];
      const labels = ["today", "wellness", "heart-rate trend", "steps trend", "sleep"];
      results.forEach((r, i) => {
        if (r.status === "fulfilled") {
          const value = (r as PromiseFulfilledResult<any>).value;
          if (i === 0) setToday(value);
          if (i === 1) setWellness(value);
          if (i === 2) setTHr(value);
          if (i === 3) setTSteps(value);
          if (i === 4) setSleep(value);
        } else {
          failed.push(labels[i]);
        }
      });
      setError(
        failed.length
          ? `Some daily data is unavailable (${failed.join(", ")}). Live values above are still updating.`
          : null
      );
      setLoading(false);
    })();
  }, []);

  // Poll the estimated step count every 2 s while the tab is visible.
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      if (document.hidden) return;
      try {
        const s = await api.steps();
        if (alive) setStepsInfo(s);
      } catch {
        /* keep the previous value */
      }
    };
    tick();
    const id = window.setInterval(tick, 2000);
    const onVisible = () => {
      if (!document.hidden) tick();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      alive = false;
      window.clearInterval(id);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, []);

  async function refreshSleep() {
    try {
      setSleep(await api.sleepSummary(7));
    } catch {
      /* keep the previous summary */
    }
  }

  const hr = pick(today, "heart_rate") || {};
  const act = pick(today, "activity") || {};
  const temp = pick(today, "temperature") || {};

  const hrSeries = trendSeries(tHr);
  const stepSeries = trendSeries(tSteps);
  const hrDelta = deltaOf(hrSeries);
  const stepDelta = deltaOf(stepSeries);
  const score = num(wellness?.wellness_score);
  const scoreLow = num(wellness?.score_low);
  const scoreHigh = num(wellness?.score_high);
  // base + live for responsiveness, with the persisted daily total as a
  // monotonic floor: ML reclassification of the same 5-min window must not
  // make the displayed count jump backwards.
  const stepsLive =
    stepsInfo && typeof stepsInfo.base_steps === "number" && typeof stepsInfo.live_steps === "number"
      ? stepsInfo.base_steps + stepsInfo.live_steps
      : null;
  const stepsStored = stepsInfo && typeof stepsInfo.steps === "number" ? stepsInfo.steps : null;
  const stepsDisplay =
    stepsStored !== null
      ? Math.max(stepsLive ?? 0, stepsStored)
      : stepsLive;
  const stepsMoving = stepsInfo?.moving === true;
  const liveLatest = live.data?.latest ?? null;
  const latestAge = liveLatest?.recordedAt
    ? (Date.now() - new Date(liveLatest.recordedAt).getTime()) / 1000
    : null;
  const sensorsLive = latestAge !== null && latestAge >= 0 && latestAge <= 20;
  const showStepsMoving = stepsMoving && sensorsLive;

  const metrics = [
    { label: "Resting HR", value: num(hr.resting), digits: 0, suffix: " bpm", spark: hrSeries, delta: hrDelta, unit: " bpm" },
    { label: "Avg HR", value: num(hr.average), digits: 0, suffix: " bpm", spark: hrSeries, delta: null, unit: " bpm" },
    { label: "Steps", value: stepsDisplay ?? num(act.steps), digits: 0, suffix: "", spark: stepSeries, delta: stepDelta, unit: "" },
    { label: "Active min", value: num(act.active_minutes), digits: 0, suffix: " min", spark: stepSeries, delta: null, unit: " min" },
    { label: "Temp", value: num(temp.average), digits: 1, suffix: " °C", spark: [], delta: null, unit: " °C" },
  ];

  return (
    <>
      <h2 className="section-title">
        Today at a <span className="hl">glance</span>
      </h2>

      <LiveNow
        latest={live.data?.latest ?? null}
        readings={live.data?.readings ?? []}
        steps={stepsDisplay}
        stepsMoving={showStepsMoving}
      />
      {live.error && !live.data && (
        <div className="muted" style={{ marginBottom: 12 }}>
          Live readings are unavailable right now — {live.error}
        </div>
      )}
      {error && (
        <div className="muted" style={{ marginBottom: 12 }}>
          {error}
        </div>
      )}

      {loading ? (
        <div className="grid cols-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <div className="card anim-up" key={i} style={{ animationDelay: `${i * 50}ms` }}>
              <div className="skel line" style={{ width: "42%" }} />
              <div className="skel big" />
              <div className="skel" style={{ height: 40, marginTop: 10 }} />
            </div>
          ))}
        </div>
      ) : (
        <>
          <div className="muted" style={{ margin: "0 0 10px" }}>
            Daily cards update after each closed 5-min window; live values above are immediate.
          </div>
          <div className="grid cols-3">
            {metrics.map((m, i) => (
              <div className="card lift glance-rise" key={m.label} style={rise(i)}>
                <div className="label">{m.label}</div>
                <div className="value">
                  <CountUp value={m.value} digits={m.digits} suffix={m.suffix} />
                </div>
                {m.label === "Steps" && showStepsMoving && (
                  <span className="steps-moving">
                    <span className="move-dot" />
                    moving
                  </span>
                )}
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
              <WellnessRing
                score={score}
                stress={wellness?.stress_level}
                confidence={wellness?.confidence}
                provisional={wellness?.provisional === true}
                scoreLow={scoreLow}
                scoreHigh={scoreHigh}
              />
            </div>
          </div>
        </>
      )}

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
