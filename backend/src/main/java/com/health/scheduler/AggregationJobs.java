package com.health.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled aggregation (plan Section 12.4 — corrected version).
 * For each user we compute a per-user watermark CTE, aggregate only CLOSED
 * windows, and use ON CONFLICT DO UPDATE so revisits never leave stale or
 * partial results. The current (open) window is excluded so a run at 10:07
 * can't freeze the partial 10:05 window in place.
 */
@Component
public class AggregationJobs {

	private static final String FIVE_MIN = """
			WITH wm AS (
			    SELECT user_id,
			           COALESCE(MAX(window_start), '1970-01-01'::timestamp) - INTERVAL '5 minutes' AS last_ws
			    FROM sensor_readings_5min
			    GROUP BY user_id
			)
			INSERT INTO sensor_readings_5min
			    (user_id, window_start, heart_rate_avg, heart_rate_min, heart_rate_max,
			     accel_mag_avg, accel_mag_std, motion_ratio, temperature_avg, reading_count, processed_for_activity)
			SELECT sr.user_id,
			    date_trunc('hour', sr.recorded_at) + INTERVAL '5 min' *
			        FLOOR(EXTRACT(MINUTE FROM sr.recorded_at) / 5) AS window_start,
			    AVG(CASE WHEN sr.heart_rate BETWEEN 30 AND 220 THEN sr.heart_rate END),
			    MIN(CASE WHEN sr.heart_rate BETWEEN 30 AND 220 THEN sr.heart_rate END),
			    MAX(CASE WHEN sr.heart_rate BETWEEN 30 AND 220 THEN sr.heart_rate END),
			    AVG(SQRT(sr.accel_x*sr.accel_x+sr.accel_y*sr.accel_y+sr.accel_z*sr.accel_z)),
			    STDDEV(SQRT(sr.accel_x*sr.accel_x+sr.accel_y*sr.accel_y+sr.accel_z*sr.accel_z)),
			    AVG(CASE WHEN ABS(SQRT(sr.accel_x*sr.accel_x+sr.accel_y*sr.accel_y+sr.accel_z*sr.accel_z) - 1.0) > 0.12 THEN 1.0 ELSE 0.0 END),
			    AVG(sr.temperature), COUNT(*), FALSE
			FROM sensor_readings sr
			LEFT JOIN wm ON wm.user_id = sr.user_id
			WHERE sr.recorded_at > COALESCE(wm.last_ws, '1970-01-01'::timestamp)
			  AND date_trunc('hour', sr.recorded_at) + INTERVAL '5 min' *
			        FLOOR(EXTRACT(MINUTE FROM sr.recorded_at) / 5)
			      < date_trunc('hour', NOW()) + INTERVAL '5 min' *
			        FLOOR(EXTRACT(MINUTE FROM NOW()) / 5)
			GROUP BY sr.user_id, window_start
			ON CONFLICT (user_id, window_start) DO UPDATE SET
			    heart_rate_avg  = EXCLUDED.heart_rate_avg,
			    heart_rate_min  = EXCLUDED.heart_rate_min,
			    heart_rate_max  = EXCLUDED.heart_rate_max,
			    accel_mag_avg   = EXCLUDED.accel_mag_avg,
			    accel_mag_std   = EXCLUDED.accel_mag_std,
			    motion_ratio    = EXCLUDED.motion_ratio,
			    temperature_avg = EXCLUDED.temperature_avg,
			    reading_count   = EXCLUDED.reading_count
			""";

	private static final String HOURLY = """
			WITH wm AS (
			    SELECT user_id,
			           COALESCE(MAX(window_start), '1970-01-01'::timestamp) - INTERVAL '1 hour' AS last_ws
			    FROM sensor_readings_hourly
			    GROUP BY user_id
			)
			INSERT INTO sensor_readings_hourly
			    (user_id, window_start, heart_rate_avg, heart_rate_min, heart_rate_max,
			     accel_mag_avg, accel_mag_std, temperature_avg, reading_count)
			SELECT s.user_id,
			    date_trunc('hour', s.window_start) AS window_start,
			    AVG(CASE WHEN s.heart_rate_avg BETWEEN 30 AND 220 THEN s.heart_rate_avg END),
			    MIN(CASE WHEN s.heart_rate_min >= 30 THEN s.heart_rate_min END),
			    MAX(CASE WHEN s.heart_rate_max <= 220 THEN s.heart_rate_max END),
			    AVG(s.accel_mag_avg), AVG(s.accel_mag_std), AVG(s.temperature_avg), SUM(s.reading_count)
			FROM sensor_readings_5min s
			LEFT JOIN wm ON wm.user_id = s.user_id
			WHERE s.window_start > COALESCE(wm.last_ws, '1970-01-01'::timestamp)
			GROUP BY s.user_id, date_trunc('hour', s.window_start)
			ON CONFLICT (user_id, window_start) DO UPDATE SET
			    heart_rate_avg  = EXCLUDED.heart_rate_avg,
			    heart_rate_min  = EXCLUDED.heart_rate_min,
			    heart_rate_max  = EXCLUDED.heart_rate_max,
			    accel_mag_avg   = EXCLUDED.accel_mag_avg,
			    accel_mag_std   = EXCLUDED.accel_mag_std,
			    temperature_avg = EXCLUDED.temperature_avg,
			    reading_count   = EXCLUDED.reading_count
			""";

	private final JdbcTemplate jdbcTemplate;

	public AggregationJobs(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Scheduled(fixedRateString = "${app.analytics.aggregate-interval-ms:300000}")
	public void aggregate5MinWindows() {
		jdbcTemplate.update(FIVE_MIN);
	}

	@Scheduled(fixedRateString = "${app.analytics.aggregate-interval-ms:300000}")
	public void aggregateHourlyWindows() {
		jdbcTemplate.update(HOURLY);
	}
}
