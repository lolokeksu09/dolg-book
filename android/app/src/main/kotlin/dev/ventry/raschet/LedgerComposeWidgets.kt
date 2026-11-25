package dev.ventry.raschet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val ledgerBg = Color(LedgerTheme.BG)
internal val ledgerPanel = Color(LedgerTheme.PANEL)
internal val ledgerText = Color(LedgerTheme.TEXT)
internal val ledgerMuted = Color(LedgerTheme.MUTED)
internal val ledgerAccent = Color(LedgerTheme.ACCENT)
internal val ledgerAlert = Color(LedgerTheme.ALERT)
internal val ledgerRed = Color(LedgerTheme.RED)
internal val ledgerBorder = Color(LedgerTheme.BORDER)
internal val ledgerDayFormat = DateTimeFormatter.ofPattern("dd.MM.yy", Locale("ru", "RU"))
internal fun ledgerMoney(cents: Long) =
    String.format(Locale("ru", "RU"), "%,.2f ₽", cents / 100.0).replace('\u00a0', ' ')

@Composable
internal fun LedgerMetric(title: String, value: String, color: Color = ledgerMuted, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = action)
            .semantics { contentDescription = title }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = ledgerText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(value, color = color, fontSize = 13.sp)
        }
        Text("›", color = ledgerMuted, fontSize = 22.sp)
    }
    HorizontalDivider(color = ledgerBorder.copy(alpha = 0.46f))
}

@Composable
internal fun LedgerTotal(title: String, amount: Long, detail: String? = null) {
    val paper = Color(0xfff4f0df)
    val ink = Color(LedgerTheme.INK)
    Column(Modifier.fillMaxWidth().background(paper, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(ledgerMoney(amount), color = ink, fontWeight = FontWeight.SemiBold, fontSize = 38.sp,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum", letterSpacing = (-1).sp))
        if (detail != null) {
            HorizontalDivider(color = ink.copy(alpha = 0.22f))
            Text(detail, color = ink, fontSize = 12.sp)
        }
    }
}

/** Empty-state illustration uses the actual navigation identity rather than sample transactions. */
@Composable
internal fun LedgerEmpty(icon: Int, title: String, description: String, action: String,
    onAction: () -> Unit, largeText: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)
        .background(ledgerPanel, RoundedCornerShape(16.dp))
        .border(1.dp, ledgerBorder.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(48.dp).background(Color(LedgerTheme.ELEVATED), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = ledgerAccent, modifier = Modifier.size(24.dp))
        }
        Text(title, color = ledgerText, fontWeight = FontWeight.SemiBold, fontSize = if (largeText) 22.sp else 20.sp)
        Text(description, color = ledgerMuted, fontSize = if (largeText) 16.sp else 14.sp)
        Button(onClick = onAction, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action) }
    }
}


internal fun promiseNotices(plan: ClientDirectory.FollowUp, progress: PaymentPromises.Progress?, today: LocalDate): List<DetailNotice> {
    if (progress == null) return listOf(DetailNotice(
        "Связаться: " + ledgerDayFormat.format(plan.date) + if (plan.note.isEmpty()) "" else " · " + plan.note,
        if (plan.date.isAfter(today)) ledgerMuted else ledgerAlert
    ))
    val result = mutableListOf(DetailNotice("Обещано " + ledgerMoney(plan.amount) + " до " + ledgerDayFormat.format(plan.date), ledgerText))
    if (!progress.valid) result += DetailNotice("Нужно обновить обещание: исходная операция не найдена.", ledgerAlert)
    else {
        val caption = if (progress.fulfilled()) {
            if (progress.onTime >= plan.amount) "Выполнено" else "Выполнено после срока"
        } else if (progress.missed) "Срок обещания прошёл" else "Ожидается оплата"
        result += DetailNotice(caption + " · получено " + ledgerMoney(minOf(progress.paid, plan.amount)) +
            " из " + ledgerMoney(plan.amount), if (progress.fulfilled()) ledgerAccent else if (progress.missed) ledgerAlert else ledgerMuted)
        if (!progress.fulfilled()) result += DetailNotice("Осталось по обещанию: " + ledgerMoney(plan.amount - progress.paid), ledgerMuted)
    }
    if (plan.note.isNotEmpty()) result += DetailNotice(plan.note, ledgerMuted)
    return result
}
