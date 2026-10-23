# pyrefly: ignore [missing-import]
from fastapi import FastAPI
# pyrefly: ignore [missing-import]
from fastapi.middleware.cors import CORSMiddleware

from app.classifiers import assess_wellness, classify_activity, estimate_sleep
from app.schemas import (
    ActivityResponse,
    ActivityWindow,
    SleepResponse,
    SleepWindow,
    WellnessRequest,
    WellnessResponse,
)

app = FastAPI(title="SentiaAI ML Service", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health_check():
    return {"status": "ok", "service": "ml-service", "version": "0.1.0", "models_loaded": []}


@app.post("/classify/activity", response_model=ActivityResponse)
async def classify_activity_endpoint(windows: list[ActivityWindow]):
    results = []
    for w in windows:
        activity_type, confidence, steps = classify_activity(
            w.accel_mag_std, w.accel_mag_avg, w.hr_avg, w.motion_ratio
        )
        results.append({
            "user_id": w.user_id,
            "window_start": w.window_start,
            "activity_type": activity_type,
            "confidence": confidence,
            "steps_estimate": steps,
            "accel_mag_avg": w.accel_mag_avg,
        })
    return {"results": results}


@app.post("/estimate/sleep", response_model=SleepResponse)
async def estimate_sleep_endpoint(windows: list[SleepWindow]):
    start, end, duration_min, interruptions = estimate_sleep(
        [w.model_dump() for w in windows]
    )
    if start is None:
        return SleepResponse(detected=False)
    return SleepResponse(
        sleep_start=start.isoformat(),
        sleep_end=end.isoformat(),
        duration_min=duration_min,
        interruptions=interruptions,
        detected=True,
    )


@app.post("/assess/wellness", response_model=WellnessResponse)
async def assess_wellness_endpoint(req: WellnessRequest):
    result = assess_wellness(
        req.date,
        req.stats.model_dump(),
        req.baseline.model_dump(),
    )
    return WellnessResponse(**result)
