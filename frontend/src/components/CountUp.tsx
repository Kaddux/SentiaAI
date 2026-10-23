import { useEffect, useRef, useState } from "react";

function prefersReduced(): boolean {
  return (
    typeof window !== "undefined" &&
    !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
  );
}

// Animates a number from its previous value to `value` (easeOutCubic).
// Falls back to the final value instantly under prefers-reduced-motion.
export default function CountUp({
  value,
  digits = 0,
  suffix = "",
  duration = 900,
}: {
  value: number | null;
  digits?: number;
  suffix?: string;
  duration?: number;
}) {
  const [display, setDisplay] = useState<number | null>(value);
  const fromRef = useRef<number>(0);
  const rafRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (value === null) {
      setDisplay(null);
      return;
    }
    if (prefersReduced() || duration <= 0) {
      fromRef.current = value;
      setDisplay(value);
      return;
    }
    const from = fromRef.current;
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - t, 3);
      setDisplay(from + (value - from) * eased);
      if (t < 1) rafRef.current = requestAnimationFrame(tick);
      else fromRef.current = value;
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      fromRef.current = value;
    };
  }, [value, duration]);

  if (display === null) return <>—</>;
  return (
    <>
      {display.toFixed(digits)}
      {suffix}
    </>
  );
}
