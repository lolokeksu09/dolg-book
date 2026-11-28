package dev.ventry.raschet.presentation

import android.os.Bundle
import androidx.lifecycle.ViewModel
import dev.ventry.raschet.ClientDirectory
import dev.ventry.raschet.ClientStatement
import dev.ventry.raschet.CollectionsInsights
import dev.ventry.raschet.data.WriteDestination
import dev.ventry.raschet.data.WriteReceipt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Screen-only state. No Activity, database, network or license ownership. */
class BookFiltersViewModel : ViewModel() {
    data class Book(
        val payments: Boolean = false, val from: LocalDate? = null, val to: LocalDate? = null,
        val query: String = "", val onlyUnpaid: Boolean = false, val autoExpand: Boolean = false,
        val limit: Int = 30, val expanded: Set<String> = emptySet(),
        val calendar: Boolean = false, val calendarDay: LocalDate? = null
    ) {
        fun hasFilter() = from != null || query.isNotBlank() || onlyUnpaid
        fun summary(): String {
            val fmt = DateTimeFormatter.ofPattern("dd.MM.yy", Locale("ru", "RU"))
            val dates = from?.let { fmt.format(it) + if (to == null || to == it) "" else " — " + fmt.format(to) } ?: "Все даты"
            return dates + (if (onlyUnpaid) " · непогашенные" else "") + (if (query.isBlank()) "" else " · " + query.trim())
        }
    }
    data class Clients(
        val scope: String = ClientDirectory.ACTIVE, val filter: String = CollectionsInsights.ALL,
        val query: String = "", val limit: Int = 60,
        val amountFirst: Boolean = false, val recentFirst: Boolean = false
    )
    data class CollectionsQueue(val query: String = "", val urgent: Boolean = false,
        val plan: String = "ALL", val limit: Int = 60)
    data class Detail(val clientId: String? = null, val filter: String = ClientStatement.ALL, val limit: Int = 80)
    data class History(val clientId: String? = null, val query: String = "", val filter: String = "Все",
        val from: LocalDate? = null, val to: LocalDate? = null, val limit: Int = 100)
    data class Report(val from: LocalDate, val to: LocalDate, val limit: Int = 14)
    data class State(
        val book: Book = Book(), val clients: Clients = Clients(), val queue: CollectionsQueue = CollectionsQueue(),
        val detail: Detail = Detail(), val history: History = History(),
        val report: Report = LocalDate.now().let { Report(it.withDayOfMonth(1), it) },
        val settingsExpanded: String = ""
    )

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val book get() = state.value.book
    val clients get() = state.value.clients
    val queue get() = state.value.queue
    val detail get() = state.value.detail
    val history get() = state.value.history
    val report get() = state.value.report
    val settingsExpanded get() = state.value.settingsExpanded
    private var initialized = false
    private fun updateBook(f: (Book) -> Book) = mutable.update { it.copy(book = f(it.book)) }
    private fun updateClients(f: (Clients) -> Clients) = mutable.update { it.copy(clients = f(it.clients)) }
    private fun updateQueue(f: (CollectionsQueue) -> CollectionsQueue) = mutable.update { it.copy(queue = f(it.queue)) }
    private fun updateDetail(f: (Detail) -> Detail) = mutable.update { it.copy(detail = f(it.detail)) }
    private fun updateHistory(f: (History) -> History) = mutable.update { it.copy(history = f(it.history)) }
    private fun immutableDays(days: Collection<String>): Set<String> = Collections.unmodifiableSet(LinkedHashSet(days))
    private fun Book.cleared() = copy(from = null, to = null, query = "", onlyUnpaid = false, limit = 30, autoExpand = false)

    fun setBookMode(payments: Boolean) = updateBook {
        if (it.payments == payments) it else it.cleared().copy(payments = payments, expanded = emptySet(), autoExpand = true)
    }
    fun clearBookFilters() = updateBook { it.cleared().copy(calendarDay = null) }
    fun applyBookFilters(query: String, from: LocalDate?, to: LocalDate?, unpaid: Boolean) {
        require((from == null && to == null) || (from != null && to != null && !to.isBefore(from)))
        updateBook {
            val next = it.copy(query = query.trim(), from = from, to = to, onlyUnpaid = unpaid && !it.payments,
                limit = 30, calendarDay = null, expanded = emptySet())
            next.copy(autoExpand = next.hasFilter())
        }
    }
    fun toggleBookDay(key: String) = updateBook { it.copy(expanded = immutableDays(if (key in it.expanded) it.expanded - key else it.expanded + key)) }
    fun finishBookAutoExpand(key: String?) = updateBook { it.copy(expanded = if (key == null) it.expanded else immutableDays(it.expanded + key), autoExpand = false) }
    fun setBookLimit(limit: Int) = updateBook { it.copy(limit = limit) }
    fun setCalendar(calendar: Boolean, day: LocalDate?) = updateBook { it.copy(calendar = calendar, calendarDay = day) }

