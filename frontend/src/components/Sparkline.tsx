// Tiny SVG sparkline. `values` may contain nulls (missing days) — they're skipped.
export default function Sparkline({
  values,
  lime = false,
  delay = 0,
}: {
  values: (number | null)[];
  lime?: boolean;
  delay?: number;
}) {
  const nums = values.filter((v): v is number => typeof v === "number");
  if (nums.length < 2) return <div className="spark" />;
  const w = 100, h = 32, pad = 2;
  const min = Math.min(...nums), max = Math.max(...nums);
  const span = max - min || 1;
  const pts: [number, number][] = [];
  values.forEach((v, i) => {
    if (typeof v !== "number") return;
    const x = pad + (i / Math.max(1, values.length - 1)) * (w - pad * 2);
    const y = h - pad - ((v - min) / span) * (h - pad * 2);
    pts.push([x, y]);
  });
  const line = pts.map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(" ");
  const area = `${line} L${pts[pts.length - 1][0].toFixed(1)} ${h} L${pts[0][0].toFixed(1)} ${h} Z`;
  const last = pts[pts.length - 1];
  return (
    <div className={`spark-wrap${lime ? " lime" : ""}`}>
      <svg className={`spark${lime ? " lime" : ""}`} viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none">
        <path className="area" d={area} style={{ animationDelay: `${0.3 + delay}s` }} />
        <path className="line" d={line} pathLength={1} style={{ animationDelay: `${0.1 + delay}s` }} />
      </svg>
      <span
        className="spark-dot"
        style={{
          left: `${(last[0] / w) * 100}%`,
          top: `${(last[1] / h) * 100}%`,
          animationDelay: `${0.55 + delay}s`,
        }}
      />
    </div>
  );
}
