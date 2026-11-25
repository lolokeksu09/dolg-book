package dev.ventry.raschet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import dev.ventry.raschet.data.LedgerSnapshot
import dev.ventry.raschet.presentation.BookScreenData
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookCollectionsComposeTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 25)
    private fun fixture(): BookScreenData {
        val clients = listOf(LedgerStore.Client("a", "Алиса", "+7 900 123 45 67"),
            LedgerStore.Client("b", "Борис", ""))
        val entries = listOf(
            LedgerStore.Entry("d1", "a", "debt", 10000L, "2026-11-20T12:00:00Z", "2026-11-24", "", ""),
            LedgerStore.Entry("p1", "a", "payment", 3000L, "2026-11-21T12:00:00Z", "", "", ""),
            LedgerStore.Entry("d2", "b", "debt", 2000L, "2026-11-22T12:00:00Z", "", "", "")
        )
        return BookScreenData(LedgerSnapshot(clients, entries), today, CollectionsInsights.analyze(clients, entries, today))
    }

    @Test fun bookFiltersAllocateBeforeFilteringAndDateExpansionOpensExactEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = "qa_book_compose_" + UUID.randomUUID()
        try {
            val directory = ClientDirectory(context, storage)
            val data = fixture()
            val selectedDay = LocalDate.of(2026, 11, 20)
            val book = projectBook(data, false, selectedDay, selectedDay, "89001234567", true, directory,
                null, true, ZoneOffset.UTC)
            assertEquals(9000L, book.total)
            assertEquals(2, book.debtors)
            assertEquals(1, book.urgent)
            assertEquals(10000L, book.recorded)
            assertEquals(7000L, book.remaining)
            assertEquals(7000L, checkNotNull(book.days.single().unpaid["d1"]))
            assertTrue(book.needsBackup)
            val paymentBook = projectBook(data, true, null, null, "", false, directory, null, false, ZoneOffset.UTC)
            assertEquals(3000L, paymentBook.recorded)
            assertEquals(0L, paymentBook.remaining)
            assertFalse(paymentBook.needsBackup)
            val toggled = AtomicReference("")
            val opened = AtomicReference("")
            val actions = BookActions({}, {}, {}, {}, {}, {}, { toggled.set(it) }, {}, {},
                {}, { opened.set(it) }, {}, {}, {}, {}, {}, {})
            compose.setContent {
                LedgerMaterialTheme {
                    BookContent(book, false, true, "20.11.26", emptySet(), 30, false, actions)
                }
            }
            compose.onNodeWithTag("book-list").performScrollToIndex(7)
            compose.onNodeWithContentDescription("20.11.26, записей 1, записано 100,00 ₽, осталось 70,00 ₽, раскрыть").performClick()
            assertEquals("2026-11-20", toggled.get())
            compose.onNodeWithTag("book-list").performScrollToIndex(8)
            compose.onNodeWithContentDescription("Запись · Алиса · 100,00 ₽").performClick()
            assertEquals("d1", opened.get())
        } finally { context.deleteSharedPreferences(storage) }
    }

    @Test fun collectionProjectionKeepsPriorityAndSevenDayTotalsSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = "qa_collections_compose_" + UUID.randomUUID()
        try {
            val directory = ClientDirectory(context, storage)
            val model = projectCollections(fixture(), directory, ZoneOffset.UTC)
            assertEquals(9000L, model.total)
            assertEquals(7000L, model.overdue)
            assertEquals(0L, model.upcoming)
            assertEquals(listOf("a", "b"), model.work.map { it.status.client.id })
            assertEquals(listOf("a"), CollectionWorklist.select(model.work, "", true).map { it.status.client.id })
            assertEquals(listOf("a"), CollectionWorklist.select(model.work, "89001234567", false).map { it.status.client.id })
            val filter = AtomicReference("")
            val actions = CollectionActions({}, {}, {}, { filter.set(it) }, {}, {}, {})
            compose.setContent {
                LedgerMaterialTheme { CollectionContent(model, "", false, 60, false, actions) }
            }
            compose.onNodeWithText("К возврату всего").assertExists()
            compose.onNodeWithContentDescription("Просрочено").performClick()
            assertEquals(CollectionsInsights.LATE, filter.get())
        } finally { context.deleteSharedPreferences(storage) }
    }
}
