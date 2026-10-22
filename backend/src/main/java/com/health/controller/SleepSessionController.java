package com.health.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.health.dto.SleepEndDto;
import com.health.dto.SleepRatingDto;
import com.health.dto.SleepSessionDto;
import com.health.dto.TimezoneDto;
import com.health.model.SleepSession;
import com.health.security.JwtPrincipal;
import com.health.service.SleepSessionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sleep-sessions")
public class SleepSessionController {

	private final SleepSessionService service;

	public SleepSessionController(SleepSessionService service) {
		this.service = service;
	}

	@PostMapping("/start")
	public ResponseEntity<?> start(@AuthenticationPrincipal JwtPrincipal principal) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.start(principal.userId()));
	}

	@PostMapping("/end")
	public ResponseEntity<?> end(@AuthenticationPrincipal JwtPrincipal principal,
			@RequestBody(required = false) SleepEndDto body) {
		Instant endedAt = body == null ? null : body.endedAt();
		return ResponseEntity.ok(service.end(principal.userId(), endedAt));
	}

	@PostMapping
	public ResponseEntity<?> create(@AuthenticationPrincipal JwtPrincipal principal,
			@Valid @RequestBody SleepSessionDto dto) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.createPast(principal.userId(), dto));
	}

	@PutMapping("/{id}")
	public ResponseEntity<?> update(@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable Long id, @Valid @RequestBody SleepSessionDto dto) {
		return ResponseEntity.ok(service.update(principal.userId(), id, dto));
	}

	@PatchMapping("/{id}/rating")
	public ResponseEntity<?> rate(@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable Long id, @Valid @RequestBody SleepRatingDto dto) {
		service.setRating(principal.userId(), id, dto.rating());
		return ResponseEntity.ok(Map.of("id", id, "userRating", dto.rating()));
	}

	@DeleteMapping("/{id}")
	public Map<String, Object> delete(@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable Long id) {
		service.delete(principal.userId(), id);
		return Map.of("id", id, "deleted", true);
	}

	@DeleteMapping
	public Map<String, Object> deleteAll(@AuthenticationPrincipal JwtPrincipal principal) {
		return Map.of("deleted", service.deleteAll(principal.userId()));
	}

	@GetMapping("/open")
	public ResponseEntity<?> open(@AuthenticationPrincipal JwtPrincipal principal) {
		var session = service.open(principal.userId());
		return session.isPresent() ? ResponseEntity.ok(session.get()) : ResponseEntity.noContent().build();
	}

	@GetMapping("/latest")
	public ResponseEntity<?> latest(@AuthenticationPrincipal JwtPrincipal principal) {
		var session = service.latest(principal.userId());
		return session.isPresent() ? ResponseEntity.ok(session.get()) : ResponseEntity.noContent().build();
	}

	@GetMapping
	public List<SleepSession> range(@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam Instant from, @RequestParam Instant to) {
		return service.range(principal.userId(), from, to);
	}

	@GetMapping("/summary")
	public Map<String, Object> summary(@AuthenticationPrincipal JwtPrincipal principal,
			@RequestParam(defaultValue = "7") int days) {
		return service.summary(principal.userId(), days);
	}

	@PutMapping("/timezone")
	public Map<String, Object> timezone(@AuthenticationPrincipal JwtPrincipal principal,
			@Valid @RequestBody TimezoneDto dto) {
		return service.updateTimezone(principal.userId(), dto.timezone());
	}
}
