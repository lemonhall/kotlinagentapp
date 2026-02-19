package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.calendar.FakeCalendarPermissionChecker
import com.lsl.kotlin_agent_app.agent.tools.calendar.InMemoryCalendarStore
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.cal.CalCommandTestHooks
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolCalTest {

    @Test
    fun cal_listCalendars_withoutReadPermission_returnsPermissionDenied() =
        runTerminalExecToolTest(
            setup = {
                CalCommandTestHooks.install(
                    store = InMemoryCalendarStore(),
                    permissions = FakeCalendarPermissionChecker(read = false, write = false),
                );
                { CalCommandTestHooks.clear() }
            },
        ) { tool ->
            val out = tool.exec("cal list-calendars")
            assertTrue(out.exitCode != 0)
            assertEquals("PermissionDenied", out.errorCode)
        }

    @Test
    fun cal_listCalendars_withReadPermission_supportsOutArtifact() =
        runTerminalExecToolTest(
            setup = {
                val store =
                    InMemoryCalendarStore().apply {
                        addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                        addCalendar(id = 2, displayName = "Work", accountName = "me", accountType = "local")
                    }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = false),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
            val outRel = "artifacts/cal/test-calendars.json"
            val out = tool.exec("cal list-calendars --max 1 --out $outRel")
            assertEquals(0, out.exitCode)
            assertTrue(out.artifacts.contains(".agents/$outRel"))

            val outFile = File(tool.filesDir, ".agents/$outRel")
            assertTrue(outFile.exists())
            assertTrue(outFile.readText(Charsets.UTF_8).contains("\"count_total\""))
        }

    @Test
    fun cal_listEvents_withReadPermission_supportsOutArtifact() =
        runTerminalExecToolTest(
            setup = {
                val store =
                    InMemoryCalendarStore().apply {
                        addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                    }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
            val created =
                tool.exec(
                    "cal create-event --calendar-id 1 --title \"Demo\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z --confirm",
                )
            assertEquals(0, created.exitCode)

            val outRel = "artifacts/cal/test-events.json"
            val listed =
                tool.exec(
                    "cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --out $outRel",
                )
            assertEquals(0, listed.exitCode)
            assertTrue(listed.artifacts.contains(".agents/$outRel"))

            val outFile = File(tool.filesDir, ".agents/$outRel")
            assertTrue(outFile.exists())
            assertTrue(outFile.readText(Charsets.UTF_8).contains("\"events\""))
        }

    @Test
    fun cal_createEvent_withoutConfirm_isRejected() =
        runTerminalExecToolTest(
            setup = {
            CalCommandTestHooks.install(
                store = InMemoryCalendarStore(),
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
            val out =
                tool.exec(
                    "cal create-event --calendar-id 1 --title \"t\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z",
                )
            assertTrue(out.exitCode != 0)
            assertEquals("ConfirmRequired", out.errorCode)
        }

    @Test
    fun cal_createUpdateAddReminderDelete_happyPath() =
        runTerminalExecToolTest(
            setup = {
                val store =
                    InMemoryCalendarStore().apply {
                        addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                    }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
            val created =
                tool.exec(
                    "cal create-event --calendar-id 1 --title \"Demo\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z --remind-minutes 15 --confirm",
                )
            assertEquals(0, created.exitCode)
            val eventId = (created.result?.get("event_id") as? JsonPrimitive)?.content?.toLongOrNull()
            assertTrue("expected event_id", (eventId ?: 0L) > 0L)

            val updated =
                tool.exec(
                    "cal update-event --event-id $eventId --location \"Room\" --confirm",
                )
            assertEquals(0, updated.exitCode)

            val reminder =
                tool.exec(
                    "cal add-reminder --event-id $eventId --minutes 30 --confirm",
                )
            assertEquals(0, reminder.exitCode)

            val listed =
                tool.exec(
                    "cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --max 50",
                )
            assertEquals(0, listed.exitCode)
            val events = listed.result?.get("events")?.jsonArray
            assertTrue("expected at least 1 event", (events?.size ?: 0) >= 1)

            val deleted =
                tool.exec(
                    "cal delete-event --event-id $eventId --confirm",
                )
            assertEquals(0, deleted.exitCode)
        }
}
