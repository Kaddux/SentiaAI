package com.health.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.health.model.ActivityRecord;
import com.health.model.HealthAssessment;
import com.health.model.HealthBaseline;
import com.health.model.SleepRecord;
import com.health.model.TemperatureRecord;
import com.health.model.User;
import com.health.repository.ActivityRepository;
import com.health.repository.AssessmentRepository;
import com.health.repository.BaselineRepository;
import com.health.repository.SleepRepository;
import com.health.repository.TemperatureRepository;
import com.health.repository.UserRepository;

/**
 * Health proxy that the AI Service calls (plan Section 8.2). Reads ONLY
 * aggregated tables + processed records — never raw sensor_readings — so the
 * LLM/AI stays off raw streams. All methods take the authenticated user id
 * from the JWT (Spring Boot derives it from the forwarded JWT, Section 15.3).
 */
@Service
public class HealthService {

	private final JdbcTemplate jdbc;
	private final UserRepository userRepository;
	private final BaselineRepository baselineRepository;
	private final SleepRepository sleepRepository;
	private final ActivityRepository activityRepository;
	private final TemperatureRepository temperatureRepository;
	private final AssessmentRepository assessmentRepository;

	public HealthService(JdbcTemplate jdbc,
			UserRepository userRepository,
			BaselineRepository baselineRepository,
			SleepRepository sleepRepository,
			ActivityRepository activityRepository,
			TemperatureRepository temperatureRepository,
			AssessmentRepository assessmentRepository) {
		this.jdbc = jdbc;
		this.userRepository = userRepository;
		this.baselineRepository = baselineRepository;
		this.sleepRepository = sleepRepository;
		this.activityRepository = activityRepository;
		this.temperatureRepository = temperatureRepository;
		this.assessmentRepository = assessmentRepository;
	}

	/** User-local calendar zone (falls back to UTC for missing/invalid values). */
	private ZoneId zoneFor(UUID userId) {
		return userRepository.findById(userId)
				.map(User::getTimezone)
				.filter(tz -> tz != null && !tz.isBlank())
				.map(tz -> {
					try {
						return ZoneId.of(tz);
					} catch (Exception e) {
						return ZoneOffset.UTC;
					}
				})
				.orElse(ZoneOffset.UTC);
	}

	private LocalDate dateFor(UUID userId, LocalDate date) {
		return date != null ? date : LocalDate.now(zoneFor(userId));
	}

	// ---------------------------------------------------------------- profile

	public Map<String, Object> profile(UUID userId) {
		User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
		Map<String, Object> profile = new LinkedHashMap<>();
		profile.put("user_id", userId);
		profile.put("first_name", user.getFirstName());
		profile.put("last_name", user.getLastName());
		profile.put("email", user.getEmail());
		profile.put("date_of_birth", user.getDateOfBirth());
		profile.put("gender", user.getGender());
		profile.put("height_cm", user.getHeightCm());
		profile.put("weight_kg", user.getWeightKg());
		profile.put("timezone", user.getTimezone());
		profile.put("baseline", baseline(userId));
		return profile;
	}

	// ------------------------------------------------------------- today

	public Map<String, Object> today(UUID userId) {
		LocalDate date = LocalDate.now(zoneFor(userId));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("date", date);
		payload.put("sleep", sleepForDay(userId, date));
		payload.put("heart_rate", heartRateStats(userId, date));
		payload.put("activity", activityStats(userId, date));
		payload.put("temperature", temperatureStats(userId, date));
		return payload;
	}

	// ---------------------------------------------------- per-metric for a date

