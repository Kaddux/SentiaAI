package com.health.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.health.model.TemperatureRecord;

public interface TemperatureRepository extends JpaRepository<TemperatureRecord, Long> {

	@Query("""
			SELECT t FROM TemperatureRecord t
			WHERE t.userId = :userId AND t.timestamp >= :from AND t.timestamp < :to
			ORDER BY t.timestamp DESC""")
	List<TemperatureRecord> findForRange(@Param("userId") UUID userId,
			@Param("from") Instant from, @Param("to") Instant to);
}
