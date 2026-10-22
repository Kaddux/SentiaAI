import { useEffect, useState } from "react";
import { api } from "../api";

function fmtDur(min: number | null | undefined): string {
  if (min === null || min === undefined) return "—";
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function fmtElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 60000));
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function SleepLogCard({
  onOpenDetail,
}: {
  onOpenDetail: (mode?: "past" | "detail") => void;
}) {
  const [open, setOpen] = useState<any>(null);
  const [last, setLast] = useState<any>(null);
  const [morning, setMorning] = useState<any>(null);
  const [now, setNow] = useState(Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [staleHours, setStaleHours] = useState(14);
  const [staleDismissed, setStaleDismissed] = useState(false);
  const [wake, setWake] = useState(() => toLocalInput(new Date()));

  async function load() {
    try {
      const [o, l, s] = await Promise.all([
        api.sleepOpen(),
        api.sleepLatest(),
        api.sleepSummary(1),
      ]);
      setOpen(o ?? null);
      setLast(l ?? null);
      setStaleHours(s?.staleOpenHours ?? 14);
    } catch (e: any) {
      setError(e.message);
    }
  }

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    if (!open) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [open]);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      const s = await api.sleepStart();
      setOpen(s);
      setMorning(null);
      setStaleDismissed(false);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function end(endedAt?: string) {
    setBusy(true);
    setError(null);
    try {
      const s = await api.sleepEnd(endedAt);
      setOpen(null);
      setMorning(s);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function rate(id: number, rating: number) {
    try {
      await api.rateSleep(id, rating);
      setMorning((m: any) => (m ? { ...m, userRating: rating } : m));
    } catch (e: any) {
      setError(e.message);
    }
  }

  const elapsedMs = open ? now - new Date(open.startTs).getTime() : 0;
  const stale = !!open && elapsedMs > staleHours * 3600000;

  return (
    <div className="card sleep-card anim-up">
      <div className="sleep-copy">
        <div className="label">
          Sleep <span className="sleep-source">logged by you</span>
        </div>
        {open ? (
          <>
            <div className="sleep-timer">Sleeping for {fmtElapsed(elapsedMs)}</div>
            <div className="meta">
              Started {new Date(open.startTs).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
            </div>
          </>
        ) : (
          <>
            <div className="sleep-headline">Log your sleep</div>
            <div className="meta">
              The wearable can’t record overnight — log bedtime and wake time yourself.
            </div>
            {last && <div className="meta">Last logged: {fmtDur(last.totalSleepMin)}</div>}
          </>
        )}
        {error && <div className="error">{error}</div>}
      </div>

      <div className="sleep-buttons">
        {open ? (
          <button className="btn" disabled={busy} onClick={() => end()}>
            I&apos;m awake
          </button>
        ) : (
          <button className="btn" disabled={busy} onClick={start}>
            Going to sleep
          </button>
        )}
        <button className="btn ghost" onClick={() => onOpenDetail("past")}>
          Log a past night
        </button>
      </div>

      {stale && !staleDismissed && (
        <div className="sleep-stale">
          <div>Did you forget to tap &quot;I&apos;m awake&quot;?</div>
          <div className="sleep-stale-row">
            <input type="datetime-local" value={wake} onChange={(e) => setWake(e.target.value)} />
            <button className="btn" disabled={busy} onClick={() => end(new Date(wake).toISOString())}>
              Set wake time
            </button>
            <button className="btn ghost" onClick={() => setStaleDismissed(true)}>
              Still sleeping
            </button>
          </div>
        </div>
      )}

      {morning && (
        <div className="sleep-morning">
          <div className="label">Good morning</div>
          <div className="sleep-timer">You slept about {fmtDur(morning.totalSleepMin)}.</div>
          <div className="meta">Does that look right?</div>
          <div className="sleep-morning-row">
            <div className="rating" aria-label="How rested do you feel?">
              {[1, 2, 3, 4, 5].map((n) => (
                <button
                  key={n}
                  type="button"
                  className={`rating-pill ${morning.userRating === n ? "on" : ""}`}
                  onClick={() => rate(morning.id, n)}
                  aria-label={`${n} out of 5`}
                >
                  {n}
                </button>
              ))}
              <span className="muted">rested</span>
            </div>
            <div className="sleep-buttons">
              <button className="btn" onClick={() => setMorning(null)}>
                Confirm
              </button>
              <button className="btn ghost" onClick={() => onOpenDetail("detail")}>
                Edit
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
