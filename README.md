# Sentia AI — AI-Powered IoT Personal Health Assistant

Sentia AI is an end-to-end **IoT + ML + LLM** health assistant. A wearable device
(ESP32-C3) captures heart rate, motion (accelerometer/gyroscope), temperature, battery,
and a panic button; the data flows into a Spring Boot backend, is aggregated and turned
into health signals by a rule-based ML service, and a DeepSeek/`openai`-style agent
answers natural-language questions about your health — grounded in *your* data, never in
raw sensor noise.

> This is a **minor-project** implementation. It is **not** a medical device — all output
> is informational, non-diagnostic, and encourages professional support when appropriate.

---

## What it does

- **Ingests live wearable data** (via UDP broadcast → bridge, or direct HTTP batch POST).
- **Aggregates + computes health signals** (activity, sleep, wellness) from the stream.
- Lets you **chat** with it: *"How was my sleep last night?"* → grounded answer using the
  `get_sleep_data` tool, personal baselines, and a **self-hosted/cloud LLM**.
- **Tracks personal baselines** (14-day rolling), **trends**, and **daily/weekly summaries**.
- **Manages reminders** ("remind me to stretch"), **health dashboard** APIs, and (designed)
  **spoken replies** via self-hosted Piper TTS + faster-whisper voice input.

---

## Architecture (high level)

```
ESP32-C3 (HR · accel · temp · battery · panic)  ── UDP ─┐
        └────────────────────────────────────── HTTP ──┤
                                                      ▼
                    Spring Boot backend  ──► PostgreSQL (public + ai_schema)
                     │ (auth, ingest, health proxy, reminders, schedulers)
                     ├─────► ML service (rule-based activity/sleep/wellness)
                     └─────► AI service (agent + tools + memory + summaries)
                                  └──► LLM: NVIDIA NIM (primary) → DeepSeek (fallback)
```

See **[`ARCHITECTURE.md`](./ARCHITECTURE.md)** for the full decision log and program flow,
and **[`HLD.md`](./HLD.md)** for the design diagram.

---

## Tech stack

| Layer | Tech |
|---|---|
| Backend | Java 21 · **Spring Boot 4.1** · JPA/JDBC · JWT |
| Database | **PostgreSQL 16** (monthly-partitioned raw, 5-min/hourly aggregates, two schemas) |
| ML | Python 3.12 · **FastAPI** · rule/statistical (no training) |
| AI | Python 3.12 · **FastAPI** · tool-calling agent (15 tools) + memory |
| LLM | **NVIDIA NIM** `openai/gpt-oss-20b` (default) → **DeepSeek** fallback → optional **Ollama** |
| STT / TTS (designed) | **faster-whisper** · **Piper** (self-hosted) |
| Hardware | **ESP32-C3** + MAX30102, MPU6050, DS18B20, battery, panic button |
| Orchestration | **Docker Compose** |

---

## Quick start

### 1. Prerequisites
- **Docker Desktop** (with Linux containers).
- A JDK ≥ 21 (for local `./mvnw` runs; the backend image builds with JDK 21 in-container).
- An **NVIDIA NIM API key** from `https://build.nvidia.com` (→ Settings → API keys,
  `nvapi-...`). New accounts get free credits.

### 2. Configure
Copy the example env and set your key:
```bash
cp .env.example .env      # then edit .env
```
Key entries:
```
# .env
LLM_PROVIDER=nvidia
NVIDIA_API_KEY=nvapi-...          # your build.nvidia.com key
NVIDIA_MODEL=openai/gpt-oss-20b
NVIDIA_BASE_URL=https://integrate.api.nvidia.com/v1
```

### 3. Run the stack
```bash
docker compose up -d --build
docker compose ps        # postgres, backend, ai-service, ml-service all healthy
```
Optional local LLM (alternative): `docker compose --profile llm up -d` then
`LLM_PROVIDER=ollama`.

### 4. Try it
```bash
# Login (seeded demo user) → get a token
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@sentia.ai","password":"password123"}'

# Ask the assistant (replace <token>)
curl -s -X POST http://localhost:8000/api/assistant/chat \
  -H "Content-Type: application/json" -H "Authorization: Bearer <token>" \
  -d '{"message":"How was my sleep last night?","conversation_id":null}'
```
Or run the helper script: `powershell -ExecutionPolicy Bypass -File chat-test.ps1`
("How was my sleep last night?"). A **Postman collection**
(`SentiaAI.postman_collection.json`) covers all endpoints.

### 5. Simulate / ingest real data
```bash
# Replay a wearable CSV (from udp_dashboard.py / udp_ingest_bridge.py) into the backend
SENTIA_EMAIL=dev@sentia.ai SENTIA_PASSWORD=password123 \
  python csv_ingest_sim.py path/to/wearable_log.csv
```
### Live ingestion while signed in (dev)

Restart `npm run dev` after pulling this change (the plugin is loaded at server
startup). Run the dashboard with `npm run dev` from `frontend/`, then sign in. The Vite
dev server hosts a small Node UDP listener (`frontend/vite.bridge.ts`) on port
`4210`; it parses the wearable's CSV packets and posts batches to
`/api/sensors/ingest` **as the signed-in user**. Signing out stops the listener.
No Python install is needed.

