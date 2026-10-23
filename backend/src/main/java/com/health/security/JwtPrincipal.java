package com.health.security;

import java.util.UUID;

public record JwtPrincipal(UUID userId, String email) {
}
