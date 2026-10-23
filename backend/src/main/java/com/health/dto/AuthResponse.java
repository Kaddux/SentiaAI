package com.health.dto;

import java.util.UUID;

public record AuthResponse(
		String token,
		UUID userId,
		String email) {
}