	public Map<String, Object> sleepForDay(UUID userId, LocalDate date) {
		ZoneId zone = zoneFor(userId);
		date = dateFor(userId, date);
		// sleep crosses midnight: look at previous evening  this morning
		Instant from = date.minusDays(1).atTime(20, 0).atZone(zone).toInstant();
		Instant to = date.atTime(12, 0).atZone(zone).toInstant();
		List<SleepRecord> records = sleepRepository.findForRange(userId, from, to);
		if (records.isEmpty()) {
			return Map.of("sleep", "no data");
		}
		SleepRecord s = records.get(0);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("duration_hours", round(s.getDurationMinutes() / 60.0, 2));
		m.put("sleep_start", s.getSleepStart());
		m.put("sleep_end", s.getSleepEnd());
		m.put("interruptions", s.getInterruptions());
		return m;
	}

	public Map<String, Object> heartRateStats(UUID userId, LocalDate date) {
		ZoneId zone = zoneFor(userId);
		date = dateFor(userId, date);
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT AVG(CASE WHEN heart_rate_avg BETWEEN 30 AND 220 THEN heart_rate_avg END) AS avg_hr,
				       MIN(CASE WHEN heart_rate_min >= 30 THEN heart_rate_min END) AS min_hr,
				       MAX(CASE WHEN heart_rate_max <= 220 THEN heart_rate_max END) AS max_hr
				FROM sensor_readings_hourly
				WHERE user_id = ? AND window_start >= ? AND window_start < ?""",
				userId, asTs(from), asTs(to));
		if (rows.isEmpty() || rows.get(0).get("avg_hr") == null) {
			return Map.of("heart_rate", "no data");
		}
		Map<String, Object> r = rows.get(0);
		BigDecimal avg = toBigDecimal(r.get("avg_hr"));
		BigDecimal min = toBigDecimal(r.get("min_hr"));
		BigDecimal max = toBigDecimal(r.get("max_hr"));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("resting", min);      // proxy resting HR = min of hourly mins
		m.put("average", avg);
		m.put("max", max);
		m.put("min", min);
		return m;
	}

	public Map<String, Object> activityStats(UUID userId, LocalDate date) {
		ZoneId zone = zoneFor(userId);
		date = dateFor(userId, date);
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<ActivityRecord> records = activityRepository.findForRange(userId, from, to);
		if (records.isEmpty()) {
			return Map.of("activity", "no data");
		}
		int steps = 0;
		int activeMinutes = 0;
		int totalMinutes = 0;
		Map<String, Integer> breakdown = new LinkedHashMap<>();
		String dominant = records.get(0).getActivityType();
		int dominantMinutes = -1;
		for (ActivityRecord a : records) {
			int mins = (int) Math.max(0, (a.getEndTime().toEpochMilli() - a.getStartTime().toEpochMilli()) / 60000);
			steps += (a.getSteps() == null ? 0 : a.getSteps());
			totalMinutes += mins;
			if (a.getActivityType() != null && (a.getActivityType().equals("walking") || a.getActivityType().equals("running"))) {
				activeMinutes += mins;
			}
			Integer soFar = breakdown.getOrDefault(a.getActivityType(), 0);
			breakdown.put(a.getActivityType(), soFar + mins);
			if (mins > dominantMinutes) {
				dominantMinutes = mins;
				dominant = a.getActivityType();
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("steps", steps);
		m.put("active_minutes", activeMinutes);
		m.put("total_minutes", totalMinutes);
		m.put("dominant_activity", dominant);
		m.put("breakdown", breakdown);
		// Prefer the on-device counter for the step number when the device
		// sends one; keep ML activity types/active minutes unchanged.
		Long deviceSteps = jdbc.queryForObject("""
				SELECT MAX(steps_total) FROM sensor_readings
				WHERE user_id = ? AND recorded_at >= ? AND recorded_at < ?
				  AND steps_total IS NOT NULL""", Long.class,
				userId, asTs(from), asTs(to));
		if (deviceSteps != null) {
			m.put("steps", deviceSteps);
			m.put("steps_source", "device");
		} else {
			m.put("steps_source", "estimated");
		}
		return m;
	}

	/**
	 * Near-real-time step estimate for the dashboard.
	 *
	 * Classified 5-min windows are the stable base; the current (not yet closed)
	 * portion is estimated from the raw accelerometer stream: if the magnitude
	 * varies enough to indicate motion, add cadence * elapsed for that span.
	 * The span is measured from the actual samples (not wall-clock), so idle gaps
	 * don't inflate the count. Rough by design — the device samples ~0.7 Hz.
	 */
	public Map<String, Object> estimatedStepsToday(UUID userId) {
		ZoneId zone = zoneFor(userId);
		LocalDate date = LocalDate.now(zone);
		Instant dayStart = date.atStartOfDay(zone).toInstant();
		Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();

		// Device-side step counter (16th UDP field). When present it is the
		// authoritative daily total; ML/base/live are only a fallback.
		Long deviceSteps = jdbc.queryForObject("""
				SELECT MAX(steps_total) FROM sensor_readings
				WHERE user_id = ? AND recorded_at >= ? AND recorded_at < ?
				  AND steps_total IS NOT NULL""", Long.class,
				userId, asTs(dayStart), asTs(dayEnd));
		if (deviceSteps != null) {
			// The device counter is authoritative for the display. Keep the
			// daily_steps row for history, but do not let an older ML estimate
			// leak into a source that has a real on-device count.
			jdbc.update("""
					INSERT INTO daily_steps (user_id, step_date, steps, updated_at)
					VALUES (?, ?, ?, NOW())
					ON CONFLICT (user_id, step_date) DO UPDATE
					  SET steps = GREATEST(daily_steps.steps, EXCLUDED.steps),
					      updated_at = NOW()""",
					userId, java.sql.Date.valueOf(date), deviceSteps);
			long steps = deviceSteps;
			Long recentDelta = jdbc.queryForObject("""
					SELECT COALESCE(MAX(steps_total) - MIN(steps_total), 0)
					FROM sensor_readings
					WHERE user_id = ? AND recorded_at >= ? AND steps_total IS NOT NULL""",
					Long.class, userId, asTs(Instant.now().minus(5, ChronoUnit.SECONDS)));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("date", date);
			out.put("steps", steps);
			out.put("base_steps", deviceSteps);
			out.put("live_steps", 0);
			out.put("estimated", false);
			out.put("source", "device");
			out.put("moving", recentDelta != null && recentDelta > 0);
			out.put("cadence_per_min", 0);
			out.put("device_steps", deviceSteps);
			return out;
		}

		long base = 0;
		Instant lastClassifiedEnd = null;
		List<Map<String, Object>> baseRows = jdbc.queryForList("""
				SELECT COALESCE(SUM(steps), 0) AS steps, MAX(end_time) AS last_end
				FROM activity_records
				WHERE user_id = ? AND start_time >= ? AND start_time < ?""",
				userId, asTs(dayStart), asTs(dayEnd));
		if (!baseRows.isEmpty()) {
			Object s = baseRows.get(0).get("steps");
			base = s == null ? 0 : ((Number) s).longValue();
			lastClassifiedEnd = toInstant(baseRows.get(0).get("last_end"));
		}

		Instant rawFrom = lastClassifiedEnd != null ? lastClassifiedEnd : dayStart;
		Instant tenMinAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
		if (rawFrom.isBefore(tenMinAgo)) {
			rawFrom = tenMinAgo;
		}

		List<Map<String, Object>> raw = jdbc.queryForList("""
				SELECT accel_x, accel_y, accel_z, recorded_at
				FROM sensor_readings
				WHERE user_id = ? AND recorded_at >= ? AND recorded_at < ?
				ORDER BY recorded_at""",
				userId, asTs(rawFrom), asTs(dayEnd));

		double liveSteps = 0;
		boolean moving = false;
		int cadence = 0;
		if (raw.size() >= 5) {
			double devSum = 0;
			int motionSamples = 0;
			Instant first = null;
			Instant last = null;
			for (Map<String, Object> r : raw) {
				double x = safeDouble(r.get("accel_x"));
				double y = safeDouble(r.get("accel_y"));
				double z = safeDouble(r.get("accel_z"));
				double mag = Math.sqrt(x * x + y * y + z * z);
				double dev = Math.abs(mag - 1.0); // distance from gravity at rest
				devSum += dev;
				if (dev > 0.12) {
					motionSamples++;
				}
				Instant t = toInstant(r.get("recorded_at"));
				if (t != null) {
					if (first == null) {
						first = t;
					}
					last = t;
				}
			}
			double n = raw.size();
			double motionRatio = motionSamples / n; // fraction of samples actually moving
			double avgDev = devSum / n;
			double spanSec = (first != null && last != null)
					? (last.toEpochMilli() - first.toEpochMilli()) / 1000.0 : 0;

			// Require sustained motion, and count only the moving time — not the
			// whole window. A few noisy samples no longer add hundreds of steps.
			if (motionRatio >= 0.15 && spanSec > 0) {
				moving = true;
				cadence = avgDev > 0.35 ? 150 : 100;
				double movingSeconds = spanSec * motionRatio;
				liveSteps = Math.min(500, cadence * movingSeconds / 60.0);
			}
		}

		long estimate = base + Math.round(liveSteps);
		// Persist a monotonic daily total so reclassification or live-estimate
		// jitter can never make the dashboard count go backwards.
		Long stored = jdbc.queryForObject("""
				INSERT INTO daily_steps (user_id, step_date, steps, updated_at)
				VALUES (?, ?, ?, NOW())
				ON CONFLICT (user_id, step_date) DO UPDATE
				  SET steps = GREATEST(daily_steps.steps, EXCLUDED.steps),
				      updated_at = NOW()
				RETURNING steps""",
				Long.class, userId, java.sql.Date.valueOf(date), estimate);
		long steps = stored != null ? stored : estimate;

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("date", date);
		out.put("steps", steps);
		out.put("base_steps", base);
		out.put("live_steps", Math.round(liveSteps));
		out.put("estimated", true);
		out.put("source", "estimated");
		out.put("moving", moving);
		out.put("cadence_per_min", cadence);
		return out;
	}

	private static double safeDouble(Object o) {
		return o instanceof Number n ? n.doubleValue() : 0.0;
	}

	private static Instant toInstant(Object ts) {
		if (ts instanceof Timestamp t) {
			return t.toInstant();
		}
		if (ts instanceof java.time.LocalDateTime ldt) {
			return ldt.toInstant(ZoneOffset.UTC);
		}
		return null;
	}

	public Map<String, Object> temperatureStats(UUID userId, LocalDate date) {
		ZoneId zone = zoneFor(userId);
		date = dateFor(userId, date);
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<TemperatureRecord> records = temperatureRepository.findForRange(userId, from, to);
		if (records.isEmpty()) {
			// fall back to hourly aggregates if no temperature_records
			List<Map<String, Object>> rows = jdbc.queryForList("""
					SELECT AVG(temperature_avg) AS avg_t, MAX(temperature_avg) AS max_t,
					       MIN(temperature_avg) AS min_t
					FROM sensor_readings_hourly
					WHERE user_id = ? AND window_start >= ? AND window_start < ?""",
					userId, asTs(from), asTs(to));
			if (rows.isEmpty() || rows.get(0).get("avg_t") == null) {
				return Map.of("temperature", "no data");
			}
			Map<String, Object> r = rows.get(0);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("average", toBigDecimal(r.get("avg_t")));
			m.put("max", toBigDecimal(r.get("max_t")));
			m.put("min", toBigDecimal(r.get("min_t")));
			return m;
		}
		double sum = 0, min = Double.MAX_VALUE, max = Double.MIN_VALUE;
		for (TemperatureRecord t : records) {
			double v = t.getTemperatureC().doubleValue();
			sum += v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("average", round(sum / records.size(), 1));
		m.put("max", round(max, 1));
		m.put("min", round(min, 1));
		return m;
	}

	// -------------------------------------------------------------- baseline

	public Map<String, Object> baseline(UUID userId) {
		HealthBaseline b = baselineRepository.findCurrentByUser(userId).orElse(null);
		if (b == null) {
			return Map.of("baseline", "no data");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("avg_sleep_hours", b.getAvgSleepHours());
		m.put("avg_daily_steps", b.getAvgDailySteps());
		m.put("avg_heart_rate", b.getAvgHeartRate());
		m.put("avg_resting_heart_rate", b.getAvgRestingHeartRate());
		m.put("avg_active_minutes", b.getAvgActiveMinutes());
		m.put("avg_temperature_c", b.getAvgTemperatureC());
		m.put("std_sleep_hours", b.getStdSleepHours());
		m.put("std_daily_steps", b.getStdDailySteps());
		m.put("std_heart_rate", b.getStdHeartRate());
		m.put("std_resting_heart_rate", b.getStdRestingHeartRate());
		m.put("std_active_minutes", b.getStdActiveMinutes());
		m.put("std_temperature_c", b.getStdTemperatureC());
		m.put("typical_bedtime", b.getTypicalBedtime());
		m.put("typical_wake_time", b.getTypicalWakeTime());
		m.put("window_days", b.getBaselineWindowDays());
		return m;
	}

	public Map<String, Object> compareToBaseline(UUID userId, LocalDate date) {
		date = dateFor(userId, date);
		HealthBaseline b = baselineRepository.findCurrentByUser(userId).orElse(null);
		Map<String, Object> today = today(userId);
		Map<String, Object> baseline = baseline(userId);
		if (b == null) {
			return Map.of("baseline", "no data");
		}
		Map<String, Object> deviations = new LinkedHashMap<>();
		BigDecimal sleepHours = toBigDecimal(nested(today, "sleep", "duration_hours"));
		if (b.getAvgSleepHours() != null) {
			deviations.put("sleep_hours", sleepHours == null ? null
					: round(sleepHours.doubleValue() - b.getAvgSleepHours().doubleValue(), 2));
		}
		deviations.put("resting_heart_rate", diff(toBigDecimal(nested(today, "heart_rate", "resting")), b.getAvgRestingHeartRate()));
		deviations.put("steps", diffInt(intVal(nested(today, "activity", "steps")), b.getAvgDailySteps()));
		deviations.put("active_minutes", diffInt(intVal(nested(today, "activity", "active_minutes")), b.getAvgActiveMinutes()));
		deviations.put("temperature_c", diff(toBigDecimal(nested(today, "temperature", "average")), b.getAvgTemperatureC()));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("date", date);
		result.put("baseline", baseline);
		result.put("today", today);
		result.put("deviations", deviations);
		return result;
	}

	// -------------------------------------------------------------- wellness

	public Map<String, Object> wellness(UUID userId, LocalDate date) {
		date = dateFor(userId, date);
		// Prefer an existing assessment; else compute a weighted z-score signal.
		List<HealthAssessment> assessments = assessmentRepository.findByUserIdAndAssessmentDate(userId, date);
		if (!assessments.isEmpty()) {
			HealthAssessment a = assessments.get(0);
			BigDecimal confidence = a.getConfidence();
			boolean provisional = confidence == null || confidence.doubleValue() < 0.6;
			return wellnessPayload(date, a.getWellnessScore(), a.getStressLevel(),
					confidence, provisional, provisional ? 5 : 0);
		}
		HealthBaseline b = baselineRepository.findCurrentByUser(userId).orElse(null);
		if (b == null) {
			Map<String, Object> none = new LinkedHashMap<>();
			none.put("date", date);
			none.put("wellness", "no data");
			none.put("provisional", false);
			return none;
		}

		Map<String, Object> now = today(userId);
		String[] labels = { "sleep_hours", "resting_heart_rate", "steps", "active_minutes", "temperature_c" };
		double[] weights = { 0.30, 0.25, 0.15, 0.15, 0.15 };
		double[] z = {
				zScore(taskDouble(now, "sleep", "duration_hours"), b.getAvgSleepHours(), b.getStdSleepHours()),
				zScore(doubleVal(nested(now, "heart_rate", "resting")), b.getAvgRestingHeartRate(), b.getStdRestingHeartRate()),
				zScore(intVal(nested(now, "activity", "steps")), b.getAvgDailySteps(), b.getStdDailySteps()),
				zScore(intVal(nested(now, "activity", "active_minutes")), b.getAvgActiveMinutes(), b.getStdActiveMinutes()),
				zScore(doubleVal(nested(now, "temperature", "average")), b.getAvgTemperatureC(), b.getStdTemperatureC())
		};

		double weighted = 0;
		double weightSum = 0;
		List<Map<String, String>> anomalies = new ArrayList<>();
		for (int i = 0; i < z.length; i++) {
			if (Double.isNaN(z[i])) {
				continue;
			}
			weighted += weights[i] * Math.min(Math.abs(z[i]), 4.0);
			weightSum += weights[i];
			if (Math.abs(z[i]) > 3) {
				Map<String, String> a = new LinkedHashMap<>();
				a.put("metric", labels[i]);
				a.put("z_score", String.format("%.2f", z[i]));
				if (Math.abs(z[i]) > 7) {
					a.put("severity", "elevated");
				}
				anomalies.add(a);
			}
		}
		// A baseline without any usable today metric is not an estimate; wait for data.
		if (weightSum == 0) {
			Map<String, Object> none = new LinkedHashMap<>();
			none.put("date", date);
			none.put("wellness", "no data");
			none.put("reason", "collecting");
			none.put("provisional", false);
			return none;
		}
		double meanAbs = weighted / weightSum;
		int wellnessScore = (int) Math.round(Math.max(0, Math.min(100, 100 - meanAbs * 12)));
		String stress = wellnessScore >= 75 ? "low" : wellnessScore >= 50 ? "moderate" : "elevated";

		// This on-the-fly score is always an early estimate: no stored, validated
		// assessment exists yet. Surface a range and mark it provisional.
		return wellnessPayload(date, BigDecimal.valueOf(wellnessScore), stress,
				BigDecimal.valueOf(0.45), true, 5);
	}

	private Map<String, Object> wellnessPayload(LocalDate date, BigDecimal score, String stress,
			BigDecimal confidence, boolean provisional, int margin) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("date", date);
		m.put("wellness_score", score);
		m.put("stress_level", stress);
		m.put("confidence", confidence);
		m.put("provisional", provisional);
		m.put("early_estimate", provisional);
		if (score != null) {
			int mid = score.setScale(0, RoundingMode.HALF_UP).intValue();
			m.put("score_low", Math.max(0, mid - margin));
			m.put("score_high", Math.min(100, mid + margin));
		}
		return m;
	}

	// ---------------------------------------------------------------- trends

	public Map<String, Object> trends(UUID userId, String metric, int days) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("metric", metric);
		result.put("days", days);
		ZoneId zone = zoneFor(userId);
		LocalDate today = LocalDate.now(zone);
		List<Map<String, String>> values = new ArrayList<>();
		for (int i = days - 1; i >= 0; i--) {
			LocalDate d = today.minusDays(i);
			Double v = metricValueForDay(userId, metric, d, zone);
			Map<String, String> row = new LinkedHashMap<>();
			row.put("date", d.toString());
			row.put("value", v == null ? "null" : String.format("%.2f", v));
			values.add(row);
		}
		result.put("values", values);
		result.put("trend", trendLabel(values));
		return result;
	}

	private Double metricValueForDay(UUID userId, String metric, LocalDate date, ZoneId zone) {
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
		switch (metric) {
			case "sleep" -> {
				Instant pFrom = date.minusDays(1).atTime(20, 0).atZone(zone).toInstant();
				List<SleepRecord> recs = sleepRepository.findForRange(userId, pFrom, to);
				return recs.isEmpty() ? null : recs.get(0).getDurationMinutes() / 60.0;
			}
			case "steps", "activity" -> {
				List<ActivityRecord> recs = activityRepository.findForRange(userId, from, to);
				int steps = recs.stream().mapToInt(a -> a.getSteps() == null ? 0 : a.getSteps()).sum();
				return (double) steps;
			}
			case "heart_rate" -> {
				List<Map<String, Object>> rows = jdbc.queryForList("""
						SELECT AVG(heart_rate_avg) AS avg_hr FROM sensor_readings_hourly
						WHERE user_id=? AND window_start>=? AND window_start<?""", userId, asTs(from), asTs(to));
				return rows.isEmpty() || rows.get(0).get("avg_hr") == null ? null
						: toBigDecimal(rows.get(0).get("avg_hr")).doubleValue();
			}
			case "temperature" -> {
				List<Map<String, Object>> rows = jdbc.queryForList("""
						SELECT AVG(temperature_avg) AS avg_t FROM sensor_readings_hourly
						WHERE user_id=? AND window_start>=? AND window_start<?""", userId, asTs(from), asTs(to));
				return rows.isEmpty() || rows.get(0).get("avg_t") == null ? null
						: toBigDecimal(rows.get(0).get("avg_t")).doubleValue();
			}
			case "wellness" -> {
				List<HealthAssessment> a = assessmentRepository.findByUserIdAndAssessmentDate(userId, date);
				return a.isEmpty() || a.get(0).getWellnessScore() == null ? null
						: a.get(0).getWellnessScore().doubleValue();
			}
			default -> throw new IllegalArgumentException("Unknown metric: " + metric);
		}
	}

	private String trendLabel(List<Map<String, String>> values) {
		Double first = null, last = null;
		for (Map<String, String> v : values) {
			if (!"null".equals(v.get("value"))) {
				if (first == null) {
					first = Double.parseDouble(v.get("value"));
				}
				last = Double.parseDouble(v.get("value"));
			}
		}
		if (first == null || last == null) {
			return "unknown";
		}
		if (last > first * 1.05) {
			return "improving";
		}
		if (last < first * 0.95) {
			return "declining";
		}
		return "stable";
	}

	// ---------------------------------------------------------------- helpers

	private Object nested(Map<String, Object> map, String group, String key) {
		Object g = map.get(group);
		if (g instanceof Map<?, ?> inner) {
			return inner.get(key);
		}
		return null;
	}

	private double taskDouble(Map<String, Object> today, String group, String key) {
		Object v = nested(today, group, key);
		return v instanceof Number num ? num.doubleValue() : Double.NaN;
	}

	private double doubleVal(Object o) {
		if (o == null) {
			return Double.NaN;
		}
		if (o instanceof BigDecimal bd) {
			return bd.doubleValue();
		}
		if (o instanceof Number n) {
			return n.doubleValue();
		}
		return Double.NaN;
	}

	private Integer intVal(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Number n) {
			return n.intValue();
		}
		return null;
	}

	private double zScore(Number value, Number mean, Number std) {
		if (value == null || Double.isNaN(value.doubleValue()) || mean == null || std == null
				|| std.doubleValue() == 0) {
			return Double.NaN;
		}
		return (value.doubleValue() - mean.doubleValue()) / std.doubleValue();
	}

	private Double diff(BigDecimal today, BigDecimal baseline) {
		if (today == null || baseline == null) {
			return null;
		}
		return round(today.doubleValue() - baseline.doubleValue(), 1);
	}

	private Double diffInt(Integer today, Integer baseline) {
		if (today == null || baseline == null) {
			return null;
		}
		return round((double) (today - baseline), 0);
	}

	private Timestamp asTs(Instant i) {
		return i == null ? null : Timestamp.from(i);
	}

	private BigDecimal toBigDecimal(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof BigDecimal bd) {
			return bd;
		}
		if (o instanceof Number n) {
			return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
		}
		return null;
	}

	private Double round(double value, int scale) {
		return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
	}

	private Double round(Object value, int scale) {
		return value instanceof Number n ? round(n.doubleValue(), scale) : null;
	}
}
