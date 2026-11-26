package dev.ventry.raschet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ventry.raschet.presentation.BookScreenData
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.function.Consumer
import java.util.function.BiConsumer
import java.util.function.IntConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class BookUiModel(
    val total: Long, val debtors: Int, val urgent: Int,
    val people: Map<String, LedgerStore.Client>, val days: List<BookDays.Day>,
    val hasDays: Boolean, val recorded: Long, val remaining: Long,
    val draft: String?, val needsBackup: Boolean, val today: LocalDate
)

internal fun projectBook(data: BookScreenData, payments: Boolean, from: LocalDate?, to: LocalDate?,
    query: String, onlyUnpaid: Boolean, directory: ClientDirectory,
    pending: QuickDebtDrafts.Draft?, backupMissing: Boolean, zone: ZoneId): BookUiModel {
    val entries = data.snapshot.entries
    val people = data.snapshot.clients.associateBy { it.id }
    val promises = PaymentPromises.project(data.statuses, directory, entries, data.today, zone)
    val work = CollectionWorklist.build(data.statuses, directory, data.today, promises)
    val allDays = if (payments) BookDays.groupPayments(entries, zone) else BookDays.group(entries, zone)
    val days = BookDays.select(allDays, people, from, to, query, onlyUnpaid && !payments)
    val draft = pending?.takeIf { it.id !in people && entries.none { entry -> entry.id == it.id } }
        ?.let { it.name.ifEmpty { "Продолжить черновик" } }
    return BookUiModel(data.statuses.sumOf { it.balance }, work.size, work.count { it.priority <= CollectionWorklist.LIMIT },
        people, days, allDays.isNotEmpty(), days.sumOf { it.recorded }, days.sumOf { it.remaining },
        draft, backupMissing && entries.isNotEmpty(), data.today)
}

internal data class BookActions(
    val debt: () -> Unit, val payment: () -> Unit, val collections: () -> Unit,
    val mode: (Boolean) -> Unit, val filters: () -> Unit, val clear: () -> Unit,
    val toggle: (String) -> Unit, val autoExpanded: (String?) -> Unit, val limit: (Int) -> Unit,
    val openClient: (String) -> Unit, val operation: (String) -> Unit,
    val payClient: (String) -> Unit, val debtClient: (String) -> Unit,
    val debtDate: (LocalDate) -> Unit, val paymentDate: (LocalDate) -> Unit,
    val report: () -> Unit, val backup: () -> Unit, val analytics: (() -> Unit)? = null
)

