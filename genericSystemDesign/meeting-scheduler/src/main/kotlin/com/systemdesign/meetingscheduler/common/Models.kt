package com.systemdesign.meetingscheduler.common

import java.time.LocalDateTime
import java.time.Duration

/**
 * Core domain models for Meeting Scheduler System.
 *
 * Extensibility Points:
 * - New recurrence patterns: Add to RecurrenceRule enum and update expansion logic
 * - New meeting statuses: Add to MeetingStatus enum and update schedulers
 * - New conflict resolution: Extend ConflictResult sealed class
 * - New observer types: Implement CalendarObserver interface
 *
 * Breaking Changes Required For:
 * - Changing TimeSlot representation (epoch vs LocalDateTime)
 * - Multi-timezone support
 */

data class User(
    val id: String,
    val name: String,
    val email: String
)

data class Room(
    val id: String,
    val name: String,
    val capacity: Int,
    val amenities: Set<String> = emptySet()
) {
    fun hasAmenity(amenity: String): Boolean = amenities.contains(amenity.lowercase())
}

data class TimeSlot(
    val start: LocalDateTime,
    val end: LocalDateTime
) {
    init {
        require(!end.isBefore(start)) { "End time must be on or after start time" }
    }

    val duration: Duration get() = Duration.between(start, end)

    fun overlaps(other: TimeSlot): Boolean {
        return start.isBefore(other.end) && other.start.isBefore(end)
    }

    fun contains(dateTime: LocalDateTime): Boolean {
        return !dateTime.isBefore(start) && dateTime.isBefore(end)
    }

    fun adjacentTo(other: TimeSlot): Boolean {
        return end == other.start || other.end == start
    }
}

enum class MeetingStatus {
    SCHEDULED,
    CANCELLED,
    COMPLETED,
    RESCHEDULED
}

enum class RecurrenceRule {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY
}

data class Meeting(
    val id: String,
    val title: String,
    val organizer: User,
    val attendees: MutableSet<User>,
    val room: Room?,
    val timeSlot: TimeSlot,
    var status: MeetingStatus = MeetingStatus.SCHEDULED,
    val recurrence: RecurrenceRule = RecurrenceRule.NONE,
    val description: String = ""
) {
    fun isActive(): Boolean = status == MeetingStatus.SCHEDULED

    fun hasAttendee(userId: String): Boolean =
        organizer.id == userId || attendees.any { it.id == userId }

    fun allParticipantIds(): Set<String> =
        attendees.map { it.id }.toSet() + organizer.id
}

sealed class ConflictResult {
    data object NoConflict : ConflictResult()
    data class Conflict(val conflictingMeetings: List<Meeting>) : ConflictResult()
}

sealed class ScheduleResult {
    data class Success(val meeting: Meeting) : ScheduleResult()
    data class RoomConflict(val room: Room, val conflicting: List<Meeting>) : ScheduleResult()
    data class AttendeeConflict(val user: User, val conflicting: List<Meeting>) : ScheduleResult()
    data class NoRoomAvailable(val requiredCapacity: Int) : ScheduleResult()
    data class InvalidRequest(val reason: String) : ScheduleResult()
}

interface CalendarObserver {
    fun onMeetingScheduled(meeting: Meeting)
    fun onMeetingCancelled(meeting: Meeting, reason: String)
    fun onMeetingRescheduled(meeting: Meeting, oldSlot: TimeSlot, newSlot: TimeSlot)
    fun onAttendeeAdded(meeting: Meeting, attendee: User)
    fun onAttendeeRemoved(meeting: Meeting, attendee: User)
}
