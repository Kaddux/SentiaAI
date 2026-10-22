// Dev-only hooks for the UDP bridge hosted by the Vite dev server.
const BASE = "/__sentia/bridge";

async function call(path: string, body?: unknown): Promise<void> {
  if (!import.meta.env.DEV) return;
  try {
    const res = await fetch(`${BASE}${path}`, {
      method: "POST",
      headers: body ? { "Content-Type": "application/json" } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      const detail = await res.text().catch(() => "");
      console.warn(`[bridge] ${path} failed (${res.status})`, detail);
      return;
    }
    const type = res.headers.get("content-type") || "";
    if (!type.includes("application/json")) {
      console.warn("[bridge] dev bridge is not active - restart the Vite dev server");
      return;
    }
    const data: any = await res.json().catch(() => null);
    if (data?.state === "error") {
      console.warn(`[bridge] ${data.lastError || "bridge error"}`);
    }
  } catch (e) {
    console.warn("[bridge] dev bridge unavailable", e);
  }
}

export function startBridge(token: string): void {
  if (!token) return;
  void call("/start", { token });
}

export function stopBridge(): void {
  void call("/stop");
}
