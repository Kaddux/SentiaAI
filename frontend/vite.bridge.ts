import dgram from "node:dgram";
import fs from "node:fs";
import path from "node:path";
import type { Plugin } from "vite";

// Dev-only UDP ingestion hosted by the Vite dev server. The socket is bound
// only while a user is signed in; it POSTs to the backend as that user.
const BACKEND = (process.env.SENTIA_BACKEND || "http://localhost:8080").replace(/\/+$/, "");
const UDP_PORT = Number(process.env.SENTIA_UDP_PORT || 4210);
const BATCH_SIZE = Math.max(1, Number(process.env.SENTIA_BATCH_SIZE || 20));
const BATCH_SECS = Math.max(1, Number(process.env.SENTIA_BATCH_SECS || 5));
const TELEMETRY = process.env.SENTIA_BRIDGE_TELEMETRY !== "0";
const DRY_RUN = process.env.SENTIA_BRIDGE_DRY_RUN === "1";
const MAX_BUFFER = 500;
const CONTROL_PATH = "/__sentia/bridge";

type BridgeState = "idle" | "starting" | "running" | "stopping" | "error";

type Reading = {
  recordedAt: string;
  temperature: number | null;
  heartRate: number | null;
  accelX: number | null;
  accelY: number | null;
  accelZ: number | null;
  gyroX: number | null;
  gyroY: number | null;
  gyroZ: number | null;
  batteryV: number | null;
  batteryPct: number | null;
  buttonPressed: number | null;
  tempValid: number | null;
  fingerDetected: number | null;
};

let state: BridgeState = "idle";
let socket: any = null;
let token: string | null = null;
let buffer: Reading[] = [];
let flushTimer: any = null;
let lastPost = Date.now();
let packets = 0;
let posted = 0;
let batches = 0;
let malformed = 0;
let lastError: string | null = null;
let startedAt: string | null = null;
let startPromise: Promise<any> | null = null;
let posting = false;
let stopRequested = false;

