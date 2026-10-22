import { useEffect, useRef, useState } from "react";
import { api } from "../api";

type Reminder = {
  id: number;
  title: string;
  description?: string | null;
  reminderTime: string;
  lastTriggered?: string | null;
};

type Snooze = { title: string; until: number };

function soundEnabled(): boolean {
  try {
    return localStorage.getItem("sentia.sound") !== "off";
  } catch {
    return true;
  }
}

/** Soft two-note chime (E5 → A5) synthesized with Web Audio. */
function playChime() {
  try {
    const Ctx = window.AudioContext || (window as any).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    ctx.resume?.().catch(() => {});
    const start = ctx.currentTime + 0.02;
    [659.25, 880].forEach((freq, i) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = freq;
      const t = start + i * 0.16;
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(0.07, t + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.55);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(t);
      osc.stop(t + 0.6);
    });
    window.setTimeout(() => ctx.close().catch(() => {}), 1600);
  } catch {
    /* audio is optional */
  }
}

function CheckIcon() {
  return (
    <svg className="check" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="check-circle" cx="12" cy="12" r="10" />
      <path className="check-mark" d="M7.5 12.5l3 3 6-6.5" />
    </svg>
  );
}

function mmss(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

/**
 * Polls GET /api/reminders/due and shows in-app toasts + browser notifications.
 * Mounted once in App so it works on every screen.
 */
export default function ReminderAlerts() {
  const [due, setDue] = useState<Reminder[]>([]);
  const [snoozes, setSnoozes] = useState<Record<number, Snooze>>({});
  const [completed, setCompleted] = useState<Record<number, boolean>>({});
  const [now, setNow] = useState(() => Date.now());
  const seen = useRef<Set<string>>(new Set());
  const snoozed = useRef<Map<number, number>>(new Map());
  const acked = useRef<Set<string>>(new Set());

  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const list: Reminder[] = await api.dueReminders();
        if (!alive) return;
        const nowMs = Date.now();
        const visible: Reminder[] = [];
        for (const r of list) {
          const key = `${r.id}:${r.lastTriggered ?? ""}`;
          if (acked.current.has(key)) continue;

          const snoozeUntil = snoozed.current.get(r.id) ?? 0;
          if (snoozeUntil > nowMs) continue;

          if (!seen.current.has(key)) {
            seen.current.add(key);
            if (soundEnabled()) playChime();
            if (typeof Notification !== "undefined" && Notification.permission === "granted") {
              try {
                new Notification("Sentia reminder", { body: r.title });
              } catch {
                /* ignore */
              }
            }
          }
          visible.push(r);
        }
        setDue(visible);
      } catch {
        /* keep the current list on transient errors */
      }
    };
    tick();
    const id = window.setInterval(tick, 5000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, []);

  // Tick the snooze countdown while at least one card is waiting.
  useEffect(() => {
    if (Object.keys(snoozes).length === 0) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [snoozes]);

  // Release expired snoozes so the next poll shows the reminder again.
  useEffect(() => {
    const expired = Object.entries(snoozes)
      .filter(([, s]) => s.until <= now)
      .map(([id]) => Number(id));
    if (expired.length === 0) return;
    setSnoozes((s) => {
      const next = { ...s };
      expired.forEach((id) => delete next[id]);
      return next;
    });
    expired.forEach((id) => snoozed.current.delete(id));
  }, [now, snoozes]);

  async function done(r: Reminder) {
    acked.current.add(`${r.id}:${r.lastTriggered ?? ""}`);
    setCompleted((c) => ({ ...c, [r.id]: true }));
    try {
      await api.ackReminder(r.id);
    } catch {
      /* ignore */
    }
    window.setTimeout(() => {
      setDue((d) => d.filter((x) => x.id !== r.id));
      setCompleted((c) => {
        const next = { ...c };
        delete next[r.id];
        return next;
      });
    }, 900);
  }

  function snooze(r: Reminder) {
    const until = Date.now() + 5 * 60 * 1000;
    snoozed.current.set(r.id, until);
    setSnoozes((s) => ({ ...s, [r.id]: { title: r.title, until } }));
    setDue((d) => d.filter((x) => x.id !== r.id));
    setNow(Date.now());
  }

  if (due.length === 0 && Object.keys(snoozes).length === 0) return null;

  return (
    <div className="reminder-toasts">
      {due.map((r) => {
        const isDone = !!completed[r.id];
        return (
          <div
            className={`reminder-toast${isDone ? " done" : ""}`}
            key={`${r.id}:${r.lastTriggered ?? ""}`}
          >
            {isDone ? (
              <div className="reminder-done">
                <CheckIcon />
                <div>
                  <div className="label">Reminder</div>
                  <div className="reminder-title">{r.title}</div>
                  <div className="meta">Marked as done</div>
                </div>
              </div>
            ) : (
              <>
                <div className="label">Reminder</div>
                <div className="reminder-title">{r.title}</div>
                {r.description && <div className="meta">{r.description}</div>}
                <div className="reminder-actions">
                  <button className="btn" onClick={() => done(r)}>
                    Done
                  </button>
                  <button className="btn ghost" onClick={() => snooze(r)}>
                    Snooze 5m
                  </button>
                </div>
              </>
            )}
          </div>
        );
      })}

      {Object.entries(snoozes).map(([id, s]) => {
        const remaining = s.until - now;
        const pct = Math.max(0, Math.min(100, (remaining / (5 * 60 * 1000)) * 100));
        return (
          <div className="reminder-toast snoozed-toast" key={`snooze-${id}`}>
            <span
              className="snooze-ring"
              style={{ background: `conic-gradient(var(--lime) ${pct}%, var(--surface-2) 0)` }}
              aria-hidden="true"
            >
              <span className="snooze-ring-inner">{mmss(remaining)}</span>
            </span>
            <div>
              <div className="label">Snoozed</div>
              <div className="reminder-title">{s.title}</div>
              <div className="meta">Back in {mmss(remaining)}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
