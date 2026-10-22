package com.health.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Manual sleep log. Timestamps are stored in UTC (TIMESTAMP, matching the rest
 * of the schema). `endTs` / `totalSleepMin` are null while a session is open.
 */
@Entity
@Table(name = "sleep_session")
public class SleepSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "start_ts", nullable = false)
	private Instant startTs;

	@Column(name = "end_ts")
	private Instant endTs;

	@Column(name = "total_sleep_min")
	private Integer totalSleepMin;

	@Column(name = "user_rating")
	private Integer userRating;

	/** NIGHT or NAP. Provisional while open; recomputed on close/edit. */
	@Column(nullable = false, length = 8)
	private String type = "NIGHT";

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void prePersist() {
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public void setUserId(UUID userId) {
		this.userId = userId;
	}

	public Instant getStartTs() {
		return startTs;
	}

	public void setStartTs(Instant startTs) {
		this.startTs = startTs;
	}

	public Instant getEndTs() {
		return endTs;
	}

	public void setEndTs(Instant endTs) {
		this.endTs = endTs;
	}

	public Integer getTotalSleepMin() {
		return totalSleepMin;
	}

	public void setTotalSleepMin(Integer totalSleepMin) {
		this.totalSleepMin = totalSleepMin;
	}

	public Integer getUserRating() {
		return userRating;
	}

	public void setUserRating(Integer userRating) {
		this.userRating = userRating;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
