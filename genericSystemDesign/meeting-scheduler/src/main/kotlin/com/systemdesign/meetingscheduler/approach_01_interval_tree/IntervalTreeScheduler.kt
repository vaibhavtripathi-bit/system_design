package com.systemdesign.meetingscheduler.approach_01_interval_tree

import com.systemdesign.meetingscheduler.common.*
import java.time.LocalDateTime
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 1: Interval Tree for Efficient Overlap Detection
 *
 * Uses an augmented interval tree data structure to detect meeting conflicts
 * in O(log n + k) time where k is the number of overlapping meetings.
 * Supports finding free slots across multiple attendees and rooms.
 *
 * Pattern: Interval Tree (augmented BST)
 *
 * Trade-offs:
 * + O(log n + k) conflict detection vs O(n) for linear scan
 * + Efficient free-slot queries across large calendars
 * + Natural representation for time-based scheduling
 * - More complex insertion/deletion than a simple list
 * - Rebalancing overhead on mutations
 * - Higher memory footprint per node (augmented max field)
 *
 * When to use:
 * - When calendars have many meetings and conflict checks are frequent
 * - When free-slot finding across multiple attendees is a core feature
 * - When scheduling performance matters more than implementation simplicity
 *
 * Extensibility:
 * - New query types: Add traversal methods to IntervalTree
 * - Multi-room scheduling: Maintain per-room trees
 * - Recurring meetings: Expand recurrence into individual interval nodes
 */

private class IntervalNode(
    val meeting: Meeting,
    val low: LocalDateTime,
    val high: LocalDateTime
) {
    var left: IntervalNode? = null
    var right: IntervalNode? = null
    var maxEnd: LocalDateTime = high
    var height: Int = 1
}

class IntervalTree {
    private var root: IntervalNode? = null
    private var size: Int = 0

    fun size(): Int = size

    fun insert(meeting: Meeting) {
        root = insert(root, meeting)
        size++
    }

    fun remove(meetingId: String): Boolean {
        val originalSize = size
        root = remove(root, meetingId)
        return size < originalSize
    }

    fun findOverlapping(slot: TimeSlot): List<Meeting> {
        val results = mutableListOf<Meeting>()
        findOverlapping(root, slot.start, slot.end, results)
        return results
    }

    fun findAllInRange(start: LocalDateTime, end: LocalDateTime): List<Meeting> {
        val results = mutableListOf<Meeting>()
        findOverlapping(root, start, end, results)
        return results
    }

    fun allMeetings(): List<Meeting> {
        val results = mutableListOf<Meeting>()
        inorder(root, results)
        return results
    }

    private fun inorder(node: IntervalNode?, results: MutableList<Meeting>) {
        if (node == null) return
        inorder(node.left, results)
        results.add(node.meeting)
        inorder(node.right, results)
    }

    private fun insert(node: IntervalNode?, meeting: Meeting): IntervalNode {
        if (node == null) return IntervalNode(meeting, meeting.timeSlot.start, meeting.timeSlot.end)

        if (meeting.timeSlot.start < node.low ||
            (meeting.timeSlot.start == node.low && meeting.id < node.meeting.id)
        ) {
            node.left = insert(node.left, meeting)
        } else {
            node.right = insert(node.right, meeting)
        }

        updateNode(node)
        return balance(node)
    }

    private fun remove(node: IntervalNode?, meetingId: String): IntervalNode? {
        if (node == null) return null

        if (node.meeting.id == meetingId) {
            size--
            if (node.left == null) return node.right
            if (node.right == null) return node.left

            val successor = findMin(node.right!!)
            val newRight = remove(node.right, successor.meeting.id)
            size++
            val replacement = IntervalNode(successor.meeting, successor.low, successor.high)
            replacement.left = node.left
            replacement.right = newRight
            updateNode(replacement)
            return balance(replacement)
        }

        node.left = remove(node.left, meetingId)
        node.right = remove(node.right, meetingId)
        updateNode(node)
        return balance(node)
    }

    private fun findMin(node: IntervalNode): IntervalNode {
        var current = node
        while (current.left != null) current = current.left!!
        return current
    }

    private fun findOverlapping(
        node: IntervalNode?,
        low: LocalDateTime,
        high: LocalDateTime,
        results: MutableList<Meeting>
    ) {
        if (node == null) return
        if (node.left != null && node.left!!.maxEnd.isAfter(low)) {
            findOverlapping(node.left, low, high, results)
        }
        if (node.low.isBefore(high) && low.isBefore(node.high) && node.meeting.isActive()) {
            results.add(node.meeting)
        }
        if (node.right != null && node.low.isBefore(high)) {
            findOverlapping(node.right, low, high, results)
        }
    }

    private fun height(node: IntervalNode?): Int = node?.height ?: 0

    private fun balanceFactor(node: IntervalNode): Int = height(node.left) - height(node.right)

