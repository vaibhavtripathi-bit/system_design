package com.systemdesign.meetingscheduler

import com.systemdesign.meetingscheduler.common.*
import com.systemdesign.meetingscheduler.approach_01_interval_tree.*
import com.systemdesign.meetingscheduler.approach_02_strategy_scheduling.*
import com.systemdesign.meetingscheduler.approach_03_observer_calendar.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.Duration
import java.time.LocalDateTime

class MeetingSchedulerTest {

    private val baseTime = LocalDateTime.of(2026, 4, 1, 9, 0)

    private fun user(id: String, name: String = "User $id") =
        User(id, name, "$id@example.com")

    private fun room(id: String, capacity: Int, vararg amenities: String) =
        Room(id, "Room $id", capacity, amenities.toSet())

    private fun slot(hourStart: Int, hourEnd: Int) =
        TimeSlot(baseTime.withHour(hourStart), baseTime.withHour(hourEnd))

    private fun slot(start: LocalDateTime, end: LocalDateTime) = TimeSlot(start, end)

    @Nested
    inner class ModelsTest {

        @Test
        fun `TimeSlot detects overlapping slots`() {
            val slot1 = slot(9, 10)
            val slot2 = slot(9, 11)
            val slot3 = slot(11, 12)

            assertTrue(slot1.overlaps(slot2))
            assertFalse(slot1.overlaps(slot3))
        }

        @Test
        fun `TimeSlot rejects invalid range`() {
            assertThrows(IllegalArgumentException::class.java) {
                TimeSlot(baseTime.withHour(10), baseTime.withHour(9))
            }
        }

        @Test
        fun `TimeSlot calculates duration`() {
            val ts = slot(9, 11)
            assertEquals(Duration.ofHours(2), ts.duration)
        }

        @Test
        fun `TimeSlot contains checks point in time`() {
            val ts = slot(9, 11)
            assertTrue(ts.contains(baseTime.withHour(10)))
            assertFalse(ts.contains(baseTime.withHour(11)))
            assertFalse(ts.contains(baseTime.withHour(8)))
        }

        @Test
        fun `TimeSlot adjacentTo detects back-to-back slots`() {
            val ts1 = slot(9, 10)
            val ts2 = slot(10, 11)
            assertTrue(ts1.adjacentTo(ts2))
            assertFalse(ts1.adjacentTo(slot(11, 12)))
        }

        @Test
        fun `Meeting tracks participants correctly`() {
            val organizer = user("org")
            val attendee = user("att")
            val meeting = Meeting(
                id = "m1", title = "Test", organizer = organizer,
                attendees = mutableSetOf(attendee), room = null, timeSlot = slot(9, 10)
            )

            assertTrue(meeting.hasAttendee("org"))
            assertTrue(meeting.hasAttendee("att"))
            assertFalse(meeting.hasAttendee("other"))
            assertEquals(setOf("org", "att"), meeting.allParticipantIds())
        }

        @Test
        fun `Room checks amenities case-insensitively`() {
            val r = room("R1", 10, "whiteboard", "projector")
            assertTrue(r.hasAmenity("Whiteboard"))
            assertFalse(r.hasAmenity("tv"))
        }
    }

    @Nested
    inner class IntervalTreeSchedulerTest {

        private lateinit var scheduler: IntervalTreeScheduler
        private val alice = user("alice", "Alice")
        private val bob = user("bob", "Bob")
        private val carol = user("carol", "Carol")
        private val roomA = room("A", 10, "whiteboard")
        private val roomB = room("B", 4)

        @BeforeEach
        fun setup() {
            scheduler = IntervalTreeScheduler()
            scheduler.addRoom(roomA)
            scheduler.addRoom(roomB)
        }

        @Test
        fun `schedules a meeting successfully`() {
            val result = scheduler.scheduleMeeting(
                title = "Standup",
                organizer = alice,
                attendees = setOf(bob),
                room = roomA,
                timeSlot = slot(9, 10)
            )
            assertTrue(result is ScheduleResult.Success)
            val meeting = (result as ScheduleResult.Success).meeting
            assertEquals("Standup", meeting.title)
            assertEquals(MeetingStatus.SCHEDULED, meeting.status)
        }

        @Test
        fun `detects attendee conflict`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val result = scheduler.scheduleMeeting("M2", carol, setOf(bob), roomB, slot(9, 10))

            assertTrue(result is ScheduleResult.AttendeeConflict)
            assertEquals("bob", (result as ScheduleResult.AttendeeConflict).user.id)
        }

