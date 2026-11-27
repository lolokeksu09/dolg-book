package dev.ventry.raschet

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ventry.raschet.license.*
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LicensedStagingTest {
    @get:Rule val compose=createAndroidComposeRule<LicenseActivity>()

    @Test fun signedTrialAndLedgerSafety() {
        assumeTrue(BuildConfig.LICENSE_REQUIRED)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val code=checkNotNull(InstrumentationRegistry.getArguments().getString("activation"))
        val vault=LicenseVault(context)
        val database="license-acceptance-isolated.db"
        context.deleteDatabase(database)
        vault.delete()
        val book=LedgerStore(context,database)
        fun denied(block:()->Unit) {
            try {block();fail("Unlicensed debt accepted")}catch(e:IllegalArgumentException){
                assertTrue(e.message.orEmpty().contains("подписка"))
            }
        }
        try {
            book.addClient("Тестовый клиент","")
            val id=book.clients().single().id
            denied{book.addEntry(id,"debt",10000,"","")}
            assertTrue(book.entriesForClient(id).isEmpty())
            compose.waitUntil(30_000){
                compose.onAllNodesWithTag("license_code").fetchSemanticsNodes().any{
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithTag("license_code").performTextInput(code)
            compose.onNodeWithTag("license_activate").performScrollTo().performClick()
            compose.onNodeWithTag("license_confirm").performClick()
            compose.waitUntil(30_000){compose.onAllNodesWithText("Ответ сервера проверен.").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Доступ активен").assertExists()
            assertNotNull(LicenseManager.overview(context).subscription)
            compose.onNodeWithTag("share_subscription").assertExists()
            val active=checkNotNull(vault.read())
            val verifier=EntitlementVerifier(BuildConfig.LICENSE_PUBLIC_KEY.unhex(32))
            val c=verifier.verify(checkNotNull(active.envelope),active.installation)
            assertTrue(c.allowed)
            assertTrue(c.validUntil!!-c.issued in 604770..604800)
            assertTrue(c.leaseUntil-c.issued<=MAX_LEASE)
            assertNull(active.pendingCode)
            assertNull(active.pendingRefresh)
            assertTrue(LicenseManager.permits(context))
            book.addEntry(id,"debt",10000,"","")
            assertEquals(10000L,LedgerStore.balance(book.entriesForClient(id),id))
            val backup=book.exportJson()
            // A typo or an invalid replacement must not destroy valid access.
            compose.onNodeWithTag("license_code").performTextInput("0".repeat(64))
            compose.onNodeWithTag("license_activate").performScrollTo().performClick()
            compose.onNodeWithTag("license_confirm").performClick()
            compose.waitUntil(30_000){compose.onAllNodesWithText("Код или привязка недоступны. Обратитесь в Ventry.").fetchSemanticsNodes().isNotEmpty()}
            val afterWrong=checkNotNull(vault.read())
            assertEquals(active.refresh,afterWrong.refresh)
            assertEquals(active.envelope,afterWrong.envelope)
            assertNull(afterWrong.pendingCode)
            assertTrue(LicenseManager.permits(context))
            assertEquals(backup,book.exportJson())
            // Persist only the pending credential as if the process died after
            // the first activation committed but before saving the response.
            // The unusable code ensures recovery must use the refresh endpoint.
            vault.write(active.copy(pendingCode="1".repeat(64),pendingRefresh=active.refresh,envelope=null,anchor=null))
            assertFalse(LicenseManager.permits(context))
            runBlocking{LicenseManager.activate(context,null)}
            val recovered=checkNotNull(vault.read())
            assertEquals(active.refresh,recovered.refresh)
            assertNull(recovered.pendingCode)
            assertNull(recovered.pendingRefresh)
            assertEquals(c.subscription,verifier.verify(checkNotNull(recovered.envelope),recovered.installation).subscription)
            assertEquals(c.validUntil,verifier.verify(checkNotNull(recovered.envelope),recovered.installation).validUntil)
            assertTrue(LicenseManager.permits(context))
            assertEquals(backup,book.exportJson())
            // Simulate offline lease expiry using only the isolated test vault.
            val a=checkNotNull(active.anchor)
            vault.write(active.copy(anchor=a.copy(elapsedMs=a.elapsedMs-73*3600*1000L,wallSec=a.wallSec-73*3600L)))
            assertFalse(LicenseManager.permits(context))
            assertEquals("Требуется онлайн-проверка",LicenseManager.overview(context).title)
            denied{book.addEntry(id,"debt",100,"","")}
            assertEquals(backup,book.exportJson())
            book.addEntry(id,"payment",3000,"","")
            assertEquals(7000L,LedgerStore.balance(book.entriesForClient(id),id))
            book.importJson(backup)
            assertEquals(10000L,LedgerStore.balance(book.entriesForClient(id),id))
            val broken=JSONObject(active.envelope!!).apply{val sig=getString("signature_hex");put("signature_hex",(if(sig[0]=='0')"1" else "0")+sig.drop(1))}
            vault.write(active.copy(envelope=broken.toString()))
            assertFalse(LicenseManager.permits(context))
            denied{book.addEntry(id,"debt",100,"","")}
            runBlocking{LicenseManager.refresh(context)}
            assertTrue(LicenseManager.permits(context))
            assertEquals(backup,book.exportJson())
            vault.write(active.copy(anchor=a.copy(boot=a.boot+1)))
            assertFalse(LicenseManager.permits(context))
            vault.write(active)
            assertTrue(LicenseManager.permits(context))
            compose.onNodeWithTag("license_refresh").performScrollTo().performClick()
            compose.waitUntil(30_000){compose.onAllNodesWithText("Ответ сервера проверен.").fetchSemanticsNodes().isNotEmpty()}
            compose.waitForIdle()
            assertTrue(LicenseManager.permits(context))
            assertEquals(backup,book.exportJson())
        } finally {
            book.close();context.deleteDatabase(database);vault.delete()
        }
    }
}
