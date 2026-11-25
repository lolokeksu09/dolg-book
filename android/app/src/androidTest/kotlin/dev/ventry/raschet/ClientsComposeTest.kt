package dev.ventry.raschet

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ClientsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyBookOffersFirstClientWithoutRedundantFilters() {
        val additions = AtomicInteger()
        val actions = ClientListActions({}, {}, { additions.incrementAndGet() }, {}, {}, {}, {})
        compose.setContent {
            LedgerMaterialTheme {
                ClientListContent(ClientListData(0, emptyList(), emptyMap()),
                    ClientDirectory.ACTIVE, CollectionsInsights.ALL, false, false,
                    null, "", 60, false, actions)
            }
        }
        compose.onNodeWithText("Пока нет клиентов").assertExists()
        compose.onNodeWithText("Фильтры клиентов").assertDoesNotExist()
        compose.onNodeWithText("Добавить первого клиента").performClick()
        assertEquals(1, additions.get())
    }

    @Test fun searchSelectionAndFilterActionsUseExistingClientModel() {
        val alice = CollectionsInsights.CustomerStatus(
            LedgerStore.Client("alice", "Алиса", "+7 900 123 45 67"), 150000, 50000, 0, null
        )
        val boris = CollectionsInsights.CustomerStatus(
            LedgerStore.Client("boris", "Борис", ""), 0, 0, 0, LocalDate.of(2026, 11, 25)
        )
        val query = AtomicReference("")
        val opened = AtomicReference("")
        val filters = AtomicInteger()
        val actions = ClientListActions({ query.set(it) }, {}, {}, { filters.incrementAndGet() },
            { opened.set(it) }, {}, {})
        compose.setContent {
            LedgerMaterialTheme {
                ClientListContent(ClientListData(2, listOf(alice, boris), mapOf("alice" to 100000L)),
                    ClientDirectory.ACTIVE, CollectionsInsights.ALL, false, false,
                    "alice", "", 60, false, actions)
            }
        }
        val aliceDescription = "Алиса, остаток 1 500,00 ₽, просрочено 500,00 ₽, лимит превышен"
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription(aliceDescription).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(aliceDescription).assertIsSelected().performClick()
        assertEquals("alice", opened.get())
        compose.onNodeWithContentDescription("Фильтры клиентов").performClick()
        assertEquals(1, filters.get())
        compose.onNodeWithContentDescription("Поиск по имени или телефону").performTextInput("89001234567")
        compose.waitUntil(10000) {
            query.get() == "89001234567" &&
                compose.onAllNodesWithContentDescription("Борис, остаток 0,00 ₽").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription(aliceDescription).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(aliceDescription).assertExists()
        compose.onNodeWithContentDescription("Борис, остаток 0,00 ₽").assertDoesNotExist()
    }
}
