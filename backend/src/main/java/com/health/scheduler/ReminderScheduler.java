package com.health.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.health.model.Reminder;
import com.health.repository.ReminderRepository;

/**
 * Fires reminders at their scheduled local time. Every scan it checks active
 * reminders, matches the recurrence, and stamps `last_triggered` once per
 * occurrence. The frontend polls GET /api/reminders/due for recent fires.
 *
 * reminder_time is interpreted in `app.reminders.zone` (default Asia/Kolkata),
 * because the containers run in UTC.
 */
@Component
public class ReminderScheduler {

	private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
	/** How late a reminder may still fire (e.g. if the app was briefly down). */
	private static final long GRACE_MINUTES = 15;

	private final ReminderRepository reminders;
	private final ZoneId zone;

	public ReminderScheduler(ReminderRepository reminders,
			@Value("${app.reminders.zone:Asia/Kolkata}") String zone) {
		this.reminders = reminders;
		this.zone = ZoneId.of(zone);
	}

	@Scheduled(fixedRateString = "${app.reminders.scan-interval-ms:5000}")
	public void fireDueReminders() {
		Instant now = Instant.now();
		LocalDate today = now.atZone(zone).toLocalDate();

		List<Reminder> active = reminders.findAllActive();
		for (Reminder r : active) {
			try {
				if (r.getReminderTime() == null || !matchesRecurrence(r, today)) {
					continue;
				}
				LocalTime time = r.getReminderTime();
				Instant due = today.atTime(time).atZone(zone).toInstant();
				if (now.isBefore(due) || now.isAfter(due.plus(GRACE_MINUTES, ChronoUnit.MINUTES))) {
					continue;
				}
				Instant last = r.getLastTriggered();
				if (last != null && !last.isBefore(due)) {
					continue; // already fired for this occurrence
				}
				r.setLastTriggered(now);
				if (r.getRecurrence() == null || "none".equalsIgnoreCase(r.getRecurrence())) {
					r.setActive(false); // one-off fires exactly once
				}
				reminders.save(r);
				log.info("reminder_fired id={} user={} title={}", r.getId(), r.getUserId(), r.getTitle());
			} catch (Exception e) {
				log.error("reminder_fire_failed id={}", r.getId(), e);
			}
		}
	}

	private boolean matchesRecurrence(Reminder r, LocalDate today) {
		String recurrence = r.getRecurrence() == null ? "none" : r.getRecurrence().toLowerCase();
		return switch (recurrence) {
			case "daily" -> true;
			case "weekly" -> {
				Integer[] days = r.getDaysOfWeek();
				int dow = today.getDayOfWeek().getValue(); // 1=Mon .. 7=Sun
				if (days != null && days.length > 0) {
					for (Integer d : days) {
						if (d != null && d == dow) {
							yield true;
						}
					}
					yield false;
				}
				yield r.getCreatedAt() != null
						&& r.getCreatedAt().atZone(zone).getDayOfWeek().getValue() == dow;
			}
			case "monthly" -> {
				int createdDay = r.getCreatedAt() != null
						? r.getCreatedAt().atZone(zone).getDayOfMonth()
						: today.getDayOfMonth();
				yield today.getDayOfMonth() == createdDay;
			}
			default -> true; // "none" — one-off
		};
	}
}
