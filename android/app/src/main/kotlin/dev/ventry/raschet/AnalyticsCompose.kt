package dev.ventry.raschet

import android.app.DatePickerDialog
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ventry.raschet.domain.AnalyticsExport
import dev.ventry.raschet.domain.AnalyticsPeriods
import dev.ventry.raschet.domain.AnalyticsProjection
import dev.ventry.raschet.domain.AnalyticsSeries
import dev.ventry.raschet.presentation.AnalyticsResult
import dev.ventry.raschet.presentation.AnalyticsViewModel
import dev.ventry.raschet.presentation.BookScreenData
import dev.ventry.raschet.presentation.ReadState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.function.BiConsumer
import java.util.function.Consumer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items

internal val analyticsDate = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale("ru", "RU"))
internal fun analyticsMoney(amount: Long): String = String.format(Locale("ru", "RU"),
    "%,.2f ₽", BigDecimal.valueOf(amount, 2)).replace('\u00a0', ' ').replace('\u202f', ' ')

internal fun analyticsRange(from: LocalDate, to: LocalDate) =
    if (from == to) analyticsDate.format(from) else analyticsDate.format(from) + " — " + analyticsDate.format(to)

object AnalyticsCompose {
    @JvmStatic
    fun create(owner: ComponentActivity, data: BookScreenData, model: AnalyticsViewModel,
        onBack: Runnable, onReport: Runnable, onHistory: BiConsumer<LocalDate, LocalDate>,
        onCustomer: Consumer<String>, fromOverview: Boolean, onExport: Consumer<AnalyticsExport.Spec>): ComposeView {
        model.bindBook(data)
        return ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                LedgerMaterialTheme {
                    val request by model.request.collectAsState()
                    val state by model.state.collectAsState()
                    var overviewSection by remember { mutableStateOf(AnalyticsSection.SUMMARY) }
                    var personalSection by remember(request.customerId) { mutableStateOf(AnalyticsSection.SUMMARY) }
                    val section = if (request.customerId == null) overviewSection else personalSection
                    Box(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 800.dp).fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onBack::run) { Text(if (request.customerId == null) "← Книга" else if (fromOverview) "← Статистика" else "← Клиент") }
                            Spacer(Modifier.weight(1f))
                            if (request.customerId == null) TextButton(onClick = onReport::run) { Text("Финансовый отчёт") }
                        }
                        AnalyticsPeriodsRow(request.selection.preset) { preset ->
                            if (preset == AnalyticsPeriods.Preset.CUSTOM) {
                                val initial = request.selection.custom
                                pickRange(owner, data.today, initial) { model.select(AnalyticsPeriods.Selection(preset, it)) }
                            } else model.select(AnalyticsPeriods.Selection(preset))
                        }
                        AnalyticsSectionRow(section, request.customerId != null) { chosen ->
                            if (request.customerId == null) overviewSection = chosen else personalSection = chosen
                        }
                        HorizontalDivider(color = ledgerBorder.copy(alpha = .5f))
                        when (val current = state) {
                            is ReadState.Ready -> {
                                if (current.value.request != request) AnalyticsLoading()
                                else if (request.customerId == null) key(section) { AnalyticsOverview(current.value, onHistory, onCustomer, onExport, section) }
                                else key(section) { ClientAnalyticsContent(current.value, onHistory, onExport, section) }
                            }
                            is ReadState.Failed -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Не удалось рассчитать статистику.", color = ledgerText)
                                Text("Данные книги не изменены.", color = ledgerMuted)
                                OutlinedButton(onClick = model::retry) { Text("Повторить") }
                            }
                            else -> AnalyticsLoading()
                        }
                    }
                    }
                }
            }
        }
    }

    private fun pickRange(owner: ComponentActivity, today: LocalDate, initial: AnalyticsProjection.Range?,
        selected: (AnalyticsProjection.Range) -> Unit) {
        fun pick(title: String, start: LocalDate, minimum: LocalDate?, done: (LocalDate) -> Unit) {
            val picker = DatePickerDialog(owner, { _, y, m, d -> done(LocalDate.of(y, m + 1, d)) },
                start.year, start.monthValue - 1, start.dayOfMonth)
            val zone = java.time.ZoneId.systemDefault()
            picker.datePicker.maxDate = today.atStartOfDay(zone).toInstant().toEpochMilli()
            if (minimum != null) picker.datePicker.minDate = minimum.atStartOfDay(zone).toInstant().toEpochMilli()
            picker.setTitle(title)
            picker.show()
        }
        pick("Начало периода", minOf(initial?.from ?: today.withDayOfMonth(1), today), null) { from ->
            pick("Конец периода", (initial?.to ?: today).coerceIn(from, today), from) { to ->
                if (!from.isAfter(to) && !to.isAfter(today)) selected(AnalyticsProjection.Range(from, to))
            }
        }
    }
}

