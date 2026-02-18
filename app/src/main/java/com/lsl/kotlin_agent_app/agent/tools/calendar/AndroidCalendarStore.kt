package com.lsl.kotlin_agent_app.agent.tools.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

internal class AndroidCalendarStore(
    appContext: Context,
) : CalendarStore {
    private val ctx = appContext.applicationContext

    override fun listCalendars(): List<CalendarSummary> {
        val cr = ctx.contentResolver
        val out = mutableListOf<CalendarSummary>()

        val projection =
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.IS_PRIMARY,
            )

        cr.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cur ->
            val idxId = cur.getColumnIndex(CalendarContract.Calendars._ID)
            val idxName = cur.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val idxAccName = cur.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val idxAccType = cur.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
            val idxOwner = cur.getColumnIndex(CalendarContract.Calendars.OWNER_ACCOUNT)
            val idxVisible = cur.getColumnIndex(CalendarContract.Calendars.VISIBLE)
            val idxPrimary = cur.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

            while (cur.moveToNext()) {
                val id = cur.getLong(idxId)
                val name = cur.getString(idxName).orEmpty()
                val accountName = cur.getString(idxAccName)
                val accountType = cur.getString(idxAccType)
                val owner = cur.getString(idxOwner)
                val visible = cur.getInt(idxVisible) != 0
                val isPrimary =
                    if (idxPrimary >= 0) {
                        cur.getInt(idxPrimary) != 0
                    } else {
                        null
                    }

                out.add(
                    CalendarSummary(
                        id = id,
                        displayName = name,
                        accountName = accountName,
                        accountType = accountType,
                        ownerAccount = owner,
                        visible = visible,
                        isPrimary = isPrimary,
                    ),
                )
            }
        }

        return out
    }

    override fun listEvents(
        fromTimeMs: Long,
        toTimeMs: Long,
        calendarId: Long?,
    ): List<CalendarEventSummary> {
        val cr = ctx.contentResolver
        val out = mutableListOf<CalendarEventSummary>()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, fromTimeMs)
        ContentUris.appendId(builder, toTimeMs)

        val projection =
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            )

        val selection =
            if (calendarId != null) {
                "${CalendarContract.Instances.CALENDAR_ID}=?"
            } else {
                null
            }
        val selectionArgs =
            if (calendarId != null) {
                arrayOf(calendarId.toString())
            } else {
                null
            }

        cr.query(builder.build(), projection, selection, selectionArgs, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            val idxEventId = cur.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val idxCalId = cur.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
            val idxTitle = cur.getColumnIndex(CalendarContract.Instances.TITLE)
            val idxBegin = cur.getColumnIndex(CalendarContract.Instances.BEGIN)
            val idxEnd = cur.getColumnIndex(CalendarContract.Instances.END)
            val idxAllDay = cur.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val idxLoc = cur.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

            while (cur.moveToNext()) {
                val eventId = cur.getLong(idxEventId)
                val calId = cur.getLong(idxCalId)
                val title = cur.getString(idxTitle).orEmpty()
                val begin = cur.getLong(idxBegin)
                val end = cur.getLong(idxEnd)
                val allDay = cur.getInt(idxAllDay) != 0
                val loc = cur.getString(idxLoc)

                out.add(
                    CalendarEventSummary(
                        id = eventId,
                        calendarId = calId,
                        title = title,
                        startTimeMs = begin,
                        endTimeMs = end,
                        allDay = allDay,
                        location = loc,
                    ),
                )
            }
        }

        return out
    }

    override fun createEvent(request: CreateEventRequest): CreateEventResult {
        val cr = ctx.contentResolver
        val values =
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, request.calendarId)
                put(CalendarContract.Events.TITLE, request.title)
                put(CalendarContract.Events.DTSTART, request.startTimeMs)
                put(CalendarContract.Events.DTEND, request.endTimeMs)
                put(CalendarContract.Events.ALL_DAY, if (request.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!request.location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, request.location)
            }

        val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = uri?.lastPathSegment?.toLongOrNull() ?: 0L
        if (eventId <= 0L) {
            error("failed to create event")
        }

        val reminderAdded =
            if (request.remindMinutes != null) {
                addReminder(eventId = eventId, minutes = request.remindMinutes) != null
            } else {
                false
            }

        return CreateEventResult(eventId = eventId, reminderAdded = reminderAdded)
    }

    override fun updateEvent(request: UpdateEventRequest): UpdateEventResult {
        val cr = ctx.contentResolver
        val values = ContentValues()
        val updated = mutableListOf<String>()

        if (request.title != null) {
            values.put(CalendarContract.Events.TITLE, request.title)
            updated.add("title")
        }
        if (request.startTimeMs != null) {
            values.put(CalendarContract.Events.DTSTART, request.startTimeMs)
            updated.add("start_time_ms")
        }
        if (request.endTimeMs != null) {
            values.put(CalendarContract.Events.DTEND, request.endTimeMs)
            updated.add("end_time_ms")
        }
        if (request.allDay != null) {
            values.put(CalendarContract.Events.ALL_DAY, if (request.allDay) 1 else 0)
            updated.add("all_day")
        }
        if (request.location != null) {
            values.put(CalendarContract.Events.EVENT_LOCATION, request.location)
            updated.add("location")
        }

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, request.eventId)
        if (values.size() > 0) {
            cr.update(uri, values, null, null)
        }

        return UpdateEventResult(eventId = request.eventId, updatedFields = updated)
    }

    override fun deleteEvent(eventId: Long): Boolean {
        val cr = ctx.contentResolver
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val n = cr.delete(uri, null, null)
        return n > 0
    }

    override fun addReminder(
        eventId: Long,
        minutes: Int,
    ): Long? {
        val cr = ctx.contentResolver
        val values =
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
        val uri = cr.insert(CalendarContract.Reminders.CONTENT_URI, values)
        return uri?.lastPathSegment?.toLongOrNull()
    }
}

