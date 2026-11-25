package dev.ventry.raschet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ventry.raschet.presentation.BookScreenData
import java.util.Locale
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private val ledgerBackground = Color(LedgerTheme.BG)
private val panel = Color(LedgerTheme.PANEL)
private val border = Color(LedgerTheme.BORDER)
private val text = Color(LedgerTheme.TEXT)
private val muted = Color(LedgerTheme.MUTED)
private val accent = Color(LedgerTheme.ACCENT)
private val alert = Color(LedgerTheme.ALERT)
private val red = Color(LedgerTheme.RED)
private val selectedBackground = Color(LedgerTheme.SELECTED)

@Composable
internal fun LedgerMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, onPrimary = Color(LedgerTheme.INK),
            background = ledgerBackground, onBackground = text,
            surface = panel, onSurface = text, onSurfaceVariant = muted,
            outline = border, error = red,
            secondary = accent, onSecondary = Color(LedgerTheme.INK),
            secondaryContainer = selectedBackground, onSecondaryContainer = text,
            primaryContainer = selectedBackground, onPrimaryContainer = text,
            tertiary = alert, onTertiary = Color(LedgerTheme.INK),
            tertiaryContainer = panel, onTertiaryContainer = text,
            surfaceVariant = selectedBackground, errorContainer = Color(0xff542d2d),
            onError = Color(0xff421b1b), onErrorContainer = Color(0xffffdada)
        ), shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp), extraLarge = RoundedCornerShape(18.dp)),
        content = content
    )
}

internal data class ClientListData(
    val allCount: Int,
    val scoped: List<CollectionsInsights.CustomerStatus>,
    val limits: Map<String, Long>
)

internal data class ClientListActions(
    val search: (String) -> Unit,
    val limit: (Int) -> Unit,
    val add: () -> Unit,
    val filters: () -> Unit,
    val open: (String) -> Unit,
    val resetScope: () -> Unit,
    val resetFilter: () -> Unit
)

