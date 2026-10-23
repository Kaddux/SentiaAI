-- Host-side incremental step detector state for devices that only send the
-- 15-field UDP CSV (accel/gyro, no step counter).
CREATE TABLE IF NOT EXISTS step_state (
    user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    step_date           DATE NOT NULL,
    last_processed_at   TIMESTAMP,
    last_signal         DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_peak_at        TIMESTAMP,
    steps               INTEGER NOT NULL DEFAULT 0,
    moving              BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMP DEFAULT NOW()
);
