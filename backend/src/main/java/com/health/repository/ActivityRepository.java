package com.health.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.health.model.ActivityRecord;

public interface ActivityRepository extends JpaRepository<ActivityRecord, Long> {

	@Query("""
			SELECT a FROM ActivityRecord a
			WHERE a.userId = :userId
			  AND a.startTime >= :from AND a.startTime < :to
			ORDER BY a.startTime DESC""")
	List<ActivityRecord> findForRange(@Param("userId") UUID userId,
			@Param("from") Instant from, @Param("to") Instant to);
}
