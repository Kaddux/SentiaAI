package com.health.scheduler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.health.model.HealthBaseline;
import com.health.repository.BaselineRepository;

/**
 * Baseline recalculation, tuned for a short-wear device (~1 h battery, one demo
 * session) instead of an all-day 14-day history.
 *
 * - Lookback is configured in HOURS (`app.analytics.lookback-hours`, default 24)
 *   so a single session can produce a baseline. A session longer than a day can
 *   raise it; the original 14-day daily model is `lookback-hours: 336`.
 * - HR + temperature use per-5-min-bucket values, so 12 buckets in an hour still
 *   give a real standard deviation (hourly alone would yield a single bucket).
 * - Steps / active minutes stay as per-day totals so they compare 1:1 with the
 *   dashboard, but over a short window they become "per session".
 * - `std * (1 + floor)` guards against std == 0, which otherwise makes every
 *   z-score NaN and freezes wellness at its fallback.
 * - Runs on a short cadence (`app.analytics.baseline-interval-ms`) so the demo
 *   sees a baseline within minutes rather than waiting for the 08:00 cron.
 */
@Component
public class BaselineJob {

	private static final Logger log = LoggerFactory.getLogger(BaselineJob.class);

	private final JdbcTemplate jdbc;
	private final BaselineRepository baselineRepo;
	private final int lookbackHours;
	private final int minBuckets;
	private final double stdFloorPct;

	public BaselineJob(JdbcTemplate jdbc, BaselineRepository baselineRepo,
			@Value("${app.analytics.lookback-hours:24}") int lookbackHours,
			@Value("${app.analytics.min-buckets:2}") int minBuckets,
			@Value("${app.analytics.std-floor-pct:0.15}") double stdFloorPct) {
		this.jdbc = jdbc;
		this.baselineRepo = baselineRepo;
		this.lookbackHours = lookbackHours;
		this.minBuckets = minBuckets;
		this.stdFloorPct = stdFloorPct;
	}

	@Scheduled(fixedRateString = "${app.analytics.baseline-interval-ms:300000}")
	public void recomputeBaselines() {
		List<UUID> users = jdbc.queryForList(
				"SELECT DISTINCT user_id FROM sensor_readings_5min ORDER BY user_id", UUID.class);
		if (users.isEmpty()) {
			return;
		}
		Timestamp cutoff = Timestamp.from(Instant.now().minus(lookbackHours, ChronoUnit.HOURS));
		for (UUID user : users) {
			try {
				HealthBaseline b = computeBaseline(user, cutoff);
				if (b == null) {
					continue;
				}
				// Update the current row in place (avoids a new row every 5 min);
				// insert only if the user has no baseline yet.
				// Update the current row in place (avoids a new row every 5 min);
				// insert only if the user has no baseline yet.
				HealthBaseline saved = baselineRepo.findCurrentSingle(user)
						.map(existing -> {
							b.setId(existing.getId());
							return baselineRepo.save(b);
						})
						.orElseGet(() -> baselineRepo.save(b));
				// calculated_at is DB/insert-only on the entity; refresh it explicitly.
				jdbc.update("UPDATE health_baselines SET calculated_at = NOW() WHERE id = ?", saved.getId());
				log.info("baseline_recomputed user={} window_h={}", user, lookbackHours);
			} catch (Exception e) {
				log.error("baseline_recompute_failed user={}", user, e);
			}
		}
	}

