package com.health.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.health.model.HealthAssessment;

public interface AssessmentRepository extends JpaRepository<HealthAssessment, Long> {

	List<HealthAssessment> findByUserIdAndAssessmentDate(UUID userId, LocalDate date);

	@Query("""
			SELECT a FROM HealthAssessment a
			WHERE a.userId = :userId AND a.assessmentType = :type
			ORDER BY a.assessmentDate DESC LIMIT 1""")
	Optional<HealthAssessment> findLatest(@Param("userId") UUID userId, @Param("type") String type);
}
