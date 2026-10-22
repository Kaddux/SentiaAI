package com.health.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ReadingDto(
		Instant recordedAt,
		Integer heartRate,
		BigDecimal accelX,
		BigDecimal accelY,
		BigDecimal accelZ,
		BigDecimal temperature,
		// wearable extras (ESP32 UDP CSV) — all nullable
		BigDecimal gyroX,
		BigDecimal gyroY,
		BigDecimal gyroZ,
		BigDecimal batteryV,
		Integer batteryPct,
		Integer buttonPressed,
		Integer tempValid,
		Integer fingerDetected,
		Integer stepsTotal) {
}
