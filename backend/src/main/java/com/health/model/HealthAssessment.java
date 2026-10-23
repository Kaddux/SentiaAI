package com.health.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "health_assessments")
public class HealthAssessment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "assessment_type", nullable = false)
	private String assessmentType;

	@Column(name = "assessed_at", insertable = false, updatable = false)
	private Instant assessedAt;

	@Column(name = "assessment_date", nullable = false)
	private LocalDate assessmentDate;

	@Column(name = "wellness_score")
	private BigDecimal wellnessScore;

	@Column(name = "stress_level")
	private String stressLevel;

	private BigDecimal confidence;

	@JdbcTypeCode(SqlTypes.JSON)
	private String factors;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_output")
	private String rawOutput;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	public Long getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public void setUserId(UUID userId) {
		this.userId = userId;
	}

	public String getAssessmentType() {
		return assessmentType;
	}

	public void setAssessmentType(String assessmentType) {
		this.assessmentType = assessmentType;
	}

	public Instant getAssessedAt() {
		return assessedAt;
	}

	public LocalDate getAssessmentDate() {
		return assessmentDate;
	}

	public void setAssessmentDate(LocalDate assessmentDate) {
		this.assessmentDate = assessmentDate;
	}

	public BigDecimal getWellnessScore() {
		return wellnessScore;
	}

	public void setWellnessScore(BigDecimal wellnessScore) {
		this.wellnessScore = wellnessScore;
	}

	public String getStressLevel() {
		return stressLevel;
	}

	public void setStressLevel(String stressLevel) {
		this.stressLevel = stressLevel;
	}

	public BigDecimal getConfidence() {
		return confidence;
	}

	public void setConfidence(BigDecimal confidence) {
		this.confidence = confidence;
	}

	public String getFactors() {
		return factors;
	}

	public void setFactors(String factors) {
		this.factors = factors;
	}

	public String getRawOutput() {
		return rawOutput;
	}

	public void setRawOutput(String rawOutput) {
		this.rawOutput = rawOutput;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