    fun openQueue() = mutable.update { it.copy(queue = CollectionsQueue()) }
    fun setQueueQuery(query: String) = updateQueue { it.copy(query = query, limit = 60) }
    fun setQueueUrgent(urgent: Boolean) = updateQueue { it.copy(urgent = urgent, limit = 60) }
    fun setQueueLimit(limit: Int) = updateQueue { it.copy(limit = limit) }
    fun setQueuePlan(plan: String) = updateQueue { it.copy(plan = plan) }

    fun openClientFilter(filter: String) = updateClients { it.copy(scope = ClientDirectory.ACTIVE, filter = filter, limit = 60, query = "") }
    fun setClientQuery(query: String) = updateClients { it.copy(query = query, limit = 60) }
    fun setClientLimit(limit: Int) = updateClients { it.copy(limit = limit) }
    fun setClientScope(scope: String) = updateClients { it.copy(scope = scope, filter = CollectionsInsights.ALL, limit = 60) }
    fun setClientFilter(filter: String) = updateClients { it.copy(filter = filter, limit = 60) }
    fun setClientSort(amountFirst: Boolean, recentFirst: Boolean) {
        require(!(amountFirst && recentFirst))
        updateClients { it.copy(amountFirst = amountFirst, recentFirst = recentFirst, limit = 60) }
    }
    fun afterArchive(archived: Boolean) = updateClients {
        it.copy(scope = if (archived) ClientDirectory.ACTIVE else ClientDirectory.ARCHIVE, filter = CollectionsInsights.ALL, query = "")
    }

    fun focusDetail(id: String) = updateDetail { if (id == it.clientId) it else Detail(clientId = id) }
    fun setDetailFilter(filter: String) = updateDetail { it.copy(filter = filter, limit = 80) }
    fun setDetailLimit(limit: Int) = updateDetail { it.copy(limit = limit) }

    /** Validate before publishing either range; rejected input leaves state intact. */
    fun setPeriod(report: Boolean, from: LocalDate, to: LocalDate): Boolean {
        if (from.isAfter(to) || to.isAfter(from.plusDays(365))) return false
        if (report) mutable.update { it.copy(report = Report(from, to)) }
        else updateHistory { it.copy(from = from, to = to, limit = 100) }
        return true
    }
    fun openHistory(from: LocalDate?, to: LocalDate?, clientId: String?) =
        mutable.update { it.copy(history = History(clientId = clientId, from = from, to = to)) }
    fun openReportHistory() = mutable.update { it.copy(history = History(from = it.report.from, to = it.report.to)) }
    fun setHistoryFilter(filter: String) = updateHistory { it.copy(filter = filter, limit = 100) }
    fun clearHistoryPeriod() = updateHistory { it.copy(from = null, to = null, limit = 100) }
    fun clearHistoryClient() = updateHistory { it.copy(clientId = null, limit = 100) }
    fun setHistoryQuery(query: String) = updateHistory { it.copy(query = query, limit = 100) }
    fun setHistoryLimit(limit: Int) = updateHistory { it.copy(limit = limit) }
    fun clearHistory() = mutable.update { it.copy(history = History()) }
    fun setReportLimit(limit: Int) = mutable.update { it.copy(report = it.report.copy(limit = limit)) }
    fun closeSettings() = mutable.update { it.copy(settingsExpanded = "") }
    fun toggleSettings(section: String) = mutable.update { it.copy(settingsExpanded = if (it.settingsExpanded == section) "" else section) }

    fun onWrite(receipt: WriteReceipt, clientOrigin: BookNavigationViewModel.Screen) = mutable.update { state ->
        val day = receipt.day
        val showDebt = receipt.kind == "debt" && !(clientOrigin == BookNavigationViewModel.Screen.BOOK && receipt.destination == WriteDestination.DETAIL)
        val showPayment = receipt.kind == "payment" && receipt.destination == WriteDestination.BOOK
        val book = if (day != null && (showDebt || showPayment)) {
            val expanded = if (showPayment) setOf(day.toString()) else state.book.expanded + day.toString()
            state.book.cleared().copy(payments = showPayment, limit = maxOf(30, receipt.rank), calendarDay = day, expanded = immutableDays(expanded))
        } else state.book
        val clients = if (receipt.destination == WriteDestination.CLIENTS)
            state.clients.copy(scope = ClientDirectory.ACTIVE, filter = CollectionsInsights.ALL, query = "", limit = 60)
        else state.clients
        state.copy(book = book, clients = clients)
    }

