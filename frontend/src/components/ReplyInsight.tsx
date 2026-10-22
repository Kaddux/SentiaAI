import { useEffect, useState } from "react";
import { api } from "../api";
import Sparkline from "./Sparkline";

export type InsightTopic = "sleep" | "wellness" | "heart" | "activity" | "temperature" | "weekly";

// One compact visual per reply, chosen from the tools the assistant used.
export function topicFromTools(tools: string[]): InsightTopic | null {
  const set = new Set(tools);
  if (set.has("get_sleep_data")) return "sleep";
  if (set.has("get_wellness_assessment")) return "wellness";
  if (set.has("get_heart_rate_data")) return "heart";
  if (set.has("get_activity_data")) return "activity";
  if (set.has("get_temperature_data")) return "temperature";
  if (set.has("get_weekly_summary")) return "weekly";
  return null;
}

function num(v: any): number | null {
  return typeof v === "number" && !Number.isNaN(v) ? v : null;
}
function series(trend: any): (number | null)[] {
  const vals = trend?.values;
  if (!Array.isArray(vals)) return [];
  return vals.map((v: any) => {
    const n = parseFloat(v?.value);
    return Number.isNaN(n) ? null : n;
  });
}
function dur(min: number): string {
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function Bars({ values, max }: { values: (number | null)[]; max: number }) {
  return (
    <div className="insight-bars" aria-hidden="true">
      {values.slice(-7).map((v, i) => (
        <span
          key={i}
          className={v === null ? "empty" : undefined}
          style={{
            height: v === null ? "4px" : `${Math.max(8, Math.min(100, (v / max) * 100))}%`,
            ["--i" as any]: i,
          }}
        />
      ))}
    </div>
  );
}

async function load(topic: InsightTopic) {
  switch (topic) {
    case "sleep":
      return { kind: "sleep", summary: await api.sleepSummary(7) };
    case "wellness":
      return { kind: "wellness", w: await api.wellness() };
    case "heart":
      return { kind: "heart", today: await api.today(), trend: await api.trends("heart_rate", 7) };
    case "activity":
      return { kind: "activity", today: await api.today(), trend: await api.trends("steps", 7) };
    case "temperature":
      return { kind: "temperature", today: await api.today() };
    case "weekly":
      return { kind: "weekly", summary: await api.sleepSummary(7) };
  }
}

export default function ReplyInsight({ topic }: { topic: InsightTopic }) {
  const [data, setData] = useState<any>(undefined);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    let alive = true;
    setData(undefined);
    (async () => {
      try {
        const d = await load(topic);
        if (alive) setData(d);
      } catch {
        if (alive) setData(null);
      }
    })();
    return () => {
      alive = false;
    };
  }, [topic]);

  useEffect(() => {
    const id = window.requestAnimationFrame(() => setMounted(true));
    return () => window.cancelAnimationFrame(id);
  }, []);

  if (!data) return null;

  if (data.kind === "sleep") {
    const min = num(data.summary?.lastNight?.totalSleepMin);
    if (min === null) return null;
    const nights = (data.summary?.nights ?? []).slice(-7).map((n: any) => num(n.totalSleepMin));
    const present = nights.filter((n: number | null): n is number => n !== null);
    const max = Math.max(...present, 480);
    return (
      <div className="insight-card">
        <div className="insight-label">Sleep · last night</div>
        <div className="insight-value">
          {(min / 60).toFixed(1)}
          <small>h</small>
        </div>
        <Bars values={nights} max={max} />
        <div className="insight-note">{dur(min)} logged · last 7 nights</div>
      </div>
    );
  }

  if (data.kind === "wellness") {
    const score = num(data.w?.wellness_score);
    if (score === null) return null;
    const left = mounted ? Math.max(0, Math.min(100, score)) : 0;
    return (
      <div className="insight-card">
        <div className="insight-label">Wellness</div>
        <div className="insight-value">
          {Math.round(score)}
          <small>/ 100</small>
        </div>
        <div className="insight-gauge" aria-hidden="true">
          <span className="insight-gauge-marker" style={{ left: `${left}%` }} />
        </div>
        <div className="insight-note">
          {data.w?.stress_level
            ? `stress looks ${String(data.w.stress_level).toLowerCase()}`
            : "current assessment"}
        </div>
      </div>
    );
  }

  if (data.kind === "heart") {
    const resting = num(data.today?.heart_rate?.resting);
    if (resting === null) return null;
    return (
      <div className="insight-card">
        <div className="insight-label">Heart · today</div>
        <div className="insight-row">
          <div className="insight-value">
            {resting}
            <small>bpm</small>
          </div>
          <div className="insight-spark">
            <Sparkline values={series(data.trend)} lime delay={0.1} />
          </div>
        </div>
        <div className="insight-note">resting heart rate · last 7 days</div>
      </div>
    );
  }

  if (data.kind === "activity") {
    const steps = num(data.today?.activity?.steps);
    if (steps === null) return null;
    return (
      <div className="insight-card">
        <div className="insight-label">Activity · today</div>
        <div className="insight-row">
          <div className="insight-value">
            {Math.round(steps).toLocaleString()}
            <small>steps</small>
          </div>
          <div className="insight-spark">
            <Sparkline values={series(data.trend)} delay={0.1} />
          </div>
        </div>
        <div className="insight-note">
          {num(data.today?.activity?.active_minutes) ?? 0} active min · last 7 days
        </div>
      </div>
    );
  }

  if (data.kind === "temperature") {
    const t = num(data.today?.temperature?.average);
    if (t === null) return null;
    const left = mounted ? Math.max(0, Math.min(100, ((t - 34) / 6) * 100)) : 0;
    return (
      <div className="insight-card">
        <div className="insight-label">Temperature · today</div>
        <div className="insight-value">
          {t.toFixed(1)}
          <small>°C</small>
        </div>
        <div className="insight-scale" aria-hidden="true">
          <span className="insight-scale-marker" style={{ left: `${left}%` }} />
        </div>
        <div className="insight-scale-labels">
          <span>34</span>
          <span>37</span>
          <span>40</span>
        </div>
        <div className="insight-note">skin temperature average</div>
      </div>
    );
  }

  const nights = (data.summary?.nights ?? []).slice(-7);
  const values = nights.map((n: any) => num(n.totalSleepMin));
  const present = values.filter((n: number | null): n is number => n !== null);
  if (!present.length) return null;
  const avg = present.reduce((a, b) => a + b, 0) / present.length;
  return (
    <div className="insight-card">
      <div className="insight-label">This week</div>
      <div className="insight-value">
        {(avg / 60).toFixed(1)}
        <small>h avg sleep</small>
      </div>
      <Bars values={values} max={Math.max(...present, 480)} />
      <div className="insight-note">{present.length} nights logged</div>
    </div>
  );
}
