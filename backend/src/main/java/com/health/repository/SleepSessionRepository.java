package com.health.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.health.model.SleepSession;

public interface SleepSessionRepository extends JpaRepository<SleepSession, Long> {

	Optional<SleepSession> findFirstByUserIdAndEndTsIsNullOrderByStartTsDesc(UUID userId);

	Optional<SleepSession> findFirstByUserIdAndTypeAndEndTsIsNotNullOrderByStartTsDesc(UUID userId, String type);

	Optional<SleepSession> findByIdAndUserId(Long id, UUID userId);

	List<SleepSession> findByUserIdAndStartTsGreaterThanEqualAndStartTsLessThanOrderByStartTsAsc(
			UUID userId, Instant from, Instant to);

	@Query("""
			SELECT s FROM SleepSession s
			WHERE s.userId = :userId AND s.endTs IS NOT NULL AND s.startTs >= :from
			ORDER BY s.startTs ASC""")
	List<SleepSession> findClosedSince(@Param("userId") UUID userId, @Param("from") Instant from);

	@Query("""
			SELECT s FROM SleepSession s
			WHERE s.userId = :userId
			  AND (:excludeId IS NULL OR s.id <> :excludeId)
			  AND s.startTs < :end
			  AND (s.endTs IS NULL OR s.endTs > :start)""")
	List<SleepSession> findOverlapping(@Param("userId") UUID userId, @Param("excludeId") Long excludeId,
			@Param("start") Instant start, @Param("end") Instant end);

	@Modifying
	@Query("DELETE FROM SleepSession s WHERE s.userId = :userId")
	int deleteByUserId(@Param("userId") UUID userId);
}
