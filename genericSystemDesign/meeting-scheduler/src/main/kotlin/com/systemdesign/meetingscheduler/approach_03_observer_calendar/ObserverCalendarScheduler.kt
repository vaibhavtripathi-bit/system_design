package com.systemdesign.meetingscheduler.approach_03_observer_calendar

import com.systemdesign.meetingscheduler.common.*
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Approach 3: Observer Pattern for Calendar Event Management
 *
 * The calendar acts as a subject that notifies observers (attendees, rooms,
 * notification services) of scheduling changes. Supports RSVP tracking,
 * reminders, and conflict notifications.
 *
 * Pattern: Observer / Publish-Subscribe
 *
 * Trade-offs:
 * + Loose coupling between scheduling logic and side effects
 * + Easy to add new notification channels, analytics, or integrations
 * + Observers can be added/removed at runtime
 * + Each observer is testable in isolation
 * - Potential for event ordering issues across observers
 * - Debugging harder with many observers reacting to the same event
 * - Memory leaks if observers not properly deregistered
 *
 * When to use:
 * - When multiple systems react to calendar changes (email, push, Slack, analytics)
 * - When RSVP and attendee management is a first-class concern
 * - When notification logic should be decoupled from scheduling logic
 *
 * Extensibility:
 * - New notification channel: Implement CalendarObserver
 * - New event type: Add method to CalendarObserver and notify in scheduler
 * - Async notifications: Wrap observer dispatch in coroutines
 */

abstract class BaseCalendarObserver : CalendarObserver {
    override fun onMeetingScheduled(meeting: Meeting) {}
    override fun onMeetingCancelled(meeting: Meeting, reason: String) {}
    override fun onMeetingRescheduled(meeting: Meeting, oldSlot: TimeSlot, newSlot: TimeSlot) {}
    override fun onAttendeeAdded(meeting: Meeting, attendee: User) {}
    override fun onAttendeeRemoved(meeting: Meeting, attendee: User) {}
}

data class Notification(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val channel: String,
    val recipient: String,
    val message: String,
    val meetingId: String,
    val timestamp: LocalDateTime = LocalDateTime.now()
)

enum class RsvpStatus { PENDING, ACCEPTED, DECLINED, TENTATIVE }

data class RsvpRecord(
    val userId: String,
    val meetingId: String,
    val status: RsvpStatus,
    val respondedAt: LocalDateTime = LocalDateTime.now()
)

class EmailCalendarNotifier : BaseCalendarObserver() {
    private val _sent = mutableListOf<Notification>()
    val sentNotifications: List<Notification> get() = _sent.toList()

    override fun onMeetingScheduled(meeting: Meeting) {
        val allParticipants = meeting.attendees + meeting.organizer
        for (user in allParticipants) {
            _sent.add(Notification(
                type = "Meeting Invitation",
                channel = "EMAIL",
                recipient = user.email,
                message = "You're invited to '${meeting.title}' on ${meeting.timeSlot.start}" +
                    (meeting.room?.let { " in ${it.name}" } ?: "") +
                    ". Organized by ${meeting.organizer.name}.",
                meetingId = meeting.id
            ))
        }
    }

    override fun onMeetingCancelled(meeting: Meeting, reason: String) {
        val allParticipants = meeting.attendees + meeting.organizer
        for (user in allParticipants) {
            _sent.add(Notification(
                type = "Meeting Cancelled",
                channel = "EMAIL",
                recipient = user.email,
                message = "'${meeting.title}' has been cancelled. Reason: $reason",
                meetingId = meeting.id
            ))
        }
    }

    override fun onMeetingRescheduled(meeting: Meeting, oldSlot: TimeSlot, newSlot: TimeSlot) {
        val allParticipants = meeting.attendees + meeting.organizer
        for (user in allParticipants) {
            _sent.add(Notification(
                type = "Meeting Rescheduled",
                channel = "EMAIL",
                recipient = user.email,
                message = "'${meeting.title}' moved from ${oldSlot.start} to ${newSlot.start}.",
                meetingId = meeting.id
            ))
        }
    }

