package com.health.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil {

	private final SecretKey key;
	private final long expirationMs;

	public JwtUtil(@Value("${jwt.secret}") String secret,
			@Value("${jwt.expiration-ms:86400000}") long expirationMs) {
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.expirationMs = expirationMs;
	}

	public String generateToken(UUID userId, String email) {
		Date now = new Date();
		return Jwts.builder()
				.subject(userId.toString())
				.claim("email", email)
				.issuedAt(now)
				.expiration(new Date(now.getTime() + expirationMs))
				.signWith(key)
				.compact();
	}

	public UUID extractUserId(String token) {
		return UUID.fromString(extractClaims(token).getSubject());
	}

	public String extractEmail(String token) {
		return extractClaims(token).get("email", String.class);
	}

	public boolean isValid(String token) {
		try {
			extractClaims(token);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private Claims extractClaims(String token) {
		return Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload();
	}

}
