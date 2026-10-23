-- ============================================================
-- SentiaAI — Full schema (public + ai_schema)
-- Runs via /docker-entrypoint-initdb.d/01-init.sql
-- ============================================================

CREATE SCHEMA IF NOT EXISTS ai_schema;

-- ============================================================
-- public schema — owned by Spring Boot
-- ============================================================

-- Users
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),
    date_of_birth   DATE,
    gender          VARCHAR(20),
    height_cm       DECIMAL(5,1),
    weight_kg       DECIMAL(5,1),
    timezone        VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- ============================================================
-- Sensor readings — PARTITIONED by month
-- ============================================================

CREATE TABLE sensor_readings (
    id              BIGSERIAL,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recorded_at     TIMESTAMP NOT NULL,
    heart_rate      INTEGER,
    accel_x         DECIMAL(8,4),
    accel_y         DECIMAL(8,4),
    accel_z         DECIMAL(8,4),
    temperature     DECIMAL(4,1),
    -- extra wearable fields (ESP32): gyro, battery, panic button, flags
    gyro_x          DECIMAL(8,4),
    gyro_y          DECIMAL(8,4),
    gyro_z          DECIMAL(8,4),
    battery_v       DECIMAL(4,2),
    battery_pct     INTEGER,
    button_pressed  INTEGER,
    temp_valid      INTEGER,
    finger_detected INTEGER,
    steps_total     INTEGER,
    created_at      TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);

-- Initial monthly partitions (previous month → current + 12 ahead).
-- Spring Boot's PartitionManager also creates these at runtime with
-- CREATE TABLE IF NOT EXISTS, but seed.sql runs before the backend
-- starts, so partitions must already exist here.
DO $$
DECLARE
    m DATE;
    p_name TEXT;
    start_ts TEXT;
    end_ts TEXT;
BEGIN
    FOR i IN -1..12 LOOP
        m := date_trunc('month', CURRENT_DATE + make_interval(months => i))::date;
        p_name := 'sensor_readings_' || to_char(m, 'YYYY_MM');
        start_ts := to_char(m, 'YYYY-MM-DD');
        end_ts := to_char(m + interval '1 month', 'YYYY-MM-DD');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF sensor_readings '
            'FOR VALUES FROM (%L) TO (%L)',
            p_name, start_ts, end_ts);
    END LOOP;
END $$;

CREATE TABLE sensor_readings_default PARTITION OF sensor_readings DEFAULT;

CREATE INDEX idx_sensor_user_time ON sensor_readings(user_id, recorded_at DESC);

-- ============================================================
-- Aggregated sensor data
-- ============================================================

CREATE TABLE sensor_readings_5min (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    window_start    TIMESTAMP NOT NULL,
    heart_rate_avg  DECIMAL(5,1),
    heart_rate_min  INTEGER,
    heart_rate_max  INTEGER,
    accel_mag_avg   DECIMAL(8,4),
    accel_mag_std   DECIMAL(8,4),
    motion_ratio    DECIMAL(4,3),
    temperature_avg DECIMAL(4,1),
    reading_count   INTEGER,
    processed_for_activity BOOLEAN DEFAULT FALSE,
    UNIQUE(user_id, window_start)
);
CREATE INDEX idx_sensor5min_user ON sensor_readings_5min(user_id, window_start DESC);

CREATE TABLE sensor_readings_hourly (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    window_start    TIMESTAMP NOT NULL,
    heart_rate_avg  DECIMAL(5,1),
    heart_rate_min  INTEGER,
    heart_rate_max  INTEGER,
    accel_mag_avg   DECIMAL(8,4),
    accel_mag_std   DECIMAL(8,4),
    temperature_avg DECIMAL(4,1),
    reading_count   INTEGER,
    UNIQUE(user_id, window_start)
);
CREATE INDEX idx_sensorhr_user ON sensor_readings_hourly(user_id, window_start DESC);

-- ============================================================
-- Processed health records
-- ============================================================

