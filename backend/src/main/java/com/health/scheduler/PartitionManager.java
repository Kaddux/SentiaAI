package com.health.scheduler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the monthly partitions for sensor_readings (current month + 12 ahead)
 * at startup, so the partition table is always ready before the first insert.
 * Mirrors the DO $$ block in init.sql but runs at runtime too.
 *
 * Tolerant: partition DDL is PostgreSQL-specific. On a non-PostgreSQL DB (e.g.
 * the H2 test profile) creation is skipped with a warning rather than failing
 * context startup.
 */
@Component
public class PartitionManager {

	private static final Logger log = LoggerFactory.getLogger(PartitionManager.class);
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy_MM");

	private final JdbcTemplate jdbcTemplate;

	public PartitionManager(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void ensureMonthlyPartitions() {
		LocalDate now = LocalDate.now();
		for (int i = -1; i <= 12; i++) {
			LocalDate month = now.plusMonths(i);
			String partitionName = "sensor_readings_" + month.format(MONTH);
			LocalDate start = month.withDayOfMonth(1);
			LocalDate end = month.plusMonths(1).withDayOfMonth(1);
			String ddl = String.format("""
					CREATE TABLE IF NOT EXISTS %s
					PARTITION OF sensor_readings
					FOR VALUES FROM ('%s') TO ('%s')""",
					partitionName, start, end);
			try {
				jdbcTemplate.execute(ddl);
			} catch (Exception ex) {
				log.warn("Partition creation skipped (non-PostgreSQL DB?): {}", ex.getMessage());
				return;
			}
		}
	}
}
