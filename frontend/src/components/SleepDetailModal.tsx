import { useEffect, useState } from "react";
import { api } from "../api";
import CountUp from "./CountUp";

const pad = (n: number) => String(n).padStart(2, "0");

function fmtDur(min: number | null | undefined): string {
  if (min === null || min === undefined) return "—";
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function localDateKey(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function timeInput(iso: string): string {
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function SleepDetailModal({
  open,
  mode,
  onClose,
  onChanged,
}: {
  open: boolean;
  mode: "past" | "detail";
  onClose: () => void;
  onChanged: () => void;
}) {
  const [sessions, setSessions] = useState<any[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  const [date, setDate] = useState(() => localDateKey(new Date().toISOString()));
  const [bedtime, setBedtime] = useState("23:00");
  const [wake, setWake] = useState("07:00");
  const [type, setType] = useState("NIGHT");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);

  async function load() {
    const to = new Date();
    to.setHours(23, 59, 59, 999);
    const from = new Date();
    from.setDate(from.getDate() - 13);
    from.setHours(0, 0, 0, 0);
    try {
      const list = await api.sleepRange(from.toISOString(), to.toISOString());
      setSessions(list ?? []);
      setSelected((cur) => {
        if (cur) return cur;
        const nights = (list ?? []).filter((s: any) => s.type === "NIGHT" && s.endTs);
        if (nights.length === 0) return cur;
        nights.sort((a: any, b: any) => new Date(b.endTs).getTime() - new Date(a.endTs).getTime());
        return localDateKey(nights[0].endTs);
      });
      setError(null);
    } catch (e: any) {
      setError(e.message);
    }
  }

  useEffect(() => {
    if (!open) return;
    setMounted(false);
    load();
    const id = window.setTimeout(() => setMounted(true), 60);
    return () => window.clearTimeout(id);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  const days = Array.from({ length: 14 }, (_, i) => {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - (13 - i));
    return { key: localDateKey(d.toISOString()), label: d.toLocaleDateString([], { weekday: "short" }) };
  });

  const nightByDate = new Map<string, any>();
  for (const s of sessions) {
    if (s.type === "NIGHT" && s.endTs) {
      const k = localDateKey(s.endTs);
      const cur = nightByDate.get(k);
      if (!cur || new Date(s.endTs) > new Date(cur.endTs)) nightByDate.set(k, s);
    }
  }
  const max = Math.max(1, ...days.map((d) => nightByDate.get(d.key)?.totalSleepMin ?? 0));
  const selectedSession = selected ? nightByDate.get(selected) : null;

  const last7 = sessions
    .filter((s) => s.type === "NIGHT" && s.endTs)
    .sort((a, b) => new Date(b.endTs).getTime() - new Date(a.endTs).getTime())
    .slice(0, 7);
  const avg7 = last7.length
    ? Math.round(last7.reduce((a, s) => a + (s.totalSleepMin ?? 0), 0) / last7.length)
    : null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!date || !bedtime || !wake) {
      setError("Please fill in the date, bedtime, and wake time.");
      return;
    }
    const start = new Date(`${date}T${bedtime}:00`);
    const end = new Date(`${date}T${wake}:00`);
    if (end <= start) end.setDate(end.getDate() + 1);
    const minutes = (end.getTime() - start.getTime()) / 60000;
    if (minutes > 16 * 60) {
      setError("A sleep session cannot exceed 16 hours.");
      return;
    }
    setSaving(true);
    try {
      const payload: any = { startTs: start.toISOString(), endTs: end.toISOString() };
      if (editingId) {
        payload.type = type;
        await api.updateSleep(editingId, payload);
      } else {
        await api.createSleep(payload);
      }
      setEditingId(null);
      await load();
      onChanged();
    } catch (err: any) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  }

  function edit(s: any) {
    setEditingId(s.id);
    setDate(localDateKey(s.startTs));
    setBedtime(timeInput(s.startTs));
    setWake(s.endTs ? timeInput(s.endTs) : "07:00");
    setType(s.type || "NIGHT");
    setError(null);
  }

  async function remove(id: number) {
    if (!window.confirm("Delete this sleep session?")) return;
    try {
      await api.deleteSleep(id);
      await load();
      onChanged();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function rate(id: number, rating: number) {
    try {
      await api.rateSleep(id, rating);
      await load();
      onChanged();
    } catch (e: any) {
      setError(e.message);
    }
  }

  if (!open) return null;

  return (
    <div className="sleep-modal-backdrop" onClick={onClose}>
      <div
        className="sleep-modal anim-up"
        role="dialog"
        aria-modal="true"
        aria-label="Sleep details"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="sleep-modal-head">
          <h2 className="section-title" style={{ margin: 0 }}>
            Sleep <span className="hl">log</span>
          </h2>
          <span className="sleep-source">logged by you</span>
          <div className="spacer" />
          <button className="btn ghost" onClick={onClose}>
            Close
          </button>
        </div>

        {error && <div className="error">{error}</div>}

        <div className="sleep-chart" aria-label="Last 14 nights">
          {days.map((d) => {
            const s = nightByDate.get(d.key);
            const pct = s ? Math.max(4, Math.round(((s.totalSleepMin ?? 0) / max) * 100)) : 0;
            return (
              <button
                type="button"
                key={d.key}
                className={`sleep-bar-wrap ${selected === d.key ? "selected" : ""}`}
                onClick={() => setSelected(d.key)}
                title={s ? fmtDur(s.totalSleepMin) : "No sleep logged"}
              >
                <div className="sleep-bar-track">
                  <div className="sleep-bar" style={{ height: mounted ? `${pct}%` : "0%" }} />
                </div>
                <span className="sleep-bar-label">{d.label}</span>
              </button>
            );
          })}
        </div>

        <div className="sleep-selected">
          <div>
            <div className="label">7-night average</div>
            <div className="value" style={{ fontSize: 24 }}>
              <CountUp value={avg7 === null ? null : avg7 / 60} digits={1} suffix=" h" />
            </div>
          </div>
          {selectedSession && (
            <>
              <div>
                <div className="label">Bedtime</div>
                <div className="value" style={{ fontSize: 20 }}>{timeInput(selectedSession.startTs)}</div>
              </div>
              <div>
                <div className="label">Wake</div>
                <div className="value" style={{ fontSize: 20 }}>{timeInput(selectedSession.endTs)}</div>
              </div>
              <div>
                <div className="label">Total</div>
                <div className="value" style={{ fontSize: 20 }}>{fmtDur(selectedSession.totalSleepMin)}</div>
              </div>
            </>
          )}
        </div>

        <form className="sleep-form" onSubmit={submit}>
          <label className="field">
            <span>{editingId ? "Start date" : "Date"}</span>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label className="field">
            <span>Bedtime</span>
            <input type="time" value={bedtime} onChange={(e) => setBedtime(e.target.value)} />
          </label>
          <label className="field">
            <span>Wake</span>
            <input type="time" value={wake} onChange={(e) => setWake(e.target.value)} />
          </label>
          {editingId && (
            <label className="field">
              <span>Type</span>
              <select value={type} onChange={(e) => setType(e.target.value)}>
                <option value="NIGHT">Night</option>
                <option value="NAP">Nap</option>
              </select>
            </label>
          )}
          <button className="btn" disabled={saving}>
            {editingId ? "Save changes" : "Log night"}
          </button>
          {editingId && (
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                setEditingId(null);
                setError(null);
              }}
            >
              Cancel
            </button>
          )}
        </form>

        <div className="sleep-history">
          <div className="label">History (last 14 days)</div>
          {sessions.length === 0 && <div className="muted">No sleep logged yet.</div>}
          {sessions.map((s) => (
            <div className="sleep-history-row" key={s.id}>
              <span>{new Date(s.startTs).toLocaleDateString()}</span>
              <span className="muted">{s.type}</span>
              <span>{s.endTs ? fmtDur(s.totalSleepMin) : "open"}</span>
              <div className="rating">
                {[1, 2, 3, 4, 5].map((n) => (
                  <button
                    type="button"
                    key={n}
                    className={`rating-pill ${s.userRating === n ? "on" : ""}`}
                    onClick={() => rate(s.id, n)}
                    aria-label={`${n} out of 5`}
                  >
                    {n}
                  </button>
                ))}
              </div>
              <button type="button" className="btn ghost" onClick={() => edit(s)}>
                Edit
              </button>
              <button type="button" className="btn danger" onClick={() => remove(s.id)}>
                Delete
              </button>
            </div>
          ))}
        </div>
        <div className="meta" style={{ marginTop: 12 }}>
          {mode === "past" ? "Log a past night above." : "All sleep data here was logged by you."}
        </div>
      </div>
    </div>
  );
}
