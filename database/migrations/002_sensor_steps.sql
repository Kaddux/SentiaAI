-- Device-side daily step counter (16th UDP CSV field).
-- The firmware sends cumulative steps for the local day; the bridge forwards
-- it as stepsTotal and the backend keeps the daily maximum as a monotonic floor.
ALTER TABLE sensor_readings ADD COLUMN IF NOT EXISTS steps_total INTEGER;
