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
@Table(name = "sleep_records")
public class SleepRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "sleep_start", nullable = false)
	private Instant sleepStart;

	@Column(name = "sleep_end", nullable = false)
	private Instant sleepEnd;

	@Column(name = "duration_minutes", nullable = false)
	private Integer durationMinutes;

	// DEFERRED columns (uncomputable from wrist accel alone) — kept nullable.
	@Column(name = "quality_score")
	private BigDecimal qualityScore;

	@Column(name = "deep_sleep_min")
	private Integer deepSleepMin;

	@Column(name = "light_sleep_min")
	private Integer lightSleepMin;

	@Column(nullable = false)
	private Integer interruptions = 0;

	@Column(nullable = false)
	private String source = "estimated";

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

	public Instant getSleepStart() {
		return sleepStart;
	}

	public void setSleepStart(Instant sleepStart) {
		this.sleepStart = sleepStart;
	}

	public Instant getSleepEnd() {
		return sleepEnd;
	}

	public void setSleepEnd(Instant sleepEnd) {
		this.sleepEnd = sleepEnd;
	}

	public Integer getDurationMinutes() {
		return durationMinutes;
	}

	public void setDurationMinutes(Integer durationMinutes) {
		this.durationMinutes = durationMinutes;
	}

	public BigDecimal getQualityScore() {
		return qualityScore;
	}

	public void setQualityScore(BigDecimal qualityScore) {
		this.qualityScore = qualityScore;
	}

	public Integer getDeepSleepMin() {
		return deepSleepMin;
	}

	public void setDeepSleepMin(Integer deepSleepMin) {
		this.deepSleepMin = deepSleepMin;
	}

	public Integer getLightSleepMin() {
		return lightSleepMin;
	}

	public void setLightSleepMin(Integer lightSleepMin) {
		this.lightSleepMin = lightSleepMin;
	}

	public Integer getInterruptions() {
		return interruptions;
	}

	public void setInterruptions(Integer interruptions) {
		this.interruptions = interruptions;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