function logDir(): string {
  const cwd = process.cwd();
  const root = fs.existsSync(path.resolve(cwd, "docker-compose.yml"))
    ? cwd
    : path.resolve(cwd, "..");
  const dir = path.join(root, "logs");
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function logLine(msg: string) {
  console.log(`[bridge] ${msg}`);
  try {
    fs.appendFileSync(path.join(logDir(), "udp_bridge.log"), `[${new Date().toISOString()}] ${msg}\n`);
  } catch {
    /* logging is best-effort */
  }
}

function toFloat(v: string | undefined): number | null {
  if (v === undefined || v === null || v.trim() === "") return null;
  const n = Number(v.trim());
  return Number.isFinite(n) ? n : null;
}

function toInt(v: string | undefined): number | null {
  const n = toFloat(v);
  return n === null ? null : Math.trunc(n);
}

function parsePacket(line: string): Reading | null {
  const p = line.trim().split(",").map((s) => s.trim());
  let temp: string, tValid: string, hrRaw: string, finger: string;
  let battV: string, battPct: string, button: string;
  let ax: string, ay: string, az: string, gx: string, gy: string, gz: string;

  if (p.length === 13) {
    [temp, tValid, hrRaw, finger, battV, battPct, button, ax, ay, az, gx, gy, gz] = p;
  } else if (p.length >= 15) {
    temp = p[0];
    tValid = p[1];
    hrRaw = p[2];
    finger = p[4];
    battV = p[5];
    battPct = p[6];
    button = p[7];
    ax = p[8];
    ay = p[9];
    az = p[10];
    gx = p[11];
    gy = p[12];
    gz = p[13];
  } else {
    return null;
  }

  const fingerN = toInt(finger);
  const hr = toInt(hrRaw);
  const heartRate =
    fingerN === 0 || hr === null || hr < 30 || hr > 220 ? null : hr;

  return {
    recordedAt: new Date().toISOString(),
    temperature: toFloat(temp),
    heartRate,
    accelX: toFloat(ax),
    accelY: toFloat(ay),
    accelZ: toFloat(az),
    gyroX: toFloat(gx),
    gyroY: toFloat(gy),
    gyroZ: toFloat(gz),
    batteryV: toFloat(battV),
    batteryPct: toInt(battPct),
    buttonPressed: toInt(button),
    tempValid: toInt(tValid),
    fingerDetected: fingerN,
  };
}

function telemetryLine(r: Reading): string {
  const temp = r.temperature === null ? "--" : `${r.temperature.toFixed(2)}C`;
  const hr = r.heartRate === null ? "--" : `${r.heartRate} bpm`;
  const batt = r.batteryV === null ? "--" : `${r.batteryV.toFixed(2)}V (${r.batteryPct ?? "?"}%)`;
  return `temp ${temp} | HR ${hr} | batt ${batt} | finger=${r.fingerDetected ?? "?"}`;
}

function status() {
  return {
    state,
    port: UDP_PORT,
    backend: BACKEND,
    batchSize: BATCH_SIZE,
    batchSecs: BATCH_SECS,
    dryRun: DRY_RUN,
    packets,
    posted,
    batches,
    malformed,
    lastError,
    tokenSet: !!token,
    startedAt,
  };
}

function fail(reason: string) {
  lastError = reason;
  if (flushTimer) {
    clearInterval(flushTimer);
    flushTimer = null;
  }
  const sock = socket;
  socket = null;
  if (sock) {
    try {
      sock.close();
    } catch {
      /* ignore */
    }
  }
  token = null;
  buffer = [];
  state = "error";
  logLine(`stopped: ${reason}`);
}

async function flush() {
  if (posting || buffer.length === 0 || (!token && !DRY_RUN)) return;
  posting = true;
  const batch = buffer.splice(0, buffer.length);
  lastPost = Date.now();
  try {
    if (DRY_RUN) {
      batches += 1;
      posted += batch.length;
      logLine(`[dry-run] would POST ${batch.length} readings | total ${packets}`);
      return;
    }
    const res = await fetch(`${BACKEND}/api/sensors/ingest`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ readings: batch }),
      signal: AbortSignal.timeout(10000),
    });
    if (res.status === 401 || res.status === 403) {
      fail("Session expired or not authorized - sign in again");
      return;
    }
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`HTTP ${res.status}${text ? `: ${text.slice(0, 160)}` : ""}`);
    }
    const body: any = await res.json().catch(() => ({}));
    const inserted = Number(body.inserted ?? batch.length);
    posted += inserted;
    batches += 1;
    lastError = null;
    logLine(`POSTed ${batch.length} readings (${inserted} inserted) | total ${packets}`);
  } catch (e: any) {
    lastError = e?.message || "post failed";
    logLine(`ERROR posting ${batch.length} readings: ${lastError}`);
  } finally {
    posting = false;
  }
}

function handlePacket(msg: any) {
  const line = String(msg).trim();
  if (!line) return;
  const reading = parsePacket(line);
  if (!reading) {
    malformed += 1;
    if (malformed <= 5) logLine(`skip malformed: ${line.slice(0, 120)}`);
    return;
  }
  packets += 1;
  buffer.push(reading);
  if (buffer.length > MAX_BUFFER) buffer.splice(0, buffer.length - MAX_BUFFER);
  if (TELEMETRY) console.log(`[bridge] ${telemetryLine(reading)}`);
  if (buffer.length >= BATCH_SIZE) void flush();
}

