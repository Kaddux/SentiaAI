package com.health.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import com.health.model.ActivityRecord;
import com.health.model.HealthAssessment;
import com.health.model.HealthBaseline;
import com.health.model.SleepRecord;
import com.health.repository.ActivityRepository;
import com.health.repository.AssessmentRepository;
import com.health.repository.BaselineRepository;
import com.health.repository.SleepRepository;
import com.health.service.MlServiceClient;

/**
 * Scheduled ML jobs (plan Section 13): drive the stateless ML service and
 * persist the returned signals. Activity runs every 5 min on unprocessed
 * windows; sleep runs at 6 AM; wellness at 8 AM.
 */
@Component
public class MlJobs {

	private static final Logger log = LoggerFactory.getLogger(MlJobs.class);

	private final JdbcTemplate jdbc;
	private final MlServiceClient mlClient;
	private final ActivityRepository activityRepo;
	private final SleepRepository sleepRepo;
	private final AssessmentRepository assessmentRepo;
	private final BaselineRepository baselineRepo;

	public MlJobs(JdbcTemplate jdbc, MlServiceClient mlClient,
			ActivityRepository activityRepo, SleepRepository sleepRepo,
			AssessmentRepository assessmentRepo, BaselineRepository baselineRepo) {
		this.jdbc = jdbc;
		this.mlClient = mlClient;
		this.activityRepo = activityRepo;
		this.sleepRepo = sleepRepo;
		this.assessmentRepo = assessmentRepo;
		this.baselineRepo = baselineRepo;
	}

