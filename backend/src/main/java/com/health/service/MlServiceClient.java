package com.health.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin HTTP client to the stateless ML service (plan Section 13). Uses the JDK
 * HttpClient + Jackson to POST JSON and read back the responses, avoiding any
 * Spring RestClient converter/serialization ambiguity. HTTP/1.1 is forced to
 * avoid body-framing quirks.
 */
@Component
public class MlServiceClient {

	private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.build();
	private final ObjectMapper json = new ObjectMapper();
	private final String baseUrl;

	public MlServiceClient(@Value("${ml-service.url}") String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public JsonNode postJson(String path, Object body) {
		try {
			String payload = json.writeValueAsString(body);
			log.info("ml_request path={} body={}", path, payload);
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(baseUrl + path))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new RuntimeException("ML HTTP " + response.statusCode() + ": " + response.body());
			}
			return json.readTree(response.body());
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("ML call failed", e);
		}
	}
}