    private fun updateNode(node: IntervalNode) {
        node.height = 1 + maxOf(height(node.left), height(node.right))
        node.maxEnd = maxOf(
            node.high,
            node.left?.maxEnd ?: node.high,
            node.right?.maxEnd ?: node.high
        )
    }

    private fun balance(node: IntervalNode): IntervalNode {
        val bf = balanceFactor(node)
        if (bf > 1) {
            if (balanceFactor(node.left!!) < 0) {
                node.left = rotateLeft(node.left!!)
            }
            return rotateRight(node)
        }
        if (bf < -1) {
            if (balanceFactor(node.right!!) > 0) {
                node.right = rotateRight(node.right!!)
            }
            return rotateLeft(node)
        }
        return node
    }

    private fun rotateLeft(node: IntervalNode): IntervalNode {
        val newRoot = node.right!!
        node.right = newRoot.left
        newRoot.left = node
        updateNode(node)
        updateNode(newRoot)
        return newRoot
    }

    private fun rotateRight(node: IntervalNode): IntervalNode {
        val newRoot = node.left!!
        node.left = newRoot.right
        newRoot.right = node
        updateNode(node)
        updateNode(newRoot)
        return newRoot
    }

    private fun maxOf(a: LocalDateTime, b: LocalDateTime, c: LocalDateTime): LocalDateTime {
        var max = a
        if (b.isAfter(max)) max = b
        if (c.isAfter(max)) max = c
        return max
    }
}

class IntervalTreeScheduler {
    private val userTrees = ConcurrentHashMap<String, IntervalTree>()
    private val roomTrees = ConcurrentHashMap<String, IntervalTree>()
    private val meetings = ConcurrentHashMap<String, Meeting>()
    private val rooms = ConcurrentHashMap<String, Room>()

    fun addRoom(room: Room) {
        rooms[room.id] = room
    }

    fun scheduleMeeting(
        title: String,
        organizer: User,
        attendees: Set<User>,
        room: Room?,
        timeSlot: TimeSlot,
        recurrence: RecurrenceRule = RecurrenceRule.NONE,
        description: String = ""
    ): ScheduleResult {
        if (timeSlot.duration < Duration.ofMinutes(5)) {
            return ScheduleResult.InvalidRequest("Meeting must be at least 5 minutes")
        }

        if (room != null && room.capacity < attendees.size + 1) {
            return ScheduleResult.InvalidRequest(
                "Room ${room.name} capacity (${room.capacity}) insufficient for ${attendees.size + 1} participants"
            )
        }

        val allParticipants = attendees + organizer
        for (participant in allParticipants) {
            val tree = userTrees[participant.id]
            if (tree != null) {
                val conflicts = tree.findOverlapping(timeSlot)
                if (conflicts.isNotEmpty()) {
                    return ScheduleResult.AttendeeConflict(participant, conflicts)
                }
            }
        }

        if (room != null) {
            val roomTree = roomTrees[room.id]
            if (roomTree != null) {
                val conflicts = roomTree.findOverlapping(timeSlot)
                if (conflicts.isNotEmpty()) {
                    return ScheduleResult.RoomConflict(room, conflicts)
                }
            }
        }

        val slotsToSchedule = expandRecurrence(timeSlot, recurrence)

        val scheduledMeetings = mutableListOf<Meeting>()
        for (slot in slotsToSchedule) {
            val meeting = Meeting(
                id = UUID.randomUUID().toString(),
                title = title,
                organizer = organizer,
                attendees = attendees.toMutableSet(),
                room = room,
                timeSlot = slot,
                recurrence = if (slotsToSchedule.size > 1) recurrence else RecurrenceRule.NONE,
                description = description
            )

            meetings[meeting.id] = meeting
            for (participant in allParticipants) {
                userTrees.getOrPut(participant.id) { IntervalTree() }.insert(meeting)
            }
            if (room != null) {
                roomTrees.getOrPut(room.id) { IntervalTree() }.insert(meeting)
            }
            scheduledMeetings.add(meeting)
        }

        return ScheduleResult.Success(scheduledMeetings.first())
    }

    fun cancelMeeting(meetingId: String, reason: String = "Cancelled"): Boolean {
        val meeting = meetings[meetingId] ?: return false
        if (!meeting.isActive()) return false

        meeting.status = MeetingStatus.CANCELLED
        for (participantId in meeting.allParticipantIds()) {
            userTrees[participantId]?.remove(meetingId)
        }
        if (meeting.room != null) {
            roomTrees[meeting.room.id]?.remove(meetingId)
        }
        return true
    }

