package com.health.dto;

import jakarta.validation.constraints.NotBlank;

public record TimezoneDto(@NotBlank String timezone) {
}
