import { useState } from "react";
import { api, setToken } from "../api";
import { startBridge } from "../bridge";

export default function Login({ onDone }: { onDone: () => void }) {
  const [email, setEmail] = useState("dev@sentia.ai");
  const [password, setPassword] = useState("password123");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await api.login(email, password);
      setToken(res.token);
      startBridge(res.token);
      onDone();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <div className="topbar" style={{ justifyContent: "center", marginBottom: 18 }}>
        <div className="brand">
          <span className="dot" /> Sentia AI
        </div>
      </div>
      <form className="card" onSubmit={submit}>
        <h2 style={{ marginBottom: 14 }}>Sign in</h2>
        <label className="field">
          <span>Email</span>
          <input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
        </label>
        <label className="field">
          <span>Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        {error && <div className="error">{error}</div>}
        <button className="btn" type="submit" disabled={busy} style={{ width: "100%", marginTop: 8 }}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        <div className="muted" style={{ marginTop: 12, textAlign: "center" }}>
          Demo user: dev@sentia.ai / password123
        </div>
      </form>
    </div>
  );
}
