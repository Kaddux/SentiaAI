package com.health.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.health.dto.SleepSessionDto;
import com.health.model.SleepSession;
import com.health.model.User;
import com.health.repository.SleepSessionRepository;
import com.health.repository.UserRepository;

/**
 * Manual sleep logging. All timestamps are UTC; the user's timezone is used
 * only to classify NIGHT/NAP and to label nights by the local wake date.
 *
 * Privacy: never log totals/ratings/timestamps — only IDs and error types.
 */
@Service
public class SleepSessionService {

	private static final String NIGHT = "NIGHT";
	private static final String NAP = "NAP";

	private final SleepSessionRepository sessions;
	private final UserRepository users;
	private final int napMaxMinutes;
	private final int napStartHour;
	private final int napEndHour;
	private final int maxSessionMinutes;
	private final int staleOpenHours;

	public SleepSessionService(SleepSessionRepository sessions, UserRepository users,
			@Value("${app.sleep.nap-max-minutes:180}") int napMaxMinutes,
			@Value("${app.sleep.nap-start-hour:10}") int napStartHour,
			@Value("${app.sleep.nap-end-hour:18}") int napEndHour,
			@Value("${app.sleep.max-session-minutes:960}") int maxSessionMinutes,
			@Value("${app.sleep.stale-open-hours:14}") int staleOpenHours) {
		this.sessions = sessions;
		this.users = users;
		this.napMaxMinutes = napMaxMinutes;
		this.napStartHour = napStartHour;
		this.napEndHour = napEndHour;
		this.maxSessionMinutes = maxSessionMinutes;
		this.staleOpenHours = staleOpenHours;
	}

	// ---------------------------------------------------------------- actions

	@Transactional
	public SleepSession start(UUID userId) {
		if (sessions.findFirstByUserIdAndEndTsIsNullOrderByStartTsDesc(userId).isPresent()) {
			throw new IllegalArgumentException("A sleep session is already open");
		}
		Instant now = Instant.now();
		SleepSession s = new SleepSession();
		s.setUserId(userId);
		s.setStartTs(now);
		s.setType(provisionalType(now, zone(userId)));
		return sessions.save(s);
	}

	@Transactional
	public SleepSession end(UUID userId, Instant endedAt) {
		SleepSession s = sessions.findFirstByUserIdAndEndTsIsNullOrderByStartTsDesc(userId)
				.orElseThrow(() -> new IllegalArgumentException("No open sleep session"));
		Instant end = endedAt != null ? endedAt : Instant.now();
		applyClosed(s, s.getStartTs(), end);
		return s;
	}

	@Transactional
	public SleepSession createPast(UUID userId, SleepSessionDto dto) {
		if (dto.startTs() == null || dto.endTs() == null) {
			throw new IllegalArgumentException("Bedtime and wake time are required");
		}
		SleepSession s = new SleepSession();
		s.setUserId(userId);
		s.setStartTs(dto.startTs());
		s.setUserRating(dto.userRating());
		applyClosed(s, dto.startTs(), dto.endTs());
		return sessions.save(s);
	}

