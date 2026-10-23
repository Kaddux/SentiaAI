package com.health.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.health.model.User;
import com.health.repository.UserRepository;

/**
 * Host-side step estimator for the 15-field device stream (accel + gyro only).
 *
 * The device publishes at ~2 Hz, which cannot resolve every footfall. This
 * service therefore uses a conservative peak detector over the motion signal
 * and keeps an incremental, monotonic daily count. It processes only new raw
 * samples (plus a persisted previous signal) so the same sample is never
 * counted twice, and it updates every 2 s.
 *
 * Signal = |accel magnitude - 1 g| + gyro weight * |gyro|.
 * A step is a local maximum above MOTION_THRESHOLD_G at least REFRACTORY_MS
 * after the previous one. This is deliberately simple; tune the constants
 * against a manually counted walk.
 */
@Service
public class StepsService {

	static final long MOVING_GRACE_SECONDS = 3;

	private final JdbcTemplate jdbc;
	private final UserRepository userRepository;
	private final double motionThresholdG;
	private final long refractoryMs;
	private final double gyroWeight;

	public StepsService(JdbcTemplate jdbc, UserRepository userRepository,
			@Value("${app.steps.motion-threshold-g:0.12}") double motionThresholdG,
			@Value("${app.steps.refractory-ms:280}") long refractoryMs,
			@Value("${app.steps.gyro-weight:0.01}") double gyroWeight) {
		this.jdbc = jdbc;
		this.userRepository = userRepository;
		this.motionThresholdG = motionThresholdG;
		this.refractoryMs = refractoryMs;
		this.gyroWeight = gyroWeight;
	}

	private record State(LocalDate day, Instant lastProcessed, double lastSignal,
			Instant lastPeak, int steps, boolean moving) {
	}

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

	@Scheduled(fixedRateString = "${app.steps.interval-ms:2000}")
	public void tick() {
		List<UUID> users = jdbc.queryForList("""
				SELECT DISTINCT user_id FROM sensor_readings
				WHERE recorded_at >= now() - interval '1 day'""", UUID.class);
		for (UUID user : users) {
			try {
				refresh(user);
			} catch (Exception e) {
				// Keep the scheduler alive; the next tick retries.
			}
		}
	}

	/** Process new samples for one user and return the current daily state. */
	public synchronized Map<String, Object> refresh(UUID userId) {
		ZoneId zone = zoneFor(userId);
		LocalDate today = LocalDate.now(zone);
		Instant dayStart = today.atStartOfDay(zone).toInstant();
		Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT step_date, last_processed_at, last_signal, last_peak_at, steps, moving "
						+ "FROM step_state WHERE user_id = ?", userId);
		State state;
		if (rows.isEmpty()) {
			state = new State(today, null, 0.0, null, 0, false);
		} else {
			Map<String, Object> r = rows.get(0);
			LocalDate storedDay = toLocalDate(r.get("step_date"));
			if (storedDay == null || !storedDay.equals(today)) {
				state = new State(today, null, 0.0, null, 0, false);
			} else {
				state = new State(today,
						toInstant(r.get("last_processed_at")),
						safeDouble(r.get("last_signal")),
						toInstant(r.get("last_peak_at")),
						((Number) r.get("steps")).intValue(),
						Boolean.TRUE.equals(r.get("moving")));
			}
		}

		Instant from = state.lastProcessed() != null && state.lastProcessed().isAfter(dayStart)
				? state.lastProcessed()
				: dayStart.minusMillis(1);
		List<Map<String, Object>> samples = jdbc.queryForList("""
				SELECT recorded_at, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z
				FROM sensor_readings
				WHERE user_id = ? AND recorded_at > ? AND recorded_at < ?
				ORDER BY recorded_at""", userId, Timestamp.from(from), Timestamp.from(dayEnd));

		double prevSignal = state.lastSignal();
		Instant lastPeak = state.lastPeak();
		Instant lastMovingAt = null;
		Instant lastSampleAt = null;
		int steps = state.steps();