async function start(t: string): Promise<any> {
  if (!t) throw new Error("Missing token");
  token = t;
  if (state === "running") return status();
  if (state === "starting" && startPromise) return startPromise;

  stopRequested = false;
  state = "starting";
  lastError = null;
  startedAt = null;
  buffer = [];
  lastPost = Date.now();

  startPromise = new Promise((resolve, reject) => {
    const sock = dgram.createSocket("udp4");
    let settled = false;

    const onError = (err: Error) => {
      if (settled) return;
      settled = true;
      state = "error";
      lastError = err.message;
      token = null;
      try {
        sock.close();
      } catch {
        /* ignore */
      }
      logLine(`socket error: ${err.message}`);
      reject(err);
    };

    sock.once("error", onError);
    sock.on("message", (msg: any) => {
      if (settled) handlePacket(msg);
    });
    sock.bind(UDP_PORT, "0.0.0.0", () => {
      if (settled) return;
      settled = true;
      if (stopRequested) {
        try {
          sock.close();
        } catch {
          /* ignore */
        }
        state = "idle";
        token = null;
        resolve(status());
        return;
      }
      socket = sock;
      state = "running";
      lastError = null;
      startedAt = new Date().toISOString();
      sock.removeListener("error", onError);
      sock.on("error", (err: Error) => fail(err.message));
      if (flushTimer) clearInterval(flushTimer);
      flushTimer = setInterval(() => {
        void flush();
      }, Math.max(1000, BATCH_SECS * 1000));
      logLine(`listening on UDP :${UDP_PORT} -> ${BACKEND}/api/sensors/ingest`);
      resolve(status());
    });
  });

  try {
    return await startPromise;
  } finally {
    startPromise = null;
  }
}

async function stop(): Promise<any> {
  stopRequested = true;
  if (state === "idle" && !socket) return status();
  if (flushTimer) {
    clearInterval(flushTimer);
    flushTimer = null;
  }
  const sock = socket;
  socket = null;
  token = null;
  buffer = [];
  state = "stopping";
  if (sock) {
    await new Promise<void>((resolve) => {
      try {
        sock.close(() => resolve());
      } catch {
        resolve();
      }
    });
  }
  state = "idle";
  lastError = null;
  startedAt = null;
  logLine("stopped");
  return status();
}

function sameOrigin(req: any): boolean {
  const origin = req.headers?.origin;
  if (!origin) return true;
  try {
    return new URL(origin).host === req.headers.host;
  } catch {
    return false;
  }
}

function readBody(req: any): Promise<string> {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk: any) => {
      data += chunk;
      if (data.length > 64 * 1024) {
        reject(new Error("body too large"));
        req.destroy();
      }
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

function json(res: any, code: number, body: unknown) {
  res.statusCode = code;
  res.setHeader("Content-Type", "application/json");
  res.end(JSON.stringify(body));
}

export function sentiaBridge(): Plugin {
  return {
    name: "sentia-dev-bridge",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!req.url || !req.url.startsWith(`${CONTROL_PATH}/`)) return next();
        void (async () => {
          if (!sameOrigin(req)) return json(res, 403, { error: "forbidden origin" });
          const route = req.url!.slice(CONTROL_PATH.length).split("?")[0];

          if (req.method === "GET" && route === "/status") {
            return json(res, 200, status());
          }
          if (req.method === "POST" && route === "/start") {
            const raw = await readBody(req);
            let body: any = {};
            try {
              body = raw ? JSON.parse(raw) : {};
            } catch {
              return json(res, 400, { error: "invalid JSON" });
            }
            const t = typeof body.token === "string" ? body.token.trim() : "";
            if (!t) return json(res, 400, { error: "token required" });
            try {
              return json(res, 200, await start(t));
            } catch (e: any) {
              return json(res, 409, { error: e?.message || "could not start bridge", ...status() });
            }
          }
          if (req.method === "POST" && route === "/stop") {
            return json(res, 200, await stop());
          }
          return json(res, 404, { error: "not found" });
        })().catch((e: any) => {
          if (!res.headersSent) json(res, 500, { error: e?.message || "bridge error" });
        });
      });

      server.httpServer?.once("close", () => {
        void stop();
      });
      process.once("exit", () => {
        try {
          socket?.close();
        } catch {
          /* ignore */
        }
      });
    },
  };
}
