// API client for Sentia AI. Frontend calls same-origin /api/* and Vite proxies
// to the backend (:8080) or the AI service (:8000) — see vite.config.ts.

const TOKEN_KEY = "sentia.token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}
export function setToken(t: string | null) {
  if (t) localStorage.setItem(TOKEN_KEY, t);
  else localStorage.removeItem(TOKEN_KEY);
}

async function req<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init.headers as Record<string, string>),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) {
    setToken(null);
    throw new Error("Session expired — please sign in again.");
  }
  if (!res.ok) {
    let detail = `${res.status}`;
    try {
      const j = await res.json();
      detail = j.error || j.detail || JSON.stringify(j);
    } catch {
      /* ignore */
    }
    throw new Error(detail);
  }
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

export const api = {
  login: (email: string, password: string) =>
    req<{ token: string; userId: string; email: string }>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  profile: () => req<any>("/api/health/profile"),
  today: () => req<any>("/api/health/today"),
  heartRate: () => req<any>("/api/health/heart-rate"),
  activity: () => req<any>("/api/health/activity"),
  steps: () => req<any>("/api/health/steps"),
  sleep: () => req<any>("/api/health/sleep"),
  temperature: () => req<any>("/api/health/temperature"),
  baseline: () => req<any>("/api/health/baseline"),
  wellness: () => req<any>("/api/health/wellness"),
  trends: (metric: string, days = 7) =>
    req<any>(`/api/health/trends?metric=${metric}&days=${days}`),

  recent: (limit = 60) => req<any>(`/api/sensors/recent?limit=${limit}`),

  chat: (message: string, conversation_id: number | null) =>
    req<{ conversation_id: number; message: { role: string; content: string }; tool_calls_made?: string[] }>(
      "/api/assistant/chat",
      { method: "POST", body: JSON.stringify({ message, conversation_id }) }
    ),

  reminders: () => req<any[]>("/api/reminders"),
  dueReminders: () => req<any[]>("/api/reminders/due"),
  createReminder: (r: any) =>
    req<any>("/api/reminders", { method: "POST", body: JSON.stringify(r) }),
  updateReminder: (id: number, r: any) =>
    req<any>(`/api/reminders/${id}`, { method: "PUT", body: JSON.stringify(r) }),
  deleteReminder: (id: number) =>
    req<any>(`/api/reminders/${id}`, { method: "DELETE" }),
  ackReminder: (id: number) =>
    req<any>(`/api/reminders/${id}/ack`, { method: "POST" }),

  // manual sleep logging
  sleepOpen: () => req<any>("/api/sleep-sessions/open"),
  sleepLatest: () => req<any>("/api/sleep-sessions/latest"),
  sleepSummary: (days = 7) => req<any>(`/api/sleep-sessions/summary?days=${days}`),
  sleepRange: (from: string, to: string) =>
    req<any[]>(`/api/sleep-sessions?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  sleepStart: () => req<any>("/api/sleep-sessions/start", { method: "POST" }),
  sleepEnd: (endedAt?: string) =>
    req<any>("/api/sleep-sessions/end", {
      method: "POST",
      body: JSON.stringify({ endedAt: endedAt ?? null }),
    }),
  createSleep: (s: any) =>
    req<any>("/api/sleep-sessions", { method: "POST", body: JSON.stringify(s) }),
  updateSleep: (id: number, s: any) =>
    req<any>(`/api/sleep-sessions/${id}`, { method: "PUT", body: JSON.stringify(s) }),
  rateSleep: (id: number, rating: number) =>
    req<any>(`/api/sleep-sessions/${id}/rating`, {
      method: "PATCH",
      body: JSON.stringify({ rating }),
    }),
  deleteSleep: (id: number) =>
    req<any>(`/api/sleep-sessions/${id}`, { method: "DELETE" }),
  deleteAllSleep: () => req<any>("/api/sleep-sessions", { method: "DELETE" }),
  setTimezone: (timezone: string) =>
    req<any>("/api/sleep-sessions/timezone", {
      method: "PUT",
      body: JSON.stringify({ timezone }),
    }),
};