		for (Map<String, Object> sample : samples) {
			Instant t = toInstant(sample.get("recorded_at"));
			if (t == null) {
				continue;
			}
			double ax = safeDouble(sample.get("accel_x"));
			double ay = safeDouble(sample.get("accel_y"));
			double az = safeDouble(sample.get("accel_z"));
			double gx = safeDouble(sample.get("gyro_x"));
			double gy = safeDouble(sample.get("gyro_y"));
			double gz = safeDouble(sample.get("gyro_z"));
			double mag = Math.sqrt(ax * ax + ay * ay + az * az);
			double gyroMag = Math.sqrt(gx * gx + gy * gy + gz * gz);
			double signal = Math.abs(mag - 1.0) + gyroWeight * gyroMag;

			boolean refractory = lastPeak == null
					|| Duration.between(lastPeak, t).toMillis() >= refractoryMs;
			if (prevSignal > motionThresholdG && signal <= prevSignal && refractory) {
				steps++;
				lastPeak = t;
			}
			if (signal > motionThresholdG) {
				lastMovingAt = t;
			}
			prevSignal = signal;
			lastSampleAt = t;
		}

		Instant processed = lastSampleAt != null ? lastSampleAt : state.lastProcessed();
		Instant now = Instant.now();
		// Keep the pulse alive for a few seconds after the last motion, even if
		// this refresh had no new samples (the scheduled tick and the API poll
		// can alternate).
		Instant lastMotion = lastMovingAt != null ? lastMovingAt : state.lastPeak();
		boolean moving = lastMotion != null
				&& Duration.between(lastMotion, now).getSeconds() <= MOVING_GRACE_SECONDS;

		jdbc.update("""
				INSERT INTO step_state
					(user_id, step_date, last_processed_at, last_signal, last_peak_at, steps, moving, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
				ON CONFLICT (user_id) DO UPDATE SET
					step_date = EXCLUDED.step_date,
					last_processed_at = EXCLUDED.last_processed_at,
					last_signal = EXCLUDED.last_signal,
					last_peak_at = EXCLUDED.last_peak_at,
					steps = EXCLUDED.steps,
					moving = EXCLUDED.moving,
					updated_at = NOW()""",
				userId, java.sql.Date.valueOf(today),
				processed != null ? Timestamp.from(processed) : null,
				prevSignal,
				lastPeak != null ? Timestamp.from(lastPeak) : null,
				steps, moving);

		// Keep the daily history table in sync; the state is the display source.
		jdbc.update("""
				INSERT INTO daily_steps (user_id, step_date, steps, updated_at)
				VALUES (?, ?, ?, NOW())
				ON CONFLICT (user_id, step_date) DO UPDATE SET
					steps = GREATEST(daily_steps.steps, EXCLUDED.steps),
					updated_at = NOW()""",
				userId, java.sql.Date.valueOf(today), steps);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("steps", steps);
		out.put("moving", moving);
		out.put("source", "host");
		out.put("last_processed_at", processed != null ? processed.toString() : null);
		return out;
	}

	private static LocalDate toLocalDate(Object value) {
		if (value instanceof java.sql.Date d) {
			return d.toLocalDate();
		}
		if (value instanceof LocalDate d) {
			return d;
		}
		return null;
	}

	private static Instant toInstant(Object ts) {
		if (ts instanceof Timestamp t) {
			return t.toInstant();
		}
		if (ts instanceof OffsetDateTime odt) {
			return odt.toInstant();
		}
		if (ts instanceof LocalDateTime ldt) {
			return ldt.toInstant(ZoneOffset.UTC);
		}
		return null;
	}

	private static double safeDouble(Object o) {
		if (o == null) {
			return 0.0;
		}
		if (o instanceof Number n) {
			return n.doubleValue();
		}
		try {
			return Double.parseDouble(o.toString());
		} catch (NumberFormatException e) {
			return 0.0;
		}
	}
}