@Composable
private fun AnalyticsLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ledgerAccent)
    }
}

@Composable
private fun AnalyticsPeriodsRow(selected: AnalyticsPeriods.Preset, choose: (AnalyticsPeriods.Preset) -> Unit) {
    val labels = listOf(
        AnalyticsPeriods.Preset.DAY to "День", AnalyticsPeriods.Preset.WEEK to "Неделя",
        AnalyticsPeriods.Preset.MONTH to "Месяц", AnalyticsPeriods.Preset.QUARTER to "Квартал",
        AnalyticsPeriods.Preset.YEAR to "Год", AnalyticsPeriods.Preset.ALL to "Всё время",
        AnalyticsPeriods.Preset.CUSTOM to "Период")
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        val primary = labels.take(3)
        val secondary = labels.drop(3)
        val compact = LocalDensity.current.fontScale > 1.2f
        if (compact) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { (preset, label) ->
                    FilterChip(selected = selected == preset, onClick = { choose(preset) }, label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        } else {
            listOf(primary, secondary).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.forEach { (preset, label) ->
                        val active = selected == preset
                        Box(Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(if (active) ledgerAccent else ledgerPanel, RoundedCornerShape(6.dp))
                            .clickable { choose(preset) }.semantics { this.selected = active; role = Role.Button }
                            .padding(horizontal = 3.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = if (active) Color(LedgerTheme.INK) else ledgerMuted,
                                fontSize = 12.sp, textAlign = TextAlign.Center,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
internal fun AnalyticsValue(label: String, value: String, color: Color = ledgerText) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.2f
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = ledgerMuted, fontSize = 13.sp)
            Text(value, color = color, fontWeight = FontWeight.Medium,
                style = TextStyle(fontFeatureSettings = "tnum"), fontSize = 16.sp)
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ledgerMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = color, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f), style = TextStyle(fontFeatureSettings = "tnum"), fontSize = 15.sp)
        }
    }
}

