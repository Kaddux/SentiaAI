package com.health.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "activity_records")
public class ActivityRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "start_time", nullable = false)
	private Instant startTime;

	@Column(name = "end_time", nullable = false)
	private Instant endTime;

	@Column(name = "activity_type")
	private String activityType;

	@Column(nullable = false)
	private Integer steps = 0;

	private BigDecimal confidence;

	@Column(name = "avg_accel_mag")
	private BigDecimal avgAccelMag;

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

	public Instant getStartTime() {
		return startTime;
	}

	public void setStartTime(Instant startTime) {
		this.startTime = startTime;
	}

	public Instant getEndTime() {
		return endTime;
	}

	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}

	public String getActivityType() {
		return activityType;
	}

	public void setActivityType(String activityType) {
		this.activityType = activityType;
	}

	public Integer getSteps() {
		return steps;
	}

	public void setSteps(Integer steps) {
		this.steps = steps;
	}

	public BigDecimal getConfidence() {
		return confidence;
	}

	public void setConfidence(BigDecimal confidence) {
		this.confidence = confidence;
	}

	public BigDecimal getAvgAccelMag() {
		return avgAccelMag;
	}

	public void setAvgAccelMag(BigDecimal avgAccelMag) {
		this.avgAccelMag = avgAccelMag;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