	private HealthBaseline computeBaseline(UUID user, Timestamp cutoff) {
		HealthBaseline b = new HealthBaseline();
		b.setUserId(user);
		b.setCurrent(true);
		b.setBaselineWindowDays((int) Math.max(1, Math.ceil(lookbackHours / 24.0)));

		// Resting HR — average of each 5-min bucket's minimum HR.
		double[] hr = stat("""
				SELECT AVG(heart_rate_min)::float AS avg_v, STDDEV(heart_rate_min)::float AS std_v, COUNT(*)::int AS n
				FROM sensor_readings_5min WHERE user_id = ? AND window_start >= ?""", user, cutoff);
		if (hr[2] >= minBuckets) {
			b.setAvgRestingHeartRate(round(hr[0], 1));
			b.setStdRestingHeartRate(round(Math.max(hr[1], 2.0), 1));
		}

		// Average HR — average of each 5-min bucket's average HR.
		double[] avgHr = stat("""
				SELECT AVG(NULLIF(heart_rate_avg, 0))::float AS avg_v,
				       STDDEV(NULLIF(heart_rate_avg, 0))::float AS std_v, COUNT(*)::int AS n
				FROM sensor_readings_5min WHERE user_id = ? AND window_start >= ?""", user, cutoff);
		if (avgHr[2] >= minBuckets) {
			b.setAvgHeartRate(round(avgHr[0], 1));
			b.setStdHeartRate(round(Math.max(avgHr[1], 3.0), 1));
		}

		// Temperature — average of each 5-min bucket.
		double[] temp = stat("""
				SELECT AVG(temperature_avg)::float AS avg_v, STDDEV(temperature_avg)::float AS std_v, COUNT(*)::int AS n
				FROM sensor_readings_5min WHERE user_id = ? AND window_start >= ?""", user, cutoff);
		if (temp[2] >= minBuckets) {
			b.setAvgTemperatureC(round(temp[0], 1));
			b.setStdTemperatureC(round(Math.max(temp[1], 0.2), 2));
		}

		// Steps — per-day totals (a short window makes this "per session").
		double[] steps = stat("""
				SELECT AVG(step_sum)::float AS avg_v, STDDEV(step_sum)::float AS std_v, COUNT(*)::int AS n
				FROM (SELECT date_trunc('day', start_time) AS day, SUM(steps) AS step_sum
				      FROM activity_records WHERE user_id = ? AND start_time >= ?
				      GROUP BY 1) t""", user, cutoff);
		if (steps[2] >= 1) {
			b.setAvgDailySteps((int) Math.round(steps[0]));
			b.setStdDailySteps((int) Math.max(steps[1], Math.max(10.0, Math.abs(steps[0]) * stdFloorPct)));
		}

		// Active minutes — per-day totals.
		double[] active = stat("""
				SELECT AVG(act_sum)::float AS avg_v, STDDEV(act_sum)::float AS std_v, COUNT(*)::int AS n
				FROM (SELECT date_trunc('day', start_time) AS day,
				             SUM(EXTRACT(EPOCH FROM (end_time - start_time)) / 60) AS act_sum
				      FROM activity_records WHERE user_id = ? AND start_time >= ?
				      GROUP BY 1) t""", user, cutoff);
		if (active[2] >= 1) {
			b.setAvgActiveMinutes((int) Math.round(active[0]));
			b.setStdActiveMinutes((int) Math.max(active[1], Math.max(5.0, Math.abs(active[0]) * stdFloorPct)));
		}

		// Sleep — typically absent in a one-hour demo session; leave null if so.
		double[] sleep = stat("""
				SELECT AVG(duration_minutes)::float AS avg_v, STDDEV(duration_minutes)::float AS std_v, COUNT(*)::int AS n
				FROM sleep_records WHERE user_id = ? AND sleep_start >= ?""", user, cutoff);
		if (sleep[2] >= 1) {
			b.setAvgSleepHours(round(sleep[0] / 60.0, 2));
			b.setStdSleepHours(round(Math.max(sleep[1] / 60.0, 0.1), 2));
		}

		b.setTypicalBedtime(modeTime("""
				SELECT to_char(sleep_start, 'HH24:MI') AS t FROM sleep_records
				WHERE user_id = ? AND sleep_start >= ?""", user, cutoff));
		b.setTypicalWakeTime(modeTime("""
				SELECT to_char(sleep_end, 'HH24:MI') AS t FROM sleep_records
				WHERE user_id = ? AND sleep_end >= ?""", user, cutoff));

		boolean any = b.getAvgSleepHours() != null || b.getAvgDailySteps() != null
				|| b.getAvgRestingHeartRate() != null || b.getAvgActiveMinutes() != null
				|| b.getAvgTemperatureC() != null || b.getAvgHeartRate() != null;
		return any ? b : null;
	}

	/** Returns {avg, std, n}; NaN avg/std when no rows. */
	private double[] stat(String sql, UUID user, Timestamp cutoff) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, user, cutoff);
		if (rows.isEmpty() || rows.get(0).get("avg_v") == null) {
			return new double[]{Double.NaN, Double.NaN, 0};
		}
		Map<String, Object> r = rows.get(0);
		double avg = ((Number) r.get("avg_v")).doubleValue();
		Object stdObj = r.get("std_v");
		double std = stdObj == null ? 0.0 : ((Number) stdObj).doubleValue();
		double n = r.get("n") == null ? 0 : ((Number) r.get("n")).doubleValue();
		return new double[]{avg, std, n};
	}

	/** Most common clock time (HH:MM) in the lookback window. */
	private java.time.LocalTime modeTime(String sql, UUID user, Timestamp cutoff) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, user, cutoff);
		if (rows.isEmpty()) {
			return null;
		}
		Map<String, Long> counts = new java.util.LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			counts.merge(String.valueOf(r.get("t")), 1L, Long::sum);
		}
		String best = counts.entrySet().stream()
				.max(Map.Entry.comparingByValue())
				.map(Map.Entry::getKey)
				.orElse(null);
		return best == null ? null : java.time.LocalTime.parse(best);
	}

	private static BigDecimal round(double v, int scale) {
		return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
	}
}
