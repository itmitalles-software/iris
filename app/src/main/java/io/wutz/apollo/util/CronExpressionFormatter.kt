package io.wutz.apollo.util

import io.wutz.apollo.BuildConfig
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText

/**
 * Describes a five-field cron expression in words. Returns [UiText] so the description is rendered in the
 * current locale (Apollo, issue #10); anything it cannot describe comes back unchanged as [UiText.Raw].
 */
object CronExpressionFormatter {
    private val dowNames =
        listOf(
            R.string.cron_dow_sunday,
            R.string.cron_dow_monday,
            R.string.cron_dow_tuesday,
            R.string.cron_dow_wednesday,
            R.string.cron_dow_thursday,
            R.string.cron_dow_friday,
            R.string.cron_dow_saturday,
            R.string.cron_dow_sunday,
        )
    private val monthNames =
        listOf(
            0,
            R.string.cron_month_january,
            R.string.cron_month_february,
            R.string.cron_month_march,
            R.string.cron_month_april,
            R.string.cron_month_may,
            R.string.cron_month_june,
            R.string.cron_month_july,
            R.string.cron_month_august,
            R.string.cron_month_september,
            R.string.cron_month_october,
            R.string.cron_month_november,
            R.string.cron_month_december,
        )

    fun cronToHumanReadable(schedule: String): UiText {
        val clean = schedule.trim().replace("\\s+".toRegex(), " ")
        val parts = clean.split(" ")
        if (parts.size != 5) {
            return UiText.raw(schedule)
        }

        try {
            val minutePart = parts[0]
            val hourPart = parts[1]
            val domPart = parts[2]
            val monthPart = parts[3]
            val dowPart = parts[4]

            parseSimpleShortcuts(minutePart, hourPart, domPart, monthPart, dowPart)?.let { return it }

            val hoursList = parseField(hourPart, 0, 23)
            val minutesList = parseField(minutePart, 0, 59)

            parseDayOfMonthAndMonth(domPart, monthPart, dowPart, hoursList, minutesList)?.let { return it }
            parseDaysOfWeek(dowPart, domPart, monthPart, hoursList, minutesList)?.let { return it }
            parseGeneralTime(domPart, monthPart, dowPart, hoursList, minutesList)?.let { return it }

            // Fallback
            return UiText.raw(schedule)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w("CronExpressionFormatter", "Failed to parse cron: $schedule", e)
            }
            return UiText.res(R.string.cron_desc_raw, schedule)
        }
    }

    private fun parseSimpleShortcuts(
        minutePart: String,
        hourPart: String,
        domPart: String,
        monthPart: String,
        dowPart: String,
    ): UiText? {
        if (minutePart.startsWith("*/") && hourPart == "*" && domPart == "*" && monthPart == "*" && dowPart == "*") {
            val step = minutePart.substring(2).toIntOrNull()
            if (step != null) return UiText.plural(R.plurals.cron_desc_every_minutes, step)
        }
        if (minutePart == "0" && hourPart.startsWith("*/") && domPart == "*" && monthPart == "*" && dowPart == "*") {
            val step = hourPart.substring(2).toIntOrNull()
            if (step != null) return UiText.plural(R.plurals.cron_desc_every_hours, step)
        }
        if (minutePart == "0" && hourPart == "0" && domPart == "*" && monthPart == "*" && dowPart == "*") {
            return UiText.res(R.string.cron_desc_every_day_midnight)
        }
        return null
    }

    private fun parseDayOfMonthAndMonth(
        domPart: String,
        monthPart: String,
        dowPart: String,
        hoursList: List<Int>,
        minutesList: List<Int>,
    ): UiText? {
        if (domPart != "*" && monthPart != "*" && dowPart == "*") {
            val doms = parseField(domPart, 1, 31)
            val months = parseField(monthPart, 1, 12)
            if (doms.size == 1 && months.size == 1 && hoursList.size == 1 && minutesList.size == 1) {
                val time = formatTime(hoursList[0], minutesList[0])
                // Month 0 is not a valid cron month; fail into the raw fallback instead of resolving id 0.
                val monthRes = monthNames[months[0]].takeIf { it != 0 } ?: error("Invalid month: ${months[0]}")
                val month = UiText.res(monthRes)
                val dom = formatOrdinal(doms[0])
                return UiText.res(R.string.cron_desc_on_month_day_at, month, dom, time)
            }
        }
        if (domPart != "*" && monthPart == "*" && dowPart == "*") {
            val doms = parseField(domPart, 1, 31)
            if (doms.size == 1 && hoursList.size == 1 && minutesList.size == 1) {
                val time = formatTime(hoursList[0], minutesList[0])
                val dom = formatOrdinal(doms[0])
                return UiText.res(R.string.cron_desc_monthly_on_day_at, dom, time)
            }
        }
        return null
    }

    private fun parseDaysOfWeek(
        dowPart: String,
        domPart: String,
        monthPart: String,
        hoursList: List<Int>,
        minutesList: List<Int>,
    ): UiText? {
        if (dowPart == "*" || domPart != "*" || monthPart != "*") return null
        if (hoursList.size != 1 || minutesList.size != 1) return null

        val time = formatTime(hoursList[0], minutesList[0])

        if (dowPart.contains("-")) {
            val bounds = dowPart.split("-")
            if (bounds.size == 2) {
                val start = bounds[0].toIntOrNull() ?: -1
                val end = bounds[1].toIntOrNull() ?: -1
                if (start in 0..7 && end in 0..7) {
                    val startName = UiText.res(dowNames[start])
                    val endName = UiText.res(dowNames[end])
                    return UiText.res(R.string.cron_desc_at_time_day_range, time, startName, endName)
                }
            }
        }

        val dows = parseField(dowPart, 0, 7)
        if (dows.size == 1) {
            val dowName = UiText.res(dowNames[dows[0]])
            return UiText.res(R.string.cron_desc_every_weekday_at, dowName, time)
        }

        return null
    }

    private fun parseGeneralTime(
        domPart: String,
        monthPart: String,
        dowPart: String,
        hoursList: List<Int>,
        minutesList: List<Int>,
    ): UiText? {
        if (domPart == "*" && monthPart == "*" && dowPart == "*") {
            if (hoursList.isNotEmpty() && minutesList.size == 1) {
                val times = hoursList.map { formatTime(it, minutesList[0]) }
                val timeDesc =
                    if (times.size > 1) {
                        val leading =
                            times.dropLast(1).reduce {
                                acc,
                                next,
                                ->
                                UiText.res(R.string.cron_list_separator, acc, next)
                            }
                        UiText.res(R.string.cron_list_last, leading, times.last())
                    } else {
                        times[0]
                    }
                return UiText.res(R.string.cron_desc_daily_at, timeDesc)
            }
        }
        return null
    }

    private fun formatTime(
        h: Int,
        m: Int,
    ): UiText {
        if (h == 0 && m == 0) return UiText.res(R.string.cron_time_midnight)
        return UiText.raw(String.format(java.util.Locale.ROOT, "%02d:%02d", h, m))
    }

    /** English picks st/nd/rd/th by these rules; other locales may give all four the same form (e.g. "1."). */
    private fun formatOrdinal(d: Int): UiText =
        when {
            d % 100 in 11..13 -> UiText.res(R.string.cron_ordinal_th, d)
            d % 10 == 1 -> UiText.res(R.string.cron_ordinal_st, d)
            d % 10 == 2 -> UiText.res(R.string.cron_ordinal_nd, d)
            d % 10 == 3 -> UiText.res(R.string.cron_ordinal_rd, d)
            else -> UiText.res(R.string.cron_ordinal_th, d)
        }

    private fun parseField(
        field: String,
        min: Int,
        max: Int,
    ): List<Int> {
        if (field == "*") return (min..max).toList()
        if (field.contains(",")) {
            return field
                .split(",")
                .flatMap { parseField(it, min, max) }
                .distinct()
                .sorted()
        }
        if (field.contains("-")) {
            val parts = field.split("-")
            if (parts.size == 2) {
                val start = parts[0].toIntOrNull() ?: return emptyList()
                val end = parts[1].toIntOrNull() ?: return emptyList()
                return (start..end).toList()
            }
        }
        val single = field.toIntOrNull()
        if (single != null) {
            return listOf(single)
        }
        return emptyList()
    }
}
