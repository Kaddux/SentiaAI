package com.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ReminderControllerTest extends ApiTestBase {

	@Test
	void createListDeleteReminder() throws Exception {
		String token = registerAndGetToken("rem@example.com", "password123");

		String body = mockMvc.perform(post("/api/reminders")
						.header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Morning water","reminderTime":"09:00","recurrence":"daily"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.title").value("Morning water"))
				.andReturn().getResponse().getContentAsString();

		long id = objectMapper.readTree(body).get("id").asLong();

		mockMvc.perform(get("/api/reminders").header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].title").value("Morning water"));

		mockMvc.perform(delete("/api/reminders/{id}", id).header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deleted").value(true));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/reminders"))
				.andExpect(status().isForbidden());
	}
}
