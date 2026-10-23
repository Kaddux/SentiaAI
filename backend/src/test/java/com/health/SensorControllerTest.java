package com.health;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.health.dto.ReadingDto;
import com.health.dto.SensorBatchDto;
import com.health.service.SensorService;

class SensorControllerTest extends ApiTestBase {

	@MockitoBean
	private SensorService sensorService;

	@Test
	void ingestAcceptsBatch() throws Exception {
		String token = registerAndGetToken("sensor@example.com", "password123");
		when(sensorService.ingest(any(UUID.class), any(SensorBatchDto.class))).thenReturn(3);

		mockMvc.perform(post("/api/sensors/ingest")
						.header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"readings":[
								  {"recordedAt":"2026-08-23T10:00:00Z","heartRate":72,"accelX":0.1,"accelY":0.9,"accelZ":0.2,"temperature":36.5},
								  {"recordedAt":"2026-08-23T10:00:05Z","heartRate":74,"accelX":0.1,"accelY":0.9,"accelZ":0.2,"temperature":36.5},
								  {"recordedAt":"2026-08-23T10:00:10Z","heartRate":71,"accelX":0.1,"accelY":0.9,"accelZ":0.2,"temperature":36.4}
								]}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.inserted").value(3));
	}

	@Test
	void emptyBatchReturnsBadRequest() throws Exception {
		String token = registerAndGetToken("sensor2@example.com", "password123");
		mockMvc.perform(post("/api/sensors/ingest")
						.header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"readings":[]}"""))
				.andExpect(status().isBadRequest());
	}
}
