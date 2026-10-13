package io.wutz.apollo.util

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CronExpressionFormatterTest {
    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun f(cron: String): UiText = CronExpressionFormatter.cronToHumanReadable(cron)

    private fun res(
        id: Int,
        vararg args: Any,
    ) = UiText.res(id, *args)

    private val midnight = res(R.string.cron_time_midnight)

    @Test
    fun testCronToHumanReadable() {
        assertEquals(
            res(
                R.string.cron_desc_daily_at,
                res(R.string.cron_list_last, UiText.raw("09:00"), UiText.raw("21:00")),
            ),
            f("0 9,21 * * *"),
        )
        assertEquals(
            res(
                R.string.cron_desc_at_time_day_range,
                UiText.raw("08:30"),
                res(R.string.cron_dow_monday),
                res(R.string.cron_dow_friday),
            ),
            f("30 8 * * 1-5"),
        )
        assertEquals(res(R.string.cron_desc_every_day_midnight), f("0 0 * * *"))
        assertEquals(UiText.plural(R.plurals.cron_desc_every_minutes, 15), f("*/15 * * * *"))
        assertEquals(
            res(R.string.cron_desc_every_weekday_at, res(R.string.cron_dow_monday), UiText.raw("09:00")),
            f("0 9 * * 1"),
        )
        assertEquals(
            res(R.string.cron_desc_every_weekday_at, res(R.string.cron_dow_sunday), UiText.raw("06:00")),
            f("0 6 * * 0"),
        )
        assertEquals(
            res(R.string.cron_desc_monthly_on_day_at, res(R.string.cron_ordinal_st, 1), UiText.raw("12:00")),
            f("0 12 1 * *"),
        )
        assertEquals(
            res(
                R.string.cron_desc_on_month_day_at,
                res(R.string.cron_month_january),
                res(R.string.cron_ordinal_st, 1),
                midnight,
            ),
            f("0 0 1 1 *"),
        )
        assertEquals(UiText.plural(R.plurals.cron_desc_every_hours, 2), f("0 */2 * * *"))
    }

    @Test
    fun testListOfThreeTimesAndOrdinals() {
        assertEquals(
            res(
                R.string.cron_desc_daily_at,
                res(
                    R.string.cron_list_last,
                    res(R.string.cron_list_separator, midnight, UiText.raw("09:00")),
                    UiText.raw("21:00"),
                ),
            ),
            f("0 0,9,21 * * *"),
        )
        assertEquals(
            res(R.string.cron_desc_monthly_on_day_at, res(R.string.cron_ordinal_nd, 22), UiText.raw("12:00")),
            f("0 12 22 * *"),
        )
        assertEquals(
            res(R.string.cron_desc_monthly_on_day_at, res(R.string.cron_ordinal_th, 13), UiText.raw("12:00")),
            f("0 12 13 * *"),
        )
        assertEquals(
            res(R.string.cron_desc_monthly_on_day_at, res(R.string.cron_ordinal_rd, 3), UiText.raw("12:00")),
            f("0 12 3 * *"),
        )
    }

    @Test
    fun testMalformedCron() {
        assertEquals(UiText.raw("invalid cron string"), f("invalid cron string"))
        assertEquals(UiText.raw("0 9 * *"), f("0 9 * *"))
    }

    @Test
    fun testExceptionFallback() {
        // This will cause an IndexOutOfBoundsException because 15 is out of bounds for monthNames
        assertEquals(res(R.string.cron_desc_raw, "0 12 1 15 *"), f("0 12 1 15 *"))

        // This will cause an IndexOutOfBoundsException because 8 is out of bounds for dowNames
        assertEquals(res(R.string.cron_desc_raw, "0 9 * * 8"), f("0 9 * * 8"))

        // Month 0 does not exist; it must not turn into a resource id of 0.
        assertEquals(res(R.string.cron_desc_raw, "0 12 1 0 *"), f("0 12 1 0 *"))
    }
}
