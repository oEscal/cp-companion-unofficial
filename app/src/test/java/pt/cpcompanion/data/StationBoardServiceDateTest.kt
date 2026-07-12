package pt.cpcompanion.data

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class StationBoardServiceDateTest {
    private val date = LocalDate.of(2026, 7, 12)

    @Test
    fun afterMidnightEntryFromLateBoardUsesNextDay() {
        assertEquals(
            date.plusDays(1),
            resolveBoardServiceDate(date, LocalTime.of(23, 30), "00:15"),
        )
    }

    @Test
    fun sameEveningEntryKeepsRequestDay() {
        assertEquals(
            date,
            resolveBoardServiceDate(date, LocalTime.of(21, 0), "23:45"),
        )
    }

    @Test
    fun smallClockDifferenceDoesNotAssumeRollover() {
        assertEquals(
            date,
            resolveBoardServiceDate(date, LocalTime.of(1, 0), "00:30"),
        )
    }
}
