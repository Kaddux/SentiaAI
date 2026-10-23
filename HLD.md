# Sentia AI — High-Level Design (HLD)

**Project:** AI-Powered Personal Health Assistant
**Version:** 1.0
**Date:** August 2026
**Source of detail:** [`Sentia AI.md`](./Sentia%20AI.md) (implementation plan) · [`Minor_Project_Synopsis.md`](./Minor_Project_Synopsis.md)

---

## 1. Purpose

This High-Level Design expands the workspace architecture diagram into the full system: the end-to-end pipeline from an ESP32-C3 wearable, through data ingestion and ML, to a conversational DeepSeek-backed agent that can also **speak** its answers via a self-hosted Piper TTS service.

> **Core principle (non-negotiable):** the LLM (and the TTS engine) **never** processes raw sensor streams or message raw health dumps. Only structured, whitelisted health signals reach the model, and TTS speaks only the finalized assistant text.

---

## 2. High-Level Architecture

Expanded from the workspace HLD (Excalidraw-style whiteboard sketch):

```
                      ┌─────────────────────────────┐
                      │       ESP32-C3 Wearable      │
                      ├─────────────────────────────┤
                      │ MAX30102 HR · MPU6050 ACCEL │
                      │ DS18B20 TEMP · WS2812 LED   │
                      └───────────────┬─────────────┘
                                      │
                POST /api/sensors/ingest (JWT)
                                      │
                                      ▼
                      ┌─────────────────────────────┐
                      │   SPRING BOOT BACKEND (8080) │
                      ├─────────────────────────────┤
                      │ REST APIs · JWT auth         │
                      │ Scheduler · Aggregation      │
                      │ Data Ingestion & Processing  │
                      └──────┬─────────────┬─────────┘
                             │             │
              JDBC/JPA       │             │   HTTP REST
                             ├─────────────┤
                             │             │   (scheduled ML triggers)
                             │             ▼
                             │     ┌────────────────────┐
                              │     │  ML SERVICE (8001)  │
                              │     ├────────────────────┤
                              │     │ Activity · Sleep    │
                              │     │ Wellness + flags    │
                              │     │ (stateless, no DB)  │
                             │     └──────────┬─────────┘
                             │                │  predictions
                             ▼                ▼
                      ┌─────────────────────┐  ┌──────────────────────────────┐
                      │ POSTGRESQL 16 (5432)│  │      AI SERVICE (FastAPI,    │
                      ├─────────────────────┤  │           8000)              │
                      │  public schema:     │  ├──────────────────────────────┤
                      │ · sensor_readings   │  │ Agent (15 tools) · Memory    │
                      │   (monthly part)    │  │ Health Context Engine        │
                      │ · 5min / hourly agg │  │ health-proxy client          │
                      │ · activity · sleep  │  │ TTS facade · STT client      │
                      │ · temp · baselines  │  └───────┬─────────────┬────────┘
                      │ · assessments       │          │             │
                      │ · reminders         │   HTTPS  │             │  HTTP
                      └─────────────────────┘   (LLM) │             │ (TTS)
                                                  ▼             ▼
                                        ┌────────────────┐  ┌──────────────────┐
                                        │  DeepSeek LLM  │  │  PIPER TTS (5000)│
                                        │   (api.deep-   │  ├──────────────────┤
                                        │   seek.com)    │  │ local neural TTS │
                                        └────────────────┘  │ (self-hosted)    │
                                                            └────────┬─────────┘
                                                                     │ WAV audio
                                                                     ▼
                                     ┌───────────────────────────────────────────┐
                                     │      FRONTEND (React / Flutter)           │
                                     ├───────────────────────────────────────────┤
                                     │ Chat · Health Dashboard · Reminders view  │
                                     │  · Push-to-talk voice (mic + playback)    │
                                     └──────────────┬───────────────┬────────────┘
                                                    │               │
                                    HTTP REST (auth, │               │ HTTP REST
                                    dashboard, rem.)│               │ (chat + voice)
                                                    ▼               ▼
                                          ┌─────────────────────────────┐
                                          │   SPRING BOOT BACKEND       │
                                          │   AI SERVICE (chat + voice) │
                                          └─────────────────────────────┘
                                                            ▲
                                                 audio (WAV) │  POST /api/assistant/voice
                                                            │
                                                 ┌────────────────────┐
                                                 │  STT (5001)          │
                                                 ├────────────────────┤
                                                 │ faster-whisper small│
                                                 │ (speech → text)     │
                                                 └────────────────────┘
```

