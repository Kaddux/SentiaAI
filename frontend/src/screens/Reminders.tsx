import { useEffect, useState } from "react";
import { api } from "../api";
import TimePicker from "../components/TimePicker";

const WEEKDAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

function recurrenceLabel(r: any): string {
  const rec = String(r?.recurrence || "none").toLowerCase();
  const base =
    rec === "none" ? "Once" : rec.charAt(0).toUpperCase() + rec.slice(1);
  if (rec === "weekly" && Array.isArray(r?.daysOfWeek) && r.daysOfWeek.length > 0) {
    const days = r.daysOfWeek
      .map((d: number) => WEEKDAY_NAMES[d - 1])
      .filter(Boolean)
      .join(", ");
    return days ? `${base} · ${days}` : base;
  }
  return base;
}

export default function Reminders() {
  const [items, setItems] = useState<any[]>([]);
  const [title, setTitle] = useState("");
  const [time, setTime] = useState("09:00");
  const [recurrence, setRecurrence] = useState("daily");
  const [days, setDays] = useState<number[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [sound, setSound] = useState<boolean>(() => {
    try {
      return localStorage.getItem("sentia.sound") !== "off";
    } catch {
      return true;
    }
  });
  const [notif, setNotif] = useState<string>(
    typeof Notification !== "undefined" ? Notification.permission : "unsupported"
  );

  async function load() {
    try {
      setItems(await api.reminders());
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    load();
  }, []);

  async function add() {
    if (!title.trim()) return;
    try {
      await api.createReminder({
        title,
        reminderTime: time,
        recurrence,
        daysOfWeek: recurrence === "weekly" ? days : null,
      });
      setTitle("");
      setDays([]);
      await load();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function remove(id: number) {
    try {
      await api.deleteReminder(id);
      await load();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function toggle(r: any) {
    const active = r.isActive ?? r.active;
    try {
      await api.updateReminder(r.id, {
        title: r.title,
        description: r.description,
        reminderTime: r.reminderTime,
        recurrence: r.recurrence,
        daysOfWeek: r.daysOfWeek,
        isActive: !active,
      });
      await load();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function enableNotifications() {
    if (typeof Notification === "undefined") return;
    const permission = await Notification.requestPermission();
    setNotif(permission);
  }

  function toggleSound() {
    const next = !sound;
    setSound(next);
    try {
      localStorage.setItem("sentia.sound", next ? "on" : "off");
    } catch {
      /* ignore */
    }
  }

  return (
    <>
      <h2 className="section-title">
        <span className="hl">Reminders</span>
      </h2>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="grid cols-4" style={{ alignItems: "end" }}>
          <label className="field" style={{ gridColumn: "span 2", marginBottom: 0 }}>
            <span>Title</span>
            <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Take medication" />
          </label>
          <div className="field" style={{ marginBottom: 0 }}>
            <span>Time</span>
            <TimePicker value={time} onChange={setTime} />
          </div>
          <label className="field" style={{ marginBottom: 0 }}>
            <span>Recurrence</span>
            <div className="select-wrap">
              <select className="select" value={recurrence} onChange={(e) => setRecurrence(e.target.value)}>
                <option value="none">Once</option>
                <option value="daily">Daily</option>
                <option value="weekly">Weekly</option>
                <option value="monthly">Monthly</option>
              </select>
            </div>
          </label>
        </div>

        {recurrence === "weekly" && (
          <div className="weekdays">
            {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((label, i) => {
              const day = i + 1;
              const on = days.includes(day);
              return (
                <button
                  type="button"
                  key={day}
                  className={`weekday ${on ? "on" : ""}`}
                  onClick={() => setDays((d) => (on ? d.filter((x) => x !== day) : [...d, day]))}
                  aria-pressed={on}
                >
                  {label}
                </button>
              );
            })}
          </div>
        )}

        <div style={{ marginTop: 12, display: "flex", alignItems: "center", gap: 10 }}>
          <button className="btn" onClick={add}>
            Add reminder
          </button>
          <button className="btn ghost" onClick={toggleSound}>
            {sound ? "Sound: on" : "Sound: off"}
          </button>
        </div>
      </div>

      {notif === "default" && (
        <div className="card" style={{ marginBottom: 16, display: "flex", alignItems: "center", gap: 12 }}>
          <span className="muted">Get a browser notification when a reminder fires.</span>
          <div className="spacer" />
          <button className="btn ghost" onClick={enableNotifications}>
            Enable notifications
          </button>
        </div>
      )}
      {notif === "denied" && (
        <div className="muted" style={{ marginBottom: 16 }}>
          Browser notifications are blocked for this site — in-app reminders still work.
        </div>
      )}

      {error && <div className="error">{error}</div>}
      {loading ? (
        <div className="empty">Loading…</div>
      ) : items.length === 0 ? (
        <div className="empty">No reminders yet — add one above.</div>
      ) : (
        <div className="grid" style={{ gap: 12 }}>
          {items.map((r, i) => (
            <div className="card rem lift anim-up" key={r.id} style={{ animationDelay: `${i * 50}ms` }}>
              <span className="time">{String(r.reminderTime).slice(0, 5)}</span>
              <div>
                <div style={{ fontWeight: 500 }}>{r.title}</div>
                <div className="muted">{recurrenceLabel(r)}</div>
              </div>
              <div className="spacer" />
              <button
                type="button"
                className={`toggle ${(r.isActive ?? r.active) ? "on" : ""}`}
                onClick={() => toggle(r)}
                aria-label={(r.isActive ?? r.active) ? "Disable reminder" : "Enable reminder"}
                title={(r.isActive ?? r.active) ? "Disable" : "Enable"}
              />
              <button className="btn danger" onClick={() => remove(r.id)}>
                Delete
              </button>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