    fun rescheduleMeeting(meetingId: String, newSlot: TimeSlot): ScheduleResult {
        val meeting = meetings[meetingId] ?: return ScheduleResult.InvalidRequest("Meeting not found")
        if (!meeting.isActive()) return ScheduleResult.InvalidRequest("Meeting is not active")

        val allParticipants = meeting.attendees + meeting.organizer
        for (participant in allParticipants) {
            val tree = userTrees[participant.id]
            if (tree != null) {
                val conflicts = tree.findOverlapping(newSlot).filter { it.id != meetingId }
                if (conflicts.isNotEmpty()) {
                    return ScheduleResult.AttendeeConflict(participant, conflicts)
                }
            }
        }

        if (meeting.room != null) {
            val roomTree = roomTrees[meeting.room.id]
            if (roomTree != null) {
                val conflicts = roomTree.findOverlapping(newSlot).filter { it.id != meetingId }
                if (conflicts.isNotEmpty()) {
                    return ScheduleResult.RoomConflict(meeting.room, conflicts)
                }
            }
        }

        for (participantId in meeting.allParticipantIds()) {
            userTrees[participantId]?.remove(meetingId)
        }
        if (meeting.room != null) {
            roomTrees[meeting.room.id]?.remove(meetingId)
        }

        val rescheduled = meeting.copy(
            timeSlot = newSlot,
            status = MeetingStatus.RESCHEDULED
        )
        meetings[meetingId] = rescheduled

        for (participant in allParticipants) {
            userTrees.getOrPut(participant.id) { IntervalTree() }.insert(rescheduled)
        }
        if (rescheduled.room != null) {
            roomTrees.getOrPut(rescheduled.room.id) { IntervalTree() }.insert(rescheduled)
        }

        return ScheduleResult.Success(rescheduled)
    }

    fun checkConflicts(userId: String, timeSlot: TimeSlot): ConflictResult {
        val tree = userTrees[userId] ?: return ConflictResult.NoConflict
        val overlapping = tree.findOverlapping(timeSlot)
        return if (overlapping.isEmpty()) ConflictResult.NoConflict
        else ConflictResult.Conflict(overlapping)
    }

    fun findFreeSlots(
        userIds: Set<String>,
        date: LocalDateTime,
        duration: Duration,
        workdayStart: Int = 9,
        workdayEnd: Int = 17
    ): List<TimeSlot> {
        val dayStart = date.withHour(workdayStart).withMinute(0).withSecond(0)
        val dayEnd = date.withHour(workdayEnd).withMinute(0).withSecond(0)

        val allMeetings = userIds.flatMap { userId ->
            userTrees[userId]?.findAllInRange(dayStart, dayEnd) ?: emptyList()
        }.distinctBy { it.id }
            .filter { it.isActive() }
            .sortedBy { it.timeSlot.start }

        val freeSlots = mutableListOf<TimeSlot>()
        var cursor = dayStart

        for (meeting in allMeetings) {
            if (cursor.isBefore(meeting.timeSlot.start)) {
                val gap = TimeSlot(cursor, meeting.timeSlot.start)
                if (gap.duration >= duration) {
                    freeSlots.add(gap)
                }
            }
            if (meeting.timeSlot.end.isAfter(cursor)) {
                cursor = meeting.timeSlot.end
            }
        }

        if (cursor.isBefore(dayEnd)) {
            val gap = TimeSlot(cursor, dayEnd)
            if (gap.duration >= duration) {
                freeSlots.add(gap)
            }
        }

        return freeSlots
    }

    fun findAvailableRooms(timeSlot: TimeSlot, minCapacity: Int = 1): List<Room> {
        return rooms.values.filter { room ->
            room.capacity >= minCapacity &&
                (roomTrees[room.id]?.findOverlapping(timeSlot)?.isEmpty() ?: true)
        }.sortedBy { it.capacity }
    }

    fun getUserMeetings(userId: String, from: LocalDateTime, to: LocalDateTime): List<Meeting> {
        val tree = userTrees[userId] ?: return emptyList()
        return tree.findAllInRange(from, to).sortedBy { it.timeSlot.start }
    }

    fun getRoomSchedule(roomId: String, from: LocalDateTime, to: LocalDateTime): List<Meeting> {
        val tree = roomTrees[roomId] ?: return emptyList()
        return tree.findAllInRange(from, to).sortedBy { it.timeSlot.start }
    }

    fun getMeeting(id: String): Meeting? = meetings[id]

    fun getMeetingCount(): Int = meetings.values.count { it.isActive() }

    private fun expandRecurrence(
        baseSlot: TimeSlot,
        rule: RecurrenceRule,
        occurrences: Int = 4
    ): List<TimeSlot> {
        if (rule == RecurrenceRule.NONE) return listOf(baseSlot)

        return (0 until occurrences).map { i ->
            when (rule) {
                RecurrenceRule.DAILY -> TimeSlot(
                    baseSlot.start.plusDays(i.toLong()),
                    baseSlot.end.plusDays(i.toLong())
                )
                RecurrenceRule.WEEKLY -> TimeSlot(
                    baseSlot.start.plusWeeks(i.toLong()),
                    baseSlot.end.plusWeeks(i.toLong())
                )
                RecurrenceRule.MONTHLY -> TimeSlot(
                    baseSlot.start.plusMonths(i.toLong()),
                    baseSlot.end.plusMonths(i.toLong())
                )
                RecurrenceRule.NONE -> baseSlot
            }
        }
    }
}
