import { useEffect, useMemo, useRef, useState } from "react";

// Reveals a reply word by word with a blinking caret while it streams.
export default function StreamingText({
  text,
  animate = true,
  onTick,
}: {
  text: string;
  animate?: boolean;
  onTick?: () => void;
}) {
  const words = useMemo(() => text.split(/(\s+)/), [text]);
  const total = words.length;
  const [shown, setShown] = useState(animate ? 0 : total);
  const tickRef = useRef(onTick);
  tickRef.current = onTick;

  useEffect(() => {
    if (!animate) {
      setShown(total);
      return;
    }
    setShown(0);
    let i = 0;
    const id = window.setInterval(() => {
      i += 1;
      setShown(i);
      if (i % 3 === 0 || i >= total) tickRef.current?.();
      if (i >= total) window.clearInterval(id);
    }, 24);
    return () => window.clearInterval(id);
  }, [text, animate, total]);

  const done = shown >= total;
  return (
    <span className="stream-text">
      {words.slice(0, shown).join("")}
      {!done && <span className="caret" aria-hidden="true" />}
    </span>
  );
}
