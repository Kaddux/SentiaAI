-- ============================================================
-- Dev seed data (runs after init.sql)
-- Mounted as 02-seed.sql so it runs after 01-init.sql.
-- ============================================================

-- 1. Test user (password: "password123" — BCrypt hash below)
INSERT INTO users (id, email, password_hash, first_name, last_name, height_cm, weight_kg)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    'dev@sentia.ai',
    '$2a$10$RGNuS1NgiOuoHwD3b90kE.CDwRwkWo68I8FLRpHfLhXn.UnbSn3Gu',  -- 'password123' (verified with Spring BCrypt)
    'Dev', 'User', 175.0, 70.0
);

-- 2. Sensor readings: 7 days, ~100 samples/day (10-min cadence, with 1-min jitter)
INSERT INTO sensor_readings (user_id, recorded_at, heart_rate, accel_x, accel_y, accel_z, temperature)
SELECT
    '11111111-1111-1111-1111-111111111111',
    ts,
    CASE
      WHEN EXTRACT(HOUR FROM ts) IN (22,23,0,1,2,3,4,5)  THEN 54 + (random()*10)::int
      WHEN EXTRACT(HOUR FROM ts) IN (7,17,18)             THEN 95 + (random()*35)::int
      ELSE 62 + (random()*20)::int
    END,
    CASE WHEN EXTRACT(HOUR FROM ts) IN (22,23,0,1,2,3,4,5)
      THEN 0.01 + random()*0.02 ELSE 0.05 + random()*0.4 END,
    CASE WHEN EXTRACT(HOUR FROM ts) IN (22,23,0,1,2,3,4,5)
      THEN -0.99 + random()*0.02 ELSE -0.98 + random()*0.2 END,
    CASE WHEN EXTRACT(HOUR FROM ts) IN (22,23,0,1,2,3,4,5)
      THEN 0.01 + random()*0.02 ELSE 0.02 + random()*0.2 END,
    36.1 + (random() * 0.6)
FROM generate_series(
    NOW() - INTERVAL '7 days',
    NOW(),
    INTERVAL '10 minutes'
) AS ts;

-- 3. 5-min aggregates: pre-computed from the last 6 hours for quick ML testing
INSERT INTO sensor_readings_5min
    (user_id, window_start, heart_rate_avg, heart_rate_min, heart_rate_max,
     accel_mag_avg, accel_mag_std, temperature_avg, reading_count, processed_for_activity)
SELECT
    '11111111-1111-1111-1111-111111111111',
    w.ts,
    AVG(heart_rate), MIN(heart_rate), MAX(heart_rate),
    AVG(SQRT(accel_x*accel_x+accel_y*accel_y+accel_z*accel_z)),
    STDDEV(SQRT(accel_x*accel_x+accel_y*accel_y+accel_z*accel_z)),
    AVG(temperature), COUNT(*), TRUE
FROM generate_series(
    date_trunc('hour', NOW()) - INTERVAL '6 hours',
    date_trunc('hour', NOW()),
    INTERVAL '5 minutes'
) AS w(ts)
CROSS JOIN LATERAL (
    SELECT * FROM sensor_readings sr
    WHERE sr.user_id = '11111111-1111-1111-1111-111111111111'
      AND sr.recorded_at >= w.ts AND sr.recorded_at < w.ts + INTERVAL '5 minutes'
) s
GROUP BY w.ts;

-- 4. Activity records (a few windows for today)
INSERT INTO activity_records (user_id, start_time, end_time, activity_type, steps, confidence, avg_accel_mag)
VALUES
    ('11111111-1111-1111-1111-111111111111', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours', 'sitting', 0, 0.92, 0.06),
    ('11111111-1111-1111-1111-111111111111', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '50 minutes', 'walking', 850, 0.85, 0.30);

-- 5. Sleep record for last night (MVP: no deep/light/quality staging)
INSERT INTO sleep_records (user_id, sleep_start, sleep_end, duration_minutes, interruptions, source)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    (NOW() - INTERVAL '8 hours')::date + TIME '23:15',
    NOW()::date + TIME '05:33',
    378, 3, 'estimated'
);

-- 6. Current baseline (incl. standard deviations for |z|>3 flagging)
INSERT INTO health_baselines (user_id, is_current, avg_sleep_hours, avg_daily_steps,
                              avg_resting_heart_rate, avg_active_minutes, avg_sleep_quality,
                              std_sleep_hours, std_daily_steps, std_resting_heart_rate,
                              std_active_minutes, std_temperature_c,
                              typical_bedtime, typical_wake_time, baseline_window_days)
VALUES (
    '11111111-1111-1111-1111-111111111111', TRUE,
    7.4, 8500, 64, 70, 0.74,
    0.9, 1400, 5, 22, 0.4,
    TIME '23:15', TIME '06:45', 14
);

-- 7. Reminders
INSERT INTO reminders (user_id, title, reminder_time, recurrence, is_active)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'Morning water', TIME '09:00', 'daily', TRUE),
    ('11111111-1111-1111-1111-111111111111', 'Evening workout', TIME '19:00', 'daily', TRUE),
    ('11111111-1111-1111-1111-111111111111', 'Stand up', TIME '15:30', 'weekly', TRUE);

-- 8. AI schema: one active conversation with 5 messages
INSERT INTO ai_schema.conversations (id, user_id, is_active)
VALUES (1, '11111111-1111-1111-1111-111111111111', TRUE);

-- Bump the BIGSERIAL sequence to the last explicit id so new conversations
-- created by the AI service don't collide with the seeded row.
SELECT setval('ai_schema.conversations_id_seq',
              (SELECT COALESCE(MAX(id), 1) FROM ai_schema.conversations));

INSERT INTO ai_schema.conversation_messages (conversation_id, role, content)
VALUES
    (1, 'user',      'I have been tired lately.'),
    (1, 'assistant', 'How has your sleep been?'),
    (1, 'user',      'Not very good.'),
    (1, 'assistant', 'Your sleep has been below your usual average recently.'),
    (1, 'user',      'How was my sleep last night?');

-- 9. Long-term memory entries
INSERT INTO ai_schema.user_memory (user_id, memory_key, memory_value, confidence, source)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'typical_sleep_duration', '{"value": 7.4}', 0.9, 'derived'),
    ('11111111-1111-1111-1111-111111111111', 'health_goals',            '{"value": ["improve sleep consistency"]}', 1.0, 'stated_by_user');
