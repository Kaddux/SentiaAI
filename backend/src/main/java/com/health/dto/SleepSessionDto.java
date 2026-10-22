package com.health.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Create/edit payload for a manual sleep session. Fields are optional on edit. */
public record SleepSessionDto(
		Instant startTs,
		Instant endTs,
		String type,
		@Min(1) @Max(5) Integer userRating) {
}
