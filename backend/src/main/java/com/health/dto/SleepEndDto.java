package com.health.dto;

import java.time.Instant;

/** Optional wake time when closing an open session. */
public record SleepEndDto(Instant endedAt) {
}
