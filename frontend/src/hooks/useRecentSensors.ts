import { useEffect, useRef, useState } from "react";
import { api } from "../api";

/** Polls raw readings for the live dashboard; pauses while the tab is hidden. */
export function useRecentSensors(limit = 60, intervalMs = 2000) {
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const alive = useRef(true);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => {
    alive.current = true;
    const tick = async () => {
      if (document.hidden) {
        timer.current = window.setTimeout(tick, intervalMs);
        return;
      }
      try {
        const d = await api.recent(limit);
        if (alive.current) {
          setData(d);
          setError(null);
        }
      } catch (e: any) {
        if (alive.current) setError(e.message);
      } finally {
        if (alive.current) {
          setLoading(false);
          timer.current = window.setTimeout(tick, intervalMs);
        }
      }
    };
    tick();
    return () => {
      alive.current = false;
      if (timer.current) clearTimeout(timer.current);
    };
  }, [limit, intervalMs]);

  return { data, error, loading };
}
