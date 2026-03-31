package com.systemdesign.meetingscheduler.approach_02_strategy_scheduling

import com.systemdesign.meetingscheduler.common.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 2: Strategy Pattern for Scheduling Algorithms
 *
 * Different scheduling strategies can be plugged in based on organizational needs.
 * Strategies handle slot selection, room allocation, and conflict resolution independently.
 *
 * Pattern: Strategy
 *
 * Trade-offs:
 * + Easy to add new scheduling algorithms without changing core logic
 * + Strategies can be swapped at runtime (e.g., switch from "first available" to "least conflicts")
 * + Each strategy is testable in isolation
 * - Linear scan for conflicts (O(n) per check)
 * - Strategy selection logic needed at the caller level
 * - Room allocation tightly coupled with slot selection in some strategies
 *
 * When to use:
 * - When multiple scheduling policies coexist (departments, user preferences)
 * - When scheduling algorithm should be configurable per user or org
 * - When you want to A/B test different scheduling heuristics
 *
 * Extensibility:
 * - New scheduling algorithm: Implement SchedulingStrategy interface
 * - New room allocation: Implement RoomAllocationStrategy interface
 * - Combined strategies: Use CompositeStrategy to chain behaviors
 */

interface SchedulingStrategy {
    val name: String

    fun findBestSlot(
        request: MeetingRequest,
        existingMeetings: List<Meeting>,
        availableRooms: List<Room>
    ): SlotSuggestion?
}

data class MeetingRequest(
    val title: String,
    val organizer: User,
    val attendees: Set<User>,
    val duration: Duration,
    val preferredStart: LocalDateTime,
    val preferredEnd: LocalDateTime,
    val requiredCapacity: Int = attendees.size + 1,
    val requiredAmenities: Set<String> = emptySet(),
    val description: String = ""
)

data class SlotSuggestion(
    val timeSlot: TimeSlot,
    val room: Room?,
    val score: Int,
    val reason: String
)

class FirstAvailableSlotStrategy : SchedulingStrategy {
    override val name = "First Available Slot"

    override fun findBestSlot(
        request: MeetingRequest,
        existingMeetings: List<Meeting>,
        availableRooms: List<Room>
    ): SlotSuggestion? {
        val candidateSlots = generateCandidateSlots(
            request.preferredStart, request.preferredEnd, request.duration
        )

        for (slot in candidateSlots) {
            val participantIds = request.attendees.map { it.id }.toSet() + request.organizer.id
            val hasConflict = existingMeetings.any { meeting ->
                meeting.isActive() &&
                    meeting.timeSlot.overlaps(slot) &&
                    meeting.allParticipantIds().any { it in participantIds }
            }
            if (hasConflict) continue

            val room = findAvailableRoom(slot, request, existingMeetings, availableRooms)
            if (room != null || availableRooms.isEmpty()) {
                return SlotSuggestion(slot, room, 100, "First available slot found")
            }
        }
        return null
    }
}

class ShortestDurationStrategy : SchedulingStrategy {
    override val name = "Shortest Duration"

    override fun findBestSlot(
        request: MeetingRequest,
        existingMeetings: List<Meeting>,
        availableRooms: List<Room>
    ): SlotSuggestion? {
        val participantIds = request.attendees.map { it.id }.toSet() + request.organizer.id
        val relevantMeetings = existingMeetings.filter { m ->
            m.isActive() && m.allParticipantIds().any { it in participantIds }
        }.sortedBy { it.timeSlot.start }

        val gaps = findGaps(request.preferredStart, request.preferredEnd, relevantMeetings)
        val fittingGaps = gaps.filter { it.duration >= request.duration }

        val bestGap = fittingGaps.minByOrNull { it.duration } ?: return null
        val slot = TimeSlot(bestGap.start, bestGap.start.plus(request.duration))
        val room = findAvailableRoom(slot, request, existingMeetings, availableRooms)

        if (room == null && availableRooms.isNotEmpty()) return null

        val score = 100 - (bestGap.duration.toMinutes() - request.duration.toMinutes()).toInt().coerceAtMost(50)
        return SlotSuggestion(slot, room, score, "Fits tightest available gap (${bestGap.duration.toMinutes()}min)")
    }
}

