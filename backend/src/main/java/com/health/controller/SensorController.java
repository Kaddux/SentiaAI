package com.health.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.health.dto.SensorBatchDto;
import com.health.security.JwtPrincipal;
import com.health.service.SensorService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sensors")
public class SensorController {

	private final SensorService sensorService;

	public SensorController(SensorService sensorService) {
		this.sensorService = sensorService;
	}

	/**
	 * Device → backend ingestion. Authenticated as a user via JWT (device is
	 * bound to a user account for the MVP; a dedicated device token is stretch).
	 */
	@PostMapping("/ingest")
	public ResponseEntity<Map<String, Object>> ingest(
			@AuthenticationPrincipal JwtPrincipal principal,
			@Valid @RequestBody SensorBatchDto batch) {
		int inserted = sensorService.ingest(principal.userId(), batch);
		return ResponseEntity.ok(Map.of(
				"status", "ok",
				"user_id", principal.userId().toString(),
				"inserted", inserted));
	}

	/** Latest raw readings for the frontend live panel. */
	@GetMapping("/recent")
	public ResponseEntity<Map<String, Object>> recent(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(defaultValue = "60") int limit) {
		return ResponseEntity.ok(sensorService.recent(principal.userId(), limit));
	}
}