object BookCompose {
    @JvmStatic
    @JvmOverloads
    fun create(owner: ComponentActivity, data: BookScreenData, payments: Boolean,
        from: LocalDate?, to: LocalDate?, query: String, onlyUnpaid: Boolean,
        hasFilter: Boolean, filterSummary: String, initialExpanded: Set<String>,
        autoExpand: Boolean, initialLimit: Int, largeText: Boolean, backupMissing: Boolean,
        onDebt: Runnable, onPayment: Runnable, onCollections: Runnable, onMode: Consumer<Boolean>,
        onFilters: Runnable, onClear: Runnable, onToggle: Consumer<String>,
        onAutoExpanded: Consumer<String?>, onLimit: IntConsumer, onOpenClient: Consumer<String>,
        onOperation: Consumer<String>, onPayClient: Consumer<String>, onDebtClient: Consumer<String>,
        onDebtDate: Consumer<LocalDate>, onPaymentDate: Consumer<LocalDate>,
        onReport: Runnable, onBackup: Runnable, initialCalendar: Boolean = false,
        initialCalendarDay: LocalDate? = null,
        onCalendar: BiConsumer<Boolean, LocalDate> = BiConsumer { _, _ -> },
        onAnalytics: Runnable? = null): ComposeView {
        val app = owner.applicationContext
        val actions = BookActions(onDebt::run, onPayment::run, onCollections::run, onMode::accept,
            onFilters::run, onClear::run, onToggle::accept, onAutoExpanded::accept, onLimit::accept,
            onOpenClient::accept, onOperation::accept, onPayClient::accept, onDebtClient::accept,
            onDebtDate::accept, onPaymentDate::accept, onReport::run, onBackup::run, onAnalytics?.let { run -> { run.run() } })
        val expanded = initialExpanded.toSet()
        return ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                LedgerMaterialTheme {
                    var model by remember { mutableStateOf<BookUiModel?>(null) }
                    var failed by remember { mutableStateOf(false) }
                    var attempt by remember { mutableIntStateOf(0) }
                    var firstExpanded by remember { mutableStateOf(expanded) }
                    LaunchedEffect(data, attempt) {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                projectBook(data, payments, from, to, query, onlyUnpaid, ClientDirectory(app),
                                    QuickDebtDrafts(app).get(), backupMissing, ZoneId.systemDefault())
                            }
                            coroutineContext.ensureActive()
                            if (autoExpand) {
                                val key = result.days.firstOrNull()?.date?.toString()
                                if (key != null) firstExpanded = firstExpanded + key
                                actions.autoExpanded(key)
                            }
                            model = result
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { coroutineContext.ensureActive(); failed = true }
                    }
                    val current = model
                    when {
                        current != null -> BookContent(current, payments, hasFilter, filterSummary, firstExpanded,
                            initialLimit, largeText, actions, initialCalendar, initialCalendarDay, onCalendar::accept)
                        failed -> Column(Modifier.fillMaxSize().background(ledgerBg).padding(16.dp)) {
                            Text("Ошибка чтения книги", color = ledgerText)
                            OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Повторить") }
                        }
                        else -> Box(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookContent(model: BookUiModel, payments: Boolean, hasFilter: Boolean, filterSummary: String,
    initialExpanded: Set<String>, initialLimit: Int, largeText: Boolean, actions: BookActions,
    initialCalendar: Boolean = false, initialCalendarDay: LocalDate? = null,
    onCalendar: (Boolean, LocalDate) -> Unit = { _, _ -> }) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    var limit by remember { mutableIntStateOf(initialLimit) }
    val rowLimits = remember { mutableStateMapOf<String, Int>() }
    var calendar by remember { mutableStateOf(initialCalendar) }
    var selectedDay by remember { mutableStateOf(initialCalendarDay ?: if (hasFilter) model.days.firstOrNull()?.date ?: model.today else model.today) }
    var month by remember { mutableStateOf(YearMonth.from(selectedDay)) }
    var monthGrid by remember { mutableStateOf(false) }
    val byDate = remember(model.days) { model.days.associateBy { it.date } }
    fun selectDate(date: LocalDate) {
        selectedDay = date; month = YearMonth.from(date); calendar = true
        onCalendar(true, date)
    }
    val visibleDays = if (calendar) listOfNotNull(byDate[selectedDay]) else model.days.take(limit)
    BoxWithConstraints(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.TopCenter) {
        val stacked = maxWidth < 365.dp || LocalDensity.current.fontScale > 1.2f || largeText
        LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("book-list"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            item("title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Долговая книга", color = ledgerText, fontWeight = FontWeight.SemiBold, fontSize = if (stacked) 22.sp else 24.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.report, modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Отчёт") }
                }
            }
            item("total") {
                Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    LedgerTotal("Общая задолженность", model.total)
                    HorizontalDivider(color = ledgerBorder.copy(alpha = .5f))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = actions.collections)
                        .semantics { contentDescription = "Возврат долгов" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Должников: " + model.debtors, color = ledgerMuted, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        Text(if (model.urgent > 0) "К возврату: " + model.urgent else "Возврат долгов",
                            color = if (model.urgent > 0) ledgerAlert else ledgerAccent, fontSize = 12.sp)
                        Text(" ›", color = ledgerAccent, fontSize = 20.sp)
                    }
                }
            }
            item("quick") {
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = actions.debt, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ Записать долг") }
                    OutlinedButton(onClick = actions.payment, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Принять оплату") }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.debt, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("+ Записать долг") }
                    OutlinedButton(onClick = actions.payment, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Принять оплату") }
                }
            }
            actions.analytics?.let { open ->
                item("analytics") { BookAnalyticsEntry(open) }
            }
            if (model.draft != null) item("draft") { LedgerMetric("Незавершённая запись", model.draft, ledgerAccent, actions.debt) }
            item("modes") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for ((title, value) in listOf("Долги по датам" to false, "Оплаты по датам" to true)) {
                        val active = payments == value
                        Column(Modifier.weight(1f)
                            .clickable { if (!active) actions.mode(value) }
                            .semantics { selected = active; contentDescription = title },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center) {
                                Text(title, color = if (active) ledgerText else ledgerMuted,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 13.sp, textAlign = TextAlign.Center)
                            }
                            Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) ledgerAccent else ledgerBorder.copy(alpha = .45f)))
                        }
                    }
                }
            }
            item("section") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (payments) "Полученные оплаты" else "Записи по датам", color = ledgerText,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (hasFilter) Text(filterSummary, color = ledgerMuted, fontSize = 12.sp)
                    }
                    if (calendar) TextButton(onClick = {
                        calendar = false; monthGrid = false; onCalendar(false, selectedDay)
                    }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("Список дат")
                    }
                    else TextButton(onClick = { selectDate(selectedDay); monthGrid = true },
                        modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("Календарь")
                    }
                }
            }
            if (model.hasDays) item("filters") {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = actions.filters)
                    .semantics { contentDescription = "Фильтры книги" }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Фильтры книги", color = ledgerAccent, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(filterSummary, color = ledgerMuted, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                if (hasFilter) Text(if (payments) "Получено по фильтру: " + ledgerMoney(model.recorded)
                    else "По фильтру: " + ledgerMoney(model.recorded) + " · осталось " + ledgerMoney(model.remaining),
                    color = ledgerMuted, fontSize = 12.sp)
            }
            if (!monthGrid) item("week") {
                BookWeek(selectedDay, if (calendar) selectedDay else null, model.today, byDate, payments,
                    onWeek = ::selectDate, onDay = ::selectDate,
                    onMonth = { selectDate(selectedDay); monthGrid = true })
            }
            if (calendar) {
                if (monthGrid) item("calendar") {
                    BookCalendar(month, selectedDay, model.today, byDate, payments,
                        onMonth = { next ->
                            month = next
                            selectedDay = if (next == YearMonth.from(model.today)) model.today else next.atDay(1)
                            onCalendar(true, selectedDay)
                        },
                        onDay = { selectDate(it); monthGrid = false })
                }
                item("selected-calendar-date") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selectedDay.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru", "RU"))),
                                color = ledgerText, fontWeight = FontWeight.SemiBold)
                            val day = byDate[selectedDay]
                            Text("Записей: " + (day?.entries?.size ?: 0), color = ledgerMuted, fontSize = 12.sp)
                        }
                        if (selectedDay != model.today) TextButton(onClick = { selectDate(model.today); monthGrid = false }) { Text("Сегодня") }
                    }
                }
                if (visibleDays.isEmpty()) item("calendar-empty") {
                    Text(if (hasFilter) "Нет записей по выбранным условиям за этот день." else
                        if (payments) "За этот день нет оплат." else "За этот день нет долгов.", color = ledgerMuted)
                    if (!selectedDay.isAfter(model.today)) OutlinedButton(
                        onClick = { if (payments) actions.paymentDate(selectedDay) else actions.debtDate(selectedDay) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (payments) "+ Оплата за этот день" else "+ Долг за этот день")
                    }
                }
            }
            if (!calendar && !model.hasDays) item("empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (payments) "Пока нет оплат" else "Пока нет долгов", color = ledgerText, fontWeight = FontWeight.SemiBold)
                    Text(if (payments) "Здесь появятся поступления от покупателей." else "Запишите первый долг — он появится в книге за нужную дату.", color = ledgerMuted, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            else if (!calendar && model.days.isEmpty()) item("empty-filter") {
                Text("Нет записей по выбранным условиям.", color = ledgerMuted)
                OutlinedButton(onClick = actions.clear, modifier = Modifier.fillMaxWidth()) { Text("Сбросить фильтры книги") }
            }
            for (day in visibleDays) {
                val key = day.date.toString()
                val open = calendar || key in expanded
                item("day-" + key) {
                    val description = (if (calendar) "Сводка · " else "") + ledgerDayFormat.format(day.date) + ", записей " + day.entries.size +
                        (if (payments) ", получено " + ledgerMoney(day.recorded) else ", записано " + ledgerMoney(day.recorded) +
                            ", осталось " + ledgerMoney(day.remaining)) + if (calendar) "" else if (open) ", свернуть" else ", раскрыть"
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                        if (calendar) Modifier else Modifier.clickable {
                            expanded = if (open) expanded - key else expanded + key
                            actions.toggle(key)
                        }
                    ).semantics { selected = open; contentDescription = description }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (calendar) (if (payments) "Получено за день" else "Записано за день") else ledgerDayFormat.format(day.date),
                                color = if (calendar) ledgerMuted else ledgerText, fontWeight = FontWeight.Medium)
                            if (!calendar) Text("Записей: " + day.entries.size, color = ledgerMuted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(ledgerMoney(day.recorded), color = ledgerText, fontWeight = FontWeight.Medium,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                            Text(if (payments) "Получено" else "Осталось " + ledgerMoney(day.remaining),
                                color = if (payments || day.remaining == 0L) ledgerAccent else ledgerAlert, fontSize = 12.sp)
                        }
                        if (!calendar) Text(if (open) "⌃" else "⌄", color = ledgerMuted, fontSize = 20.sp)
                    }
                    HorizontalDivider(color = ledgerBorder)
                }
                if (open) {
                    val count = rowLimits[key] ?: 60
                    items(day.entries.take(count), key = { "book-entry-" + it.id }) { e ->
                        val person = model.people[e.clientId]
                        if (person != null) BookEntryRow(day, e, person, payments, actions)
                    }
                    if (day.entries.size > count) item("more-" + key) {
                        OutlinedButton(onClick = { rowLimits[key] = count + 60 }, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё записи") }
                    }
                    if (!calendar || !day.date.isAfter(model.today)) item("date-add-" + key) {
                        OutlinedButton(onClick = { if (payments) actions.paymentDate(day.date) else actions.debtDate(day.date) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (payments) "+ Оплата за этот день" else "+ Долг за этот день")
                        }
                    }
                }
            }
            if (!calendar && model.days.size > limit) item("more-dates") {
                OutlinedButton(onClick = { limit += 30; actions.limit(limit) }, modifier = Modifier.fillMaxWidth()) { Text("Показать более ранние даты") }
            }
            item("report") { TextButton(onClick = actions.report, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Финансовый отчёт") } }
            if (model.needsBackup) item("backup") { LedgerMetric("Создать JSON-копию", "Сохраните книгу перед сменой устройства", action = actions.backup) }
        }
    }
}