> Notes: `ai_schema` (PostgreSQL) is written by the AI Service via asyncpg/SQLAlchemy; health data is read *only* through the Spring Boot health-proxy REST APIs. Arrows are simplified for legibility — see the ownership table and Section 6 (Communication Matrix) for the exact direction of each hop.

**Ownership boundary:**

| Block | Owns | Reads |
|---|---|---|
| Spring Boot | `public` schema (users, sensor data, aggregate, health records, baselines, reminders) | its own writes |
| ML Service | nothing (stateless) | receives HTTP features |
| AI Service | `ai_schema` (conversations, memory, summaries, token/tts usage) | health data via Spring Boot REST only |
| Piper | nothing (stateless inference) | receives text via HTTP |
| STT | nothing (stateless) | receives audio via HTTP |

---

## 3. Service Inventory

| Service | Tech | Role | Port | DB Access |
|---|---|---|---|---|
| `backend` | Java 21, Spring Boot 4.1 | Ingestion, auth, schedulers, health proxy, reminders | 8080 | `public` (JDBC/JPA) |
| `ai-service` | Python 3.12, FastAPI | Agent, tools, memory, context engine, TTS + STT + voice endpoint | 8000 | `ai_schema` (asyncpg) |
| `ml-service` | Python 3.12, FastAPI | Activity, sleep, wellness (rule/statistical, incl. anomaly flags) | 8001 | none |
| `piper` | Python 3.12, piper-tts | Local neural TTS (WAV) | 5000 | none |
| `stt` | Python 3.12, faster-whisper | Local speech-to-text | 5001 | none |
| `postgres` | PostgreSQL 16 | Persistence (2 schemas) | 5432 | — |

---

## 4. Hardware Layer (Sensor Ingestion)

The dev-wearable BOM maps to the ingestion device:

| Hardware | Function in System |
|---|---|
| **ESP32-C3 Super Mini** | MCU — samples sensors, buffers, HTTP POSTs to backend |
| **MAX30102** (×1) | Heart rate + SpO₂ → `heart_rate` readings |
| **MPU6050** (×1) | 3-axis accel + gyro → `accel_x/y/z` (activity/motion) |
| **DS18B20** (×2) | Body/environment temperature → `temperature` readings |
| **WS2812 LED** (×1) | Status/notification light on device |
| **Active buzzer** (×1) | Audible notification beep (not speech — TTS is served by Piper) |
| **Push buttons** (×2), **SPST switches** (×5) | Manual control, power, mode toggle |
| **Li-Po 600mAh** + **2× TP4056** + **buck-boost** | Power: 3.7V cell, charging, regulated 3.3V rail |
| **Prototype board**, Berg strips, **JST** connectors | Physical integration |

> **Note on on-device speech:** the ESP32-C3 cannot run quality TTS (CPU/RAM, no DAC, Wi-Fi bandwidth). Spoken answers come from **Piper (server-side)** and are streamed to the phone/web client; the buzzer is for short beeps only.

---

## 5. Data Flow (End-to-End Sequence)

```
ESP32 samples (HR / accel / temp)
  → POST /api/sensors/ingest (JWT device auth)
  → sensor_readings (monthly partition, public schema)
  → @Scheduled 5-min: aggregate → sensor_readings_5min  (watermark, ON CONFLICT DO NOTHING)
  → @Scheduled 5-min: POST /classify/activity           → activity_records
  → @Scheduled 6 AM:   POST /estimate/sleep           → sleep_records
  → @Scheduled 8 AM:   POST /assess/wellness          → health_assessments (incl. |z|>3 anomaly flags)
  → @Scheduled 9 AM:   trigger daily summary          → ai_schema.daily_summaries
  ──────────────────────────────────────────────────────────────
User: "How was my sleep last night?"
  → POST /api/assistant/chat (JWT)
  → sanitize → build [system + tier1 context + short-term history + user]
  → agent loop (≤5 tool calls): execute_tool(user_id FROM JWT)
      → Spring Boot health proxy → tool result → append
  → final assistant text
  → POST /api/assistant/tts { text } → Piper /synthesize → WAV → frontend <audio>

Verbal turn (push-to-talk):
  → mic (MediaRecorder → webm/opus) → POST /api/assistant/voice (JWT)
  → STT at :5001 → text → sanitize → run_agent() → final text
  → Piper → WAV → frontend plays + shows transcript
```

