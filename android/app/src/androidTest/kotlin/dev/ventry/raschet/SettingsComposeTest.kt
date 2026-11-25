package dev.ventry.raschet

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun state(expanded: String = "", protected: Boolean = false, points: Boolean = false) =
        SettingsSnapshot("Магазин", false, false, protected, protected, expanded, false,
            "JSON-копия ещё не сохранена через это приложение.",
            if (points) "Сохранено точек: 1" else "Пока нет точек.",
            points, false, false)

    private fun actions(log: AtomicReference<String>, count: AtomicInteger) = SettingsActions(
        { log.set("shop") }, { log.set("large:$it") }, { log.set("reminder:$it") },
        { log.set(it) }, { log.set("auth") }, { log.set("lock") },
        { log.set("screenshots") }, { log.set("create") }, { count.incrementAndGet() },
        { log.set("recovery:$it") }, { log.set("json") }, { log.set("csv") }, { log.set("restore-json") }, { log.set("catalog") }
    )

    @Test fun collapsedSettingsAndPermissionControlledToggle() {
        val log = AtomicReference("")
        val count = AtomicInteger()
        compose.setContent { LedgerMaterialTheme { SettingsContent(state(), actions(log, count)) } }
        compose.onNodeWithText("Создать защищённую точку").assertDoesNotExist()
        compose.onNodeWithContentDescription("Каталог товаров").performScrollTo().performClick()
        assertEquals("catalog", log.get())
        compose.onNodeWithContentDescription("Крупный текст").assertIsOff().performClick()
        assertEquals("large:true", log.get())
        compose.onNodeWithContentDescription("Крупный текст").assertIsOff()
        compose.onNodeWithContentDescription("Ежедневные напоминания").performScrollTo().performClick()
        assertEquals("reminder:true", log.get())
        compose.onNodeWithContentDescription("Ежедневные напоминания").assertIsOff()
        compose.onNodeWithContentDescription("Резервные копии").performScrollTo().performClick()
        assertEquals("backup", log.get())
        assertEquals(0, count.get())
    }

    @Test fun protectedSettingsRequestAuthenticationAndDoNotExposeScreenshotOverride() {
        val log = AtomicReference("")
        val count = AtomicInteger()
        compose.setContent { LedgerMaterialTheme { SettingsContent(state("privacy", true), actions(log, count)) } }
        compose.onNodeWithText("Разрешить снимки экрана").assertDoesNotExist()
        compose.onNodeWithText("Отключить защиту входа").performScrollTo().performClick()
        assertEquals("auth", log.get())
        compose.onNodeWithText("Заблокировать книгу").performScrollTo().performClick()
        assertEquals("lock", log.get())
    }

    @Test fun recoveryActionsRemainExplicitAndNoPointCannotBeRestored() {
        val log = AtomicReference("")
        val count = AtomicInteger()
        compose.setContent { LedgerMaterialTheme { SettingsContent(state("backup"), actions(log, count)) } }
        compose.onNodeWithText("Восстановить защищённую точку").assertDoesNotExist()
        compose.onNodeWithText("Создать защищённую точку").performScrollTo().performClick()
        assertEquals("create", log.get())
        compose.onNodeWithContentDescription("Восстановить из JSON").performScrollTo().performClick()
        assertEquals("restore-json", log.get())
        assertEquals(0, count.get())
    }

    @Test fun existingRecoveryPointRoutesToConfirmationCallback() {
        val log = AtomicReference("")
        val count = AtomicInteger()
        compose.setContent { LedgerMaterialTheme { SettingsContent(state("backup", points = true), actions(log, count)) } }
        compose.onNodeWithText("Восстановить защищённую точку").performScrollTo().performClick()
        assertEquals(1, count.get())
    }
}
