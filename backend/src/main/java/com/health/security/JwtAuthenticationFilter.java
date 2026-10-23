package com.health.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtUtil jwtUtil;

	public JwtAuthenticationFilter(JwtUtil jwtUtil) {
		this.jwtUtil = jwtUtil;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String header = request.getHeader("Authorization");

		if (header != null && header.startsWith(BEARER_PREFIX)) {
			String token = header.substring(BEARER_PREFIX.length());
			try {
				if (jwtUtil.isValid(token)) {
					var principal = new JwtPrincipal(jwtUtil.extractUserId(token),
							jwtUtil.extractEmail(token));
					var authentication = new UsernamePasswordAuthenticationToken(
							principal, null,
							List.of(new SimpleGrantedAuthority("ROLE_USER")));
					SecurityContextHolder.getContext().setAuthentication(authentication);
				}
			} catch (JwtException | IllegalArgumentException e) {
				SecurityContextHolder.clearContext();
			}
		}

		filterChain.doFilter(request, response);
	}

}
