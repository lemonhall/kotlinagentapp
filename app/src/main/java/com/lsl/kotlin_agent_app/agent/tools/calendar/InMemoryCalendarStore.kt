package com.lsl.kotlin_agent_app.agent.tools.calendar

import java.util.concurrent.atomic.AtomicLong

internal class InMemoryCalendarStore : CalendarStore {
    private val calendarById = linkedMapOf<Long, CalendarSummary>()

    private data class EventRecord(
        val id: Long,
        val calendarId: Long,
        var title: String,
        var startTimeMs: Long,
        var endTimeMs: Long,
        var allDay: Boolean,
        var location: String?,
        val reminderMinutes: MutableList<Int>,
    )

    private val eventById = linkedMapOf<Long, EventRecord>()
    private val eventIdSeq = AtomicLong(1000L)
    private val reminderIdSeq = AtomicLong(2000L)

    fun addCalendar(
        id: Long,
        displayName: String,
        accountName: String? = null,
        accountType: String? = null,
        ownerAccount: String? = null,
        visible: Boolean = true,
        isPrimary: Boolean? = null,
    ) {
        calendarById[id] =
            CalendarSummary(
                id = id,
                displayName = displayName,
                accountName = accountName,
                accountType = accountType,
                ownerAccount = ownerAccount,
                visible = visible,
                isPrimary = isPrimary,
            )
    }

    override fun listCalendars(): List<CalendarSummary> = calendarById.values.toList()

    override fun listEvents(
        fromTimeMs: Long,
        toTimeMs: Long,
        calendarId: Long?,
    ): List<CalendarEventSummary> {
        return eventById.values
            .asSequence()
            .filter { e ->
                (calendarId == null || e.calendarId == calendarId) &&
                    e.startTimeMs < toTimeMs &&
                    e.endTimeMs > fromTimeMs
            }
            .map { e ->
                CalendarEventSummary(
                    id = e.id,
                    calendarId = e.calendarId,
                    title = e.title,
                    startTimeMs = e.startTimeMs,
                    endTimeMs = e.endTimeMs,
                    allDay = e.allDay,
                    location = e.location,
                )
            }
            .sortedBy { it.startTimeMs }
            .toList()
    }

    override fun createEvent(request: CreateEventRequest): CreateEventResult {
        val id = eventIdSeq.incrementAndGet()
        val reminderMinutes = mutableListOf<Int>()
        if (request.remindMinutes != null) reminderMinutes.add(request.remindMinutes)
        eventById[id] =
            EventRecord(
                id = id,
                calendarId = request.calendarId,
                title = request.title,
                startTimeMs = request.startTimeMs,
                endTimeMs = request.endTimeMs,
                allDay = request.allDay,
                location = request.location,
                reminderMinutes = reminderMinutes,
            )
        return CreateEventResult(eventId = id, reminderAdded = request.remindMinutes != null)
    }

    override fun updateEvent(request: UpdateEventRequest): UpdateEventResult {
        val e = eventById[request.eventId] ?: return UpdateEventResult(eventId = request.eventId, updatedFields = emptyList())
        val updated = mutableListOf<String>()
        if (request.title != null) {
            e.title = request.title
            updated.add("title")
        }
        if (request.startTimeMs != null) {
            e.startTimeMs = request.startTimeMs
            updated.add("start_time_ms")
        }
        if (request.endTimeMs != null) {
            e.endTimeMs = request.endTimeMs
            updated.add("end_time_ms")
        }
        if (request.allDay != null) {
            e.allDay = request.allDay
            updated.add("all_day")
        }
        if (request.location != null) {
            e.location = request.location
            updated.add("location")
        }
        return UpdateEventResult(eventId = request.eventId, updatedFields = updated)
    }

    override fun deleteEvent(eventId: Long): Boolean = eventById.remove(eventId) != null

    override fun addReminder(
        eventId: Long,
        minutes: Int,
    ): Long? {
        val e = eventById[eventId] ?: return null
        e.reminderMinutes.add(minutes)
        return reminderIdSeq.incrementAndGet()
    }
}

