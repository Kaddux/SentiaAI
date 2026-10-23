package com.health.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.health.dto.AuthRequest;
import com.health.dto.AuthResponse;
import com.health.dto.RegisterRequest;
import com.health.model.User;
import com.health.repository.UserRepository;
import com.health.security.JwtUtil;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtUtil = jwtUtil;
	}

	public AuthResponse register(RegisterRequest request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new IllegalArgumentException("Email is already registered.");
		}
		User user = new User(
				request.email().toLowerCase(),
				passwordEncoder.encode(request.password()),
				request.firstName(),
				request.lastName());
		user = userRepository.save(user);
		return toAuthResponse(user);
	}

	public AuthResponse login(AuthRequest request) {
		User user = userRepository.findByEmail(request.email().toLowerCase())
				.orElseThrow(() -> new IllegalArgumentException("Invalid email or password."));
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new IllegalArgumentException("Invalid email or password.");
		}
		return toAuthResponse(user);
	}

	private AuthResponse toAuthResponse(User user) {
		String token = jwtUtil.generateToken(user.getId(), user.getEmail());
		return new AuthResponse(token, user.getId(), user.getEmail());
	}
}
