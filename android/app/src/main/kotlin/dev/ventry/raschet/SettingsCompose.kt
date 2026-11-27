package dev.ventry.raschet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer

/** A read-only presentation snapshot; security, preferences and actual backups remain Activity-owned. */
data class SettingsSnapshot(
    val shopName: String, val largeText: Boolean, val reminders: Boolean,
    val privacyEnabled: Boolean, val hideScreens: Boolean, val expanded: String,
    val externalBackupConfirmed: Boolean, val lastExportText: String,
    val recoveryStatus: String, val recoveryAvailable: Boolean, val recoveryError: Boolean,
    val autoRecovery: Boolean
)

data class SettingsActions(
    val shop: Runnable, val largeText: Consumer<Boolean>, val reminders: Consumer<Boolean>,
    val expand: Consumer<String>, val authenticate: Runnable, val lock: Runnable,
    val screenshots: Runnable, val createPoint: Runnable, val restorePoint: Runnable,
    val autoRecovery: Consumer<Boolean>, val json: Runnable, val csv: Runnable,
    val restoreJson: Runnable, val catalog: Runnable
)

object SettingsCompose {
    @JvmStatic
    fun create(owner: ComponentActivity, state: SettingsSnapshot, actions: SettingsActions): ComposeView =
        ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { LedgerMaterialTheme { SettingsContent(state, actions) } }
        }

    @JvmStatic
    fun createLicensed(owner: ComponentActivity, state: SettingsSnapshot, actions: SettingsActions, license: Runnable): ComposeView =
        ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { LedgerMaterialTheme { SettingsContent(state, actions, license) } }
        }
}

@Composable
internal fun SettingsContent(state: SettingsSnapshot, actions: SettingsActions, license: Runnable? = null) {
    Box(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize().verticalScroll(rememberScrollState())
            .testTag("settings-list").padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Настройки", color = ledgerText, fontSize = if (state.largeText) 28.sp else 24.sp,
                fontWeight = FontWeight.SemiBold)
            if (license != null) {
                SettingsSection("Подписка Ventry", state.largeText)
                SettingsPanel { LedgerMetric("Лицензия", "Активация и обновление доступа", action = license::run) }
            }
            SettingsSection("Безопасность и данные", state.largeText)
            SettingsPanel {
                LedgerMetric("Резервные копии", if (state.externalBackupConfirmed) "Экспорт и восстановление"
                    else "Нет подтверждённой внешней копии",
                    if (state.externalBackupConfirmed) ledgerMuted else ledgerAlert) { actions.expand.accept("backup") }
                LedgerMetric("Защита входа", if (state.privacyEnabled) "Включена" else "Выключена",
                    if (state.privacyEnabled) ledgerAccent else ledgerMuted) { actions.expand.accept("privacy") }
            }
            if (state.expanded == "privacy") {
                SettingsPanel {
                    Text(if (state.privacyEnabled) "Вход защищён кодом устройства" else "Защита входа выключена",
                        color = ledgerText, fontWeight = FontWeight.Bold)
                    SettingsHint("Подтверждение кодом Android при открытии и после минуты вне приложения.", state.largeText)
                    OutlinedButton(onClick = actions.authenticate::run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (state.privacyEnabled) "Отключить защиту входа" else "Защитить вход")
                    }
                    if (state.privacyEnabled) {
                        OutlinedButton(onClick = actions.lock::run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Заблокировать книгу")
                        }
                    } else {
                        OutlinedButton(onClick = actions.screenshots::run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (state.hideScreens) "Разрешить снимки экрана" else "Скрывать снимки и недавние")
                        }
                    }
                    SettingsHint("При защите входа снимки блокируются. База отдельно не шифруется.", state.largeText)
                }
            }
            if (state.expanded == "backup") {
                Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Внешняя копия книги", color = ledgerText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(state.lastExportText, color = if (state.externalBackupConfirmed) ledgerMuted else ledgerAlert,
                        fontSize = if (state.largeText) 16.sp else 13.sp)
                    SettingsHint("JSON сохраняет данные книги для переноса на другое устройство. Файл не зашифрован.", state.largeText)
                    Button(onClick = actions.json::run, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Скачать JSON-копию") }
                }
                SettingsPanel {
                    Text("Защищённые точки восстановления", color = ledgerText, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp))
                    SettingsHint(state.recoveryStatus, state.largeText)
                    if (state.recoveryError) Text("Автозащита не смогла создать последнюю копию. Сохраните JSON вручную.",
                        color = ledgerRed, fontSize = 13.sp)
                    Text("Зашифрованы на этом устройстве. После удаления приложения или сброса устройства восстановить их нельзя.",
                        color = ledgerAlert, fontSize = if (state.largeText) 15.sp else 12.sp)
                    OutlinedButton(onClick = actions.createPoint::run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Создать защищённую точку")
                    }
                    if (state.recoveryAvailable) OutlinedButton(onClick = actions.restorePoint::run,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Восстановить защищённую точку") }
                }
                SettingsToggle("Автозащита после изменений",
                    "Создаёт локальную зашифрованную точку после сохранения. При серии быстрых записей сохраняется последнее состояние. Хранятся до четырёх точек.",
                    "Автозащита книги", state.autoRecovery, state.largeText, actions.autoRecovery::accept)
                SettingsSection("Экспорт и восстановление", state.largeText)
                SettingsPanel {
                    LedgerMetric("Экспортировать CSV", "Таблица операций", action = actions.csv::run)
                    LedgerMetric("Восстановить из JSON", "Полная замена данных после подтверждения",
                        ledgerAlert, action = actions.restoreJson::run)
                }
                SettingsPanel {
                    Text("Важно о резервных копиях", color = ledgerAlert, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 10.dp))
                    SettingsHint("При удалении приложения локальная книга будет потеряна. JSON-копия содержит имена, телефоны и суммы без шифрования.",
                        state.largeText)
                }
            }
            SettingsSection("Магазин", state.largeText)
            SettingsPanel {
                LedgerMetric(state.shopName, "Изменить название", action = actions.shop::run)
                LedgerMetric("Каталог товаров", "Цены, единицы и избранное", action = actions.catalog::run)
            }
            SettingsSection("Удобство работы", state.largeText)
            SettingsPanel {
                SettingsToggle("Крупный текст", "Увеличить текст для удобного чтения", "Крупный текст",
                    state.largeText, state.largeText, actions.largeText::accept)
                SettingsToggle("Ежедневно проверять сроки", "Локальные напоминания о долгах",
                    "Ежедневные напоминания", state.reminders, state.largeText, actions.reminders::accept)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, large: Boolean) {
    Text(title, color = ledgerMuted, fontSize = if (large) 16.sp else 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun SettingsHint(value: String, large: Boolean) {
    Text(value, color = ledgerMuted, fontSize = if (large) 16.sp else 13.sp)
}

@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** State is committed by the callback, never optimistically flipped before permission/OS confirmation. */
@Composable
private fun SettingsToggle(title: String, detail: String, description: String,
    checked: Boolean, large: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = changed)
        .semantics { contentDescription = description }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = ledgerText, fontWeight = FontWeight.Medium, fontSize = if (large) 19.sp else 15.sp)
            SettingsHint(detail, large)
        }
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedThumbColor = ledgerBg, checkedTrackColor = ledgerAccent,
            uncheckedThumbColor = ledgerMuted, uncheckedTrackColor = ledgerPanel,
            uncheckedBorderColor = ledgerBorder))
    }
    HorizontalDivider(color = ledgerBorder)
}