/** Transitional View host: the existing Activity owns navigation, not this composition. */
object ClientsCompose {
    @JvmStatic
    fun create(
        owner: ComponentActivity, data: BookScreenData, scope: String, filter: String,
        amountFirst: Boolean, recentFirst: Boolean, selectedId: String?, query: String,
        visibleLimit: Int, largeText: Boolean, onSearch: Consumer<String>, onLimit: IntConsumer,
        onAdd: Runnable, onFilters: Runnable, onOpen: Consumer<String>,
        onResetScope: Runnable, onResetFilter: Runnable
    ): ComposeView {
        val application = owner.applicationContext
        val actions = ClientListActions(
            onSearch::accept, onLimit::accept, onAdd::run, onFilters::run,
            onOpen::accept, onResetScope::run, onResetFilter::run
        )
        return ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                LedgerMaterialTheme {
                    var prepared by remember(data, scope) { mutableStateOf<ClientListData?>(null) }
                    var failed by remember(data, scope) { mutableStateOf(false) }
                    var attempt by remember { mutableIntStateOf(0) }
                    LaunchedEffect(data, scope, attempt) {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val directory = ClientDirectory(application)
                                val scoped = directory.select(data.statuses, scope)
                                ClientListData(data.snapshot.clients.size, scoped,
                                    scoped.associate { it.client.id to directory.debtLimit(it.client.id) })
                            }
                            coroutineContext.ensureActive()
                            prepared = result
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        }
                    }
                    val model = prepared
                    when {
                        model != null -> ClientListContent(model, scope, filter, amountFirst, recentFirst,
                            selectedId, query, visibleLimit, largeText, actions)
                        failed -> Column(Modifier.fillMaxSize().background(ledgerBackground).padding(16.dp)) {
                            Text("Ошибка чтения клиентов", color = text)
                            OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Повторить") }
                        }
                        else -> Box(Modifier.fillMaxSize().background(ledgerBackground), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

private fun money(cents: Long): String =
    String.format(Locale("ru", "RU"), "%,.2f ₽", cents / 100.0).replace('\u00a0', ' ')

@Composable
internal fun ClientListContent(
    model: ClientListData, scope: String, filter: String, amountFirst: Boolean,
    recentFirst: Boolean, selectedId: String?, initialQuery: String, initialLimit: Int,
    largeText: Boolean, actions: ClientListActions
) {
    var query by remember { mutableStateOf(initialQuery) }
    var limit by remember { mutableIntStateOf(initialLimit) }
    var visible by remember(model, filter, amountFirst, recentFirst, query) {
        mutableStateOf<List<CollectionsInsights.CustomerStatus>?>(null)
    }
    var filterFailed by remember(model, filter, amountFirst, recentFirst, query) { mutableStateOf(false) }
    var filterAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(model, filter, amountFirst, recentFirst, query, filterAttempt) {
        try {
            val result = withContext(Dispatchers.Default) {
                CollectionsInsights.visible(model.scoped, filter, amountFirst, recentFirst, query)
            }
            coroutineContext.ensureActive()
            visible = result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            filterFailed = true
        }
    }
    fun search(value: String) {
        query = value
        limit = 60
        actions.search(value)
    }

    val result = visible
    BoxWithConstraints(Modifier.fillMaxSize().background(ledgerBackground), contentAlignment = Alignment.TopCenter) {
        val compact = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.2f && !largeText
        LazyColumn(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item("title") {
                Text("Клиенты", color = text, fontSize = if (largeText) 24.sp else 20.sp, fontWeight = FontWeight.Bold)
            }
            if (model.allCount == 0) {
                item("empty-book") {
                    ClientEmpty("Пока нет клиентов", "Добавьте клиента, чтобы записывать долги и оплаты.",
                        "Добавить первого клиента", actions.add, largeText)
                }
            } else {
                item("tools") {
                    if (compact) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClientSearchField(query, ::search, Modifier.weight(1f), largeText)
                            Button(onClick = actions.add, shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "+ Добавить клиента" },
                                contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Text("+", fontSize = 22.sp)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = actions.add, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("+ Добавить клиента")
                            }
                            ClientSearchField(query, ::search, Modifier.fillMaxWidth(), largeText)
                        }
                    }
                }
                item("filters") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(onClick = actions.filters)
                        .semantics { contentDescription = "Фильтры клиентов" }
                        .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Фильтры клиентов", color = text, fontSize = if (largeText) 16.sp else 14.sp, fontWeight = FontWeight.Medium)
                            Text(scope + " · " + filter + if (recentFirst) " · недавние" else if (amountFirst) " · по остатку" else " · по имени",
                                color = muted, fontSize = 12.sp)
                        }
                        Text("›", color = muted, fontSize = 22.sp)
                    }
                    HorizontalDivider(color = border)
                }
                when {
                    filterFailed -> item("filter-error") {
                        ClientEmpty("Ошибка поиска", "Данные не изменены.", "Повторить",
                            { filterFailed = false; filterAttempt++ }, largeText)
                    }
                    result == null -> item("loading-results") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    result.isEmpty() -> item("empty-results") {
                        when {
                            model.scoped.isEmpty() && scope != ClientDirectory.ACTIVE && query.isBlank() ->
                                ClientEmpty(if (scope == ClientDirectory.ARCHIVE) "Архив пуст" else "Нет избранных клиентов",
                                    "Управляйте списками из карточки клиента.", "Рабочие клиенты", actions.resetScope, largeText)
                            query.isNotBlank() -> ClientEmpty("Никого не нашли", "Попробуйте другое имя или номер телефона.",
                                "Очистить поиск", { search("") }, largeText)
                            filter == CollectionsInsights.CLEAR -> ClientEmpty("Нет клиентов без долга",
                                "Здесь будут клиенты с нулевым остатком.", "Показать всех", actions.resetFilter, largeText)
                            filter == CollectionsInsights.LATE -> ClientEmpty("Просроченных долгов нет",
                                "Просрочка появится здесь, если срок возврата прошёл и долг не погашен.",
                                "Показать всех", actions.resetScope, largeText)
                            filter == CollectionsInsights.SOON -> ClientEmpty("Нет ближайших сроков",
                                "Нет непогашенных долгов со сроком возврата в следующие 7 дней.",
                                "Показать всех", actions.resetScope, largeText)
                            else -> ClientEmpty("Нет клиентов с долгом", "Здесь будут клиенты с непогашенным остатком.",
                                "Показать всех", actions.resetScope, largeText)
                        }
                    }
                    else -> {
                        item("summary") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Клиентов: " + result.size, color = muted, fontSize = 12.sp)
                                Text("Долг: " + money(result.sumOf { it.balance }), color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = border)
                        }
                        items(result.take(limit), key = { it.client.id }) { status ->
                            ClientRow(status, model.limits[status.client.id] ?: 0L, selectedId == status.client.id, largeText) {
                                actions.open(status.client.id)
                            }
                        }
                        if (result.size > limit) item("more") {
                            OutlinedButton(onClick = { limit += 60; actions.limit(limit) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("Показать ещё клиентов")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientSearchField(value: String, changed: (String) -> Unit, modifier: Modifier, largeText: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = changed, singleLine = true,
        placeholder = { Text("Поиск по имени или телефону", fontSize = if (largeText) 15.sp else 13.sp) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = if (largeText) 17.sp else 15.sp),
        modifier = modifier.semantics { contentDescription = "Поиск по имени или телефону" },
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun ClientEmpty(title: String, description: String, action: String, run: () -> Unit, largeText: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = text, fontSize = if (largeText) 20.sp else 18.sp, fontWeight = FontWeight.Bold)
        Text(description, color = muted, fontSize = if (largeText) 16.sp else 14.sp)
        OutlinedButton(onClick = run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
private fun ClientRow(status: CollectionsInsights.CustomerStatus, limit: Long, isSelected: Boolean, largeText: Boolean, open: () -> Unit) {
    val customer = status.client
    val amount = status.balance
    val limitText = if (limit > 0 && amount >= limit) {
        if (amount > limit) "Лимит превышен" else "Лимит исчерпан"
    } else ""
    val description = customer.name + ", остаток " + money(amount) +
        (if (status.overdue > 0) ", просрочено " + money(status.overdue) else "") +
        (if (limitText.isNotEmpty()) ", " + limitText.lowercase(Locale.ROOT) else "")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(if (isSelected) selectedBackground else ledgerBackground, RoundedCornerShape(6.dp))
        .clickable(onClick = open)
        .semantics { contentDescription = description; selected = isSelected }
        .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(customer.name, color = text, fontSize = if (largeText) 17.sp else 15.sp, fontWeight = FontWeight.SemiBold)
            if (customer.phone.isNotEmpty()) Text(customer.phone, color = muted, fontSize = 13.sp)
            if (status.overdue > 0) Text("Просрочено: " + money(status.overdue), color = red, fontSize = 12.sp)
            else if (status.upcoming > 0) Text("Ближайший срок: " + status.nextDue + " · за 7 дней: " + money(status.upcoming),
                color = alert, fontSize = 12.sp)
            if (limitText.isNotEmpty()) Text(limitText, color = alert, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(amount), color = text, fontSize = if (largeText) 18.sp else 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            if (amount == 0L) Text("Нет долга", color = muted, fontSize = 11.sp)
        }
        Text("›", color = muted, fontSize = 19.sp)
    }
    HorizontalDivider(color = border)
}
