package com.health.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.health.model.Reminder;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

	@Query("""
			SELECT r FROM Reminder r
			WHERE r.userId = :userId AND r.deletedAt IS NULL AND r.isActive = true
			ORDER BY r.reminderTime ASC""")
	List<Reminder> findActiveByUser(@Param("userId") UUID userId);

	@Query("""
			SELECT r FROM Reminder r
			WHERE r.id = :id AND r.userId = :userId AND r.deletedAt IS NULL""")
	java.util.Optional<Reminder> findActiveByIdAndUser(@Param("id") Long id, @Param("userId") UUID userId);

	@Query("""
			SELECT r FROM Reminder r
			WHERE r.deletedAt IS NULL AND r.isActive = true""")
	List<Reminder> findAllActive();

	@Query("""
			SELECT r FROM Reminder r
			WHERE r.userId = :userId AND r.deletedAt IS NULL
			  AND r.lastTriggered IS NOT NULL
			  AND r.lastTriggered >= :since
			  AND (r.lastAcknowledged IS NULL OR r.lastAcknowledged < r.lastTriggered)
			ORDER BY r.lastTriggered DESC""")
	List<Reminder> findDueForUser(@Param("userId") UUID userId, @Param("since") Instant since);
}
