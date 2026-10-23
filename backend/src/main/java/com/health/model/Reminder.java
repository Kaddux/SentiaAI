package com.health.model;

import java.time.Instant;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reminders")
public class Reminder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private java.util.UUID userId;

	@Column(nullable = false)
	private String title;

	private String description;

	@Column(name = "reminder_time", nullable = false)
	private LocalTime reminderTime;

	@Column(nullable = false)
	private String recurrence = "none";

	@Column(name = "days_of_week")
	private Integer[] daysOfWeek;

	@Column(name = "is_active")
	private boolean isActive = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "last_triggered")
	private Instant lastTriggered;

	@Column(name = "last_acknowledged")
	private Instant lastAcknowledged;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	public Long getId() {
		return id;
	}

	public java.util.UUID getUserId() {
		return userId;
	}

	public void setUserId(java.util.UUID userId) {
		this.userId = userId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public LocalTime getReminderTime() {
		return reminderTime;
	}

	public void setReminderTime(LocalTime reminderTime) {
		this.reminderTime = reminderTime;
	}

	public String getRecurrence() {
		return recurrence;
	}

	public void setRecurrence(String recurrence) {
		this.recurrence = recurrence;
	}

	public Integer[] getDaysOfWeek() {
		return daysOfWeek;
	}

	public void setDaysOfWeek(Integer[] daysOfWeek) {
		this.daysOfWeek = daysOfWeek;
	}

	public boolean isActive() {
		return isActive;
	}

	public void setActive(boolean active) {
		isActive = active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public void setDeletedAt(Instant deletedAt) {
		this.deletedAt = deletedAt;
	}

	public Instant getLastTriggered() {
		return lastTriggered;
	}

	public void setLastTriggered(Instant lastTriggered) {
		this.lastTriggered = lastTriggered;
	}

	public Instant getLastAcknowledged() {
		return lastAcknowledged;
	}

	public void setLastAcknowledged(Instant lastAcknowledged) {
		this.lastAcknowledged = lastAcknowledged;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
