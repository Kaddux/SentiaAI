package com.health.dto;

import java.time.LocalTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReminderDto(
		@NotBlank String title,
		String description,
		@NotNull LocalTime reminderTime,
		@NotBlank String recurrence,
		Integer[] daysOfWeek,
		Boolean isActive) {

	public ReminderDto {
		recurrence = recurrence.isBlank() ? "none" : recurrence;
	}
}
