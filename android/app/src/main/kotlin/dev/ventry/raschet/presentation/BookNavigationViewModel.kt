package dev.ventry.raschet.presentation

import android.os.Bundle
import androidx.lifecycle.ViewModel
import dev.ventry.raschet.PendingFileRequests
import dev.ventry.raschet.data.WriteDestination

/** Routes the book UI without holding an Activity, a book snapshot or any credentials. */
class BookNavigationViewModel : ViewModel() {
    enum class Screen(val savedName: String, val title: String = savedName) {
        BOOK("Главная", "Книга"),
        CLIENTS("Клиенты"),
        HISTORY("История"),
        SETTINGS("Настройки"),
        COLLECTIONS("Возврат"),
        REPORT("Отчёт"),
        ANALYTICS("Статистика");

        fun mainTab(): Screen = when (this) {
            COLLECTIONS, REPORT, ANALYTICS -> BOOK
            else -> this
        }

        companion object {
            internal fun restore(value: String?, fallback: Screen): Screen =
                entries.firstOrNull { it.savedName == value } ?: fallback
        }
    }

    data class State(
        val screen: Screen = Screen.BOOK,
        val clientId: String? = null,
        val clientOrigin: Screen = Screen.CLIENTS,
        val analyticsClientId: String? = null,
        val analyticsOrigin: Screen = Screen.CLIENTS,
        val historyFromAnalytics: Boolean = false,
    )

    val pendingFiles = PendingFileRequests()

    var state = State()
        private set
    private var initialized = false

    fun restore(saved: Bundle?) {
        if (initialized) return
        initialized = true
        if (saved == null) return
        pendingFiles.restore(saved)
        state = State(
            screen = Screen.restore(saved.getString("tab"), Screen.BOOK),
            clientId = saved.getString("client"),
            clientOrigin = when {
                saved.getBoolean("return_to_book") -> Screen.BOOK
                saved.getBoolean("return_to_collections") -> Screen.COLLECTIONS
                else -> Screen.CLIENTS
            },
            analyticsClientId = saved.getString("analytics_client_id"),
            analyticsOrigin = Screen.restore(saved.getString("analytics_return_tab"), Screen.CLIENTS),
            historyFromAnalytics = saved.getBoolean("history_from_analytics"),
        )
    }

    fun save(out: Bundle) {
        pendingFiles.save(out)
        out.putString("tab", state.screen.savedName)
        out.putString("client", state.clientId)
        out.putBoolean("return_to_book", state.clientOrigin == Screen.BOOK)
        out.putBoolean("return_to_collections", state.clientOrigin == Screen.COLLECTIONS)
        out.putString("analytics_client_id", state.analyticsClientId)
        out.putString("analytics_return_tab", state.analyticsOrigin.savedName)
        out.putBoolean("history_from_analytics", state.historyFromAnalytics)
    }

    fun selectTab(screen: Screen) { state = State(screen = screen) }

    fun show(screen: Screen) { state = state.copy(screen = screen, clientId = null) }

    fun showRoot(screen: Screen) {
        state = state.copy(screen = screen, clientId = null, clientOrigin = Screen.CLIENTS)
    }

    fun openClient(id: String, origin: Screen) {
        state = state.copy(screen = Screen.CLIENTS, clientId = id, clientOrigin = origin)
    }

    fun openOperationClient(id: String) {
        val origin = when (state.screen) {
            Screen.BOOK, Screen.COLLECTIONS -> state.screen
            Screen.CLIENTS -> state.clientOrigin
            else -> Screen.CLIENTS
        }
        openClient(id, origin)
    }

    fun closeClient() {
        showRoot(state.clientOrigin)
    }

    fun clearClient() { state = state.copy(clientId = null) }

    fun openAnalytics() {
        state = state.copy(screen = Screen.ANALYTICS, clientId = null,
            analyticsClientId = null, historyFromAnalytics = false)
    }

    fun openClientAnalytics(id: String) {
        state = state.copy(screen = Screen.ANALYTICS, clientId = null,
            analyticsClientId = id, analyticsOrigin = state.screen, historyFromAnalytics = false)
    }

    fun closeAnalytics() {
        val client = state.analyticsClientId
        val origin = state.analyticsOrigin
        state = state.copy(
            screen = if (client == null) Screen.BOOK else origin,
            clientId = client?.takeUnless { origin == Screen.ANALYTICS },
            analyticsClientId = null,
            historyFromAnalytics = false,
        )
    }

    fun openAnalyticsHistory() {
        state = state.copy(screen = Screen.HISTORY, historyFromAnalytics = true)
    }

    fun openAnalyticsReport() {
        state = state.copy(screen = Screen.REPORT, analyticsClientId = null)
    }

    fun onWrite(destination: WriteDestination, clientId: String?) {
        when (destination) {
            WriteDestination.CLIENTS -> showRoot(Screen.CLIENTS)
            WriteDestination.BOOK -> showRoot(Screen.BOOK)
            WriteDestination.COLLECTIONS -> showRoot(Screen.COLLECTIONS)
            WriteDestination.DETAIL -> state = state.copy(screen = Screen.CLIENTS, clientId = clientId)
            WriteDestination.KEEP -> Unit
        }
    }

    fun back(): Boolean {
        when {
            state.clientId != null -> closeClient()
            state.screen == Screen.ANALYTICS -> closeAnalytics()
            state.screen == Screen.HISTORY && state.historyFromAnalytics ->
                state = state.copy(screen = Screen.ANALYTICS, historyFromAnalytics = false)
            state.screen != Screen.BOOK -> show(Screen.BOOK)
            else -> return false
        }
        return true
    }
}
