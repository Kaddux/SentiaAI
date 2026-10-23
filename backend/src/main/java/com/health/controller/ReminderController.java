package com.health.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.health.dto.ReminderDto;
import com.health.security.JwtPrincipal;
import com.health.service.ReminderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

	private final ReminderService reminderService;

	public ReminderController(ReminderService reminderService) {
		this.reminderService = reminderService;
	}

	@PostMapping
	public ResponseEntity<?> create(@AuthenticationPrincipal JwtPrincipal principal,
			@Valid @RequestBody ReminderDto dto) {
		return ResponseEntity.status(201).body(reminderService.create(principal.userId(), dto));
	}

	@GetMapping
	public List<?> list(@AuthenticationPrincipal JwtPrincipal principal) {
		return reminderService.list(principal.userId());
	}

	/** Reminders that fired recently and are waiting for the user to acknowledge. */
	@GetMapping("/due")
	public List<?> due(@AuthenticationPrincipal JwtPrincipal principal) {
		return reminderService.due(principal.userId());
	}

	@PostMapping("/{id}/ack")
	public Map<String, Object> acknowledge(@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable Long id) {
		reminderService.acknowledge(principal.userId(), id);
		return Map.of("id", id, "acknowledged", true);
	}

	@PutMapping("/{id}")
	public ResponseEntity<?> update(@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable Long id, @Valid @RequestBody ReminderDto dto) {
		return ResponseEntity.ok(reminderService.update(principal.userId(), id, dto));
	}

	@DeleteMapping("/{id}")
	public Map<String, Object> delete(@AuthenticationPrincipal JwtPrincipal principal,
			@PathVariable Long id) {
		reminderService.delete(principal.userId(), id);
		return Map.of("id", id, "deleted", true);
	}
}
