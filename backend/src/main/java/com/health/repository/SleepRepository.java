package com.health.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.health.model.SleepRecord;

public interface SleepRepository extends JpaRepository<SleepRecord, Long> {

	@Query("""
			SELECT s FROM SleepRecord s
			WHERE s.userId = :userId AND s.sleepStart >= :from AND s.sleepStart < :to
			ORDER BY s.sleepStart DESC""")
	List<SleepRecord> findForRange(@Param("userId") UUID userId,
			@Param("from") Instant from, @Param("to") Instant to);

	@Query("""
			SELECT s FROM SleepRecord s
			WHERE s.userId = :userId AND s.sleepStart >= :from AND s.sleepStart < :to
			ORDER BY s.sleepStart DESC LIMIT 1""")
	Optional<SleepRecord> findLatestForRange(@Param("userId") UUID userId,
			@Param("from") Instant from, @Param("to") Instant to);
}