CREATE TABLE activity_records (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    start_time      TIMESTAMP NOT NULL,
    end_time        TIMESTAMP NOT NULL,
    activity_type   VARCHAR(30),
    steps           INTEGER DEFAULT 0,
    confidence      DECIMAL(4,3),
    avg_accel_mag   DECIMAL(8,4),
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_activity_user_time ON activity_records(user_id, start_time DESC);

CREATE TABLE sleep_records (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sleep_start     TIMESTAMP NOT NULL,
    sleep_end       TIMESTAMP NOT NULL,
    duration_minutes INTEGER NOT NULL,
    -- MVP: deep/light staging + quality are DEFERRED (uncomputable from wrist
    -- accelerometer alone). Kept nullable for future HRV data.
    quality_score   DECIMAL(4,3),
    deep_sleep_min  INTEGER,
    light_sleep_min INTEGER,
    interruptions   INTEGER DEFAULT 0,
    source          VARCHAR(20) DEFAULT 'estimated',
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_sleep_user_time ON sleep_records(user_id, sleep_start DESC);

-- ============================================================
-- Manual sleep logging (battery cannot record overnight)
-- ============================================================

CREATE TABLE sleep_session (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    start_ts        TIMESTAMP NOT NULL,
    end_ts          TIMESTAMP NULL,
    total_sleep_min INTEGER NULL,
    user_rating     INTEGER NULL,
    type            VARCHAR(8) NOT NULL DEFAULT 'NIGHT',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT sleep_session_end_after_start
        CHECK (end_ts IS NULL OR end_ts > start_ts),
    CONSTRAINT sleep_session_closed_totals
        CHECK ((end_ts IS NULL AND total_sleep_min IS NULL)
            OR (end_ts IS NOT NULL AND total_sleep_min IS NOT NULL)),
    CONSTRAINT sleep_session_total_range
        CHECK (total_sleep_min IS NULL OR (total_sleep_min >= 0 AND total_sleep_min <= 960)),
    CONSTRAINT sleep_session_rating_range
        CHECK (user_rating IS NULL OR (user_rating >= 1 AND user_rating <= 5)),
    CONSTRAINT sleep_session_type_check
        CHECK (type IN ('NIGHT', 'NAP'))
);
CREATE INDEX idx_sleep_session_user_start ON sleep_session(user_id, start_ts);
CREATE UNIQUE INDEX uq_sleep_session_one_open ON sleep_session(user_id) WHERE end_ts IS NULL;

CREATE TABLE temperature_records (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    timestamp       TIMESTAMP NOT NULL,
    temperature_c   DECIMAL(4,1) NOT NULL,
    source          VARCHAR(20) DEFAULT 'sensor'
);
CREATE INDEX idx_temp_user_time ON temperature_records(user_id, timestamp DESC);

-- ============================================================
-- Health baselines (with history)
-- ============================================================

CREATE TABLE health_baselines (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    calculated_at   TIMESTAMP DEFAULT NOW(),
    is_current      BOOLEAN DEFAULT TRUE,
    avg_sleep_hours         DECIMAL(4,2),
    avg_daily_steps         INTEGER,
    avg_heart_rate          DECIMAL(5,1),
    avg_resting_heart_rate  DECIMAL(5,1),
    avg_active_minutes      INTEGER,
    avg_sleep_quality       DECIMAL(4,3),
    avg_temperature_c       DECIMAL(4,1),
    -- standard deviations so the wellness/|z|>3 flagging (plan Section 19.4)
    -- is computable from stored baselines without recomputing variance
    std_sleep_hours         DECIMAL(4,2),
    std_daily_steps         INTEGER,
    std_heart_rate          DECIMAL(5,1),
    std_resting_heart_rate  DECIMAL(5,1),
    std_active_minutes      INTEGER,
    std_temperature_c       DECIMAL(4,2),
    typical_bedtime         TIME,
    typical_wake_time       TIME,
    baseline_window_days    INTEGER DEFAULT 14
);
CREATE INDEX idx_baseline_user ON health_baselines(user_id, calculated_at DESC);

-- ============================================================
-- Health assessments (ML outputs)
-- ============================================================

CREATE TABLE health_assessments (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assessment_type VARCHAR(30) NOT NULL,
    assessed_at     TIMESTAMP DEFAULT NOW(),
    assessment_date DATE NOT NULL,
    wellness_score  DECIMAL(4,1),
    stress_level    VARCHAR(20),
    confidence      DECIMAL(4,3),
    factors         JSONB,
    raw_output      JSONB,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_assess_user_date ON health_assessments(user_id, assessment_date DESC);

-- ============================================================
-- Reminders (soft delete)
-- ============================================================

CREATE TABLE reminders (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    reminder_time   TIME NOT NULL,
    recurrence      VARCHAR(30) DEFAULT 'none',
    days_of_week    INTEGER[],
    is_active       BOOLEAN DEFAULT TRUE,
    deleted_at      TIMESTAMP NULL,
    last_triggered  TIMESTAMP,
    last_acknowledged TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_reminders_active ON reminders(user_id, is_active) WHERE deleted_at IS NULL;

-- Monotonic daily step total (never decreases during the day).
CREATE TABLE daily_steps (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    step_date   DATE NOT NULL,
    steps       INTEGER NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (user_id, step_date)
);

-- Incremental host-side step detector over the 15-field accel/gyro stream.
CREATE TABLE step_state (
    user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    step_date           DATE NOT NULL,
    last_processed_at   TIMESTAMP,
    last_signal         DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_peak_at        TIMESTAMP,
    steps               INTEGER NOT NULL DEFAULT 0,
    moving              BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMP DEFAULT NOW()
);

-- ============================================================
-- ai_schema — owned by AI Service
-- ============================================================

CREATE TABLE ai_schema.conversations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    started_at      TIMESTAMP DEFAULT NOW(),
    ended_at        TIMESTAMP,
    is_active       BOOLEAN DEFAULT TRUE
);
CREATE INDEX idx_conv_user ON ai_schema.conversations(user_id, started_at DESC);

CREATE TABLE ai_schema.conversation_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES ai_schema.conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL,
    tool_calls      JSONB,
    tool_call_id    VARCHAR(100),
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_msg_conv ON ai_schema.conversation_messages(conversation_id, created_at);

CREATE TABLE ai_schema.user_memory (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    memory_key      VARCHAR(100) NOT NULL,
    memory_value    JSONB NOT NULL,
    confidence      DECIMAL(4,3) DEFAULT 1.0,
    source          VARCHAR(50),
    last_updated    TIMESTAMP DEFAULT NOW(),
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, memory_key)
);
CREATE INDEX idx_memory_user ON ai_schema.user_memory(user_id);

CREATE TABLE ai_schema.daily_summaries (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    summary_date    DATE NOT NULL,
    version         INTEGER DEFAULT 1,
    sleep_hours     DECIMAL(4,2),
    steps           INTEGER,
    resting_hr      DECIMAL(5,1),
    avg_hr          DECIMAL(5,1),
    max_hr          INTEGER,
    active_minutes  INTEGER,
    temperature_avg DECIMAL(4,1),
    wellness_score  DECIMAL(4,1),
    summary_text    TEXT,
    recommendations TEXT,
    health_context  JSONB,
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, summary_date)
);

CREATE TABLE ai_schema.weekly_summaries (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    week_start      DATE NOT NULL,
    week_end        DATE NOT NULL,
    version         INTEGER DEFAULT 1,
    avg_sleep_hours     DECIMAL(4,2),
    avg_steps           INTEGER,
    avg_resting_hr      DECIMAL(5,1),
    avg_active_minutes  INTEGER,
    sleep_consistency   DECIMAL(4,3),
    exercise_consistency DECIMAL(4,3),
    wellness_trend      VARCHAR(20),
    vs_previous_week    JSONB,
    summary_text        TEXT,
    recommendations     TEXT,
    created_at          TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, week_start)
);

CREATE TABLE ai_schema.token_usage (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             UUID NOT NULL,
    conversation_id     BIGINT REFERENCES ai_schema.conversations(id) ON DELETE SET NULL,
    operation           VARCHAR(50) NOT NULL,
    model               VARCHAR(50) NOT NULL,
    prompt_tokens       INTEGER NOT NULL,
    completion_tokens   INTEGER NOT NULL,
    total_tokens        INTEGER NOT NULL,
    cost_usd            DECIMAL(10,6) NOT NULL,
    created_at          TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_token_user_time ON ai_schema.token_usage(user_id, created_at DESC);
