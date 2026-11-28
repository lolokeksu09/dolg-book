package dev.ventry.raschet.presentation

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BookFiltersRestoreTest {
    @Test fun restoresLegacyKeysAndKeepsNewStateAfterRecreation() {
        val day = LocalDate.of(2026, 11, 21)
        val vm = BookFiltersViewModel()
        vm.restore(Bundle().apply {
            putString("book_from", day.toString()); putString("book_to", day.toString())
            putString("search", "Alex"); putString("history_filter", "Оплаты")
            putString("collection_query", "Beta")
            putStringArrayList("expanded_book_days", arrayListOf(day.toString()))
        })
        assertEquals("Alex", vm.clients.query)
        assertEquals("Оплаты", vm.history.filter)
        assertEquals("Beta", vm.queue.query)
        assertEquals(day, vm.book.from)
        vm.setClientQuery("New")
        vm.restore(Bundle().apply { putString("search", "Old") })
        assertEquals("New", vm.clients.query)
        vm.setBookMode(true)
        val saved = Bundle()
        vm.save(saved)
        val restored = BookFiltersViewModel()
        restored.restore(saved)
        assertEquals(vm.state.value, restored.state.value)
        assertTrue(restored.book.autoExpand)
    }
    @Test fun malformedLegacyDatesDoNotCrashOrLeaveHalfRange() {
        val vm = BookFiltersViewModel()
        vm.restore(Bundle().apply {
            putString("book_from", "not a date"); putString("book_to", "2026-11-21")
            putString("report_from", "2026-11-22"); putString("report_to", "2026-11-21")
        })
        assertNull(vm.book.from); assertNull(vm.book.to)
        assertFalse(vm.report.to.isBefore(vm.report.from))
    }
}
