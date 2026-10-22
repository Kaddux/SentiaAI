// Real heart-rate samples only. Null samples break the line (no fabricated
// waveform, no interpolation across sensor dropouts).
export default function HrTrace({
  values,
  live = true,
  className = "",
}: {
  values: (number | null)[];
  live?: boolean;
  className?: string;
}) {
  const nums = values.filter((v): v is number => typeof v === "number");
  if (nums.length < 2) {
    return <div className={`hr-trace empty${className ? ` ${className}` : ""}`} />;
  }

  const w = 100, h = 32, pad = 2;
  const min = Math.min(...nums), max = Math.max(...nums);
  const span = max - min || 1;
  const segments: string[] = [];
  let current: [number, number][] = [];

  const flush = () => {
    if (current.length >= 2) {
      segments.push(
        current
          .map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)} ${p[1].toFixed(1)}`)
          .join(" ")
      );
    }
    current = [];
  };

  values.forEach((v, i) => {
    if (typeof v !== "number") {
      flush();
      return;
    }
    const x = pad + (i / Math.max(1, values.length - 1)) * (w - pad * 2);
    const y = h - pad - ((v - min) / span) * (h - pad * 2);
    current.push([x, y]);
  });
  flush();

  return (
    <div className={`hr-trace ${live ? "live" : "idle"}${className ? ` ${className}` : ""}`} aria-hidden="true">
      <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none">
        {segments.map((d, i) => (
          <path key={i} className="hr-trace-line" d={d} />
        ))}
      </svg>
    </div>
  );
}
