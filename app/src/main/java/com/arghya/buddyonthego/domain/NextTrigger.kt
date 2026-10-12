package com.arghya.buddyonthego.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** ALM-3. daysMask bit 0 = Monday ... bit 6 = Sunday. A mask of 0 means a one-time alarm. */
object NextTrigger {
    fun next(
        hour: Int,
        minute: Int,
        daysMask: Int,
        nowMs: Long,
        zone: ZoneId,
        skipDate: LocalDate? = null,
    ): Long? {
        val today = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone).toLocalDate()
        val span = if (daysMask == 0) 2 else 15
        for (offset in 0 until span) {
            val date = today.plusDays(offset.toLong())
            if (daysMask != 0) {
                val bit = date.dayOfWeek.value - 1
                if (daysMask and (1 shl bit) == 0) continue
                if (skipDate != null && date == skipDate) continue
            }
            // ZonedDateTime.of moves a non-existent (DST gap) time forward and picks the earlier offset for a repeated time.
            val candidate = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
            if (candidate > nowMs) return candidate
        }
        return null
    }

    fun dateOf(ms: Long, zone: ZoneId): LocalDate =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toLocalDate()
}