class LeastConflictsStrategy : SchedulingStrategy {
    override val name = "Least Conflicts"

    override fun findBestSlot(
        request: MeetingRequest,
        existingMeetings: List<Meeting>,
        availableRooms: List<Room>
    ): SlotSuggestion? {
        val candidateSlots = generateCandidateSlots(
            request.preferredStart, request.preferredEnd, request.duration
        )

        val scored = candidateSlots.mapNotNull { slot ->
            val allParticipantIds = request.attendees.map { it.id }.toSet() + request.organizer.id
            val directConflicts = existingMeetings.count { m ->
                m.isActive() && m.timeSlot.overlaps(slot) &&
                    m.allParticipantIds().any { it in allParticipantIds }
            }

            if (directConflicts > 0) return@mapNotNull null

            val adjacentPressure = existingMeetings.count { m ->
                m.isActive() && m.allParticipantIds().any { it in allParticipantIds } &&
                    (m.timeSlot.end == slot.start || m.timeSlot.start == slot.end ||
                        Duration.between(m.timeSlot.end, slot.start).abs() <= Duration.ofMinutes(15) ||
                        Duration.between(slot.end, m.timeSlot.start).abs() <= Duration.ofMinutes(15))
            }

            val room = findAvailableRoom(slot, request, existingMeetings, availableRooms)
            if (room == null && availableRooms.isNotEmpty()) return@mapNotNull null

            val score = 100 - (adjacentPressure * 15)
            SlotSuggestion(slot, room, score, "Adjacent meetings: $adjacentPressure")
        }

        return scored.maxByOrNull { it.score }
    }
}

class PreferredTimeSlotStrategy(
    private val preferredHourStart: Int = 10,
    private val preferredHourEnd: Int = 12
) : SchedulingStrategy {
    override val name = "Preferred Time Slot"

    override fun findBestSlot(
        request: MeetingRequest,
        existingMeetings: List<Meeting>,
        availableRooms: List<Room>
    ): SlotSuggestion? {
        val candidateSlots = generateCandidateSlots(
            request.preferredStart, request.preferredEnd, request.duration
        )

        val scored = candidateSlots.mapNotNull { slot ->
            val participantIds = request.attendees.map { it.id }.toSet() + request.organizer.id
            val hasConflict = existingMeetings.any { m ->
                m.isActive() && m.timeSlot.overlaps(slot) &&
                    m.allParticipantIds().any { it in participantIds }
            }
            if (hasConflict) return@mapNotNull null

            val room = findAvailableRoom(slot, request, existingMeetings, availableRooms)
            if (room == null && availableRooms.isNotEmpty()) return@mapNotNull null

            val hour = slot.start.hour
            val inPreferred = hour in preferredHourStart until preferredHourEnd
            val distFromPreferred = if (inPreferred) 0
            else minOf(
                kotlin.math.abs(hour - preferredHourStart),
                kotlin.math.abs(hour - preferredHourEnd)
            )

            val score = if (inPreferred) 100 else maxOf(0, 80 - distFromPreferred * 10)
            SlotSuggestion(slot, room, score, if (inPreferred) "Within preferred hours" else "Outside preferred hours")
        }

        return scored.maxByOrNull { it.score }
    }
}

private fun generateCandidateSlots(
    rangeStart: LocalDateTime,
    rangeEnd: LocalDateTime,
    duration: Duration,
    stepMinutes: Long = 30
): List<TimeSlot> {
    val slots = mutableListOf<TimeSlot>()
    var cursor = rangeStart
    while (cursor.plus(duration) <= rangeEnd) {
        slots.add(TimeSlot(cursor, cursor.plus(duration)))
        cursor = cursor.plusMinutes(stepMinutes)
    }
    return slots
}

