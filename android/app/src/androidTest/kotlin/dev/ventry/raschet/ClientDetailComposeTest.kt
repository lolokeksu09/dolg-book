package dev.ventry.raschet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.ventry.raschet.data.LedgerSnapshot
import dev.ventry.raschet.presentation.BookScreenData
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ClientDetailComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statementAndActionsPreserveExistingFinancialContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directoryName = "qa_detail_directory_" + UUID.randomUUID()
        val draftsName = "qa_detail_drafts_" + UUID.randomUUID()
        try {
            val directory = ClientDirectory(context, directoryName)
            val drafts = LedgerDrafts(context, draftsName)
            val client = LedgerStore.Client("client", "Тестовый клиент", "")
            val today = LocalDate.of(2026, 11, 25)
            val entries = listOf(
                LedgerStore.Entry("debt", "client", "debt", 10000L, "2026-11-20T12:00:00Z", "2026-11-24", "", ""),
                LedgerStore.Entry("payment", "client", "payment", 3000L, "2026-11-21T12:00:00Z", "", "", "")
            )
            val statuses = CollectionsInsights.analyze(listOf(client), entries, today)
            val snapshot = BookScreenData(LedgerSnapshot(listOf(client), entries), today, statuses)
            directory.setDebtLimit(client.id, 6000L)
            val all = checkNotNull(detailProjection(snapshot, client.id, ClientStatement.ALL, directory, drafts, ZoneOffset.UTC))
            assertEquals(7000L, all.status.balance)
            assertEquals(2, all.historyCount)
            val unpaid = checkNotNull(detailProjection(snapshot, client.id, ClientStatement.UNPAID, directory, drafts, ZoneOffset.UTC))
            assertEquals(listOf("debt"), unpaid.rows.map { it.entry.id })
            assertEquals(7000L, checkNotNull(unpaid.rows.single().remaining))
            assertNull(detailProjection(snapshot, "missing", ClientStatement.ALL, directory, drafts, ZoneOffset.UTC))
            assertNotNull(all.notices.find { it.text.startsWith("Лимит:") })
            val paymentCalls = AtomicInteger()
            val opened = AtomicReference("")
            val actions = DetailActions({}, {}, {}, { paymentCalls.incrementAndGet() }, {}, {},
                { opened.set(it) }, {}, {})
            compose.setContent {
                LedgerMaterialTheme {
                    ClientDetailContent(unpaid, ClientStatement.UNPAID, 80, false, false, actions)
                }
            }
            compose.onNodeWithText("Остаток долга").assertExists()
            compose.onNodeWithText("Оплата").performClick()
            assertEquals(1, paymentCalls.get())
            compose.onNodeWithTag("client-detail-list").performScrollToIndex(4)
            compose.onNodeWithContentDescription("Запись · 100,00 ₽").performClick()
            assertEquals("debt", opened.get())
        } finally {
            context.deleteSharedPreferences(directoryName)
            context.deleteSharedPreferences(draftsName)
        }
    }

    @Test fun settledClientOffersDebtButNotMeaninglessPayment() {
        val client = LedgerStore.Client("settled", "Погашенный клиент", "")
        val status = CollectionsInsights.CustomerStatus(client, 0L, 0L, 0L, null)
        val model = DetailData(status, false, emptyList(), emptyList(), emptyList(), 0)
        val debts = AtomicInteger()
        val payments = AtomicInteger()
        val actions = DetailActions({}, {}, { debts.incrementAndGet() }, { payments.incrementAndGet() },
            {}, {}, {}, {}, {})
        compose.setContent {
            LedgerMaterialTheme {
                ClientDetailContent(model, ClientStatement.ALL, 80, false, false, actions)
            }
        }
        compose.onNodeWithText("Остаток долга").assertExists()
        compose.onNodeWithText("Оплата").assertDoesNotExist()
        compose.onNodeWithText("+ Долг").performClick()
        assertEquals(1, debts.get())
        assertEquals(0, payments.get())
    }

}
