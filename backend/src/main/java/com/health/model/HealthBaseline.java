package com.health.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "health_baselines")
public class HealthBaseline {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "calculated_at", insertable = false, updatable = false)
	private Instant calculatedAt;

	@Column(name = "is_current")
	private boolean isCurrent = true;

	@Column(name = "avg_sleep_hours")
	private BigDecimal avgSleepHours;

	@Column(name = "avg_daily_steps")
	private Integer avgDailySteps;

	@Column(name = "avg_heart_rate")
	private BigDecimal avgHeartRate;

	@Column(name = "avg_resting_heart_rate")
	private BigDecimal avgRestingHeartRate;

	@Column(name = "avg_active_minutes")
	private Integer avgActiveMinutes;

	@Column(name = "avg_sleep_quality")
	private BigDecimal avgSleepQuality;

	@Column(name = "avg_temperature_c")
	private BigDecimal avgTemperatureC;

	@Column(name = "std_sleep_hours")
	private BigDecimal stdSleepHours;

	@Column(name = "std_daily_steps")
	private Integer stdDailySteps;

	@Column(name = "std_heart_rate")
	private BigDecimal stdHeartRate;

	@Column(name = "std_resting_heart_rate")
	private BigDecimal stdRestingHeartRate;

	@Column(name = "std_active_minutes")
	private Integer stdActiveMinutes;

	@Column(name = "std_temperature_c")
	private BigDecimal stdTemperatureC;

	@Column(name = "typical_bedtime")
	private LocalTime typicalBedtime;

	@Column(name = "typical_wake_time")
	private LocalTime typicalWakeTime;

	@Column(name = "baseline_window_days")
	private Integer baselineWindowDays = 14;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public UUID getUserId() {
		return userId;
	}

	public void setUserId(UUID userId) {
		this.userId = userId;
	}

	public Instant getCalculatedAt() {
		return calculatedAt;
	}

	public boolean isCurrent() {
		return isCurrent;
	}

	public void setCurrent(boolean current) {
		isCurrent = current;
	}

	public BigDecimal getAvgSleepHours() {
		return avgSleepHours;
	}

	public void setAvgSleepHours(BigDecimal avgSleepHours) {
		this.avgSleepHours = avgSleepHours;
	}

	public Integer getAvgDailySteps() {
		return avgDailySteps;
	}

	public void setAvgDailySteps(Integer avgDailySteps) {
		this.avgDailySteps = avgDailySteps;
	}

	public BigDecimal getAvgHeartRate() {
		return avgHeartRate;
	}

	public void setAvgHeartRate(BigDecimal avgHeartRate) {
		this.avgHeartRate = avgHeartRate;
	}

	public BigDecimal getAvgRestingHeartRate() {
		return avgRestingHeartRate;
	}

	public void setAvgRestingHeartRate(BigDecimal avgRestingHeartRate) {
		this.avgRestingHeartRate = avgRestingHeartRate;
	}

	public Integer getAvgActiveMinutes() {
		return avgActiveMinutes;
	}

	public void setAvgActiveMinutes(Integer avgActiveMinutes) {
		this.avgActiveMinutes = avgActiveMinutes;
	}

	public BigDecimal getAvgSleepQuality() {
		return avgSleepQuality;
	}

	public void setAvgSleepQuality(BigDecimal avgSleepQuality) {
		this.avgSleepQuality = avgSleepQuality;
	}

	public BigDecimal getAvgTemperatureC() {
		return avgTemperatureC;
	}

	public void setAvgTemperatureC(BigDecimal avgTemperatureC) {
		this.avgTemperatureC = avgTemperatureC;
	}

	public BigDecimal getStdSleepHours() {
		return stdSleepHours;
	}

	public void setStdSleepHours(BigDecimal stdSleepHours) {
		this.stdSleepHours = stdSleepHours;
	}

	public Integer getStdDailySteps() {
		return stdDailySteps;
	}

	public void setStdDailySteps(Integer stdDailySteps) {
		this.stdDailySteps = stdDailySteps;
	}

	public BigDecimal getStdHeartRate() {
		return stdHeartRate;
	}

	public void setStdHeartRate(BigDecimal stdHeartRate) {
		this.stdHeartRate = stdHeartRate;
	}

	public BigDecimal getStdRestingHeartRate() {
		return stdRestingHeartRate;
	}

	public void setStdRestingHeartRate(BigDecimal stdRestingHeartRate) {
		this.stdRestingHeartRate = stdRestingHeartRate;
	}

	public Integer getStdActiveMinutes() {
		return stdActiveMinutes;
	}

	public void setStdActiveMinutes(Integer stdActiveMinutes) {
		this.stdActiveMinutes = stdActiveMinutes;
	}

	public BigDecimal getStdTemperatureC() {
		return stdTemperatureC;
	}

	public void setStdTemperatureC(BigDecimal stdTemperatureC) {
		this.stdTemperatureC = stdTemperatureC;
	}

	public LocalTime getTypicalBedtime() {
		return typicalBedtime;
	}

	public void setTypicalBedtime(LocalTime typicalBedtime) {
		this.typicalBedtime = typicalBedtime;
	}

	public LocalTime getTypicalWakeTime() {
		return typicalWakeTime;
	}

	public void setTypicalWakeTime(LocalTime typicalWakeTime) {
		this.typicalWakeTime = typicalWakeTime;
	}

	public Integer getBaselineWindowDays() {
		return baselineWindowDays;
	}

	public void setBaselineWindowDays(Integer baselineWindowDays) {
		this.baselineWindowDays = baselineWindowDays;
	}
}