private fun findGaps(
    rangeStart: LocalDateTime,
    rangeEnd: LocalDateTime,
    sortedMeetings: List<Meeting>
): List<TimeSlot> {
    val gaps = mutableListOf<TimeSlot>()
    var cursor = rangeStart

    for (meeting in sortedMeetings) {
        val meetingStart = meeting.timeSlot.start.coerceAtLeast(rangeStart)
        val meetingEnd = meeting.timeSlot.end.coerceAtMost(rangeEnd)

        if (cursor.isBefore(meetingStart)) {
            gaps.add(TimeSlot(cursor, meetingStart))
        }
        if (meetingEnd.isAfter(cursor)) {
            cursor = meetingEnd
        }
    }

    if (cursor.isBefore(rangeEnd)) {
        gaps.add(TimeSlot(cursor, rangeEnd))
    }
    return gaps
}

private fun findAvailableRoom(
    slot: TimeSlot,
    request: MeetingRequest,
    existingMeetings: List<Meeting>,
    availableRooms: List<Room>
): Room? {
    return availableRooms
        .filter { room -> room.capacity >= request.requiredCapacity }
        .filter { room -> request.requiredAmenities.all { room.hasAmenity(it) } }
        .filter { room ->
            existingMeetings.none { m ->
                m.isActive() && m.room?.id == room.id && m.timeSlot.overlaps(slot)
            }
        }
        .minByOrNull { it.capacity }
}

class StrategyBasedMeetingScheduler(
    private var strategy: SchedulingStrategy = FirstAvailableSlotStrategy()
) {
    private val meetings = ConcurrentHashMap<String, Meeting>()
    private val rooms = mutableListOf<Room>()

    fun setStrategy(strategy: SchedulingStrategy) {
        this.strategy = strategy
    }

    fun getStrategyName(): String = strategy.name

    fun addRoom(room: Room) {
        rooms.add(room)
    }

    fun scheduleMeeting(request: MeetingRequest): ScheduleResult {
        if (request.duration < Duration.ofMinutes(5)) {
            return ScheduleResult.InvalidRequest("Meeting must be at least 5 minutes")
        }

        val suggestion = strategy.findBestSlot(request, meetings.values.toList(), rooms)
            ?: return ScheduleResult.NoRoomAvailable(request.requiredCapacity)

        val meeting = Meeting(
            id = UUID.randomUUID().toString(),
            title = request.title,
            organizer = request.organizer,
            attendees = request.attendees.toMutableSet(),
            room = suggestion.room,
            timeSlot = suggestion.timeSlot,
            description = request.description
        )

        meetings[meeting.id] = meeting
        return ScheduleResult.Success(meeting)
    }

    fun scheduleAtExactSlot(
        title: String,
        organizer: User,
        attendees: Set<User>,
        room: Room?,
        timeSlot: TimeSlot,
        description: String = ""
    ): ScheduleResult {
        val participantIds = attendees.map { it.id }.toSet() + organizer.id

        for (existing in meetings.values) {
            if (!existing.isActive() || !existing.timeSlot.overlaps(timeSlot)) continue
            val overlap = existing.allParticipantIds().firstOrNull { it in participantIds }
            if (overlap != null) {
                val conflictUser = if (overlap == organizer.id) organizer
                else attendees.first { it.id == overlap }
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
        return ScheduleResult.Success(meeting)
    }

    fun cancelMeeting(meetingId: String): Boolean {
        val meeting = meetings[meetingId] ?: return false
        if (!meeting.isActive()) return false
        meeting.status = MeetingStatus.CANCELLED
        return true
    }

    fun getMeeting(id: String): Meeting? = meetings[id]

    fun getMeetingsForUser(userId: String): List<Meeting> {
        return meetings.values
            .filter { it.isActive() && it.hasAttendee(userId) }
            .sortedBy { it.timeSlot.start }
    }

    fun getSuggestions(request: MeetingRequest, strategies: List<SchedulingStrategy>): List<SlotSuggestion> {
        val allMeetings = meetings.values.toList()
        return strategies.mapNotNull { s ->
            s.findBestSlot(request, allMeetings, rooms)
        }.sortedByDescending { it.score }
    }

    fun getActiveMeetingCount(): Int = meetings.values.count { it.isActive() }
}
