package dev.ventry.raschet.presentation

import dev.ventry.raschet.data.WriteDestination
import dev.ventry.raschet.data.WriteReceipt
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BookFiltersViewModelTest {
    private val day = LocalDate.of(2026, 11, 21)
    @Test fun changingModeClearsIncompatibleBookFiltersAndExpansions() {
        val vm = BookFiltersViewModel()
        vm.applyBookFilters("  Alex  ", day, day, true)
        vm.toggleBookDay(day.toString())
        vm.setBookLimit(90)
        assertTrue(vm.book.hasFilter())
        assertTrue(vm.book.onlyUnpaid)
        vm.setBookMode(true)
        assertTrue(vm.book.payments)
        assertFalse(vm.book.hasFilter())
        assertEquals(30, vm.book.limit)
        assertTrue(vm.book.expanded.isEmpty())
        assertTrue(vm.book.autoExpand)
        vm.finishBookAutoExpand(day.toString())
        assertFalse(vm.book.autoExpand)
        assertEquals(setOf(day.toString()), vm.book.expanded)
        vm.applyBookFilters("Alex", day, day, true)
        assertFalse(vm.book.onlyUnpaid)
    }
    @Test fun clientAndDetailActionsKeepOtherScreensIndependent() {
        val vm = BookFiltersViewModel()
        vm.setClientQuery("Alex")
        vm.setClientLimit(120)
        vm.setClientSort(true, false)
        assertEquals(60, vm.clients.limit)
        assertEquals("Alex", vm.clients.query)
        vm.focusDetail("a")
        vm.setDetailFilter("Оплаты")
        vm.setDetailLimit(160)
        vm.focusDetail("a")
        assertEquals(160, vm.detail.limit)
        vm.focusDetail("b")
        assertEquals(80, vm.detail.limit)
        assertEquals("Все", vm.detail.filter)
        assertTrue(vm.clients.amountFirst)
        assertEquals("Alex", vm.clients.query)
    }
    @Test fun invalidPeriodDoesNotChangeHistoryOrReport() {
        val vm = BookFiltersViewModel()
        val before = vm.state.value
        assertFalse(vm.setPeriod(false, day, day.minusDays(1)))
        assertFalse(vm.setPeriod(true, day, day.plusDays(366)))
        assertEquals(before, vm.state.value)
        assertTrue(vm.setPeriod(true, day, day.plusDays(2)))
        vm.openReportHistory()
        assertEquals(day, vm.history.from)
        assertEquals(day.plusDays(2), vm.history.to)
        assertNull(vm.history.clientId)
    }
    @Test fun paymentReceiptReturnsToItsDateWithoutOldFilter() {
        val vm = BookFiltersViewModel()
        vm.applyBookFilters("Other", day.minusDays(1), day.minusDays(1), true)
        vm.toggleBookDay(day.minusDays(1).toString())
        vm.onWrite(WriteReceipt(WriteDestination.BOOK, "payment", "id", day, 75, false), BookNavigationViewModel.Screen.BOOK)
        assertTrue(vm.book.payments)
        assertFalse(vm.book.hasFilter())
        assertEquals(75, vm.book.limit)
        assertEquals(day, vm.book.calendarDay)
        assertEquals(setOf(day.toString()), vm.book.expanded)
    }
    @Test fun detailDebtFromBookDoesNotChangeBookFilters() {
        val vm = BookFiltersViewModel()
        vm.applyBookFilters("Alex", day, day, true)
        val old = vm.book
        vm.onWrite(WriteReceipt(WriteDestination.DETAIL, "debt", "id", day, 1, false), BookNavigationViewModel.Screen.BOOK)
        assertEquals(old, vm.book)
    }
    @Test fun queueResetsOnlyItsOwnState() {
        val vm = BookFiltersViewModel()
        vm.setQueueQuery("Alex")
        vm.setQueueUrgent(true)
        vm.setQueuePlan("PROMISE")
        vm.setQueueLimit(120)
        vm.setClientQuery("Other")
        vm.openQueue()
        assertEquals(BookFiltersViewModel.CollectionsQueue(), vm.queue)
        assertEquals("Other", vm.clients.query)
    }
}