    override fun onAttendeeAdded(meeting: Meeting, attendee: User) {
        _sent.add(Notification(
            type = "Added to Meeting",
            channel = "EMAIL",
            recipient = attendee.email,
            message = "You've been added to '${meeting.title}' on ${meeting.timeSlot.start}.",
            meetingId = meeting.id
        ))
    }

    override fun onAttendeeRemoved(meeting: Meeting, attendee: User) {
        _sent.add(Notification(
            type = "Removed from Meeting",
            channel = "EMAIL",
            recipient = attendee.email,
            message = "You've been removed from '${meeting.title}'.",
            meetingId = meeting.id
        ))
    }
}

class ConflictNotifier : BaseCalendarObserver() {
    private val _alerts = mutableListOf<ConflictAlert>()
    val alerts: List<ConflictAlert> get() = _alerts.toList()

    data class ConflictAlert(
        val userId: String,
        val meetingId: String,
        val conflictingMeetingId: String,
        val message: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    private val allMeetings = ConcurrentHashMap<String, Meeting>()

    override fun onMeetingScheduled(meeting: Meeting) {
        for (participantId in meeting.allParticipantIds()) {
            val conflicts = allMeetings.values.filter { existing ->
                existing.isActive() &&
                    existing.id != meeting.id &&
                    existing.timeSlot.overlaps(meeting.timeSlot) &&
                    existing.hasAttendee(participantId)
            }
            for (conflict in conflicts) {
                _alerts.add(ConflictAlert(
                    userId = participantId,
                    meetingId = meeting.id,
                    conflictingMeetingId = conflict.id,
                    message = "'${meeting.title}' overlaps with '${conflict.title}'"
                ))
            }
        }
        allMeetings[meeting.id] = meeting
    }

    override fun onMeetingCancelled(meeting: Meeting, reason: String) {
        allMeetings.remove(meeting.id)
    }

    override fun onMeetingRescheduled(meeting: Meeting, oldSlot: TimeSlot, newSlot: TimeSlot) {
        allMeetings[meeting.id] = meeting
    }
}

class RsvpTracker : BaseCalendarObserver() {
    private val rsvps = ConcurrentHashMap<String, MutableMap<String, RsvpRecord>>()

    override fun onMeetingScheduled(meeting: Meeting) {
        val meetingRsvps = ConcurrentHashMap<String, RsvpRecord>()
        for (attendee in meeting.attendees) {
            meetingRsvps[attendee.id] = RsvpRecord(
                userId = attendee.id,
                meetingId = meeting.id,
                status = RsvpStatus.PENDING
            )
        }
        meetingRsvps[meeting.organizer.id] = RsvpRecord(
            userId = meeting.organizer.id,
            meetingId = meeting.id,
            status = RsvpStatus.ACCEPTED
        )
        rsvps[meeting.id] = meetingRsvps
    }

    override fun onMeetingCancelled(meeting: Meeting, reason: String) {
        rsvps.remove(meeting.id)
    }

    override fun onAttendeeAdded(meeting: Meeting, attendee: User) {
        rsvps.getOrPut(meeting.id) { mutableMapOf() }[attendee.id] = RsvpRecord(
            userId = attendee.id,
            meetingId = meeting.id,
            status = RsvpStatus.PENDING
        )
    }

    override fun onAttendeeRemoved(meeting: Meeting, attendee: User) {
        rsvps[meeting.id]?.remove(attendee.id)
    }

    fun respond(meetingId: String, userId: String, status: RsvpStatus): Boolean {
        val meetingRsvps = rsvps[meetingId] ?: return false
        val existing = meetingRsvps[userId] ?: return false
        meetingRsvps[userId] = existing.copy(status = status, respondedAt = LocalDateTime.now())
        return true
    }

    fun getRsvpStatus(meetingId: String): Map<String, RsvpStatus> {
        return rsvps[meetingId]?.mapValues { it.value.status } ?: emptyMap()
    }

    fun getAcceptedCount(meetingId: String): Int {
        return rsvps[meetingId]?.values?.count { it.status == RsvpStatus.ACCEPTED } ?: 0
    }

