package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.cal

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.calendar.AndroidCalendarPermissionChecker
import com.lsl.kotlin_agent_app.agent.tools.calendar.AndroidCalendarStore
import com.lsl.kotlin_agent_app.agent.tools.calendar.CalendarEventSummary
import com.lsl.kotlin_agent_app.agent.tools.calendar.CalendarPermissionChecker
import com.lsl.kotlin_agent_app.agent.tools.calendar.CalendarStore
import com.lsl.kotlin_agent_app.agent.tools.calendar.CalendarSummary
import com.lsl.kotlin_agent_app.agent.tools.calendar.CreateEventRequest
import com.lsl.kotlin_agent_app.agent.tools.calendar.UpdateEventRequest
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.hasFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseIntFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireConfirm
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal object CalCommandTestHooks {
    @Volatile private var storeOverride: CalendarStore? = null
    @Volatile private var permissionsOverride: CalendarPermissionChecker? = null

    fun install(
        store: CalendarStore,
        permissions: CalendarPermissionChecker,
    ) {
        storeOverride = store
        permissionsOverride = permissions
    }

    fun clear() {
        storeOverride = null
        permissionsOverride = null
    }

    internal fun getStoreOrNull(): CalendarStore? = storeOverride

    internal fun getPermissionsOrNull(): CalendarPermissionChecker? = permissionsOverride
}

internal class PermissionDenied(
    message: String,
) : IllegalArgumentException(message)

