-- 001_sleep_session.sql
-- Manual sleep logging (wearable battery cannot record overnight).
-- Idempotent: safe to run against an existing database.
-- Fresh installs get the same DDL from database/init.sql.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';

CREATE TABLE IF NOT EXISTS sleep_session (
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

CREATE INDEX IF NOT EXISTS idx_sleep_session_user_start
    ON sleep_session(user_id, start_ts);

-- At most one open session per user.
CREATE UNIQUE INDEX IF NOT EXISTS uq_sleep_session_one_open
    ON sleep_session(user_id)
    WHERE end_ts IS NULL;