	@Transactional
	public SleepSession update(UUID userId, Long id, SleepSessionDto dto) {
		SleepSession s = sessions.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new IllegalArgumentException("Sleep session not found"));
		if (dto.startTs() != null) {
			s.setStartTs(dto.startTs());
		}
		if (dto.endTs() != null) {
			applyClosed(s, s.getStartTs(), dto.endTs());
		} else if (s.getEndTs() != null) {
			applyClosed(s, s.getStartTs(), s.getEndTs());
		} else {
			// Still-open session with only a start change: guard overlap up to now.
			ensureNoOverlap(userId, s.getId(), s.getStartTs(), Instant.now());
		}
		if (dto.type() != null && !dto.type().isBlank()) {
			s.setType(normalizeType(dto.type()));
		}
		if (dto.userRating() != null) {
			validateRating(dto.userRating());
			s.setUserRating(dto.userRating());
		}
		return s;
	}

	@Transactional
	public void setRating(UUID userId, Long id, Integer rating) {
		validateRating(rating);
		SleepSession s = sessions.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new IllegalArgumentException("Sleep session not found"));
		s.setUserRating(rating);
	}

	@Transactional
	public void delete(UUID userId, Long id) {
		SleepSession s = sessions.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new IllegalArgumentException("Sleep session not found"));
		sessions.delete(s);
	}

	@Transactional
	public int deleteAll(UUID userId) {
		return sessions.deleteByUserId(userId);
	}

	// ----------------------------------------------------------------- reads

	public Optional<SleepSession> open(UUID userId) {
		return sessions.findFirstByUserIdAndEndTsIsNullOrderByStartTsDesc(userId);
	}

	public Optional<SleepSession> latest(UUID userId) {
		return sessions.findFirstByUserIdAndTypeAndEndTsIsNotNullOrderByStartTsDesc(userId, NIGHT)
				.or(() -> sessions.findFirstByUserIdAndTypeAndEndTsIsNotNullOrderByStartTsDesc(userId, NAP));
	}

	public List<SleepSession> range(UUID userId, Instant from, Instant to) {
		if (from == null || to == null || !to.isAfter(from)) {
			throw new IllegalArgumentException("Invalid date range");
		}
		return sessions.findByUserIdAndStartTsGreaterThanEqualAndStartTsLessThanOrderByStartTsAsc(userId, from, to);
	}

	/**
	 * Dashboard/assistant summary. Nightly series uses NIGHT sessions labelled by
	 * the local wake date; naps are excluded from the nightly figures.
	 */
	public Map<String, Object> summary(UUID userId, int days) {
		int n = Math.max(1, Math.min(31, days));
		ZoneId zone = zone(userId);
		Instant from = Instant.now().minus(n + 2L, ChronoUnit.DAYS);

		List<SleepSession> nights = sessions.findClosedSince(userId, from).stream()
				.filter(s -> NIGHT.equals(s.getType()))
				.toList();

		Map<LocalDate, SleepSession> byEndDate = new HashMap<>();
		for (SleepSession s : nights) {
			LocalDate d = s.getEndTs().atZone(zone).toLocalDate();
			SleepSession cur = byEndDate.get(d);
			if (cur == null || s.getEndTs().isAfter(cur.getEndTs())) {
				byEndDate.put(d, s);
			}
		}

		LocalDate today = LocalDate.now(zone);
		List<Map<String, Object>> series = new ArrayList<>();
		long sum = 0;
		int count = 0;
		for (int i = n - 1; i >= 0; i--) {
			LocalDate d = today.minusDays(i);
			SleepSession s = byEndDate.get(d);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", d.toString());
			Integer total = s == null ? null : s.getTotalSleepMin();
			row.put("totalSleepMin", total);
			if (total != null) {
				sum += total;
				count++;
			}
			series.add(row);
		}

		SleepSession last = nights.stream()
				.max(Comparator.comparing(SleepSession::getEndTs)).orElse(null);
		SleepSession previous = last == null ? null : nights.stream()
				.filter(s -> s.getEndTs().isBefore(last.getEndTs()))
				.max(Comparator.comparing(SleepSession::getEndTs)).orElse(null);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", "logged");
		out.put("timezone", zone.getId());
		out.put("staleOpenHours", staleOpenHours);
		out.put("lastNight", last == null ? null : sessionMap(last, zone));
		out.put("previousNight", previous == null ? null : sessionMap(previous, zone));
		out.put("nights", series);
		out.put("averageMin", count == 0 ? null : Math.round((double) sum / count));
		return out;
	}

	@Transactional
	public Map<String, Object> updateTimezone(UUID userId, String timezone) {
		ZoneId zone;
		try {
			zone = ZoneId.of(timezone);
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid timezone: " + timezone);
		}
		User user = users.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("User not found"));
		user.setTimezone(zone.getId());
		return Map.of("timezone", zone.getId());
	}

	// --------------------------------------------------------------- helpers

	private void applyClosed(SleepSession s, Instant start, Instant end) {
		if (start == null || end == null || !end.isAfter(start)) {
			throw new IllegalArgumentException("Wake time must be after bedtime");
		}
		long minutes = Duration.between(start, end).toMinutes();
		if (minutes > maxSessionMinutes) {
			throw new IllegalArgumentException("A sleep session cannot exceed 16 hours");
		}
		ensureNoOverlap(s.getUserId(), s.getId(), start, end);
		s.setEndTs(end);
		s.setTotalSleepMin((int) minutes);
		s.setType(classify(start, end, zone(s.getUserId())));
	}

	private void ensureNoOverlap(UUID userId, Long excludeId, Instant start, Instant end) {
		if (!sessions.findOverlapping(userId, excludeId, start, end).isEmpty()) {
			throw new IllegalArgumentException("This sleep session overlaps another session");
		}
	}

	private String classify(Instant start, Instant end, ZoneId zone) {
		long minutes = Duration.between(start, end).toMinutes();
		int hour = start.atZone(zone).getHour();
		if (minutes < napMaxMinutes && hour >= napStartHour && hour < napEndHour) {
			return NAP;
		}
		return NIGHT;
	}

	private String provisionalType(Instant start, ZoneId zone) {
		int hour = start.atZone(zone).getHour();
		return hour >= napStartHour && hour < napEndHour ? NAP : NIGHT;
	}

	private String normalizeType(String type) {
		String t = type.trim().toUpperCase();
		if (!NIGHT.equals(t) && !NAP.equals(t)) {
			throw new IllegalArgumentException("Type must be NIGHT or NAP");
		}
		return t;
	}

	private void validateRating(Integer rating) {
		if (rating != null && (rating < 1 || rating > 5)) {
			throw new IllegalArgumentException("Rating must be between 1 and 5");
		}
	}

	private ZoneId zone(UUID userId) {
		return users.findById(userId)
				.map(u -> {
					try {
						return ZoneId.of(u.getTimezone());
					} catch (Exception e) {
						return ZoneOffset.UTC;
					}
				})
				.orElse(ZoneOffset.UTC);
	}

	private Map<String, Object> sessionMap(SleepSession s, ZoneId zone) {
		LocalDate endDate = s.getEndTs().atZone(zone).toLocalDate();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", s.getId());
		m.put("date", endDate.toString());
		m.put("daysAgo", ChronoUnit.DAYS.between(endDate, LocalDate.now(zone)));
		m.put("startTs", s.getStartTs());
		m.put("endTs", s.getEndTs());
		m.put("totalSleepMin", s.getTotalSleepMin());
		m.put("userRating", s.getUserRating());
		m.put("type", s.getType());
		return m;
	}
}
