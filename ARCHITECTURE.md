# Sentia AI — Architecture & Program Flow

A deep-dive into **how the system is structured, why each decision was made, and how a
reading flows end-to-end** from the wearable to a grounded conversational answer.

> Companions: [`Sentia AI.md`](./Sentia%20AI.md) (implementation plan) ·
> [`HLD.md`](./HLD.md) (high-level design) ·
> [`Minor_Project_Synopsis.md`](./Minor_Project_Synopsis.md) (academic write-up).

---

## 1. The system at a glance

```
 ESP32-C3 wearable
   · MAX30102  (heart-rate)        ┐
   · MPU6050   (accel + gyro)      │  continuous sensing
   · DS18B20   (temperature)       │
   · battery ADC + panic button    ┘
        │
        ▼  two data paths
   A) WiFi UDP broadcast ──> PC `udp_ingest_bridge.py` ──> POST  (recommended)
   B) HTTP batch POST firmware (`esp32/`) ───────────────────> POST
        │
        ▼
   Spring Boot backend  :8080   (owns `public` schema)
     · /api/sensors/ingest   · auth (JWT)
     · /api/health/* proxy   · /api/reminders
     · @Scheduled jobs: aggregation, ML triggers, baselines
        │
        ├─────────────► PostgreSQL 16  (public + ai_schema)
        │
        └─────► ML service :8001 (stateless, rule-based)
        │
   AI service :8000   (owns `ai_schema`; reads health via backend REST only)
     · /api/assistant/chat + tools; memory; context; summaries
     · LLM: NVIDIA NIM (primary) → DeepSeek (fallback); Ollama optional
     · (designed) Piper TTS + faster-whisper STT
        │
   Frontend (React/Flutter — external team) — chat, dashboard, reminders
```

---

## 2. Core architectural principles

1. **The LLM never sees raw sensor streams.**
   `raw → signal processing → feature extraction → ML signals → structured health data → LLM`.
   The AI service and ML service only ever read **aggregated** tables
   (`sensor_readings_5min`, `sensor_readings_hourly`) and **processed records**
   (`activity_records`, `sleep_records`, `health_assessments`, `health_baselines`).
   This is the single most important rule — it keeps the model token-efficient, safe,
   and free of noise.

2. **Ownership boundaries (one Postgres, two schemas).**
   - Spring Boot owns & writes the **`public`** schema (users, sensor data, aggregates,
     health records, baselines, assessments, reminders).
   - The **AI service** owns & writes the **`ai_schema`** (conversations, messages,
     memory, summaries, token usage).
   - The AI service reads health data **only** via the Spring Boot REST proxy; it never
     touches `public` tables directly. This prevents cross-schema leakage and keeps one
     logical source of truth for health data.

3. **Stateless ML service.** No DB access. It receives HTTP feature payloads and returns
   predictions. The backend owns persistence.

4. **No models are trained.** Activity/sleep/wellness are **rule/statistical**
   determinism (thresholds + z-scores). STT/TTS use pre-trained models (faster-whisper,
   Piper) pulled once — never trained here. This removes the cold-start/labeling burden.

5. **Everything is batched.** The device buffers readings and batches them; the backend
   batches aggregation; the AI service batches ML calls; the bridge batches UDP packets.

6. **Graceful degradation.** Each service hop has timeout + error handling; if the ML/LLM/
   backend is down, the assistant returns a friendly message instead of crashing.

---

## 3. Technology decisions & why

| Area | Choice | Rationale |
|---|---|---|
| Backend | Java 21, **Spring Boot 4.1.0** | Modern, GA release (June 2026, Spring Framework 7) |
| DB | **PostgreSQL 16** | Native partitioning, JSONB, robust for sensor volume |
| ML | Python 3.12, **FastAPI** | Async, typed (pydantic), clean for inference endpoints |
| AI | Python 3.12, **FastAPI** | Async; long-lived HTTP/LLM calls won't block the event loop |
| LLM (default) | **NVIDIA NIM** (`openai/gpt-oss-20b`) | OpenAI-compatible, strong tool-calling, free credits |
| LLM (fallback) | **DeepSeek** (`deepseek-chat`) | Auto-fallback when NIM fails; 128K context, reliable tools |
| LLM (optional) | **Ollama** (Qwen3-8B Q4) | Offline/zero-cost/private option; reduced tool set (§9.5) |
| STT / TTS | **faster-whisper** / **Piper** (self-hosted) | Privacy (audio never leaves infra), free, offline |
| Orchestration | **Docker Compose** (4–7 services) | Simple multi-service dev/deploy |

**Model-selection journey (a real lesson):** the originally recommended
`nvidia/llama-3.3-nemotron-super-49b-v1` (and its `.v1.5`) hit **end-of-life**
on build.nvidia.com → `410 Gone`. After enumerating the live `/v1/models` catalog, the
default moved to **`openai/gpt-oss-20b`** (fast MoE, tool-calling), and it's proven to
work. **Lesson: don't hardcode model ids — read them from the provider's `/models`**
(the plan now documents the exact id and the EOL risk).

