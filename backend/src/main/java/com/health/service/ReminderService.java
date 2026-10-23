package com.health.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.health.dto.ReminderDto;
import com.health.model.Reminder;
import com.health.repository.ReminderRepository;

@Service
public class ReminderService {

	private final ReminderRepository reminderRepository;

	public ReminderService(ReminderRepository reminderRepository) {
		this.reminderRepository = reminderRepository;
	}

	public Reminder create(UUID userId, ReminderDto dto) {
		Reminder r = new Reminder();
		r.setUserId(userId);
		r.setTitle(dto.title());
		r.setDescription(dto.description());
		r.setReminderTime(dto.reminderTime());
		r.setRecurrence(dto.recurrence());
		r.setDaysOfWeek(dto.daysOfWeek());
		if (dto.isActive() != null) {
			r.setActive(dto.isActive());
		}
		return reminderRepository.save(r);
	}

	public List<Reminder> list(UUID userId) {
		return reminderRepository.findActiveByUser(userId);
	}

	@Transactional
	public Reminder update(UUID userId, Long id, ReminderDto dto) {
		Reminder r = reminderRepository.findActiveByIdAndUser(id, userId)
				.orElseThrow(() -> new IllegalArgumentException("Reminder not found or access denied"));
		r.setTitle(dto.title());
		r.setDescription(dto.description());
		r.setReminderTime(dto.reminderTime());
		r.setRecurrence(dto.recurrence());
		r.setDaysOfWeek(dto.daysOfWeek());
		if (dto.isActive() != null) {
			r.setActive(dto.isActive());
		}
		return r;
	}

	/** Reminders that fired recently and haven't been acknowledged yet. */
	public List<Reminder> due(UUID userId) {
		return reminderRepository.findDueForUser(userId, Instant.now().minus(10, ChronoUnit.MINUTES));
	}

	@Transactional
	public void acknowledge(UUID userId, Long id) {
		Reminder r = reminderRepository.findActiveByIdAndUser(id, userId)
				.orElseThrow(() -> new IllegalArgumentException("Reminder not found or access denied"));
		r.setLastAcknowledged(Instant.now());
	}

	@Transactional
	public void delete(UUID userId, Long id) {
		Reminder r = reminderRepository.findActiveByIdAndUser(id, userId)
				.orElseThrow(() -> new IllegalArgumentException("Reminder not found or access denied"));
		// soft delete: mark deleted_at + deactivate
		r.setDeletedAt(Instant.now());
		r.setActive(false);
	}
}
