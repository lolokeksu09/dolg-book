package dev.ventry.raschet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ventry.raschet.domain.AnalyticsPeriods
import dev.ventry.raschet.domain.AnalyticsProjection
import dev.ventry.raschet.domain.AnalyticsSeries
import dev.ventry.raschet.domain.DatedLedger
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AnalyticsRequest(
    val selection: AnalyticsPeriods.Selection = AnalyticsPeriods.Selection(),
    val customerId: String? = null
)

data class AnalyticsResult(
    val request: AnalyticsRequest,
    val period: AnalyticsProjection.Snapshot,
    val previous: AnalyticsProjection.Snapshot?,
    val names: Map<String, String>,
    val series: List<AnalyticsSeries.Bucket>
) {
    val customer: AnalyticsProjection.Customer?
        get() = period.customers.firstOrNull { it.id == request.customerId }
    val previousCustomer: AnalyticsProjection.Customer?
        get() = previous?.customers?.firstOrNull { it.id == request.customerId }
}

/** Consumes the committed book snapshot; never opens a second database or writes operations. */
class AnalyticsViewModel : ViewModel() {
    private var book: BookScreenData? = null
    private var zone: ZoneId = ZoneId.systemDefault()
    private val mutableRequest = MutableStateFlow(AnalyticsRequest())
    val request = mutableRequest.asStateFlow()

    private val reader = LatestRead(viewModelScope) {
        val source = requireNotNull(book)
        val selected = mutableRequest.value
        val timeZone = zone
        withContext(Dispatchers.Default) {
            val context = coroutineContext
            val check = Runnable { context.ensureActive() }
            val entries = source.snapshot.entries
            val names = source.snapshot.clients.associate { it.id to it.name }
            val firstDate = if (selected.selection.preset == AnalyticsPeriods.Preset.ALL) {
                var earliest: java.time.LocalDate? = null
                entries.forEachIndexed { index, entry ->
                    if (index % 256 == 0) check.run()
                    val date = DatedLedger.localDate(entry.recordedAt, timeZone)
                    earliest = earliest?.let { minOf(it, date) } ?: date
                }
                earliest
            } else null
            val ranges = AnalyticsPeriods.resolve(selected.selection, source.today, firstDate)
            val current = AnalyticsProjection.build(entries, names.keys, ranges.range, source.today,
                timeZone, selected.customerId, check)
            val previous = ranges.previous?.let {
                AnalyticsProjection.build(entries, names.keys, it, source.today, timeZone, selected.customerId, check)
            }
            val focus = current.customers.firstOrNull { it.id == selected.customerId }
            val series = if (selected.customerId == null) AnalyticsSeries.build(ranges.range, current.opening, current.daily)
                else AnalyticsSeries.build(ranges.range, focus?.opening ?: 0L, current.focusedDaily)
            check.run()
            AnalyticsResult(selected, current, previous, names, series)
        }
    }

    val state = reader.state

    /** Pass null on a write/privacy invalidation, then bind the next committed snapshot. */
    @JvmOverloads
    fun bindBook(value: BookScreenData?, timeZone: ZoneId = ZoneId.systemDefault()) {
        if (book === value && zone == timeZone) return
        book = value
        zone = timeZone
        reader.clear()
        if (value != null) reader.refresh()
    }

    fun select(selection: AnalyticsPeriods.Selection) {
        change(mutableRequest.value.copy(selection = selection))
    }

    fun focus(customerId: String?) {
        change(mutableRequest.value.copy(customerId = customerId))
    }

    fun retry() { if (book != null) reader.refresh() }

    private fun change(value: AnalyticsRequest) {
        if (mutableRequest.value == value) return
        mutableRequest.value = value
        if (book != null) reader.refresh() else reader.clear()
    }

    override fun onCleared() {
        reader.clear()
        book = null
        super.onCleared()
    }
}