    fun getDeclinedCount(meetingId: String): Int {
        return rsvps[meetingId]?.values?.count { it.status == RsvpStatus.DECLINED } ?: 0
    }

    fun getPendingCount(meetingId: String): Int {
        return rsvps[meetingId]?.values?.count { it.status == RsvpStatus.PENDING } ?: 0
    }
}

class RoomBookingTracker : BaseCalendarObserver() {
    private val bookings = ConcurrentHashMap<String, MutableList<Meeting>>()

    override fun onMeetingScheduled(meeting: Meeting) {
        val room = meeting.room ?: return
        bookings.getOrPut(room.id) { mutableListOf() }.add(meeting)
    }

    override fun onMeetingCancelled(meeting: Meeting, reason: String) {
        val room = meeting.room ?: return
        bookings[room.id]?.removeAll { it.id == meeting.id }
    }

    override fun onMeetingRescheduled(meeting: Meeting, oldSlot: TimeSlot, newSlot: TimeSlot) {
        val room = meeting.room ?: return
        bookings[room.id]?.removeAll { it.id == meeting.id }
        bookings.getOrPut(room.id) { mutableListOf() }.add(meeting)
    }

    fun getRoomSchedule(roomId: String): List<Meeting> {
        return bookings[roomId]?.filter { it.isActive() }?.sortedBy { it.timeSlot.start } ?: emptyList()
    }

    fun isRoomAvailable(roomId: String, slot: TimeSlot): Boolean {
        return bookings[roomId]?.none { it.isActive() && it.timeSlot.overlaps(slot) } ?: true
    }

    fun getRoomUtilization(roomId: String, from: LocalDateTime, to: LocalDateTime): Double {
        val totalMinutes = java.time.Duration.between(from, to).toMinutes().toDouble()
        if (totalMinutes <= 0) return 0.0
        val bookedMinutes = bookings[roomId]
            ?.filter { it.isActive() }
            ?.filter { it.timeSlot.start.isBefore(to) && it.timeSlot.end.isAfter(from) }
            ?.sumOf { meeting ->
                val effectiveStart = maxOf(meeting.timeSlot.start, from)
                val effectiveEnd = minOf(meeting.timeSlot.end, to)
                java.time.Duration.between(effectiveStart, effectiveEnd).toMinutes()
            } ?: 0L
        return (bookedMinutes / totalMinutes) * 100.0
    }

    private fun maxOf(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isAfter(b)) a else b
    private fun minOf(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isBefore(b)) a else b
}

class ObserverCalendarScheduler {
    private val observers = CopyOnWriteArrayList<CalendarObserver>()
    private val meetings = ConcurrentHashMap<String, Meeting>()
    private val rooms = ConcurrentHashMap<String, Room>()

    fun addObserver(observer: CalendarObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: CalendarObserver) {
        observers.remove(observer)
    }

    fun getObserverCount(): Int = observers.size

    fun addRoom(room: Room) {
        rooms[room.id] = room
    }

    fun scheduleMeeting(
        title: String,
        organizer: User,
        attendees: Set<User>,
        room: Room?,
        timeSlot: TimeSlot,
        description: String = ""
    ): ScheduleResult {
        if (room != null && room.capacity < attendees.size + 1) {
            return ScheduleResult.InvalidRequest(
                "Room ${room.name} capacity (${room.capacity}) insufficient"
            )
        }

        val participantIds = attendees.map { it.id }.toSet() + organizer.id
        for (existing in meetings.values) {
            if (!existing.isActive() || !existing.timeSlot.overlaps(timeSlot)) continue
            val conflictUserId = existing.allParticipantIds().firstOrNull { it in participantIds }
            if (conflictUserId != null) {
                val conflictUser = if (conflictUserId == organizer.id) organizer
                else attendees.first { it.id == conflictUserId }
                return ScheduleResult.AttendeeConflict(conflictUser, listOf(existing))
            }
        }

        if (room != null) {
            val roomConflicts = meetings.values.filter { m ->
                m.isActive() && m.room?.id == room.id && m.timeSlot.overlaps(timeSlot)
            }
            if (roomConflicts.isNotEmpty()) {
                return ScheduleResult.RoomConflict(room, roomConflicts)
            }
        }

        val meeting = Meeting(
            id = UUID.randomUUID().toString(),
            title = title,
            organizer = organizer,
            attendees = attendees.toMutableSet(),
            room = room,
            timeSlot = timeSlot,
            description = description
        )

        meetings[meeting.id] = meeting
        notifyObservers { it.onMeetingScheduled(meeting) }
        return ScheduleResult.Success(meeting)
    }

