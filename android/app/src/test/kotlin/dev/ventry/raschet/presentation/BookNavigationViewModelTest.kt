package dev.ventry.raschet.presentation

import dev.ventry.raschet.data.WriteDestination
import dev.ventry.raschet.presentation.BookNavigationViewModel.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookNavigationViewModelTest {
    @Test
    fun clientReturnsToItsOriginAfterWritingAnOperation() {
        for (origin in listOf(Screen.BOOK, Screen.COLLECTIONS, Screen.CLIENTS)) {
            val navigation = BookNavigationViewModel()
            navigation.openClient("customer", origin)
            navigation.onWrite(WriteDestination.DETAIL, "customer")

            assertEquals(Screen.CLIENTS, navigation.state.screen)
            assertEquals("customer", navigation.state.clientId)
            assertTrue(navigation.back())
            assertEquals(origin, navigation.state.screen)
            assertNull(navigation.state.clientId)
        }
    }

    @Test
    fun historyFromAnalyticsReturnsThroughTheSameCustomerToBook() {
        val navigation = BookNavigationViewModel()
        navigation.openClient("customer", Screen.BOOK)
        navigation.openClientAnalytics("customer")
        navigation.openAnalyticsHistory()

        assertTrue(navigation.back())
        assertEquals(Screen.ANALYTICS, navigation.state.screen)
        assertEquals("customer", navigation.state.analyticsClientId)
        assertTrue(navigation.back())
        assertEquals("customer", navigation.state.clientId)
        assertTrue(navigation.back())
        assertEquals(Screen.BOOK, navigation.state.screen)
        assertFalse(navigation.back())
    }

    @Test
    fun selectingAnotherTabDropsThePreviousBackPath() {
        val navigation = BookNavigationViewModel()
        navigation.openAnalytics()
        navigation.openClientAnalytics("customer")
        navigation.openAnalyticsHistory()
        navigation.selectTab(Screen.HISTORY)

        assertNull(navigation.state.analyticsClientId)
        assertTrue(navigation.back())
        assertEquals(Screen.BOOK, navigation.state.screen)
        for (screen in listOf(Screen.COLLECTIONS, Screen.REPORT, Screen.ANALYTICS)) {
            assertEquals(Screen.BOOK, screen.mainTab())
        }
    }
}