**Jackson note (Spring Boot 4):** JSON is Jackson **3** — package `tools.jackson`
(not `com.fasterxml.jackson`). This affected the ML client and JSONB mapping.

---

## 4. Database layout

### `public` (Spring Boot owns)
- `users` — auth/profile.
- `sensor_readings` — raw, **partitioned by month** (`RANGE recorded_at`); extra columns
  `gyro_*`, `battery_*`, `button_pressed`, `temp_valid`, `finger_detected` (wearable).
- `sensor_readings_5min` — roll-up per user per 5-min window (HR avg/min/max, accel-mag
  avg/std, temp avg, count, `processed_for_activity`).
- `sensor_readings_hourly` — roll-up per user per hour (permanent tier).
- `activity_records`, `sleep_records`, `temperature_records` — ML/derived signals.
- `health_baselines` — rolling 14-day mean **and std** (`std_*`) + typical bed/wake.
- `health_assessments` — wellness (JSONB `factors`, `raw_output`).
- `reminders` — soft-delete via `deleted_at`.

### `ai_schema` (AI service owns)
- `conversations`, `conversation_messages` (JSONB `tool_calls`).
- `user_memory` (long-term), `daily_summaries`, `weekly_summaries`, `token_usage`.

### Partitioning & aggregation (the volume play)
At ~2 Hz a device produces ~172k rows/day/user (ceiling). Strategy:
- **Monthly partitions** for `sensor_readings` (created by `PartitionManager` + `init.sql`).
- **Aggregation jobs** (5-min and hourly) use a **per-user watermark CTE** and only
  aggregate **closed windows**, with **`ON CONFLICT DO UPDATE`** (idempotent, re-runnable)
  and **`LEFT JOIN`** so *brand-new users* with no prior rows are still picked up
  (a real bug found & fixed — a plain `JOIN` excluded new users forever).
- **Retention** (design §7.3/§12.3): raw 7 days (drop old partitions), 5-min 90 days
  (DELETE), hourly forever. Implemented as `RetentionJobs` (cron 3 AM / 4 AM),
  config-driven; **conversation_messages** 90-day weekly cleanup.

---

## 5. End-to-end program flow

### 5.1 Ingestion (wearable → DB)
1. **ESP32** samples sensors (non-blocking loop); HR beat detection runs every iteration.
2. **Path A (recommended):** broadcasts UDP CSV (13 fields) → PC **`udp_ingest_bridge.py`**
   parses → **batches** (60 / 30 s) → `POST /api/sensors/ingest` with a JWT.
   (Field order maps 1:1 to the schema; `recordedAt` = receive time.)
   **Path B:** `esp32/esp32_sentia_ingest.ino` batch-POSTs directly over HTTP with NTP timestamps.
3. Backend validates (JWT, `@Valid` batch) → inserts into partitioned `sensor_readings`.

### 5.2 Aggregation (interval: `app.analytics.aggregate-interval-ms`, default 5 min)
- `aggregate5MinWindows()` + `aggregateHourlyWindows()` use the watermark CTE +
  closed-window predicate + `ON CONFLICT DO UPDATE` (and `LEFT JOIN`/`COALESCE` for new users).
  The watermark backtracks one bucket (5 min / 1 h) so a late batch can't freeze a
  partial window.
- `processed_for_activity` is **preserved** on conflict (only new windows default to
  `FALSE`). Resetting it on every run re-classified windows and created duplicate
  `activity_records`.
- Both timers share one interval so a short demo session populates the 5-min **and**
  hourly tiers within minutes; production can raise it.

### 5.3 ML signals (scheduled)
- **Activity** (every 5 min): read unprocessed 5-min windows → `MlServiceClient` →
  `POST /classify/activity` → `activity_records`; mark processed.
- **Sleep** (06:00): last night's 5-min windows → `POST /estimate/sleep` → `sleep_records`.
- **Wellness** (08:00): today's stats + current baseline → `POST /assess/wellness` →
  `health_assessments` (incl. `|z|>3` anomaly flags). If no assessment exists yet, the
  `/api/health/wellness` endpoint computes the z-score signal on the fly.
- **Baselines** (every `app.analytics.baseline-interval-ms`, default 5 min): recompute
  mean+std over the last `app.analytics.lookback-hours` (default **24 h** — a single
  ~1 h demo session is enough). HR/temperature use per-5-min buckets (12 samples/hour)
  for real variance; steps/active stay per-day totals (so they compare 1:1 with the
  dashboard). Std has metric-specific minimums (HR ±2, temp ±0.2 °C) so z-scores stay
  finite; the current row is updated in place instead of inserting a new one each run.

### 5.4 Conversational agent (AI service)
- `POST /api/assistant/chat` (JWT) →
  1. **Auth** — validate the Spring-issued JWT (HS256/384/512 via PyJWT).
  2. **Conversation/memory** — create/load conversation; load short-term history (last 20).
  3. **Tier-1 context** — `health_client.tier1_context(jwt)` → profile + baseline summary.
  4. **Agent loop** — system prompt + context + history + user message → LLM.
     - If the model emits **tool calls**, `execute_tool` runs each under the **JWT user scope**
       → fetches from the Spring health proxy → appends results → LLM again (≤5 rounds).
  5. **Answer** → persist to `conversation_messages` → return `{conversation_id, message}`.
