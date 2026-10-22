import { useEffect, useState } from "react";
import { api, getToken, setToken } from "./api";
import Dashboard from "./screens/Dashboard";
import Live from "./screens/Live";
import Chat from "./screens/Chat";
import Reminders from "./screens/Reminders";
import Login from "./screens/Login";
import Settings from "./screens/Settings";
import ReminderAlerts from "./components/ReminderAlerts";

type Screen = "dashboard" | "live" | "chat" | "reminders" | "settings";

export default function App() {
  const [authed, setAuthed] = useState(!!getToken());
  const [screen, setScreen] = useState<Screen>("dashboard");
  const [name, setName] = useState<string>("there");

  useEffect(() => {
    if (!authed) return;
    api
      .profile()
      .then((p) => {
        setName(p?.first_name || "there");
        const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (p?.timezone === "UTC" && tz && tz !== "UTC") {
          api.setTimezone(tz).catch(() => {});
        }
      })
      .catch(() => {});
  }, [authed]);

  if (!authed) return <Login onDone={() => setAuthed(true)} />;

  return (
    <div className="shell">
      <div className="topbar">
        <div className="brand">
          <span className="dot" /> Sentia AI <small>Hi {name} · your personal health assistant</small>
        </div>
        <div className="spacer" />
        <button
          className="btn ghost"
          onClick={() => {
            setToken(null);
            setAuthed(false);
          }}
        >
          Sign out
        </button>
      </div>

      <div className="tabs">
        <button className={screen === "dashboard" ? "active" : ""} onClick={() => setScreen("dashboard")}>
          Health Dashboard
        </button>
        <button className={screen === "live" ? "active" : ""} onClick={() => setScreen("live")}>
          Live
        </button>
        <button className={screen === "chat" ? "active" : ""} onClick={() => setScreen("chat")}>
          Chat
        </button>
        <button className={screen === "reminders" ? "active" : ""} onClick={() => setScreen("reminders")}>
          Reminders
        </button>
        <button className={screen === "settings" ? "active" : ""} onClick={() => setScreen("settings")}>
          Settings
        </button>
      </div>

      <div className="screen" key={screen}>
        {screen === "dashboard" && <Dashboard />}
        {screen === "live" && <Live />}
        {screen === "chat" && <Chat />}
        {screen === "reminders" && <Reminders />}
        {screen === "settings" && <Settings />}
      </div>

      <div className="footer-note">Sentia AI · informational only, not a medical device</div>
      <ReminderAlerts />
    </div>
  );
}
