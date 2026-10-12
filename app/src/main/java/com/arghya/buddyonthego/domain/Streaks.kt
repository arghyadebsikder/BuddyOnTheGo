package com.arghya.buddyonthego.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class StreakResult(val current: Int, val best: Int)

/** HAB-3. scheduleType: 0 daily, 1 weekdays (daysMask), 2 N times per week. */
object Streaks {
    fun compute(
        scheduleType: Int,
        daysMask: Int,
        targetPerWeek: Int,
        done: Set<LocalDate>,
        skipped: Set<LocalDate>,
        today: LocalDate,
        grace: Boolean,
    ): StreakResult {
        if (done.isEmpty()) return StreakResult(0, 0)
        val start = done.min()
        return if (scheduleType == 2) weekly(targetPerWeek.coerceAtLeast(1), done, start, today)
        else daily(scheduleType, daysMask, done, skipped, start, today, grace)
    }

    private fun scheduled(type: Int, mask: Int, d: LocalDate): Boolean =
        if (type == 0) true else (mask and (1 shl (d.dayOfWeek.value - 1))) != 0

    private fun daily(
        type: Int, mask: Int, done: Set<LocalDate>, skipped: Set<LocalDate>,
        start: LocalDate, today: LocalDate, grace: Boolean,
    ): StreakResult {
        var cur = 0
        var best = 0
        var graceAvailable = grace
        var d = start
        while (!d.isAfter(today)) {
            // A completed day always counts, even if it was not on the schedule.
            if (d in done) {
                cur++
                if (cur > best) best = cur
            } else if (scheduled(type, mask, d) && d !in skipped && d != today) {
                if (graceAvailable) graceAvailable = false else { cur = 0; graceAvailable = grace }
            }
            d = d.plusDays(1)
        }
        return StreakResult(cur, best)
    }

    private fun weekly(target: Int, done: Set<LocalDate>, start: LocalDate, today: LocalDate): StreakResult {
        val monday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        val thisWeek = today.with(monday)
        var week = start.with(monday)
        var cur = 0
        var best = 0
        while (!week.isAfter(thisWeek)) {
            val end = week.plusDays(6)
            val count = done.count { !it.isBefore(week) && !it.isAfter(end) }
            if (count >= target) {
                cur++
                if (cur > best) best = cur
            } else if (week != thisWeek) {
                cur = 0
            }
            week = week.plusWeeks(1)
        }
        return StreakResult(cur, best)
    }
}
