package com.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared fixture for controller tests: loads the full context on H2 (create-drop),
 * provides MockMvc and a helper to register a user and capture a JWT. Endpoints
 * that hit PostgreSQL-specific tables (sensor ingest, aggregate health reads)
 * are exercised in these tests with mocked services.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestBase {

	@Autowired
	protected MockMvc mockMvc;

	protected final ObjectMapper objectMapper = new ObjectMapper();

	protected String registerAndGetToken(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"%s","firstName":"Dev","lastName":"User"}""".formatted(email, password)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").exists())
				.andReturn();
		JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
		return node.get("token").asText();
	}

	protected String bearer(String token) {
		return "Bearer " + token;
	}
}
