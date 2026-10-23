import { useEffect, useRef, useState } from "react";

const pad = (n: number) => String(n).padStart(2, "0");

const HOUR_LABELS = [12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
const MINUTE_LABELS = [0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55];

const CX = 110;
const CY = 110;

/** Analog clock-face time picker. Value/onChange use "HH:MM" (24h). */
export default function TimePicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<"hour" | "minute">("hour");
  const [hour24, setHour24] = useState(9);
  const [minute, setMinute] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);

  // Sync the draft with the incoming value whenever the popover opens.
  useEffect(() => {
    if (!open) return;
    const [h, m] = value.split(":").map((x) => parseInt(x, 10));
    if (!Number.isNaN(h)) setHour24(((h % 24) + 24) % 24);
    if (!Number.isNaN(m)) setMinute(((m % 60) + 60) % 60);
    setMode("hour");
  }, [open, value]);

  // Close on outside click / Escape.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  const ampm = hour24 < 12 ? "AM" : "PM";
  const display = `${pad(hour12)}:${pad(minute)} ${ampm}`;

  function commit() {
    onChange(`${pad(hour24)}:${pad(minute)}`);
    setOpen(false);
  }

  function selectHour(h: number) {
    const base = h % 12;
    setHour24(ampm === "PM" ? base + 12 : base);
    setMode("minute");
  }

  function setAmPm(next: "AM" | "PM") {
    const base = hour24 % 12;
    setHour24(next === "PM" ? base + 12 : base);
  }

  function handleDial(e: React.MouseEvent<SVGSVGElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    const dx = e.clientX - rect.left - rect.width / 2;
    const dy = e.clientY - rect.top - rect.height / 2;
    let deg = (Math.atan2(dy, dx) * 180) / Math.PI + 90;
    if (deg < 0) deg += 360;
    if (mode === "hour") {
      let h = Math.round(deg / 30) % 12;
      if (h === 0) h = 12;
      selectHour(h);
    } else {
      setMinute(Math.round(deg / 6) % 60);
    }
  }

  const labels = mode === "hour" ? HOUR_LABELS : MINUTE_LABELS;
  const handDeg = mode === "hour" ? (hour12 % 12) * 30 - 90 : minute * 6 - 90;
  const handRad = (handDeg * Math.PI) / 180;
  const handX = CX + 58 * Math.cos(handRad);
  const handY = CY + 58 * Math.sin(handRad);

  return (
    <div className="timepicker" ref={rootRef}>
      <button
        type="button"
        className="timepicker-input"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span>{display}</span>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2" strokeLinecap="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 2" />
        </svg>
      </button>

      {open && (
        <div className="timepicker-pop" role="dialog" aria-label="Choose a time">
          <div className="timepicker-digital">
            <button
              type="button"
              className={mode === "hour" ? "active" : ""}
              onClick={() => setMode("hour")}
            >
              {pad(hour12)}
            </button>
            <span>:</span>
            <button
              type="button"
              className={mode === "minute" ? "active" : ""}
              onClick={() => setMode("minute")}
            >
              {pad(minute)}
            </button>
            <div className="ampm">
              <button type="button" className={ampm === "AM" ? "active" : ""} onClick={() => setAmPm("AM")}>
                AM
              </button>
              <button type="button" className={ampm === "PM" ? "active" : ""} onClick={() => setAmPm("PM")}>
                PM
              </button>
            </div>
          </div>

          <svg viewBox="0 0 220 220" className="clock" onClick={handleDial}>
            <circle cx={CX} cy={CY} r="100" className="clock-face" />
            {Array.from({ length: 60 }).map((_, i) => {
              const major = i % 5 === 0;
              const a = ((i * 6 - 90) * Math.PI) / 180;
              const r1 = major ? 88 : 93;
              return (
                <line
                  key={i}
                  x1={CX + r1 * Math.cos(a)}
                  y1={CY + r1 * Math.sin(a)}
                  x2={CX + 98 * Math.cos(a)}
                  y2={CY + 98 * Math.sin(a)}
                  className={major ? "tick major" : "tick"}
                />
              );
            })}
            <line x1={CX} y1={CY} x2={handX} y2={handY} className="clock-hand" />
            <circle cx={CX} cy={CY} r="4" className="clock-center" />
            {labels.map((label, i) => {
              const a = ((i * 30 - 90) * Math.PI) / 180;
              const x = CX + 72 * Math.cos(a);
              const y = CY + 72 * Math.sin(a);
              const selected = mode === "hour" ? label === hour12 : label === minute;
              return (
                <text
                  key={label}
                  x={x}
                  y={y}
                  textAnchor="middle"
                  dominantBaseline="central"
                  className={`clock-num ${selected ? "selected" : ""}`}
                >
                  {mode === "minute" ? pad(label) : label}
                </text>
              );
            })}
          </svg>

          <div className="timepicker-actions">
            <button type="button" className="btn ghost" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button type="button" className="btn" onClick={commit}>
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
