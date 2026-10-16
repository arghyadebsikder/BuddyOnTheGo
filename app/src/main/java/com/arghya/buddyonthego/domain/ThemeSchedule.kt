package com.arghya.buddyonthego.domain

/** Auto theme: light from [dayStart] until [nightStart], dark the rest of the day. Both are minutes from midnight. */
object ThemeSchedule {
    const val DEFAULT_DAY = 6 * 60 + 30
    const val DEFAULT_NIGHT = 18 * 60 + 30

    fun isDark(nowMinute: Int, dayStart: Int, nightStart: Int): Boolean =
        if (dayStart == nightStart) false
        else if (dayStart < nightStart) nowMinute < dayStart || nowMinute >= nightStart
        else nowMinute in nightStart until dayStart // night start is earlier in the day than day start (unusual)
}