---

## 6. Communication Matrix

| From | To | Protocol | Purpose |
|---|---|---|---|
| ESP32 | Spring Boot | HTTP POST | Sensor ingestion |
| Spring Boot | PostgreSQL | JDBC/JPA | Persistence (`public`) |
| Spring Boot | ML Service | HTTP REST | Scheduled ML inference |
| Spring Boot | AI Service | HTTP REST | Daily summary trigger |
| AI Service | Spring Boot | HTTP REST | Health data (tool calls) |
| AI Service | PostgreSQL | asyncpg/SQLAlchemy | `ai_schema` only |
| AI Service | Ollama (local primary) | HTTP | LLM calls (OpenAI-compatible `/v1`) — local Qwen3-8B Q4 |
| AI Service | DeepSeek | HTTPS | LLM fallback (auto if Ollama fails) |
| AI Service | Piper | HTTP POST | `/synthesize` → WAV |
| AI Service | STT | HTTP POST | `/transcribe` → text |
| Frontend | Spring Boot | HTTP REST | Auth, dashboard, reminders |
| Frontend | AI Service | HTTP REST | Chat, speech synthesis (TTS) |
| Frontend | AI Service | HTTP REST (multipart) | Voice (audio → text + audio reply) |

---

## 7. Deployment Topology (Docker Compose)

7 services, all with healthchecks, wired by network names:

```
postgres ──healthy──> backend ──started──> ai-service
                     backend ────────────> ml-service
                     ai-service ─────────> piper
                     ai-service ─────────> stt
                     ai-service ─────────> postgres (ai_schema)
```

Key points: `no version: field`; Python one-liner healthchecks (no `curl`); volumes `postgres_data`, `piper_voices`, `stt_models`, `./ml-service/models_saved`; `.env` with `:-` defaults.

---

## 8. Non-Functional Requirements

| Concern | Approach |
|---|---|
| **Privacy** | Self-hosted Piper + STT (audio/transcripts never leave infra); never log PHI/raw sensor values; `user_id` scoping |
| **Cost** | Local LLM (Ollama) free; DeepSeek fallback ~$0.01/user/day; Piper + STT free/self-hosted |
| **Security** | 6-layer prompt-injection defense; JWT auth; tool authz from JWT not LLM args; rate limiting; audit logging |
| **Resilience** | Timeout budgets, tenacity retry, pybreaker circuit breaker, graceful degradation messages |
| **Observability** | structlog JSON + correlation IDs; token + TTS usage tracking |
| **Latency** | Aggregate curves queried (not raw); cached tier-2 context; cached TTS audio |

---

## 9. MVP Scope & Implementation Status

**Target MVP:** conversational chat, today's health summary, sleep/activity/heart-rate queries, basic reminders, spoken replies (Piper) and verbal input (push-to-talk STT).

**Current status (code):** **Phase 1 skeleton.** Docker Compose + `/health` endpoints for backend/AI/ML, JWT security config, DB schema — wired. Everything else is design-documented but not yet built.

| Area | Status |
|---|---|
| Ingestion, aggregation, retention | documented (plan §12) |
| ML endpooints | documented (plan §19) |
| Agent + 15 tools | documented (plan §9–10) |
| Memory / context engine | documented (plan §17–18) |
| Summaries + reminders | documented (plan §7, §13) |
| **TTS (Piper)** | documented (plan §28) — new |

> **Note:** the full functional detail for each area lives in [`Sentia AI.md`](./Sentia%20AI.md); this HLD is the architectural overview. Minor_Paper/Synopsis is the academic-facing description.
