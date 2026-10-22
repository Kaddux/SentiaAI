import { useEffect, useRef, useState } from "react";

// Number that briefly flashes when it changes, so a live feed feels alive.
export default function FlashValue({
  value,
  digits = 0,
  suffix = "",
}: {
  value: number | null;
  digits?: number;
  suffix?: string;
}) {
  const prev = useRef<number | null>(null);
  const [flash, setFlash] = useState(0);

  useEffect(() => {
    if (value !== null && prev.current !== null && value !== prev.current) {
      setFlash((n) => n + 1);
    }
    prev.current = value;
  }, [value]);

  return (
    <span key={flash} className={`flash-value${flash ? " flash" : ""}`}>
      {value === null ? "—" : `${value.toFixed(digits)}${suffix}`}
    </span>
  );
}
