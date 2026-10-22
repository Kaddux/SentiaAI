import { useEffect, useState } from "react";
import { api } from "../api";

export default function Settings() {
  const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const [timezone, setTimezone] = useState(browserTz || "UTC");
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .profile()
      .then((p) => setTimezone(p?.timezone || browserTz || "UTC"))
      .catch(() => {});
  }, [browserTz]);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api.setTimezone(timezone.trim());
      setTimezone(r.timezone);
      setSaved("Timezone saved");
      window.setTimeout(() => setSaved(null), 2500);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function deleteAll() {
    if (!window.confirm("Delete all your sleep data? This cannot be undone.")) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api.deleteAllSleep();
      setSaved(`Deleted ${r?.deleted ?? 0} sleep session(s)`);
      window.setTimeout(() => setSaved(null), 3500);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h2 className="section-title">
        <span className="hl">Settings</span>
      </h2>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="label">Timezone</div>
        <div className="meta">
          Used to classify night vs nap and to label nights. Sleep timestamps are stored in UTC.
        </div>
        <form className="settings-form" onSubmit={save}>
          <input
            value={timezone}
            onChange={(e) => setTimezone(e.target.value)}
            placeholder="Asia/Kolkata"
            aria-label="Timezone"
          />
          <button className="btn" disabled={busy}>
            Save
          </button>
          <button type="button" className="btn ghost" onClick={() => setTimezone(browserTz || "UTC")}>
            Use browser ({browserTz || "UTC"})
          </button>
        </form>
      </div>

      <div className="card">
        <div className="label">Your data</div>
        <div className="meta">
          Sleep is health data. Every query is scoped to your account and sleep values are never
          written to application logs.
        </div>
        <div style={{ marginTop: 14 }}>
          <button className="btn danger" onClick={deleteAll} disabled={busy}>
            Delete all my sleep data
          </button>
        </div>
      </div>

      {error && <div className="error">{error}</div>}
      {saved && <div className="muted" style={{ marginTop: 10 }}>{saved}</div>}
    </>
  );
}
