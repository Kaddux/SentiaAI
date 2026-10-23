package com.health.scheduler;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Data retention (plan Section 12.3 / 7.3).
 * - 03:00 daily: drop raw sensor_readings partitions whose whole month predates
 *   NOW() - `app.retention.raw-days` (default 7). Keeps the current month + default.
 * - 04:00 daily: delete 5-min aggregates older than `app.retention.five-min-days`
 *   (default 90). The hourly table is kept forever (the permanent tier).
 * Windows are config-driven so a demo can tighten them (e.g. raw-days=2).
 */
@Component
public class RetentionJobs {

	private static final Logger log = LoggerFactory.getLogger(RetentionJobs.class);
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy_MM");
	private static final String PARTITION_PREFIX = "sensor_readings_";

	private final JdbcTemplate jdbc;
	private final int rawDays;
	private final int fiveMinDays;

	public RetentionJobs(JdbcTemplate jdbc,
			@Value("${app.retention.raw-days:7}") int rawDays,
			@Value("${app.retention.five-min-days:90}") int fiveMinDays) {
		this.jdbc = jdbc;
		this.rawDays = rawDays;
		this.fiveMinDays = fiveMinDays;
	}

	@Scheduled(cron = "0 0 3 * * *")
	public void purgeRawSensorData() {
		List<String> partitions = jdbc.queryForList(
				"SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
						+ "AND tablename ~ '^sensor_readings_[0-9]{4}_[0-9]{2}$'",
				String.class);
		LocalDate cutoff = LocalDate.now().minusDays(rawDays);
		int dropped = 0;
		for (String partition : partitions) {
			try {
				YearMonth ym = YearMonth.parse(
						partition.substring(PARTITION_PREFIX.length()), MONTH);
				LocalDate monthEnd = ym.plusMonths(1).atDay(1);   // exclusive upper bound
				if (!monthEnd.isAfter(cutoff)) {                  // whole month older than cutoff
					jdbc.execute("DROP TABLE IF EXISTS " + partition);
					dropped++;
				}
			} catch (Exception e) {
				log.warn("retention_drop_failed partition={}", partition, e);
			}
		}
		log.info("retention_raw_dropped count={} rawDays={}", dropped, rawDays);
	}

	@Scheduled(cron = "0 0 4 * * *")
	public void purge5MinData() {
		int deleted = jdbc.update(
				"DELETE FROM sensor_readings_5min WHERE window_start < NOW() - (? * INTERVAL '1 day')",
				fiveMinDays);
		log.info("retention_5min_deleted count={} fiveMinDays={}", deleted, fiveMinDays);
	}
}
