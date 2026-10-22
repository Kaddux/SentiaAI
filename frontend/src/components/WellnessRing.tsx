import { useEffect, useState } from "react";
import CountUp from "./CountUp";

function plainConfidence(v: any): string {
  if (v === null || v === undefined || v === "") return "";
  if (typeof v === "number") return v > 0 && v <= 1 ? `${Math.round(v * 100)}%` : `${v}`;
  return String(v);
}

export default function WellnessRing({
  score,
  stress,
  confidence,
  provisional = false,
  scoreLow = null,
  scoreHigh = null,
}: {
  score: number | null;
  stress?: any;
  confidence?: any;
  provisional?: boolean;
  scoreLow?: number | null;
  scoreHigh?: number | null;
}) {
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    if (score === null) return;
    const target = Math.max(0, Math.min(100, score));
    const id = window.requestAnimationFrame(() => setProgress(target));
    return () => window.cancelAnimationFrame(id);
  }, [score]);

  const r = 48;
  const c = 2 * Math.PI * r;
  const offset = c * (1 - progress / 100);
  const conf = plainConfidence(confidence);
  const showRange = provisional && scoreLow !== null && scoreHigh !== null;
  const headline = provisional
    ? "Early estimate"
    : score === null
      ? "No assessment yet"
      : score >= 75
        ? "Doing well"
        : score >= 50
          ? "Keep an eye on it"
          : "Needs attention";

  return (
    <div className={`wellness-card${provisional ? " provisional" : ""}`}>
      <div className="ring-wrap">
        <svg viewBox="0 0 120 120" className="ring" aria-hidden="true">
          <circle className="ring-track" cx="60" cy="60" r={r} />
          {provisional ? (
            <circle className="ring-provisional" cx="60" cy="60" r={r} />
          ) : (
            <circle
              className="ring-fill"
              cx="60"
              cy="60"
              r={r}
              strokeDasharray={c}
              strokeDashoffset={offset}
            />
          )}
        </svg>
        <div className="ring-center">
          {showRange ? (
            <div className="ring-value ring-range">
              {scoreLow}
              {"–"}
              {scoreHigh}
            </div>
          ) : (
            <div className="ring-value">
              <CountUp value={score} />
            </div>
          )}
          <div className="ring-unit">
            {provisional ? "early range" : score === null ? "no score" : "/ 100"}
          </div>
        </div>
      </div>
      <div className="wellness-copy">
        <div className="label">Wellness{provisional ? " · provisional" : ""}</div>
        <div className="wellness-headline">{headline}</div>
        <div className="meta">
          {provisional
            ? "Provisional · low confidence · collecting more data"
            : `${stress ? `stress looks ${String(stress).toLowerCase()}` : "stress —"}${
                conf ? ` · confidence ${conf.toLowerCase()}` : ""
              }`}
        </div>
        {!provisional && score !== null && (
          <div className="wellness-note">Based on your baseline and today's aggregates.</div>
        )}
      </div>
    </div>
  );
}
