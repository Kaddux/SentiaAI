package com.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthControllerTest extends ApiTestBase {

	@Test
	void registerCreatesUserAndReturnsToken() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"alice@example.com","password":"password123","firstName":"Alice"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.email").value("alice@example.com"));
	}

	@Test
	void duplicateEmailReturnsConflict() throws Exception {
		registerAndGetToken("dup@example.com", "password123");
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"dup@example.com","password":"password123"}"""))
				.andExpect(status().isConflict());
	}

	@Test
	void loginReturnsToken() throws Exception {
		registerAndGetToken("bob@example.com", "password123");
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"bob@example.com","password":"password123"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void badPasswordReturnsUnauthorized() throws Exception {
		registerAndGetToken("carol@example.com", "password123");
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"carol@example.com","password":"wrongpass"}"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void invalidRegisterPayloadReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"not-an-email","password":"short"}"""))
				.andExpect(status().isBadRequest());
	}
}