    /** Same keys as the old Activity; a retained ViewModel is never overwritten by an older Bundle. */
    fun restore(saved: Bundle?) {
        if (initialized) return
        initialized = true
        if (saved == null) return
        fun date(key: String): LocalDate? = saved.getString(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        fun text(key: String, fallback: String) = saved.getString(key, fallback)
        val old = state.value
        val bookFrom = date("book_from")
        val bookTo = date("book_to")
        val validBookRange = bookFrom != null && bookTo != null && !bookTo.isBefore(bookFrom)
        val payments = saved.getBoolean("book_payments", false)
        val historyFrom = date("history_from")
        val historyTo = date("history_to")
        val validHistoryRange = historyFrom != null && historyTo != null && !historyTo.isBefore(historyFrom) && !historyTo.isAfter(historyFrom.plusDays(365))
        val reportFrom = date("report_from") ?: old.report.from
        val reportTo = date("report_to") ?: old.report.to
        val validReport = !reportTo.isBefore(reportFrom) && !reportTo.isAfter(reportFrom.plusDays(365))
        mutable.value = State(
            book = Book(payments, bookFrom.takeIf { validBookRange }, bookTo.takeIf { validBookRange },
                text("book_query", ""), !payments && saved.getBoolean("book_unpaid", false),
                saved.getBoolean("book_auto_expand", false), saved.getInt("book_day_limit", 30).coerceAtLeast(30),
                immutableDays(saved.getStringArrayList("expanded_book_days").orEmpty()),
                saved.getBoolean("book_calendar", false), date("book_calendar_day")),
            clients = Clients(text("client_scope", ClientDirectory.ACTIVE), text("client_filter", CollectionsInsights.ALL),
                text("search", ""), saved.getInt("client_visible_limit", 60).coerceAtLeast(60),
                saved.getBoolean("clients_amount_first", false) && !saved.getBoolean("clients_recent_first", false),
                saved.getBoolean("clients_recent_first", false)),
            queue = CollectionsQueue(text("collection_query", ""), saved.getBoolean("collection_urgent", false),
                text("collection_plan_filter", "ALL"), saved.getInt("collection_limit", 60).coerceAtLeast(60)),
            detail = Detail(saved.getString("detail_filter_client"), text("detail_filter", ClientStatement.ALL),
                saved.getInt("detail_visible_limit", 80).coerceAtLeast(80)),
            history = History(saved.getString("history_client_id"), text("history_query", ""), text("history_filter", "Все"),
                historyFrom.takeIf { validHistoryRange }, historyTo.takeIf { validHistoryRange },
                saved.getInt("history_visible_limit", 100).coerceAtLeast(100)),
            report = if (validReport) Report(reportFrom, reportTo, saved.getInt("report_days_limit", 14).coerceAtLeast(14)) else old.report,
            settingsExpanded = text("settings_expanded", "")
        )
    }

    fun save(out: Bundle) {
        val s = state.value
        fun date(key: String, value: LocalDate?) { out.putString(key, value?.toString()) }
        with(s.book) {
            out.putBoolean("book_payments", payments); date("book_from", from); date("book_to", to)
            out.putString("book_query", query); out.putBoolean("book_unpaid", onlyUnpaid)
            out.putBoolean("book_auto_expand", autoExpand); out.putInt("book_day_limit", limit)
            out.putStringArrayList("expanded_book_days", ArrayList(expanded))
            out.putBoolean("book_calendar", calendar); date("book_calendar_day", calendarDay)
        }
        with(s.clients) {
            out.putString("client_scope", scope); out.putString("client_filter", filter); out.putString("search", query)
            out.putInt("client_visible_limit", limit); out.putBoolean("clients_amount_first", amountFirst); out.putBoolean("clients_recent_first", recentFirst)
        }
        with(s.queue) {
            out.putString("collection_query", query); out.putBoolean("collection_urgent", urgent)
            out.putString("collection_plan_filter", plan); out.putInt("collection_limit", limit)
        }
        with(s.detail) {
            out.putString("detail_filter_client", clientId); out.putString("detail_filter", filter); out.putInt("detail_visible_limit", limit)
        }
        with(s.history) {
            out.putString("history_client_id", clientId); out.putString("history_query", query); out.putString("history_filter", filter)
            date("history_from", from); date("history_to", to); out.putInt("history_visible_limit", limit)
        }
        with(s.report) {
            date("report_from", from); date("report_to", to); out.putInt("report_days_limit", limit)
        }
        out.putString("settings_expanded", s.settingsExpanded)
    }
}