- Logs: `logs/udp_bridge.log` (plus live telemetry in the Vite terminal).
- If no packets arrive, allow **Node.js** through Windows Defender Firewall for
  private networks (inbound UDP `4210`). Signing out stops ingestion; closing the
  tab without signing out leaves it running until the dev server stops.
- Knobs: `SENTIA_UDP_PORT`, `SENTIA_BATCH_SIZE` (default 20),
  `SENTIA_BATCH_SECS` (default 5), `SENTIA_BRIDGE_DRY_RUN=1` (parse/log, no POST),
  `SENTIA_BRIDGE_TELEMETRY=0` (quiet console).
- The old `SentiaAI UDP Bridge` Windows scheduled task is disabled. The
  PowerShell/Python bridges remain manual fallbacks:

```bash
SENTIA_EMAIL=dev@sentia.ai SENTIA_PASSWORD=password123 python udp_ingest_bridge.py
```

The 5-min aggregation + ML activity job then pick the readings up automatically.

---

## Repository layout

```
backend/         Spring Boot 4.1 (REST, JWT, schedulers, JPA/JDBC)
ai-service/      FastAPI (agent, 15 tools, memory, context, summaries)
ml-service/      FastAPI (rule-based activity/sleep/wellness)
frontend/        React + Vite + TypeScript dashboard (Sentry design system)
database/        init.sql (schema) + seed.sql (demo data)
esp32/           ESP32-C3 firmware (HTTP batch POST) + README
frontend/vite.bridge.ts  dev-only UDP ingestion (starts on app login)
udp_ingest_bridge.py   manual UDP → backend fallback (PC)
csv_ingest_sim.py      replay a wearable CSV into the backend
chat-test.ps1          quick chat smoke test
docker-compose.yml     postgres, backend, ai-service, ml-service (+optional ollama)
Sentia AI.md           implementation plan (v1.x)
ARCHITECTURE.md        decisions + end-to-end flow (this repo)
SENTRY_DESIGN.md       design system for the frontend (tokens + health adaptations)
HLD.md                 high-level design diagram
Minor_Project_Synopsis.md   academic write-up
```

> **Note:** Sentia AI's Postgres is published on host port **5433** (containers use
> `postgres:5432` internally) to avoid clashing with other local Postgres instances.

---

## Frontend dashboard

A React + Vite + TypeScript dashboard styled with the **Sentry** design system
(`SENTRY_DESIGN.md`). It shows the health dashboard (metric cards + sparklines +
baseline deviations with alert/watch/ok flags), chat with the assistant, and
reminders.

```bash
docker compose up -d          # backend + AI service
cd frontend && npm install && npm run dev   # http://localhost:5173
```
Sign in with the seeded demo user: **dev@sentia.ai / password123**. The dev server
proxies `/api/assistant/*` → AI service (:8000) and `/api/*` → backend (:8080), so
no CORS setup is needed. See [`frontend/README.md`](./frontend/README.md).



## API surface (summary)

**Backend (:8080)** — 18 endpoints
- `POST /api/auth/register` · `POST /api/auth/login`
- `POST /api/sensors/ingest` (batch, JWT)
- `GET /api/health/{profile,today,heart-rate,activity,sleep,temperature,baseline,compare-baseline,wellness,trends}`
- `POST|GET|PUT|DELETE /api/reminders[…]`

**AI service (:8000)**
- `POST /api/assistant/chat` · `GET /api/assistant/history/{id}`
- `GET /api/assistant/daily-summary` · `GET /api/assistant/weekly-summary`

**ML service (:8001)**
- `POST /classify/activity` · `POST /estimate/sleep` · `POST /assess/wellness`

---

## Security & privacy
- JWT auth; the AI service validates + forwards the token back to the backend for health
  reads. Tool `user_id` always comes from the JWT, never the model.
- Six-layer prompt-injection defense (whitelist tools, JSON guards, caps, sanitization).
- **Never log** raw sensor values or PHI; structured JSON logs with correlation IDs.
- Wellness language is **non-diagnostic** and probabilistic.
- Self-hosted STT/TTS (designed) keep audio on your infra.

---

## Status / roadmap

Implemented: ingestion (UDP + HTTP), auth, health proxy, reminders, aggregation (with
new-user + ON CONFLICT fixes), rule-based ML + scheduling, baseline recompute,
tool-calling chat (NVIDIA NIM), memory, daily/weekly summaries, ESP32 firmware.

In progress: **retention/purge jobs** (`RetentionJobs`).

Designed / future: **TTS & voice loop** (Piper + faster-whisper), **frontend** (external
team), rate-limiting/observability hardening, and (long-term) upgrade of rule-based
signals once labeled data accumulates.

---

## Notes
- The wearable's **UDP broadcast** (with reduced TX power) is the current, tested path
  (see `context_2.md`); the **HTTP firmware** is the direct-device alternative.
- **Not a diagnosis.** Always consult a healthcare professional for medical concerns.