@Composable
internal fun AnalyticsHeading(title: String) {
    Text(title, color = ledgerText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun AnalyticsOverview(result: AnalyticsResult, onHistory: BiConsumer<LocalDate, LocalDate>,
    onCustomer: Consumer<String>, onExport: Consumer<AnalyticsExport.Spec>, section: AnalyticsSection = AnalyticsSection.SUMMARY) {
    val data = result.period
    var ranking by remember { mutableStateOf(AnalyticsRanking.BALANCE) }
    var rankLimit by remember(data, ranking) { mutableIntStateOf(20) }
    val ranked = remember(data, ranking, section) { if (section != AnalyticsSection.DETAILS) emptyList() else data.customers.filter { it.id in result.names && ranking.includes(it, data.range) }
        .sortedWith(compareByDescending<AnalyticsProjection.Customer> { ranking.value(it) }.thenBy { result.names[it.id] }.thenBy { it.id }) }
    LazyColumn(Modifier.fillMaxSize().testTag("analytics-list"), contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("heading") {
            Text("Статистика", color = ledgerText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(analyticsRange(data.range.from, data.range.to), color = ledgerMuted, fontSize = 13.sp)
        }
        if (section == AnalyticsSection.SUMMARY) {
        item("today") {
            Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text("Общая задолженность на сегодня", color = ledgerMuted, fontSize = 13.sp)
                Text(analyticsDate.format(data.today), color = ledgerMuted, fontSize = 12.sp)
                Text(analyticsMoney(data.current.balance), color = ledgerText, fontSize = 36.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium)
                AnalyticsValue("Должников", data.currentDebtors.toString())
                AnalyticsValue("Просрочено", analyticsMoney(data.current.overdue), ledgerAlert)
                AnalyticsValue("Должников с просрочкой", data.overdueDebtors.toString())
            }
        }
        item("chart") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsHeading("Динамика задолженности")
                AnalyticsChart(result.series)
            }
        }
        item("period") {
            Column {
                AnalyticsHeading("За выбранный период")
                AnalyticsValue("Остаток на начало", analyticsMoney(data.opening))
                AnalyticsValue("Остаток на конец", analyticsMoney(data.closing))
                AnalyticsValue("Изменение остатка", analyticsMoney(data.movement.net),
                    if (data.movement.net > 0) ledgerAlert else ledgerAccent)
                AnalyticsValue("Должников на конец", data.closingDebtors.toString())
                HorizontalDivider(color = ledgerBorder)
                AnalyticsValue("Записано долгов", analyticsMoney(data.movement.debts))
                AnalyticsValue("Получено оплат", analyticsMoney(data.movement.payments), ledgerAccent)
                AnalyticsValue("Отменено долгов", analyticsMoney(data.movement.debtReversals))
                AnalyticsValue("Отменено оплат", analyticsMoney(data.movement.paymentReversals))
                Text("Движение включает отмены по дате регистрации.", color = ledgerMuted, fontSize = 12.sp)
            }
        }
        item("effective") {
            Column {
                AnalyticsHeading("Действительные операции")
                AnalyticsValue("Долги · сумма", analyticsMoney(data.effective.debts))
                AnalyticsValue("Записей долга", data.effective.debtCount.toString())
                AnalyticsValue("Оплаты · сумма", analyticsMoney(data.effective.payments))
                AnalyticsValue("Записей оплаты", data.effective.paymentCount.toString())
                val mean = if (data.effective.debtCount == 0) null else BigDecimal.valueOf(data.effective.debts)
                    .divide(BigDecimal.valueOf(data.effective.debtCount.toLong()), 0, java.math.RoundingMode.HALF_UP).longValueExact()
                AnalyticsValue("Средняя запись долга", mean?.let(::analyticsMoney) ?: "Нет данных")
                AnalyticsValue("Максимальная запись", data.effective.maximumDebt?.let(::analyticsMoney) ?: "Нет данных")
                AnalyticsValue("Клиентов с операциями", data.activeCustomers.toString())
                AnalyticsValue("Брали в долг", data.borrowingCustomers.toString())
                AnalyticsValue("Впервые брали в долг", data.firstTimeBorrowers.toString())
                AnalyticsValue("Погасили остаток", data.settledCustomers.toString())
                Text("Без записей, отменённых к концу периода.", color = ledgerMuted, fontSize = 12.sp)
            }
        }
        result.previous?.let { previous ->
            item("comparison") {
                Column {
                    AnalyticsHeading("Сравнение периодов")
                    Text(analyticsRange(previous.range.from, previous.range.to), color = ledgerMuted, fontSize = 12.sp)
                    AnalyticsChange("Действительные долги", data.effective.debts, previous.effective.debts)
                    AnalyticsChange("Действительные оплаты", data.effective.payments, previous.effective.payments)
                    AnalyticsChange("Остаток на конец", data.closing, previous.closing)
                }
            }
        }
        }
        if (section == AnalyticsSection.DETAILS) {
        item("ranking-title") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsHeading("Рейтинг клиентов")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnalyticsRanking.entries.forEach { mode ->
                        FilterChip(selected = ranking == mode, onClick = { ranking = mode }, label = { Text(mode.title) })
                    }
                }
                Text(if (ranking == AnalyticsRanking.BALANCE || ranking == AnalyticsRanking.OVERDUE) "На сегодня" else if (ranking == AnalyticsRanking.SETTLED) "Положительный остаток на начало, нулевой на конец" else if (ranking == AnalyticsRanking.NEW) "Первый действительный долг в выбранном периоде" else "За выбранный период · действительные операции",
                    color = ledgerMuted, fontSize = 12.sp)
                if (ranked.isEmpty()) Text("Нет клиентов с таким показателем.", color = ledgerMuted)
            }
        }
        items(ranked.take(rankLimit), key = { "rank-" + it.id }) { customer ->
            val amount = ranking.value(customer)
            val label = if (ranking == AnalyticsRanking.FREQUENCY) "$amount записей" else analyticsMoney(amount)
            Column(Modifier.fillMaxWidth().clickable { onCustomer.accept(customer.id) }.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.names.getValue(customer.id), color = ledgerText, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(label, color = if (ranking == AnalyticsRanking.OVERDUE) ledgerAlert else ledgerText,
                        fontSize = 14.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                    Text("›", color = ledgerMuted, fontSize = 18.sp)
                }
                val denominator = if (ranking == AnalyticsRanking.BALANCE) data.current.balance else ranking.value(ranked.first())
                val fraction = if (denominator > 0) (amount.toDouble() / denominator.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
                if (ranking == AnalyticsRanking.BALANCE && denominator > 0) {
                    val share = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(denominator), 1, java.math.RoundingMode.HALF_UP)
                    Text(share.toPlainString().replace('.', ',') + "% общей задолженности", color = ledgerMuted, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(ledgerPanel, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(ledgerAccent, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (ranked.size > rankLimit) item("more-ranked") {
            TextButton(onClick = { rankLimit += 20 }, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё клиентов") }
        }
        }
        if (section == AnalyticsSection.REPORTS) {
        item("export") { AnalyticsExportActions(result, onExport) }
        item("history") {
            OutlinedButton(onClick = { onHistory.accept(data.range.from, data.range.to) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Операции периода") }
        }
        }
    }
}

@Composable
internal fun AnalyticsChange(label: String, current: Long, previous: Long) {
    val change = AnalyticsPeriods.Change.between(current, previous)
    AnalyticsValue(label, (if (change.absolute > 0) "+" else "") + analyticsMoney(change.absolute))
    Text("Было: " + analyticsMoney(previous) + " · " +
        (change.percent?.let { (if (it.signum() > 0) "+" else "") + it.toPlainString().replace('.', ',') + "%" }
            ?: "нет базы для процента"), color = ledgerMuted, fontSize = 12.sp)
}

@Composable
internal fun AnalyticsChart(buckets: List<AnalyticsSeries.Bucket>) {
    if (buckets.isEmpty()) return
    var index by remember(buckets) { mutableIntStateOf(buckets.lastIndex) }
    val selected = buckets[index]
    val balances = remember(buckets) { listOf(buckets.first().opening) + buckets.map { it.closing } }
    val lowAmount = minOf(0L, balances.minOrNull() ?: 0L)
    val highAmount = maxOf(1L, balances.maxOrNull() ?: 0L)
    val low = lowAmount.toDouble()
    val high = highAmount.toDouble()
    val maxFlow = remember(buckets) { buckets.maxOf { maxOf(it.movement.debts, it.movement.payments, 1L) }.toDouble() }
    Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Шкала: " + analyticsMoney(lowAmount) + " — " + analyticsMoney(highAmount), color = ledgerMuted, fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(156.dp).pointerInput(buckets) {
            detectTapGestures { tap -> index = (tap.x / size.width.coerceAtLeast(1) * buckets.size).toInt().coerceIn(0, buckets.lastIndex) }
        }.semantics {
            contentDescription = "График остатка. Начало " + analyticsMoney(balances.first()) + ", конец " + analyticsMoney(balances.last())
        }) {
            val step = size.width / buckets.size
            fun y(value: Long) = (size.height * (1.0 - (value.toDouble() - low) / (high - low))).toFloat()
            repeat(3) { n ->
                val lineY = size.height * n / 2f
                drawLine(ledgerBorder.copy(alpha = .5f), Offset(0f, lineY), Offset(size.width, lineY), 1.dp.toPx())
            }
            for (i in buckets.indices) {
                val left = i * step
                drawLine(ledgerAccent, Offset(left, y(balances[i])), Offset(left + step, y(balances[i])), 2.dp.toPx())
                drawLine(ledgerAccent, Offset(left + step, y(balances[i])), Offset(left + step, y(balances[i + 1])), 2.dp.toPx())
            }
            val marker = (index + 1) * step
            drawLine(ledgerMuted, Offset(marker, 0f), Offset(marker, size.height), 1.dp.toPx())
            drawCircle(ledgerAccent, 4.dp.toPx(), Offset(marker, y(selected.closing)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("■ Долги", color = ledgerAlert, fontSize = 12.sp)
            Text("■ Оплаты", color = ledgerAccent, fontSize = 12.sp)
        }
        Text("Нажмите на график, чтобы выбрать интервал", color = ledgerMuted, fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(82.dp).pointerInput(buckets) {
            detectTapGestures { tap -> index = (tap.x / size.width.coerceAtLeast(1) * buckets.size).toInt().coerceIn(0, buckets.lastIndex) }
        }.semantics {
            contentDescription = "Столбцы долгов и оплат; точные значения доступны под графиком"
        }) {
            val step = size.width / buckets.size
            buckets.forEachIndexed { i, bucket ->
                val debt = (size.height * bucket.movement.debts / maxFlow).toFloat()
                val pay = (size.height * bucket.movement.payments / maxFlow).toFloat()
                val opacity = if (i == index) 1f else .65f
                drawRect(ledgerAlert.copy(alpha = opacity), Offset(i * step, size.height - debt), Size(step * .42f, debt))
                drawRect(ledgerAccent.copy(alpha = opacity), Offset(i * step + step * .46f, size.height - pay), Size(step * .42f, pay))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(analyticsDate.format(buckets.first().from), color = ledgerMuted, fontSize = 11.sp)
            Text(analyticsDate.format(buckets.last().to), color = ledgerMuted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { index-- }, enabled = index > 0,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Предыдущий интервал" }) { Text("‹") }
            Text(analyticsRange(selected.from, selected.to), color = ledgerText,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp)
            TextButton(onClick = { index++ }, enabled = index < buckets.lastIndex,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Следующий интервал" }) { Text("›") }
        }
        AnalyticsValue("Остаток", analyticsMoney(selected.closing))
        AnalyticsValue("Долги", analyticsMoney(selected.movement.debts), ledgerAlert)
        AnalyticsValue("Оплаты", analyticsMoney(selected.movement.payments), ledgerAccent)
        if (selected.movement.debtReversals != 0L || selected.movement.paymentReversals != 0L) {
            AnalyticsValue("Отменено долгов", analyticsMoney(selected.movement.debtReversals))
            AnalyticsValue("Отменено оплат", analyticsMoney(selected.movement.paymentReversals))
        }
    }
}

private enum class AnalyticsRanking(val title: String) {
    BALANCE("Остаток"), OVERDUE("Просрочка"), DEBTS("Сумма долгов"), FREQUENCY("Частота"), PAYMENTS("Оплаты"), NEW("Новые"), SETTLED("Погасили");
    fun includes(customer: AnalyticsProjection.Customer, range: AnalyticsProjection.Range): Boolean = when (this) {
        NEW -> customer.firstDebtAsOfEnd?.let { !it.isBefore(range.from) && !it.isAfter(range.to) } == true
        SETTLED -> customer.opening > 0L && customer.closing == 0L
        else -> value(customer) > 0L
    }
    fun value(customer: AnalyticsProjection.Customer): Long = when (this) {
        BALANCE -> customer.current.balance
        OVERDUE -> customer.current.overdue
        DEBTS -> customer.effective.debts
        FREQUENCY -> customer.effective.debtCount.toLong()
        PAYMENTS -> customer.effective.payments
        NEW -> customer.effective.debts
        SETTLED -> customer.opening
    }
}

@Composable
internal fun AnalyticsExportActions(result: AnalyticsResult, onExport: Consumer<AnalyticsExport.Spec>) {
    val period = result.period.range
    val previous = result.previous?.range
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    fun export(pdf: Boolean) {
        onExport.accept(AnalyticsExport.Spec(period.from, period.to, previous?.from, previous?.to,
            result.request.customerId, pdf))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnalyticsHeading("Экспорт статистики")
        Text(if (result.request.customerId == null) "Только сводные показатели, без имён и телефонов."
            else "Отчёт содержит имя клиента и его финансовые показатели. Файл не зашифрован.",
            color = ledgerMuted, fontSize = 12.sp)
        OutlinedButton(onClick = { if (result.request.customerId == null) export(false) else confirm = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Сохранить CSV") }
        OutlinedButton(onClick = { if (result.request.customerId == null) export(true) else confirm = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Сохранить PDF") }
    }
    confirm?.let { pdf ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Экспорт данных клиента") },
            text = { Text("Имя и финансовые показатели будут записаны в незашифрованный файл. Выберите безопасное место хранения.") },
            confirmButton = { TextButton(onClick = { confirm = null; export(pdf) }) { Text("Продолжить") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } })
    }
}

internal enum class AnalyticsSection { SUMMARY, DETAILS, REPORTS }

@Composable
private fun AnalyticsSectionRow(section: AnalyticsSection, personal: Boolean, choose: (AnalyticsSection) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AnalyticsSection.entries.forEach { option ->
            val active = section == option
            val label = when (option) {
                AnalyticsSection.SUMMARY -> "Обзор"
                AnalyticsSection.DETAILS -> if (personal) "Детали" else "Клиенты"
                AnalyticsSection.REPORTS -> "Экспорт"
            }
            Column(Modifier.weight(1f).clickable { choose(option) }
                .semantics { selected = active; role = Role.Tab }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (active) ledgerText else ledgerMuted, fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) ledgerAccent else Color.Transparent))
            }
        }
    }
}
