package com.health.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.health.dto.ReadingDto;
import com.health.dto.SensorBatchDto;

@Service
public class SensorService {

	private final JdbcTemplate jdbcTemplate;

	public SensorService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Batch-insert raw readings into the monthly-partitioned sensor_readings
	 * table. The device samples faster than it POSTs, so a batch is the
	 * expected unit (see plan Section 12.1 "batch-POST" note).
	 */
	public int ingest(UUID userId, SensorBatchDto batch) {
		List<ReadingDto> readings = batch.readings();
		if (readings.isEmpty()) {
			return 0;
		}
		String sql = """
				INSERT INTO sensor_readings
					(user_id, recorded_at, heart_rate, accel_x, accel_y, accel_z, temperature,
					 gyro_x, gyro_y, gyro_z, battery_v, battery_pct, button_pressed,
					 temp_valid, finger_detected, steps_total)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
		int total = 0;
		for (ReadingDto r : readings) {
			total += jdbcTemplate.update(sql, userId,
					r.recordedAt() != null ? Timestamp.from(r.recordedAt()) : null,
					r.heartRate(),
					r.accelX(),
					r.accelY(),
					r.accelZ(),
					r.temperature(),
					r.gyroX(),
					r.gyroY(),
					r.gyroZ(),
					r.batteryV(),
					r.batteryPct(),
					r.buttonPressed(),
					r.tempValid(),
					r.fingerDetected(),
					r.stepsTotal());
		}
		return total;
	}

	/**
	 * Most recent raw readings for the live view. Bounded to the last 10 minutes
	 * so the query only touches the current partition(s), and the limit is clamped.
	 */
	public Map<String, Object> recent(UUID userId, int limit) {
		int n = Math.max(1, Math.min(200, limit));
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
				SELECT recorded_at, heart_rate, temperature, accel_x, accel_y, accel_z,
				       gyro_x, gyro_y, gyro_z, battery_v, battery_pct,
				       button_pressed, temp_valid, finger_detected, steps_total
				FROM sensor_readings
				WHERE user_id = ? AND recorded_at > now() - interval '10 minutes'
				ORDER BY recorded_at DESC
				LIMIT ?""", userId, n);

		List<Map<String, Object>> readings = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("recordedAt", iso(r.get("recorded_at")));
			m.put("heartRate", r.get("heart_rate"));
			m.put("temperature", r.get("temperature"));
			m.put("accelX", r.get("accel_x"));
			m.put("accelY", r.get("accel_y"));
			m.put("accelZ", r.get("accel_z"));
			m.put("gyroX", r.get("gyro_x"));
			m.put("gyroY", r.get("gyro_y"));
			m.put("gyroZ", r.get("gyro_z"));
			m.put("batteryV", r.get("battery_v"));
			m.put("batteryPct", r.get("battery_pct"));
			m.put("buttonPressed", r.get("button_pressed"));
			m.put("tempValid", r.get("temp_valid"));
			m.put("fingerDetected", r.get("finger_detected"));
			m.put("stepsTotal", r.get("steps_total"));
			readings.add(m);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("serverTime", Instant.now().toString());
		out.put("latest", readings.isEmpty() ? null : readings.get(0));
		out.put("readings", readings);
		return out;
	}

	/** Normalize the DB timestamp to ISO-8601 (Z) regardless of driver mapping. */
	private static String iso(Object ts) {
		if (ts == null) {
			return null;
		}
		if (ts instanceof Timestamp t) {
			return t.toInstant().toString();
		}
		if (ts instanceof LocalDateTime ldt) {
			return ldt.toInstant(ZoneOffset.UTC).toString();
		}
		return ts.toString();
	}
}