internal class CalCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile

    private val store: CalendarStore = CalCommandTestHooks.getStoreOrNull() ?: AndroidCalendarStore(ctx)
    private val permissions: CalendarPermissionChecker =
        CalCommandTestHooks.getPermissionsOrNull() ?: AndroidCalendarPermissionChecker(ctx)

    override val name: String = "cal"
    override val description: String = "Android Calendar Provider CLI (list/events/write/reminders) with permission + confirm guardrails."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        return try {
            when (argv[1].lowercase()) {
                "list-calendars" -> handleListCalendars(argv)
                "list-events" -> handleListEvents(argv)
                "create-event" -> handleCreateEvent(argv)
                "update-event" -> handleUpdateEvent(argv)
                "delete-event" -> handleDeleteEvent(argv)
                "add-reminder" -> handleAddReminder(argv)
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: PermissionDenied) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing calendar permission"),
                errorCode = "PermissionDenied",
                errorMessage = t.message,
            )
        } catch (t: ConfirmRequired) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing --confirm"),
                errorCode = "ConfirmRequired",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "cal error"),
                errorCode = "CalendarError",
                errorMessage = t.message,
            )
        }
    }

    private fun handleListCalendars(argv: List<String>): TerminalCommandOutput {
        requireReadPermission()
        val max = parseIntFlag(argv, "--max", defaultValue = 50).coerceAtLeast(0)
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }

        val all = store.listCalendars()
        val emitted = all.take(max)
        val truncated = all.size > emitted.size

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("cal list-calendars"))
                put("count_total", JsonPrimitive(all.size))
                put("count_emitted", JsonPrimitive(emitted.size))
                put("truncated", JsonPrimitive(truncated))
                if (outRel != null) put("out", JsonPrimitive(outRel))
                put("calendars", buildJsonArray { emitted.forEach { add(calendarJson(it)) } })
            }

        val artifacts = outRel?.let { listOf(writeOutArtifact(outRel = it, json = fullCalendarsJson(inRelOut = it, all = all))) } ?: emptyList()
        val stdout =
            buildString {
                appendLine("cal list-calendars: ${all.size} calendars" + if (truncated) " (showing ${emitted.size})" else "")
                if (outRel != null) appendLine("full list written: $outRel")
                for (c in emitted) {
                    appendLine("${c.id}\t${c.displayName}")
                }
            }.trimEnd()

        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result, artifacts = artifacts)
    }

    private fun handleListEvents(argv: List<String>): TerminalCommandOutput {
        requireReadPermission()
        val fromRaw = requireFlagValue(argv, "--from")
        val toRaw = requireFlagValue(argv, "--to")
        val fromMs = parseInstantMs(flag = "--from", raw = fromRaw)
        val toMs = parseInstantMs(flag = "--to", raw = toRaw)
        if (toMs <= fromMs) throw IllegalArgumentException("--to must be after --from")

        val calendarId = optionalFlagValue(argv, "--calendar-id")?.toLongOrNull()
        if (optionalFlagValue(argv, "--calendar-id") != null && calendarId == null) {
            throw IllegalArgumentException("invalid long for --calendar-id: ${optionalFlagValue(argv, "--calendar-id")}")
        }

        val max = parseIntFlag(argv, "--max", defaultValue = 200).coerceAtLeast(0)
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }

        val all = store.listEvents(fromTimeMs = fromMs, toTimeMs = toMs, calendarId = calendarId)
        val emitted = all.take(max)
        val truncated = all.size > emitted.size

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("cal list-events"))
                put("from", JsonPrimitive(fromRaw))
                put("to", JsonPrimitive(toRaw))
                if (calendarId != null) put("calendar_id", JsonPrimitive(calendarId))
                put("count_total", JsonPrimitive(all.size))
                put("count_emitted", JsonPrimitive(emitted.size))
                put("truncated", JsonPrimitive(truncated))
                if (outRel != null) put("out", JsonPrimitive(outRel))
                put("events", buildJsonArray { emitted.forEach { add(eventJson(it)) } })
            }

        val artifacts =
            outRel?.let {
                listOf(
                    writeOutArtifact(
                        outRel = it,
                        json = fullEventsJson(inRelOut = it, fromRaw = fromRaw, toRaw = toRaw, calendarId = calendarId, all = all),
                    ),
                )
            } ?: emptyList()

        val stdout =
            buildString {
                appendLine("cal list-events: ${all.size} events" + if (truncated) " (showing ${emitted.size})" else "")
                if (outRel != null) appendLine("full list written: $outRel")
                for (e in emitted) {
                    appendLine("${e.id}\t${e.startTimeMs}\t${e.title}")
                }
            }.trimEnd()

        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result, artifacts = artifacts)
    }

    private fun handleCreateEvent(argv: List<String>): TerminalCommandOutput {
        requireConfirm(argv)
        requireWritePermission()

        val calendarId = requireFlagValue(argv, "--calendar-id").toLongOrNull()
            ?: throw IllegalArgumentException("invalid long for --calendar-id: ${requireFlagValue(argv, "--calendar-id")}")
        val title = requireFlagValue(argv, "--title")
        val startRaw = requireFlagValue(argv, "--start")
        val endRaw = requireFlagValue(argv, "--end")
        val startMs = parseInstantMs("--start", startRaw)
        val endMs = parseInstantMs("--end", endRaw)
        if (endMs <= startMs) throw IllegalArgumentException("--end must be after --start")

        val allDay = hasFlag(argv, "--all-day")
        val location = optionalFlagValue(argv, "--location")?.takeIf { it.isNotBlank() }
        val remindMinutes = optionalFlagValue(argv, "--remind-minutes")?.let { raw ->
            raw.toIntOrNull() ?: throw IllegalArgumentException("invalid int for --remind-minutes: $raw")
        }

        val created =
            store.createEvent(
                CreateEventRequest(
                    calendarId = calendarId,
                    title = title,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    allDay = allDay,
                    location = location,
                    remindMinutes = remindMinutes,
                ),
            )

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("cal create-event"))
                put("event_id", JsonPrimitive(created.eventId))
                put("calendar_id", JsonPrimitive(calendarId))
                put("reminder_added", JsonPrimitive(created.reminderAdded))
            }

        val stdout = "cal create-event: event_id=${created.eventId}"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun handleUpdateEvent(argv: List<String>): TerminalCommandOutput {
        requireConfirm(argv)
        requireWritePermission()

        val eventId = requireFlagValue(argv, "--event-id").toLongOrNull()
            ?: throw IllegalArgumentException("invalid long for --event-id: ${requireFlagValue(argv, "--event-id")}")

        val title = optionalFlagValue(argv, "--title")?.takeIf { it.isNotBlank() }
        val startRaw = optionalFlagValue(argv, "--start")
        val endRaw = optionalFlagValue(argv, "--end")
        val startMs = startRaw?.let { parseInstantMs("--start", it) }
        val endMs = endRaw?.let { parseInstantMs("--end", it) }
        if (startMs != null && endMs != null && endMs <= startMs) throw IllegalArgumentException("--end must be after --start")

        val allDay =
            optionalFlagValue(argv, "--all-day")?.let { raw ->
                when (raw.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("invalid boolean for --all-day: $raw")
                }
            }
        val location = optionalFlagValue(argv, "--location")

        if (title == null && startMs == null && endMs == null && allDay == null && location == null) {
            return invalidArgs("no fields to update")
        }

        val updated =
            store.updateEvent(
                UpdateEventRequest(
                    eventId = eventId,
                    title = title,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    allDay = allDay,
                    location = location,
                ),
            )

        if (updated.updatedFields.isEmpty()) {
            return TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = "event not found: $eventId",
                errorCode = "NotFound",
                errorMessage = "event not found: $eventId",
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("command", JsonPrimitive("cal update-event"))
                        put("event_id", JsonPrimitive(eventId))
                        put("updated_fields", buildJsonArray { })
                    },
            )
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("cal update-event"))
                put("event_id", JsonPrimitive(eventId))
                put("updated_fields", buildJsonArray { updated.updatedFields.forEach { add(JsonPrimitive(it)) } })
            }

        val stdout = "cal update-event: event_id=$eventId"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun handleDeleteEvent(argv: List<String>): TerminalCommandOutput {
        requireConfirm(argv)
        requireWritePermission()

        val eventId = requireFlagValue(argv, "--event-id").toLongOrNull()
            ?: throw IllegalArgumentException("invalid long for --event-id: ${requireFlagValue(argv, "--event-id")}")

        val ok = store.deleteEvent(eventId)
        if (!ok) {
            return TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = "event not found: $eventId",
                errorCode = "NotFound",
                errorMessage = "event not found: $eventId",
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("command", JsonPrimitive("cal delete-event"))
                        put("event_id", JsonPrimitive(eventId))
                    },
            )
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("cal delete-event"))
                put("event_id", JsonPrimitive(eventId))
            }
        val stdout = "cal delete-event: event_id=$eventId"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun handleAddReminder(argv: List<String>): TerminalCommandOutput {
        requireConfirm(argv)
        requireWritePermission()

        val eventId = requireFlagValue(argv, "--event-id").toLongOrNull()
            ?: throw IllegalArgumentException("invalid long for --event-id: ${requireFlagValue(argv, "--event-id")}")
        val minutes = requireFlagValue(argv, "--minutes").toIntOrNull()
            ?: throw IllegalArgumentException("invalid int for --minutes: ${requireFlagValue(argv, "--minutes")}")

        val reminderId = store.addReminder(eventId = eventId, minutes = minutes)
        if (reminderId == null) {
            return TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = "event not found: $eventId",
                errorCode = "NotFound",
                errorMessage = "event not found: $eventId",
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("command", JsonPrimitive("cal add-reminder"))
                        put("event_id", JsonPrimitive(eventId))
                        put("minutes", JsonPrimitive(minutes))
                    },
            )
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("cal add-reminder"))
                put("event_id", JsonPrimitive(eventId))
                put("minutes", JsonPrimitive(minutes))
                put("reminder_id", JsonPrimitive(reminderId))
            }
        val stdout = "cal add-reminder: event_id=$eventId minutes=$minutes"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun requireReadPermission() {
        if (!permissions.hasReadCalendar()) {
            throw PermissionDenied("Missing READ_CALENDAR permission. Please grant Calendar permission in system settings.")
        }
    }

    private fun requireWritePermission() {
        if (!permissions.hasWriteCalendar()) {
            throw PermissionDenied("Missing WRITE_CALENDAR permission. Please grant Calendar permission in system settings.")
        }
    }

    private fun parseInstantMs(
        flag: String,
        raw: String,
    ): Long {
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: Throwable) {
            throw IllegalArgumentException("invalid RFC3339 for $flag: $raw")
        }
    }

    private fun calendarJson(c: CalendarSummary): JsonElement {
        return buildJsonObject {
            put("id", JsonPrimitive(c.id))
            put("display_name", JsonPrimitive(c.displayName))
            put("account_name", JsonPrimitive(c.accountName ?: ""))
            put("account_type", JsonPrimitive(c.accountType ?: ""))
            put("owner_account", JsonPrimitive(c.ownerAccount ?: ""))
            put("visible", JsonPrimitive(c.visible))
            if (c.isPrimary != null) put("is_primary", JsonPrimitive(c.isPrimary))
        }
    }

    private fun eventJson(e: CalendarEventSummary): JsonElement {
        return buildJsonObject {
            put("id", JsonPrimitive(e.id))
            put("calendar_id", JsonPrimitive(e.calendarId))
            put("title", JsonPrimitive(e.title))
            put("start_time_ms", JsonPrimitive(e.startTimeMs))
            put("end_time_ms", JsonPrimitive(e.endTimeMs))
            put("all_day", JsonPrimitive(e.allDay))
            if (!e.location.isNullOrBlank()) put("location", JsonPrimitive(e.location))
        }
    }

    private fun fullCalendarsJson(
        inRelOut: String,
        all: List<CalendarSummary>,
    ): JsonElement {
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("command", JsonPrimitive("cal list-calendars"))
            put("out", JsonPrimitive(inRelOut))
            put("count_total", JsonPrimitive(all.size))
            put("calendars", buildJsonArray { all.forEach { add(calendarJson(it)) } })
        }
    }

    private fun fullEventsJson(
        inRelOut: String,
        fromRaw: String,
        toRaw: String,
        calendarId: Long?,
        all: List<CalendarEventSummary>,
    ): JsonElement {
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("command", JsonPrimitive("cal list-events"))
            put("out", JsonPrimitive(inRelOut))
            put("from", JsonPrimitive(fromRaw))
            put("to", JsonPrimitive(toRaw))
            if (calendarId != null) put("calendar_id", JsonPrimitive(calendarId))
            put("count_total", JsonPrimitive(all.size))
            put("events", buildJsonArray { all.forEach { add(eventJson(it)) } })
        }
    }

    private fun writeOutArtifact(
        outRel: String,
        json: JsonElement,
    ): TerminalArtifact {
        val outFile = resolveWithinAgents(agentsRoot, outRel)
        val parent = outFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        outFile.writeText(json.toString() + "\n", Charsets.UTF_8)
        return TerminalArtifact(
            path = ".agents/" + relPath(agentsRoot, outFile),
            mime = "application/json",
            description = "Full calendar output (may be large).",
        )
    }

    private fun invalidArgs(message: String): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "InvalidArgs",
            errorMessage = message,
        )
    }
}