@Composable
private fun BookEntryRow(day: BookDays.Day, e: LedgerStore.Entry, person: LedgerStore.Client,
    payments: Boolean, actions: BookActions) {
    val canceled = e.id in day.canceled
    val unpaid = day.unpaid.getOrDefault(e.id, 0L)
    Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { actions.openClient(person.id) }.padding(vertical = 8.dp)) {
                Text(person.name, color = ledgerText, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(if (canceled) "Отменено" else if (payments) "Оплата" else if (unpaid == 0L) "Погашено"
                    else if (unpaid < e.amount) "Осталось " + ledgerMoney(unpaid) else "Не погашено",
                    color = if (canceled) ledgerMuted else if (payments || unpaid == 0L) ledgerAccent else ledgerAlert, fontSize = 12.sp)
                if (!canceled && e.note.isNotEmpty()) Text(e.note, color = ledgerMuted, fontSize = 12.sp)
            }
            Text(ledgerMoney(e.amount), color = if (canceled) ledgerMuted else if (payments) ledgerAccent else ledgerText,
                fontWeight = FontWeight.Medium, fontSize = 16.sp,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                textDecoration = if (canceled) TextDecoration.LineThrough else null,
                modifier = Modifier.heightIn(min = 48.dp).clickable { actions.operation(e.id) }
                    .semantics { contentDescription = "Запись · " + person.name + " · " + ledgerMoney(e.amount) }.padding(vertical = 12.dp))
        }
        if (!payments && !canceled) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (unpaid > 0) TextButton(onClick = { actions.payClient(person.id) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Оплата · " + person.name }) { Text("Оплата") }
            TextButton(onClick = { actions.debtClient(person.id) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Новый долг · " + person.name }) { Text("+ Долг") }
        }
        HorizontalDivider(color = ledgerBorder)
    }
}


/** Single accessible action; the chart glyph is decorative. */
@Composable
internal fun BookAnalyticsEntry(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().testTag("book-statistics-entry")
            .clip(shape).background(ledgerPanel).border(1.dp, ledgerAccent.copy(alpha = .55f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 80.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).background(ledgerAccent.copy(alpha = .15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(25.dp)) {
                val width = size.width / 5f
                val heights = listOf(.36f, .60f, .88f)
                heights.forEachIndexed { index, fraction ->
                    val h = size.height * fraction
                    drawRoundRect(ledgerAccent,
                        topLeft = Offset(width * index * 1.8f, size.height - h),
                        size = Size(width, h), cornerRadius = CornerRadius(2.dp.toPx()))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Статистика", color = ledgerText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("Динамика, должники и сравнение периодов", color = ledgerMuted, fontSize = 12.sp)
        }
        Text("›", color = ledgerAccent, fontSize = 28.sp)
    }
}