    fun cancelMeeting(meetingId: String, reason: String = "Cancelled"): Boolean {
        val meeting = meetings[meetingId] ?: return false
        if (!meeting.isActive()) return false
        meeting.status = MeetingStatus.CANCELLED
        notifyObservers { it.onMeetingCancelled(meeting, reason) }
        return true
    }

    fun rescheduleMeeting(meetingId: String, newSlot: TimeSlot): ScheduleResult {
        val meeting = meetings[meetingId]
            ?: return ScheduleResult.InvalidRequest("Meeting not found")
        if (!meeting.isActive()) return ScheduleResult.InvalidRequest("Meeting is not active")

        val participantIds = meeting.allParticipantIds()
        for (existing in meetings.values) {
            if (!existing.isActive() || existing.id == meetingId || !existing.timeSlot.overlaps(newSlot)) continue
            val conflictUserId = existing.allParticipantIds().firstOrNull { it in participantIds }
            if (conflictUserId != null) {
                val conflictUser = if (conflictUserId == meeting.organizer.id) meeting.organizer
                else meeting.attendees.first { it.id == conflictUserId }
                return ScheduleResult.AttendeeConflict(conflictUser, listOf(existing))
            }
        }

        if (meeting.room != null) {
            val roomConflicts = meetings.values.filter { m ->
                m.isActive() && m.id != meetingId &&
                    m.room?.id == meeting.room.id && m.timeSlot.overlaps(newSlot)
            }
            if (roomConflicts.isNotEmpty()) {
                return ScheduleResult.RoomConflict(meeting.room, roomConflicts)
            }
        }

        val oldSlot = meeting.timeSlot
        val rescheduled = meeting.copy(timeSlot = newSlot, status = MeetingStatus.RESCHEDULED)
        meetings[meetingId] = rescheduled

        notifyObservers { it.onMeetingRescheduled(rescheduled, oldSlot, newSlot) }
        return ScheduleResult.Success(rescheduled)
    }

    fun addAttendee(meetingId: String, attendee: User): Boolean {
        val meeting = meetings[meetingId] ?: return false
        if (!meeting.isActive()) return false
        if (meeting.hasAttendee(attendee.id)) return false

        if (meeting.room != null && meeting.attendees.size + 2 > meeting.room.capacity) {
            return false
        }

        meeting.attendees.add(attendee)
        notifyObservers { it.onAttendeeAdded(meeting, attendee) }
        return true
    }

    fun removeAttendee(meetingId: String, attendeeId: String): Boolean {
        val meeting = meetings[meetingId] ?: return false
        if (!meeting.isActive()) return false
        if (meeting.organizer.id == attendeeId) return false

        val attendee = meeting.attendees.find { it.id == attendeeId } ?: return false
        meeting.attendees.remove(attendee)
        notifyObservers { it.onAttendeeRemoved(meeting, attendee) }
        return true
    }

    fun getMeeting(id: String): Meeting? = meetings[id]

    fun getActiveMeetings(): List<Meeting> {
        return meetings.values.filter { it.isActive() }.sortedBy { it.timeSlot.start }
    }

    fun getMeetingsForUser(userId: String): List<Meeting> {
        return meetings.values
            .filter { it.isActive() && it.hasAttendee(userId) }
            .sortedBy { it.timeSlot.start }
    }

    private fun notifyObservers(action: (CalendarObserver) -> Unit) {
        observers.forEach { observer ->
            try {
                action(observer)
            } catch (_: Exception) {
            }
        }
    }
}