- **Tools (15).** Data fetchers (`get_sleep_data`, `get_heart_rate_data`, …),
  actions (`create_reminder`…), analysis (`get_wellness_assessment`,
  `get_daily_summary`, `get_weekly_summary` — the last two generate + store summaries).
  The set is **provider-dependent**: full 15 for NIM/DeepSeek, ~7 for local Ollama.

### 5.5 Summaries & reminders
- `get_daily_summary` / `get_weekly_summary` (tools + REST `GET /api/assistant/daily-summary`,
  `/weekly-summary`): LLM writes a grounded, non-diagnostic summary over snapshot + baseline
  (daily) / baseline + trends (weekly) and upserts into `ai_schema`.
- Reminders CRUD (create/list/update/delete; soft delete) — Phase 2.

### 5.6 Output surface
- Frontend (external team) → chat (AI service) + dashboard/reminders (backend).
- **TTS/STT (designed, §28/29):** spoken replies via **Piper**; push-to-talk **voice loop**
  via **faster-whisper**. Not yet wired in code.

---

## 6. Security model

Six defense layers for prompt-injection (the agent is the highest risk surface):
1. **Tool authorization** — `user_id` always from the JWT, never from LLM args; `ALLOWED_TOOLS`
   whitelist rejects hallucinated tool names; malformed JSON args → friendly error.
2. **System-prompt rules** — no inventing data, no diagnosis, no internal leakage.
3. **Input sanitization** — regex/length guards.
4. **Tool-call caps** — ≤3 tools per round, ≤5 rounds per turn.
5. **Rate limiting** (design) — Bucket4j at the gateway.
6. **Audit logging** — every tool execution logged (sanitized).

Plus:
- **JWT** issued by backend (HS*, 24 h); AI service validates + **forwards the JWT** to the
  backend for health reads (backend re-validates, never trusts a client `user_id`).
- **Don't expose port 8080** externally in prod — only internal via the Docker network.
- **Never log PHI/raw sensor values**; structured JSON logs + correlation IDs.
- **Health framing is non-diagnostic**; user-facing language is probabilistic.

---

## 7. Resilience & error handling

- **Timeout budgets** per hop (backend ↔ ML ↔ LLM).
- **Retry/fallback:** LLM primary (NIM) → on timeout/5xx/malformed → **DeepSeek** fallback.
- **Health-proxy degrade:** if the backend is down, `health_client` returns `{}` and the
  agent answers anyway (without data) rather than 500.
- **Graceful chat errors:** `502` "assistant temporarily unavailable" on LLM failure.

---

## 8. Key gotchas we hit & fixed (worth knowing)

- **Aggregation `JOIN` excluded new users** → `LEFT JOIN` + `COALESCE(watermark, epoch)`.
- **Hourly `ON CONFLICT` "cannot affect row a second time"** → the ambiguous alias
  `GROUP BY window_start` resolved to the real 5-min column; fixed by grouping on
  `date_trunc('hour', window_start)`.
- **Spring RestClient built with `RestClient.builder()` didn't send request bodies**
  (no Jackson-3 converter) → switched to JDK `HttpClient`, and forced **HTTP/1.1**
  (default HTTP/2 had a body-framing issue → 422).
- **Jackson 3** uses `tools.jackson.*`; **`user_id` must map to `UUID`** (Postgres
  `uuid` ≠ `varchar`) in all ORM models (conversations, summaries).
- **Time zone-less timestamps** from Postgres need normalization (`parseInstant`);
  bridge stamps receive-time, HTTP firmware uses NTP.
- **Seeded BCrypt hash was wrong** → fixed in `seed.sql`; Spring's `BCrypt` verified.
- **BIGSERIAL sequence collision** after seeding explicit ids → `setval` in `seed.sql`.
- **ESP32 `%f` printf** unreliable → use `String(value, decimals)`.

---

## 9. Current implementation status

| Area | Status |
|---|---|
| Auth, ingestion, health proxy, reminders | ✅ Done (18 backend APIs) |
| Aggregation (5-min / hourly) | ✅ Done (+ new-user & ON CONFLICT fixes; configurable cadence) |
| ML service (activity/sleep/wellness, rule-based) | ✅ Done (3 endpoints) |
| ML scheduler + baseline recompute | ✅ Done (session-aware lookback, std guards) |
| AI chat + tool-calling (15 tools) + memory | ✅ Done (NVIDIA NIM default) |
| Daily/weekly summaries | ✅ Done |
| ESP32 firmware (HTTP batch) + UDP bridge + CSV sim | ✅ Done |
| Retention jobs (raw/5-min + 90-day conversations) | ✅ Done (`RetentionJobs` + AI-service task) |
| TTS/STT (Piper / faster-whisper voice loop) | ⬜ Designed (§28/29), not wired |
| Frontend (React/Vite dashboard + chat + reminders) | ✅ Done (`frontend/`, animations + skeleton states) |
| Rate limiting / integration-test hardening | ⬜ Phase 9 |