        @Test
        fun `detects room conflict`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val result = scheduler.scheduleMeeting("M2", carol, emptySet(), roomA, slot(9, 10))

            assertTrue(result is ScheduleResult.RoomConflict)
        }

        @Test
        fun `allows non-overlapping meetings for same user`() {
            val r1 = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val r2 = scheduler.scheduleMeeting("M2", alice, setOf(bob), roomA, slot(10, 11))

            assertTrue(r1 is ScheduleResult.Success)
            assertTrue(r2 is ScheduleResult.Success)
        }

        @Test
        fun `cancels a meeting and frees the slot`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertTrue(scheduler.cancelMeeting(meetingId))
            assertFalse(scheduler.getMeeting(meetingId)!!.isActive())

            val r2 = scheduler.scheduleMeeting("M2", carol, emptySet(), roomA, slot(9, 10))
            assertTrue(r2 is ScheduleResult.Success)
        }

        @Test
        fun `reschedules a meeting`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            val rescheduleResult = scheduler.rescheduleMeeting(meetingId, slot(14, 15))
            assertTrue(rescheduleResult is ScheduleResult.Success)
            assertEquals(MeetingStatus.RESCHEDULED, (rescheduleResult as ScheduleResult.Success).meeting.status)
        }

        @Test
        fun `reschedule fails on conflict`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val r2 = scheduler.scheduleMeeting("M2", alice, setOf(carol), roomB, slot(14, 15))
            val m1Id = scheduler.getUserMeetings("alice", baseTime, baseTime.plusHours(12))
                .first { it.title == "M1" }.id

            val result = scheduler.rescheduleMeeting(m1Id, slot(14, 15))
            assertTrue(result is ScheduleResult.AttendeeConflict)
        }

        @Test
        fun `finds free slots across multiple users`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleMeeting("M2", alice, setOf(carol), roomB, slot(11, 12))

            val freeSlots = scheduler.findFreeSlots(
                setOf("alice", "bob", "carol"),
                baseTime,
                Duration.ofMinutes(30)
            )

            assertTrue(freeSlots.isNotEmpty())
            assertTrue(freeSlots.all { it.duration >= Duration.ofMinutes(30) })
            assertTrue(freeSlots.none { it.overlaps(slot(9, 10)) })
        }

        @Test
        fun `finds available rooms for a time slot`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))

            val available = scheduler.findAvailableRooms(slot(9, 10))
            assertEquals(1, available.size)
            assertEquals("B", available.first().id)
        }

        @Test
        fun `finds available rooms with capacity filter`() {
            val available = scheduler.findAvailableRooms(slot(9, 10), minCapacity = 8)
            assertEquals(1, available.size)
            assertEquals("A", available.first().id)
        }

        @Test
        fun `rejects meeting shorter than 5 minutes`() {
            val result = scheduler.scheduleMeeting(
                "Quick", alice, setOf(bob), roomA,
                TimeSlot(baseTime, baseTime.plusMinutes(3))
            )
            assertTrue(result is ScheduleResult.InvalidRequest)
        }

        @Test
        fun `rejects meeting when room capacity insufficient`() {
            val tinyRoom = room("tiny", 2)
            scheduler.addRoom(tinyRoom)
            val result = scheduler.scheduleMeeting(
                "Big Meeting", alice, setOf(bob, carol), tinyRoom, slot(9, 10)
            )
            assertTrue(result is ScheduleResult.InvalidRequest)
        }

        @Test
        fun `schedules recurring daily meetings`() {
            val result = scheduler.scheduleMeeting(
                "Daily Standup", alice, setOf(bob), roomA, slot(9, 10),
                recurrence = RecurrenceRule.DAILY
            )
            assertTrue(result is ScheduleResult.Success)

            val meetings = scheduler.getUserMeetings(
                "alice", baseTime, baseTime.plusDays(5)
            )
            assertTrue(meetings.size >= 4)
        }

        @Test
        fun `getUserMeetings returns sorted meetings`() {
            scheduler.scheduleMeeting("M2", alice, setOf(bob), roomA, slot(14, 15))
            scheduler.scheduleMeeting("M1", alice, setOf(carol), roomB, slot(9, 10))

            val meetings = scheduler.getUserMeetings("alice", baseTime, baseTime.plusHours(12))
            assertEquals("M1", meetings.first().title)
            assertEquals("M2", meetings.last().title)
        }

        @Test
        fun `getRoomSchedule returns meetings for a room`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleMeeting("M2", carol, emptySet(), roomA, slot(11, 12))
            scheduler.scheduleMeeting("M3", alice, setOf(carol), roomB, slot(9, 10))

            val schedule = scheduler.getRoomSchedule("A", baseTime, baseTime.plusHours(12))
            assertEquals(2, schedule.size)
        }

        @Test
        fun `checkConflicts returns NoConflict for free user`() {
            val result = scheduler.checkConflicts("alice", slot(9, 10))
            assertTrue(result is ConflictResult.NoConflict)
        }

        @Test
        fun `checkConflicts returns Conflict for busy user`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val result = scheduler.checkConflicts("alice", slot(9, 10))
            assertTrue(result is ConflictResult.Conflict)
        }

        @Test
        fun `getMeetingCount reflects active meetings only`() {
            val r1 = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleMeeting("M2", carol, emptySet(), roomB, slot(11, 12))
            assertEquals(2, scheduler.getMeetingCount())

            scheduler.cancelMeeting((r1 as ScheduleResult.Success).meeting.id)
            assertEquals(1, scheduler.getMeetingCount())
        }
    }

    @Nested
    inner class StrategySchedulingTest {

        private lateinit var scheduler: StrategyBasedMeetingScheduler
        private val alice = user("alice", "Alice")
        private val bob = user("bob", "Bob")
        private val carol = user("carol", "Carol")
        private val roomA = room("A", 10, "whiteboard")
        private val roomB = room("B", 4)

        @BeforeEach
        fun setup() {
            scheduler = StrategyBasedMeetingScheduler()
            scheduler.addRoom(roomA)
            scheduler.addRoom(roomB)
        }

        @Test
        fun `FirstAvailableSlot finds earliest open slot`() {
            scheduler.setStrategy(FirstAvailableSlotStrategy())

            scheduler.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))

            val request = MeetingRequest(
                title = "M2", organizer = alice, attendees = setOf(bob),
                duration = Duration.ofHours(1),
                preferredStart = baseTime.withHour(9),
                preferredEnd = baseTime.withHour(17)
            )

            val result = scheduler.scheduleMeeting(request)
            assertTrue(result is ScheduleResult.Success)
            val meeting = (result as ScheduleResult.Success).meeting
            assertFalse(meeting.timeSlot.overlaps(slot(9, 10)))
        }

        @Test
        fun `ShortestDuration picks tightest gap`() {
            scheduler.setStrategy(ShortestDurationStrategy())

            scheduler.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleAtExactSlot("M2", alice, setOf(bob), roomB, slot(11, 14))

            val request = MeetingRequest(
                title = "Fit", organizer = alice, attendees = setOf(bob),
                duration = Duration.ofMinutes(30),
                preferredStart = baseTime.withHour(9),
                preferredEnd = baseTime.withHour(17)
            )

            val result = scheduler.scheduleMeeting(request)
            assertTrue(result is ScheduleResult.Success)
            val meeting = (result as ScheduleResult.Success).meeting
            assertTrue(meeting.timeSlot.start >= baseTime.withHour(10))
            assertTrue(meeting.timeSlot.end <= baseTime.withHour(11))
        }

        @Test
        fun `LeastConflicts avoids adjacent pressure`() {
            scheduler.setStrategy(LeastConflictsStrategy())

            scheduler.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleAtExactSlot("M2", alice, setOf(bob), roomB, slot(10, 11))

            val request = MeetingRequest(
                title = "Relaxed", organizer = carol, attendees = setOf(alice),
                duration = Duration.ofHours(1),
                preferredStart = baseTime.withHour(9),
                preferredEnd = baseTime.withHour(17)
            )

            val result = scheduler.scheduleMeeting(request)
            assertTrue(result is ScheduleResult.Success)
            val meeting = (result as ScheduleResult.Success).meeting
            assertTrue(meeting.timeSlot.start.hour >= 11)
        }

        @Test
        fun `PreferredTimeSlot favors preferred hours`() {
            scheduler.setStrategy(PreferredTimeSlotStrategy(preferredHourStart = 14, preferredHourEnd = 16))

            val request = MeetingRequest(
                title = "Afternoon", organizer = alice, attendees = setOf(bob),
                duration = Duration.ofHours(1),
                preferredStart = baseTime.withHour(9),
                preferredEnd = baseTime.withHour(17)
            )

            val result = scheduler.scheduleMeeting(request)
            assertTrue(result is ScheduleResult.Success)
            val meeting = (result as ScheduleResult.Success).meeting
            assertTrue(meeting.timeSlot.start.hour in 14..15)
        }

        @Test
        fun `scheduleAtExactSlot detects attendee conflict`() {
            scheduler.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            val result = scheduler.scheduleAtExactSlot("M2", carol, setOf(bob), roomB, slot(9, 10))

            assertTrue(result is ScheduleResult.AttendeeConflict)
        }

        @Test
        fun `scheduleAtExactSlot detects room conflict`() {
            scheduler.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            val result = scheduler.scheduleAtExactSlot("M2", carol, emptySet(), roomA, slot(9, 10))

            assertTrue(result is ScheduleResult.RoomConflict)
        }

        @Test
        fun `cancels meeting successfully`() {
            val result = scheduler.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertTrue(scheduler.cancelMeeting(meetingId))
            assertEquals(MeetingStatus.CANCELLED, scheduler.getMeeting(meetingId)!!.status)
        }

        @Test
        fun `cancel returns false for unknown meeting`() {
            assertFalse(scheduler.cancelMeeting("nonexistent"))
        }

        @Test
        fun `getMeetingsForUser returns active meetings sorted`() {
            scheduler.scheduleAtExactSlot("M2", alice, setOf(bob), roomA, slot(14, 15))
            scheduler.scheduleAtExactSlot("M1", alice, setOf(carol), roomB, slot(9, 10))

            val meetings = scheduler.getMeetingsForUser("alice")
            assertEquals(2, meetings.size)
            assertEquals("M1", meetings.first().title)
        }

        @Test
        fun `getSuggestions returns ranked suggestions from multiple strategies`() {
            val request = MeetingRequest(
                title = "Test", organizer = alice, attendees = setOf(bob),
                duration = Duration.ofHours(1),
                preferredStart = baseTime.withHour(9),
                preferredEnd = baseTime.withHour(17)
            )

            val suggestions = scheduler.getSuggestions(request, listOf(
                FirstAvailableSlotStrategy(),
                PreferredTimeSlotStrategy(10, 12),
                LeastConflictsStrategy()
            ))

            assertTrue(suggestions.isNotEmpty())
            assertTrue(suggestions == suggestions.sortedByDescending { it.score })
        }

        @Test
        fun `getActiveMeetingCount excludes cancelled`() {
            val r1 = scheduler.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleAtExactSlot("M2", carol, emptySet(), roomB, slot(11, 12))
            assertEquals(2, scheduler.getActiveMeetingCount())

            scheduler.cancelMeeting((r1 as ScheduleResult.Success).meeting.id)
            assertEquals(1, scheduler.getActiveMeetingCount())
        }

        @Test
        fun `strategy name is reported correctly`() {
            scheduler.setStrategy(ShortestDurationStrategy())
            assertEquals("Shortest Duration", scheduler.getStrategyName())
        }

        @Test
        fun `rejects meeting shorter than 5 minutes`() {
            val request = MeetingRequest(
                title = "Quick", organizer = alice, attendees = setOf(bob),
                duration = Duration.ofMinutes(3),
                preferredStart = baseTime.withHour(9),
                preferredEnd = baseTime.withHour(17)
            )
            val result = scheduler.scheduleMeeting(request)
            assertTrue(result is ScheduleResult.InvalidRequest)
        }
    }

    @Nested
    inner class ObserverCalendarTest {

        private lateinit var scheduler: ObserverCalendarScheduler
        private lateinit var emailNotifier: EmailCalendarNotifier
        private lateinit var conflictNotifier: ConflictNotifier
        private lateinit var rsvpTracker: RsvpTracker
        private lateinit var roomTracker: RoomBookingTracker

        private val alice = user("alice", "Alice")
        private val bob = user("bob", "Bob")
        private val carol = user("carol", "Carol")
        private val roomA = room("A", 10)
        private val roomB = room("B", 4)

        @BeforeEach
        fun setup() {
            scheduler = ObserverCalendarScheduler()
            emailNotifier = EmailCalendarNotifier()
            conflictNotifier = ConflictNotifier()
            rsvpTracker = RsvpTracker()
            roomTracker = RoomBookingTracker()

            scheduler.addObserver(emailNotifier)
            scheduler.addObserver(conflictNotifier)
            scheduler.addObserver(rsvpTracker)
            scheduler.addObserver(roomTracker)
            scheduler.addRoom(roomA)
            scheduler.addRoom(roomB)
        }

        @Test
        fun `observers are registered correctly`() {
            assertEquals(4, scheduler.getObserverCount())
        }

        @Test
        fun `observers can be removed`() {
            scheduler.removeObserver(conflictNotifier)
            assertEquals(3, scheduler.getObserverCount())
        }

        @Test
        fun `email invitations sent on scheduling`() {
            scheduler.scheduleMeeting("Standup", alice, setOf(bob), roomA, slot(9, 10))

            val emails = emailNotifier.sentNotifications
            assertEquals(2, emails.size)
            assertTrue(emails.all { it.type == "Meeting Invitation" })
        }

        @Test
        fun `cancellation emails sent to all participants`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob, carol), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            scheduler.cancelMeeting(meetingId, "No longer needed")

            val cancelEmails = emailNotifier.sentNotifications.filter { it.type == "Meeting Cancelled" }
            assertEquals(3, cancelEmails.size)
        }

        @Test
        fun `reschedule emails sent on time change`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            scheduler.rescheduleMeeting(meetingId, slot(14, 15))

            val rescheduleEmails = emailNotifier.sentNotifications.filter { it.type == "Meeting Rescheduled" }
            assertEquals(2, rescheduleEmails.size)
        }

        @Test
        fun `RSVP tracker initializes with pending status`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob, carol), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            val statuses = rsvpTracker.getRsvpStatus(meetingId)
            assertEquals(RsvpStatus.ACCEPTED, statuses["alice"])
            assertEquals(RsvpStatus.PENDING, statuses["bob"])
            assertEquals(RsvpStatus.PENDING, statuses["carol"])
        }

        @Test
        fun `RSVP responses are tracked`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob, carol), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertTrue(rsvpTracker.respond(meetingId, "bob", RsvpStatus.ACCEPTED))
            assertTrue(rsvpTracker.respond(meetingId, "carol", RsvpStatus.DECLINED))

            assertEquals(2, rsvpTracker.getAcceptedCount(meetingId))
            assertEquals(1, rsvpTracker.getDeclinedCount(meetingId))
            assertEquals(0, rsvpTracker.getPendingCount(meetingId))
        }

        @Test
        fun `RSVP respond returns false for unknown meeting`() {
            assertFalse(rsvpTracker.respond("nonexistent", "alice", RsvpStatus.ACCEPTED))
        }

        @Test
        fun `room tracker records bookings`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleMeeting("M2", carol, emptySet(), roomA, slot(11, 12))

            val schedule = roomTracker.getRoomSchedule("A")
            assertEquals(2, schedule.size)
        }

        @Test
        fun `room tracker detects availability`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))

            assertFalse(roomTracker.isRoomAvailable("A", slot(9, 10)))
            assertTrue(roomTracker.isRoomAvailable("A", slot(10, 11)))
            assertTrue(roomTracker.isRoomAvailable("B", slot(9, 10)))
        }

        @Test
        fun `room tracker calculates utilization`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleMeeting("M2", carol, emptySet(), roomA, slot(10, 11))

            val utilization = roomTracker.getRoomUtilization(
                "A", baseTime.withHour(9), baseTime.withHour(13)
            )
            assertEquals(50.0, utilization, 0.1)
        }

        @Test
        fun `room freed on cancellation`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            scheduler.cancelMeeting(meetingId)
            assertTrue(roomTracker.isRoomAvailable("A", slot(9, 10)))
        }

        @Test
        fun `addAttendee sends notification and updates RSVP`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertTrue(scheduler.addAttendee(meetingId, carol))

            val addedEmails = emailNotifier.sentNotifications.filter { it.type == "Added to Meeting" }
            assertEquals(1, addedEmails.size)
            assertEquals("carol@example.com", addedEmails.first().recipient)

            assertEquals(RsvpStatus.PENDING, rsvpTracker.getRsvpStatus(meetingId)["carol"])
        }

        @Test
        fun `removeAttendee sends notification`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob, carol), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertTrue(scheduler.removeAttendee(meetingId, "bob"))

            val removedEmails = emailNotifier.sentNotifications.filter { it.type == "Removed from Meeting" }
            assertEquals(1, removedEmails.size)
        }

        @Test
        fun `cannot remove organizer`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertFalse(scheduler.removeAttendee(meetingId, "alice"))
        }

        @Test
        fun `cannot add duplicate attendee`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertFalse(scheduler.addAttendee(meetingId, bob))
        }

        @Test
        fun `addAttendee respects room capacity`() {
            val tinyRoom = room("tiny", 2)
            scheduler.addRoom(tinyRoom)
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), tinyRoom, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            assertFalse(scheduler.addAttendee(meetingId, carol))
        }

        @Test
        fun `detects attendee conflict on schedule`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val result = scheduler.scheduleMeeting("M2", carol, setOf(bob), roomB, slot(9, 10))

            assertTrue(result is ScheduleResult.AttendeeConflict)
        }

        @Test
        fun `detects room conflict on schedule`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val result = scheduler.scheduleMeeting("M2", carol, emptySet(), roomA, slot(9, 10))

            assertTrue(result is ScheduleResult.RoomConflict)
        }

        @Test
        fun `reschedule detects conflict at new time`() {
            val r1 = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleMeeting("M2", alice, setOf(carol), roomB, slot(14, 15))
            val m1Id = (r1 as ScheduleResult.Success).meeting.id

            val result = scheduler.rescheduleMeeting(m1Id, slot(14, 15))
            assertTrue(result is ScheduleResult.AttendeeConflict)
        }

        @Test
        fun `getActiveMeetings returns sorted active meetings`() {
            scheduler.scheduleMeeting("M2", alice, setOf(bob), roomA, slot(14, 15))
            scheduler.scheduleMeeting("M1", carol, emptySet(), roomB, slot(9, 10))

            val active = scheduler.getActiveMeetings()
            assertEquals(2, active.size)
            assertEquals("M1", active.first().title)
        }

        @Test
        fun `getMeetingsForUser filters by user`() {
            scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            scheduler.scheduleMeeting("M2", carol, emptySet(), roomB, slot(11, 12))

            val aliceMeetings = scheduler.getMeetingsForUser("alice")
            assertEquals(1, aliceMeetings.size)
            assertEquals("M1", aliceMeetings.first().title)
        }

        @Test
        fun `RSVP cleared on cancellation`() {
            val result = scheduler.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val meetingId = (result as ScheduleResult.Success).meeting.id

            scheduler.cancelMeeting(meetingId)
            assertTrue(rsvpTracker.getRsvpStatus(meetingId).isEmpty())
        }

        @Test
        fun `room capacity rejection`() {
            val tinyRoom = room("tiny", 2)
            val result = scheduler.scheduleMeeting(
                "Big", alice, setOf(bob, carol), tinyRoom, slot(9, 10)
            )
            assertTrue(result is ScheduleResult.InvalidRequest)
        }
    }

    @Nested
    inner class CrossApproachConsistencyTest {

        private val alice = user("alice", "Alice")
        private val bob = user("bob", "Bob")
        private val carol = user("carol", "Carol")
        private val roomA = room("A", 10)

        @Test
        fun `all approaches prevent double-booking same user`() {
            val intervalTree = IntervalTreeScheduler().also { it.addRoom(roomA) }
            val strategy = StrategyBasedMeetingScheduler().also { it.addRoom(roomA) }
            val observer = ObserverCalendarScheduler().also { it.addRoom(roomA) }

            intervalTree.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            strategy.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            observer.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))

            val r1 = intervalTree.scheduleMeeting("M2", carol, setOf(alice), null, slot(9, 10))
            val r2 = strategy.scheduleAtExactSlot("M2", carol, setOf(alice), null, slot(9, 10))
            val r3 = observer.scheduleMeeting("M2", carol, setOf(alice), null, slot(9, 10))

            assertTrue(r1 is ScheduleResult.AttendeeConflict)
            assertTrue(r2 is ScheduleResult.AttendeeConflict)
            assertTrue(r3 is ScheduleResult.AttendeeConflict)
        }

        @Test
        fun `all approaches prevent double-booking same room`() {
            val intervalTree = IntervalTreeScheduler().also { it.addRoom(roomA) }
            val strategy = StrategyBasedMeetingScheduler().also { it.addRoom(roomA) }
            val observer = ObserverCalendarScheduler().also { it.addRoom(roomA) }

            intervalTree.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            strategy.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            observer.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))

            val r1 = intervalTree.scheduleMeeting("M2", carol, emptySet(), roomA, slot(9, 10))
            val r2 = strategy.scheduleAtExactSlot("M2", carol, emptySet(), roomA, slot(9, 10))
            val r3 = observer.scheduleMeeting("M2", carol, emptySet(), roomA, slot(9, 10))

            assertTrue(r1 is ScheduleResult.RoomConflict)
            assertTrue(r2 is ScheduleResult.RoomConflict)
            assertTrue(r3 is ScheduleResult.RoomConflict)
        }

        @Test
        fun `all approaches allow non-overlapping meetings`() {
            val intervalTree = IntervalTreeScheduler().also { it.addRoom(roomA) }
            val strategy = StrategyBasedMeetingScheduler().also { it.addRoom(roomA) }
            val observer = ObserverCalendarScheduler().also { it.addRoom(roomA) }

            val r1a = intervalTree.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val r1b = intervalTree.scheduleMeeting("M2", alice, setOf(bob), roomA, slot(10, 11))
            val r2a = strategy.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            val r2b = strategy.scheduleAtExactSlot("M2", alice, setOf(bob), roomA, slot(10, 11))
            val r3a = observer.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val r3b = observer.scheduleMeeting("M2", alice, setOf(bob), roomA, slot(10, 11))

            assertTrue(r1a is ScheduleResult.Success)
            assertTrue(r1b is ScheduleResult.Success)
            assertTrue(r2a is ScheduleResult.Success)
            assertTrue(r2b is ScheduleResult.Success)
            assertTrue(r3a is ScheduleResult.Success)
            assertTrue(r3b is ScheduleResult.Success)
        }

        @Test
        fun `cancellation frees slot in all approaches`() {
            val intervalTree = IntervalTreeScheduler().also { it.addRoom(roomA) }
            val strategy = StrategyBasedMeetingScheduler().also { it.addRoom(roomA) }
            val observer = ObserverCalendarScheduler().also { it.addRoom(roomA) }

            val m1 = intervalTree.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))
            val m2 = strategy.scheduleAtExactSlot("M1", alice, setOf(bob), roomA, slot(9, 10))
            val m3 = observer.scheduleMeeting("M1", alice, setOf(bob), roomA, slot(9, 10))

            intervalTree.cancelMeeting((m1 as ScheduleResult.Success).meeting.id)
            strategy.cancelMeeting((m2 as ScheduleResult.Success).meeting.id)
            observer.cancelMeeting((m3 as ScheduleResult.Success).meeting.id)

            val r1 = intervalTree.scheduleMeeting("M2", carol, emptySet(), roomA, slot(9, 10))
            val r2 = strategy.scheduleAtExactSlot("M2", carol, emptySet(), roomA, slot(9, 10))
            val r3 = observer.scheduleMeeting("M2", carol, emptySet(), roomA, slot(9, 10))

            assertTrue(r1 is ScheduleResult.Success)
            assertTrue(r2 is ScheduleResult.Success)
            assertTrue(r3 is ScheduleResult.Success)
        }
    }
}
