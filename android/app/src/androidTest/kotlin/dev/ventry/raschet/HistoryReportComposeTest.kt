package dev.ventry.raschet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import dev.ventry.raschet.data.LedgerSnapshot
import dev.ventry.raschet.presentation.BookScreenData
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryReportComposeTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 11, 25)
    private fun fixture(): BookScreenData {
        val clients = listOf(LedgerStore.Client("a", "Алиса", "+7 900 123 45 67"))
        val entries = listOf(
            LedgerStore.Entry("d", "a", "debt", 10000L, "2026-11-20T12:00:00Z", "", "Яблоки", ""),
            LedgerStore.Entry("p", "a", "payment", 3000L, "2026-11-21T12:00:00Z", "", "Оплата", ""),
            LedgerStore.Entry("r", "a", "reversal", 3000L, "2026-11-22T12:00:00Z", "", "Отмена оплаты", "p")
        )
        return BookScreenData(LedgerSnapshot(clients, entries), day, CollectionsInsights.analyze(clients, entries, day))
    }

    @Test fun historyKeepsReversalAndExactOperationIdentity() {
        val data = fixture()
        val people = data.snapshot.clients.associateBy { it.id }
        val rows = projectHistory(data, people, "a", "89001234567", null, null, "Все", ZoneOffset.UTC)
        assertEquals(listOf("r", "p", "d"), rows.map { it.entry.id })
        assertTrue(rows[1].canceled)
        assertFalse(rows[0].canceled)
        val filtered = projectHistory(data, people, null, "яблоки", null, null, "Долги", ZoneOffset.UTC)
        assertEquals(listOf("d"), filtered.map { it.entry.id })
        val opened = AtomicReference("")
        val actions = HistoryActions({}, {}, {}, {}, {}, {}, {}, {}, { opened.set(it) }, {})
        compose.setContent {
            LedgerMaterialTheme {
                HistoryContent(data, null, "яблоки", null, null, "Долги", 100, false, actions)
            }
        }
        compose.onNodeWithTag("history-list").performScrollToIndex(4)
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Запись · 100,00 ₽").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Запись · 100,00 ₽").performClick()
        assertEquals("d", opened.get())
    }

    @Test fun reportSeparatesReceiptsAndReversalsAndPreservesExportAction() {
        val report = PeriodReport.build(fixture().snapshot.entries, day.minusDays(4), day, ZoneOffset.UTC)
        assertEquals(10000L, report.opening)
        assertEquals(10000L, report.closing)
        assertEquals(3000L, report.totals.payments)
        assertEquals(3000L, report.totals.paymentReversals)
        assertEquals(0L, report.totals.net())
        assertFalse(report.csv().contains("Алиса"))
        val export = AtomicInteger()
        compose.setContent {
            LedgerMaterialTheme {
                ReportContent(report, 14, ReportActions({}, {}, { export.incrementAndGet() }, {}))
            }
        }
        compose.onNodeWithText("Остаток на конец").assertExists()
        compose.onNodeWithTag("report-list").performScrollToIndex(3)
        compose.onNodeWithText("Экспорт отчёта CSV").performClick()
        assertEquals(1, export.get())
    }
}
