package com.health;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.health.service.HealthService;

class HealthControllerTest extends ApiTestBase {

	@MockitoBean
	private HealthService healthService;

	@Test
	void pingIsPublic() throws Exception {
		mockMvc.perform(get("/api/health/ping"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"));
	}

	@Test
	void profileReturnsUserData() throws Exception {
		String token = registerAndGetToken("health@example.com", "password123");
		when(healthService.profile(any(UUID.class))).thenReturn(Map.of("user_id", "x", "email", "health@example.com"));

		mockMvc.perform(get("/api/health/profile").header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("health@example.com"));
	}

	@Test
	void heartRateReturnsStats() throws Exception {
		String token = registerAndGetToken("hr@example.com", "password123");
		when(healthService.heartRateStats(any(UUID.class), any(LocalDate.class)))
				.thenReturn(Map.of("resting", 60, "average", 78, "max", 132, "min", 58));

		mockMvc.perform(get("/api/health/heart-rate?date=2026-08-23").header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resting").value(60));
	}

	@Test
	void trendsReturnsSeries() throws Exception {
		String token = registerAndGetToken("trend@example.com", "password123");
		when(healthService.trends(any(UUID.class), eq("sleep"), anyInt()))
				.thenReturn(Map.of("metric", "sleep", "days", 7, "trend", "stable"));

		mockMvc.perform(get("/api/health/trends?metric=sleep&days=7").header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.metric").value("sleep"))
				.andExpect(jsonPath("$.trend").value("stable"));
	}

	@Test
	void protectedEndpointRequiresAuth() throws Exception {
		mockMvc.perform(get("/api/health/today"))
				.andExpect(status().isForbidden());
	}
}
