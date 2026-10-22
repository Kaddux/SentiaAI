import CountUp from "./CountUp";
import Sparkline from "./Sparkline";

function fmtDur(min: number | null | undefined): string {
  if (min === null || min === undefined) return "—";
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export default function SleepTile({
  summary,
  onOpen,
}: {
  summary: any;
  onOpen: () => void;
}) {
  const last = summary?.lastNight;
  const previous = summary?.previousNight;
  const hasLastNight = !!last && (last.daysAgo ?? 99) <= 1;
  const series = (summary?.nights ?? []).map((n: any) =>
    typeof n.totalSleepMin === "number" ? n.totalSleepMin : null
  );
  const diff =
    hasLastNight && previous && typeof last.totalSleepMin === "number"
      ? last.totalSleepMin - previous.totalSleepMin
      : null;
  const trendClass = diff === null ? "ok" : diff <= -90 ? "alert" : diff < 0 ? "watch" : "ok";

  return (
    <div
      className="card lift anim-up sleep-tile"
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onOpen();
        }
      }}
      style={{ animationDelay: "300ms" }}
    >
      <div className="label">
        Sleep <span className="sleep-source">logged by you</span>
      </div>
      {hasLastNight ? (
        <>
          <div className="value">
            <CountUp value={last.totalSleepMin / 60} digits={1} suffix=" h" />
          </div>
          {diff !== null && (
            <span className={`pill ${trendClass}`}>
              {diff >= 0 ? "+" : ""}
              {Math.round(diff)} min vs previous
            </span>
          )}
          <Sparkline values={series} lime />
          <div className="meta">{fmtDur(last.totalSleepMin)} · tap for 14-day detail</div>
        </>
      ) : (
        <>
          <div className="sleep-empty">No sleep logged last night.</div>
          <button
            className="btn ghost"
            onClick={(e) => {
              e.stopPropagation();
              onOpen();
            }}
          >
            Log last night
          </button>
        </>
      )}
    </div>
  );
}
