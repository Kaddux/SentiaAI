package com.health.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.health.model.HealthBaseline;

public interface BaselineRepository extends JpaRepository<HealthBaseline, Long> {

	@Query("""
			SELECT b FROM HealthBaseline b
			WHERE b.userId = :userId AND b.isCurrent = true
			ORDER BY b.calculatedAt DESC""")
	Optional<HealthBaseline> findCurrentByUser(@Param("userId") UUID userId);

	@Query("""
			SELECT b FROM HealthBaseline b
			WHERE b.userId = :userId AND b.isCurrent = true
			ORDER BY b.calculatedAt LIMIT 1""")
	Optional<HealthBaseline> findCurrentSingle(@Param("userId") UUID userId);
}
