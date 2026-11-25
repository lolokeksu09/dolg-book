package dev.ventry.raschet

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class BookCalendarComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun monthGridIsMondayFirstAndHandlesLeapYear() {
        val feb = YearMonth.of(2024, 2)
        val weeks = calendarWeeks(feb)
        assertEquals(5, weeks.size)
        assertNull(weeks.first()[0])
        assertEquals(feb.atDay(1), weeks.first()[3])
        assertEquals(feb.atDay(29), weeks.last()[3])
        assertEquals(29, weeks.flatten().filterNotNull().size)
        val march = calendarWeeks(YearMonth.of(2026, 3))
        assertEquals(6, march.size)
        assertEquals(LocalDate.of(2026, 3, 1), march.first()[6])
        assertEquals(LocalDate.of(2026, 3, 31), march.last()[1])
    }

    @Test fun selectedDateAndMonthNavigationUseRealJournalTotals() {
        val today = LocalDate.of(2026, 11, 25)
        val date = today.minusDays(1)
        val day = BookDays.Day(date)
        day.add(LedgerStore.Entry("d","c","debt",15000L,"2026-11-24T12:00:00Z","","",""),false,10000L)
        val month = mutableStateOf(YearMonth.from(today))
        val selected = mutableStateOf(today)
        val desc = "24.11.26, записей 1, записано " + ledgerMoney(15000L) + ", осталось " + ledgerMoney(10000L)
        compose.setContent {
            LedgerMaterialTheme {
                BookCalendar(month.value, selected.value, today, mapOf(date to day), false,
                    onMonth = { month.value = it; selected.value = it.atDay(1) },
                    onDay = { selected.value = it })
            }
        }
        compose.onNodeWithContentDescription(desc).performClick()
        compose.onNodeWithContentDescription(desc).assertIsSelected()
        assertEquals(date, selected.value)
        compose.onNodeWithContentDescription("25.11.26, сегодня, нет записей").performClick()
        assertEquals(today, selected.value)
        compose.onNodeWithContentDescription("Предыдущий месяц").performClick()
        compose.onNodeWithText("Октябрь 2026").assertExists()
        assertEquals(YearMonth.of(2026, 10), month.value)
    }
}
