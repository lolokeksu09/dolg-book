package dev.ventry.raschet

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import java.util.concurrent.atomic.AtomicReference
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

    @Test fun calendarOpensOnlySelectedDateAndAddsDebtForThatDate() {
        val today = LocalDate.of(2026, 11, 25)
        val first = today.minusDays(1)
        val second = today.minusDays(2)
        val a = LedgerStore.Client("a", "Алиса", "")
        val b = LedgerStore.Client("b", "Борис", "")
        val d1 = BookDays.Day(first)
        val d2 = BookDays.Day(second)
        d1.add(LedgerStore.Entry("a1","a","debt",10000L,"2026-11-24T12:00:00Z","","",""),false,7000L)
        d2.add(LedgerStore.Entry("b1","b","debt",20000L,"2026-11-23T12:00:00Z","","",""),false,20000L)
        val chosen = AtomicReference<LocalDate>()
        val added = AtomicReference<LocalDate>()
        val model = BookUiModel(27000L, 2, 0, mapOf("a" to a,"b" to b), listOf(d1,d2),
            true, 30000L, 27000L, null, false, today)
        val actions = BookActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            { added.set(it) }, {}, {}, {})
        compose.setContent {
            LedgerMaterialTheme {
                BookContent(model, false, false, "Все даты", emptySet(), 30, false, actions,
                    true, today, { _, date -> chosen.set(date) })
            }
        }
        val firstDesc = "24.11.26, записей 1, записано " + ledgerMoney(10000L) + ", осталось " + ledgerMoney(7000L)
        val secondDesc = "23.11.26, записей 1, записано " + ledgerMoney(20000L) + ", осталось " + ledgerMoney(20000L)
        val list = compose.onNodeWithTag("book-list")
        list.performScrollToNode(hasContentDescription(firstDesc))
        compose.onNodeWithContentDescription(firstDesc).performClick()
        assertEquals(first, chosen.get())
        list.performScrollToNode(hasText("Алиса"))
        compose.onNodeWithText("Алиса").assertExists()
        compose.onNodeWithText("Борис").assertDoesNotExist()
        list.performScrollToNode(hasContentDescription(secondDesc))
        compose.onNodeWithContentDescription(secondDesc).performClick()
        list.performScrollToNode(hasText("Борис"))
        compose.onNodeWithText("Алиса").assertDoesNotExist()
        list.performScrollToNode(hasText("+ Долг за этот день"))
        compose.onNodeWithText("+ Долг за этот день").performClick()
        assertEquals(second, added.get())
    }

}
