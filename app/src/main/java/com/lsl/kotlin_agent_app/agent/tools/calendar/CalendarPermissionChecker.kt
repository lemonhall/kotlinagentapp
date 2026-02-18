package com.lsl.kotlin_agent_app.agent.tools.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal interface CalendarPermissionChecker {
    fun hasReadCalendar(): Boolean

    fun hasWriteCalendar(): Boolean
}

internal class AndroidCalendarPermissionChecker(
    private val appContext: Context,
) : CalendarPermissionChecker {
    private val ctx = appContext.applicationContext

    override fun hasReadCalendar(): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    override fun hasWriteCalendar(): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
}

internal data class FakeCalendarPermissionChecker(
    val read: Boolean,
    val write: Boolean,
) : CalendarPermissionChecker {
    override fun hasReadCalendar(): Boolean = read

    override fun hasWriteCalendar(): Boolean = write
}

