package com.lsl.kotlin_agent_app.agent.tools.calendar

internal data class CalendarSummary(
    val id: Long,
    val displayName: String,
    val accountName: String? = null,
    val accountType: String? = null,
    val ownerAccount: String? = null,
    val visible: Boolean = true,
    val isPrimary: Boolean? = null,
)

internal data class CalendarEventSummary(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val allDay: Boolean,
    val location: String? = null,
)

internal data class CreateEventRequest(
    val calendarId: Long,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val allDay: Boolean,
    val location: String? = null,
    val remindMinutes: Int? = null,
)

internal data class CreateEventResult(
    val eventId: Long,
    val reminderAdded: Boolean,
)

internal data class UpdateEventRequest(
    val eventId: Long,
    val title: String? = null,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
    val allDay: Boolean? = null,
    val location: String? = null,
)

internal data class UpdateEventResult(
    val eventId: Long,
    val updatedFields: List<String>,
)

internal interface CalendarStore {
    fun listCalendars(): List<CalendarSummary>

    fun listEvents(
        fromTimeMs: Long,
        toTimeMs: Long,
        calendarId: Long? = null,
    ): List<CalendarEventSummary>

    fun createEvent(request: CreateEventRequest): CreateEventResult

    fun updateEvent(request: UpdateEventRequest): UpdateEventResult

    fun deleteEvent(eventId: Long): Boolean

    fun addReminder(
        eventId: Long,
        minutes: Int,
    ): Long?
}

