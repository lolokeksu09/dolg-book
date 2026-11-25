package dev.ventry.raschet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Monday-first grid, padded only with empty cells, never neighboring-month records. */
internal fun calendarWeeks(month: YearMonth): List<List<LocalDate?>> {
    val offset = month.atDay(1).dayOfWeek.value - 1
    val cells = List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    return (cells + List((7 - cells.size % 7) % 7) { null }).chunked(7)
}

@Composable
internal fun BookCalendar(month: YearMonth, selectedDay: LocalDate, today: LocalDate,
    days: Map<LocalDate, BookDays.Day>, payments: Boolean,
    onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val weeks = remember(month) { calendarWeeks(month) }
    val title = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru", "RU")))
        .replaceFirstChar { it.titlecase(Locale("ru", "RU")) }
    Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(10.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onMonth(month.minusMonths(1)) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Предыдущий месяц" }) { Text("‹") }
            Text(title, color = ledgerText, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onMonth(month.plusMonths(1)) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Следующий месяц" }) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            for (weekday in listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")) {
                Text(weekday, color = ledgerMuted, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp))
            }
        }
        for (week in weeks) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (date in week) {
                if (date == null) Box(Modifier.weight(1f).heightIn(min = 48.dp))
                else {
                    val day = days[date]
                    val chosen = date == selectedDay
                    val description = buildString {
                        append(ledgerDayFormat.format(date))
                        if (date == today) append(", сегодня")
                        if (day == null) append(", нет записей")
                        else {
                            append(", записей ").append(day.entries.size)
                            append(if (payments) ", получено " else ", записано ").append(ledgerMoney(day.recorded))
                            if (!payments) append(", осталось ").append(ledgerMoney(day.remaining))
                        }
                    }
                    Column(Modifier.weight(1f).heightIn(min = 48.dp)
                        .background(if (chosen) Color(LedgerTheme.SELECTED) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { onDay(date) }.semantics { selected = chosen; contentDescription = description }
                        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(date.dayOfMonth.toString(), color = if (date == today || chosen) ledgerAccent else ledgerText,
                            fontWeight = if (date == today || chosen) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                        Text(if (day == null) "—" else day.entries.size.toString(),
                            color = if (day != null && day.remaining > 0 && !payments) ledgerAlert else ledgerMuted,
                            fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
        Text("Под датой — число записей. Выберите день, чтобы открыть список.", color = ledgerMuted, fontSize = 11.sp)
    }
}


/** The date strip is the working edge of the ledger, not a separate dashboard. */
@Composable
internal fun BookWeek(anchor: LocalDate, selectedDay: LocalDate?, today: LocalDate,
    days: Map<LocalDate, BookDays.Day>, payments: Boolean,
    onWeek: (LocalDate) -> Unit, onDay: (LocalDate) -> Unit, onMonth: () -> Unit) {
    val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
    val dates = remember(monday) { (0L..6L).map { monday.plusDays(it) } }
    val title = anchor.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru", "RU")))
        .replaceFirstChar { it.titlecase(Locale("ru", "RU")) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onMonth, contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(title + "  ▾", color = ledgerText, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
            }
            TextButton(onClick = { onWeek(anchor.minusWeeks(1)) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Предыдущая неделя" }) { Text("‹", fontSize = 24.sp) }
            TextButton(onClick = { onWeek(anchor.plusWeeks(1)) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Следующая неделя" }) { Text("›", fontSize = 24.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (date in dates) {
                val entry = days[date]
                val active = selectedDay == date
                val desc = buildString {
                    append(ledgerDayFormat.format(date))
                    if (date == today) append(", сегодня")
                    if (entry == null) append(", нет записей") else {
                        append(", записей ").append(entry.entries.size)
                        append(if (payments) ", получено " else ", записано ").append(ledgerMoney(entry.recorded))
                        if (!payments) append(", осталось ").append(ledgerMoney(entry.remaining))
                    }
                }
                Column(Modifier.weight(1f).heightIn(min = 66.dp)
                    .background(if (active) ledgerAccent else ledgerPanel, RoundedCornerShape(8.dp))
                    .clickable { onDay(date) }.semantics { selected = active; contentDescription = desc }
                    .padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Text(date.format(DateTimeFormatter.ofPattern("EE", Locale("ru", "RU"))),
                        color = if (active) Color(LedgerTheme.INK) else ledgerMuted, fontSize = 10.sp)
                    Text(date.dayOfMonth.toString(),
                        color = if (active) Color(LedgerTheme.INK) else if (date == today) ledgerAccent else ledgerText,
                        fontSize = 18.sp, fontWeight = if (active || date == today) FontWeight.SemiBold else FontWeight.Medium)
                    Text(entry?.entries?.size?.toString() ?: "—",
                        color = if (active) Color(LedgerTheme.INK) else if (entry != null) ledgerAccent else ledgerMuted,
                        fontSize = 10.sp)
                }
            }
        }
    }
}
