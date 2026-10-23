# Sentia AI — Frontend

React + Vite + TypeScript dashboard for Sentia AI, styled with the **Sentry**
design system ([`../SENTRY_DESIGN.md`](../SENTRY_DESIGN.md)) plus the health
semantic additions (alert / watch / ok).

## Run

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

The dev server proxies API calls (see `vite.config.ts`):
- `/api/assistant/*` → **AI service** `http://localhost:8000`
- `/api/*`           → **Spring Boot backend** `http://localhost:8080`

so there's no CORS setup needed. Start the stack first: `docker compose up -d`
(from the repo root).

## Sign in

Demo user (seeded): **dev@sentia.ai / password123**

## Screens

- **Health Dashboard** — metric cards (resting HR, avg HR, steps, sleep, active
  minutes, temperature) with sparklines, a wellness card, and a **baseline
  deviations** table. Flags: `|z| > 3` → **alert** (pink), `1.5 < |z| ≤ 3` →
  **watch** (amber), else **within normal** (green). Non-diagnostic.
- **Chat** — calls the AI service; shows tool calls made and the grounded reply.
- **Reminders** — list + create + delete against the backend.

## Notes

- Auth: JWT stored in `localStorage`, sent as `Authorization: Bearer`.
- New/seedless users show "no data" gracefully; the deviations table requires a
  14-day baseline.
- `npm run build` type-checks (`tsc --noEmit`) and produces `dist/`.

## Roadmap (iteration)

- Trends view (multi-metric charts), daily/weekly summary view.
- Reminder edit + recurrence UI (currently create/list/delete).
- Voice loop (Piper TTS / faster-whisper STT) once the AI service exposes it.
- Light "transactional" surface (settings/tables) per the design doc.