	// ---------------------------------------------------------- activity (5 min)
	@Scheduled(fixedRate = 300_000)
	public void runActivityClassification() {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT user_id, window_start, accel_mag_avg, accel_mag_std, motion_ratio,
				       heart_rate_avg, EXTRACT(HOUR FROM window_start)::int AS hour
				FROM sensor_readings_5min
				WHERE processed_for_activity = FALSE
				ORDER BY user_id, window_start
				LIMIT 500""");
		if (rows.isEmpty()) {
			return;
		}
		// Build the payload (stateless ML expects per-window features).
		List<Map<String, Object>> body = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Map<String, Object> w = new LinkedHashMap<>();
			w.put("user_id", String.valueOf(r.get("user_id")));
			w.put("window_start", r.get("window_start").toString());
			w.put("accel_mag_avg", toDouble(r.get("accel_mag_avg")));
			w.put("accel_mag_std", toDouble(r.get("accel_mag_std")));
			w.put("motion_ratio", toDouble(r.get("motion_ratio")));
			w.put("hr_avg", toDouble(r.get("heart_rate_avg")));
			w.put("hour", r.get("hour"));
			body.add(w);
		}
		try {
			JsonNode resp = mlClient.postJson("/classify/activity", body);
			List<ActivityRecord> records = new ArrayList<>();
			for (JsonNode r : resp.path("results")) {
				UUID userId = UUID.fromString(r.path("user_id").asText());
				Instant start = parseInstant(r.path("window_start").asText());
				records.add(mapActivity(userId, start, r));
			}
			// Idempotent: if a window was re-processed, replace its old row.
			jdbc.batchUpdate("DELETE FROM activity_records WHERE user_id = ? AND start_time = ?",
					rows.stream().map(r -> new Object[]{r.get("user_id"), r.get("window_start")})
							.toList());
			activityRepo.saveAll(records);
			// Mark processed for the windows we just handled.
			jdbc.batchUpdate("UPDATE sensor_readings_5min SET processed_for_activity = TRUE "
					+ "WHERE user_id = ? AND window_start = ?",
					rows.stream().map(r -> new Object[]{r.get("user_id"), r.get("window_start")})
							.toList());
			log.info("activity_classified", new Object[]{records.size()});
		} catch (Exception e) {
			log.error("activity_classification_failed", e);
		}
	}

	private ActivityRecord mapActivity(UUID userId, Instant start, JsonNode r) {
		ActivityRecord a = new ActivityRecord();
		a.setUserId(userId);
		a.setStartTime(start);
		a.setEndTime(start.plusSeconds(300));  // 5-min window
		a.setActivityType(r.path("activity_type").asText());
		a.setSteps(r.path("steps_estimate").asInt(0));
		a.setConfidence(java.math.BigDecimal.valueOf(r.path("confidence").asDouble(0)));
		a.setAvgAccelMag(java.math.BigDecimal.valueOf(r.path("accel_mag_avg").asDouble(0)));
		return a;
	}

	// ------------------------------------------------------------ sleep (6 AM)
	@Scheduled(cron = "0 0 6 * * *")
	public void runSleepEstimation() {
		LocalDateTime prev20 = LocalDate.now(ZoneOffset.UTC).minusDays(1).atTime(20, 0);
		LocalDateTime today12 = LocalDate.now(ZoneOffset.UTC).atTime(12, 0);
		List<UUID> users = users();
		for (UUID user : users) {
			List<Map<String, Object>> rows = jdbc.queryForList("""
					SELECT window_start, accel_mag_std FROM sensor_readings_5min
					WHERE user_id = ? AND window_start >= ? AND window_start < ?
					ORDER BY window_start""",
					user, prev20, today12);
			List<Map<String, Object>> body = new ArrayList<>();
			for (Map<String, Object> r : rows) {
				Map<String, Object> w = new LinkedHashMap<>();
				w.put("window_start", r.get("window_start").toString());
				w.put("accel_mag_std", toDouble(r.get("accel_mag_std")));
				body.add(w);
			}
			if (body.isEmpty()) {
				continue;
			}
			try {
				JsonNode resp = mlClient.postJson("/estimate/sleep", body);
				if (resp.path("detected").asBoolean(false)) {
					sleepRepo.save(mapSleep(user, resp));
				}
			} catch (Exception e) {
				log.error("sleep_estimation_failed user={}", user, e);
			}
		}
	}

	private SleepRecord mapSleep(UUID userId, JsonNode r) {
		SleepRecord s = new SleepRecord();
		s.setUserId(userId);
		s.setSleepStart(parseInstant(r.path("sleep_start").asText()));
		s.setSleepEnd(parseInstant(r.path("sleep_end").asText()));
		s.setDurationMinutes(r.path("duration_min").asInt(0));
		s.setInterruptions(r.path("interruptions").asInt(0));
		s.setSource("estimated");
		return s;
	}

	// ---------------------------------------------------------- wellness (8 AM)
	@Scheduled(cron = "0 0 8 * * *")
	public void runWellnessAssessment() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		for (UUID user : users()) {
			HealthBaseline b = baselineRepo.findCurrentByUser(user).orElse(null);
			if (b == null) {
				continue;
			}
			Map<String, Object> stats = todayStats(user, today);
			Map<String, Object> request = new LinkedHashMap<>();
			request.put("date", today);
			request.put("stats", stats);
			request.put("baseline", baselineOf(b));
			try {
				JsonNode resp = mlClient.postJson("/assess/wellness", request);
				assessmentRepo.save(mapAssessment(user, today, b, resp));
			} catch (Exception e) {
				log.error("wellness_assessment_failed user={}", user, e);
			}
		}
	}

	private HealthAssessment mapAssessment(UUID userId, LocalDate date,
			HealthBaseline b, JsonNode r) {
		HealthAssessment a = new HealthAssessment();
		a.setUserId(userId);
		a.setAssessmentType("wellness");
		a.setAssessmentDate(date);
		a.setWellnessScore(java.math.BigDecimal.valueOf(r.path("wellness_score").asInt(0)));
		a.setStressLevel(r.path("stress_level").asText());
		a.setConfidence(java.math.BigDecimal.valueOf(r.path("confidence").asDouble(0.72)));
		try {
			a.setFactors(r.path("factors").toString());
			a.setRawOutput(r.toString());
		} catch (Exception ignore) {
		}
		return a;
	}

	private Map<String, Object> todayStats(UUID userId, LocalDate date) {
		Instant from = date.atStartOfDay().toInstant(ZoneOffset.UTC);
		Instant to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
		Instant prev20 = date.minusDays(1).atTime(20, 0).toInstant(ZoneOffset.UTC);

		// steps / active minutes from activity_records
		List<Map<String, Object>> acts = jdbc.queryForList("""
				SELECT COALESCE(SUM(steps),0) AS steps,
				       COALESCE(SUM(EXTRACT(EPOCH FROM (end_time - start_time)) / 60), 0)::int AS active_minutes
				FROM activity_records WHERE user_id = ? AND start_time >= ? AND start_time < ?
				""", userId, from, to);
		// resting hr = min of hourly mins (proxy) for today
		List<Map<String, Object>> hr = jdbc.queryForList("""
				SELECT MIN(CASE WHEN heart_rate_min >= 30 THEN heart_rate_min END) AS resting FROM sensor_readings_hourly
				WHERE user_id = ? AND window_start >= ? AND window_start < ?
				""", userId, from, to);
		// sleep hours from last night's record
		List<Map<String, Object>> slp = jdbc.queryForList("""
				SELECT COALESCE(MAX(duration_minutes), 0) AS mins FROM sleep_records
				WHERE user_id = ? AND sleep_start >= ? AND sleep_start < ?
				""", userId, prev20, to);
		// temperature = average of today's hourly averages
		List<Map<String, Object>> tmp = jdbc.queryForList("""
				SELECT AVG(CASE WHEN temperature_avg > 0 THEN temperature_avg END) AS avg_t
				FROM sensor_readings_hourly WHERE user_id = ? AND window_start >= ? AND window_start < ?
				""", userId, from, to);

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("sleep_hours", num(slp, "mins") / 60.0);
		stats.put("steps", num(acts, "steps").intValue());
		stats.put("active_minutes", num(acts, "active_minutes").intValue());
		stats.put("resting_hr", num(hr, "resting") == null ? null : num(hr, "resting").doubleValue());
		stats.put("temperature_c", tmp.isEmpty() ? null : num(tmp.get(0), "avg_t"));
		return stats;
	}

	private Map<String, Object> baselineOf(HealthBaseline b) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("avg_sleep_hours", num(b.getAvgSleepHours()));
		m.put("std_sleep_hours", num(b.getStdSleepHours()));
		m.put("avg_daily_steps", b.getAvgDailySteps());
		m.put("std_daily_steps", b.getStdDailySteps());
		m.put("avg_heart_rate", num(b.getAvgHeartRate()));
		m.put("avg_resting_heart_rate", num(b.getAvgRestingHeartRate()));
		m.put("std_resting_heart_rate", num(b.getStdRestingHeartRate()));
		m.put("avg_active_minutes", b.getAvgActiveMinutes());
		m.put("std_active_minutes", b.getStdActiveMinutes());
		m.put("avg_temperature_c", num(b.getAvgTemperatureC()));
		m.put("std_temperature_c", num(b.getStdTemperatureC()));
		return m;
	}

	private List<UUID> users() {
		return jdbc.queryForList("SELECT DISTINCT user_id FROM sensor_readings_5min ORDER BY user_id", UUID.class);
	}

	private static Double toDouble(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Number n) {
			return n.doubleValue();
		}
		return Double.parseDouble(o.toString());
	}

	/**
	 * Parse a timestamp from the ML service. It may echo the DB (timezone-less
	 * "yyyy-MM-dd HH:mm:ss") or ISO ("...T...Z"). Treat timezone-less as UTC.
	 */
	private static Instant parseInstant(String s) {
		String t = s.trim().replace(' ', 'T');
		if (!t.endsWith("Z")) {
			t = t + "Z";
		}
		return Instant.parse(t);
	}

	private static Double num(Object o) {
		return o == null ? null : ((Number) o).doubleValue();
	}

	private static Double num(Map<String, Object> map, String key) {
		Object o = map.get(key);
		return o == null ? null : ((Number) o).doubleValue();
	}

	private static Double num(List<Map<String, Object>> rows, String key) {
		if (rows.isEmpty()) {
			return 0.0;
		}
		return num(rows.get(0), key);
	}
}
