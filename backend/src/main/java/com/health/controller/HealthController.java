package com.health.controller;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.health.security.JwtPrincipal;
import com.health.service.HealthService;

@RestController
@RequestMapping("/api/health")
public class HealthController {

	private final HealthService healthService;

	public HealthController(HealthService healthService) {
		this.healthService = healthService;
	}

	@GetMapping("/ping")
	public Map<String, String> ping() {
		return Map.of("status", "ok", "service", "backend");
	}

	@GetMapping("/profile")
	public Map<String, Object> profile(@AuthenticationPrincipal JwtPrincipal principal) {
		return healthService.profile(principal.userId());
	}

	@GetMapping("/today")
	public Map<String, Object> today(@AuthenticationPrincipal JwtPrincipal principal) {
		return healthService.today(principal.userId());
	}

	@GetMapping("/heart-rate")
	public Map<String, Object> heartRate(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(required = false) LocalDate date) {
		return healthService.heartRateStats(principal.userId(), date);
	}

	@GetMapping("/activity")
	public Map<String, Object> activity(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(required = false) LocalDate date) {
		return healthService.activityStats(principal.userId(), date);
	}

	@GetMapping("/steps")
	public Map<String, Object> steps(@AuthenticationPrincipal JwtPrincipal principal) {
		return healthService.estimatedStepsToday(principal.userId());
	}

	@GetMapping("/sleep")
	public Map<String, Object> sleep(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(required = false) LocalDate date) {
		return healthService.sleepForDay(principal.userId(), date);
	}

	@GetMapping("/temperature")
	public Map<String, Object> temperature(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(required = false) LocalDate date) {
		return healthService.temperatureStats(principal.userId(), date);
	}

	@GetMapping("/baseline")
	public Map<String, Object> baseline(@AuthenticationPrincipal JwtPrincipal principal) {
		return healthService.baseline(principal.userId());
	}

	@GetMapping("/compare-baseline")
	public Map<String, Object> compareBaseline(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(required = false) LocalDate date) {
		return healthService.compareToBaseline(principal.userId(), date);
	}

	@GetMapping("/wellness")
	public Map<String, Object> wellness(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(required = false) LocalDate date) {
		return healthService.wellness(principal.userId(), date);
	}

	@GetMapping("/trends")
	public Map<String, Object> trends(
			@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam String metric,
			@RequestParam(defaultValue = "7") int days) {
		return healthService.trends(principal.userId(), metric, days);
	}
}
