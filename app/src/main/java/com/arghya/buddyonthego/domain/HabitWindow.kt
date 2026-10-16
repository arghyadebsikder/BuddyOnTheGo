package com.arghya.buddyonthego.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A habit/task that has a start and a finish time. daysMask bit 0 = Monday ... bit 6 = Sunday.
 * A finish time at or before the start time means the window runs past midnight.
 */
object HabitWindow {
    data class Window(val startMs: Long, val endMs: Long) {
        /** 0f at the start, 1f at the finish. */
        fun progress(nowMs: Long): Float =
            if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

    /** Length in minutes of a window. Equal start and finish count as a full 24 hours. */
    fun lengthMinutes(startMin: Int, endMin: Int): Int =
        if (endMin > startMin) endMin - startMin else endMin + 1440 - startMin

    /** The window that contains [nowMs], or null. Looks at yesterday too, for windows that cross midnight. */
    fun activeAt(startMin: Int, endMin: Int, daysMask: Int, nowMs: Long, zone: ZoneId): Window? {
        if (startMin < 0 || endMin < 0) return null
        val mask = if (daysMask == 0) 127 else daysMask
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate()
        for (back in 1 downTo 0) {
            val date = today.minusDays(back.toLong())
            if (mask and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
            val s = ZonedDateTime.of(date, LocalTime.of(startMin / 60, startMin % 60), zone)
            val e = s.plusMinutes(lengthMinutes(startMin, endMin).toLong())
            val sMs = s.toInstant().toEpochMilli()
            val eMs = e.toInstant().toEpochMilli()
            if (nowMs in sMs until eMs) return Window(sMs, eMs)
        }
        return null
    }

    /** Start of the next window after [nowMs]. */
    fun nextStart(startMin: Int, daysMask: Int, nowMs: Long, zone: ZoneId): Long? {
        if (startMin < 0) return null
        val mask = if (daysMask == 0) 127 else daysMask
        return NextTrigger.next(startMin / 60, startMin % 60, mask, nowMs, zone)
    }
}
