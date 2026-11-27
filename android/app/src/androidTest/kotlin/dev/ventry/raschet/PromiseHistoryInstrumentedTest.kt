package dev.ventry.raschet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ventry.raschet.data.ClientCommand
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PromiseHistoryInstrumentedTest {
    @Test fun replacementUsesDisjointPaymentsAndV5RestoreIsAtomic() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="promise-history-qa-"+UUID.randomUUID()+".db"
        val today=LocalDate.now()
        val zone=ZoneId.systemDefault()
        try {
            LedgerStore(ctx,name).use { book ->
                book.addClient("Тестовый клиент","")
                val id=book.clients().single().id
                book.addEntry(id,"debt",20000,"","")
                val v2=book.exportJson()
                assertEquals(2,JSONObject(v2).getInt("version"))
                book.saveFollowUp(ClientCommand.FollowUp(id,null,today,"Первый",5000,false))
                val first=book.currentPlan(id)!!
                book.addEntry(id,"payment",3000,"","")
                book.saveFollowUp(ClientCommand.FollowUp(id,first.id,today.plusDays(1),"Срок изменён",5000,false))
                val revised=book.currentPlan(id)!!
                assertEquals(first.id,revised.id)
                assertEquals(today,revised.originalDue)
                assertEquals(today.plusDays(1),revised.due)
                book.saveFollowUp(ClientCommand.FollowUp(id,first.id,today,"Второй",4000,true))
                val second=book.currentPlan(id)!!
                assertNotEquals(first.id,second.id)
                book.addEntry(id,"payment",4000,"","")
                val snap=book.readSnapshot()
                val rows=PromiseHistory.project(snap,today,zone).associateBy { it.record.id }
                assertEquals(3000L,rows.getValue(first.id).received)
                assertEquals(4000L,rows.getValue(second.id).received)
                assertEquals(PromiseHistory.Status.CANCELED,rows.getValue(first.id).status)
                assertEquals(PromiseHistory.Status.TIMELY,rows.getValue(second.id).status)
                val all=PromiseHistory.select(rows.values.toList(),today,null,"")
                assertEquals(9000L,all.promised)
                assertEquals(7000L,all.received)
                assertEquals(1,PromiseHistory.select(rows.values.toList(),today,7,"").rows.size)
                assertEquals(13000L,LedgerStore.balance(snap.entries,id))
                val before=book.exportJson()
                assertEquals(5,JSONObject(before).getInt("version"))
                try {
                    book.saveFollowUp(ClientCommand.FollowUp(id,first.id,today,"Старый экран",1,true))
                    fail("Stale form overwrote current plan")
                } catch (_: IllegalArgumentException) {}
                assertEquals(before,book.exportJson())
                book.reverseLast(id,"Ошибочная оплата")
                val reversed=PromiseHistory.project(book.readSnapshot(),today,zone).associateBy { it.record.id }
                assertEquals(3000L,reversed.getValue(first.id).received)
                assertEquals(0L,reversed.getValue(second.id).received)
                assertEquals(PromiseHistory.Status.WAITING,reversed.getValue(second.id).status)
                book.importJson(before)
                assertEquals(before,book.exportJson())
                // Re-import must reject overlapping promise intervals and leave the old book intact.
                val broken=JSONObject(before)
                val plans=broken.getJSONArray("plans")
                plans.getJSONObject(1).put("anchor",plans.getJSONObject(0).getString("anchor"))
                try { book.importJson(broken.toString());fail("Overlap accepted") } catch (_: IllegalArgumentException) {}
                assertEquals(before,book.exportJson())
                book.saveFollowUp(ClientCommand.FollowUp(id,second.id,null,"",0,true))
                assertNull(book.currentPlan(id))
                assertEquals(2,book.followUpHistory().size)
                assertEquals(4000L,PromiseHistory.project(book.readSnapshot(),today,zone).first { it.record.id==second.id }.received)
                // Old formats remain restorable; old backups never invent agreement history.
                book.importJson(v2)
                assertTrue(book.followUpHistory().isEmpty())
                assertEquals(v2,book.exportJson())
            }
        } finally { ctx.deleteDatabase(name) }
    }

    @Test fun v3MigrationKeepsExistingDebtAndCurrentLegacyAgreementExactlyOnce() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="promise-migration-qa-"+UUID.randomUUID()+".db"
        val prefs="promise-migration-qa-"+UUID.randomUUID()
        val directory=ClientDirectory(ctx,prefs)
        val today=LocalDate.now()
        try {
            val id:String
            val other:String
            LedgerStore(ctx,name).use { old ->
                old.addClient("A","");old.addClient("B","")
                id=old.clients().first { it.name=="A" }.id
                other=old.clients().first { it.name=="B" }.id
                old.addEntry(id,"debt",10000,"","")
                old.addEntry(other,"debt",4000,"","")
                directory.setFollowUp(id,today,"Старый план",5000,old.entriesForClient(id).last().id)
                directory.setFollowUp(other,today,"Утерянная ссылка",1000,"missing")
                // Reconstruct the exact schema boundary of the previous SQLite version.
                old.writableDatabase.execSQL("DROP TABLE collection_plans")
                old.writableDatabase.version=3
            }
            var restored:String?=null
            repeat(2) {
                LedgerStore(ctx,name,directory).use { migrated ->
                    assertEquals(4,migrated.readableDatabase.version)
                    assertEquals(10000L,LedgerStore.balance(migrated.entriesForClient(id),id))
                    assertEquals(2,migrated.followUpHistory().size)
                    assertEquals("Старый план",migrated.currentPlan(id)!!.note)
                    assertNull(migrated.currentPlan(id)!!.opened)
                    val review=PromiseHistory.project(migrated.readSnapshot(),today,ZoneId.systemDefault())
                        .first { row -> row.record.clientId==other }
                    assertEquals(PromiseHistory.Status.REVIEW,review.status)
                    val json=migrated.exportJson()
                    if(restored!=null)assertEquals(restored,json)
                    restored=json
                    migrated.importJson(json)
                    assertEquals(json,migrated.exportJson())
                }
            }
        } finally { ctx.deleteDatabase(name);ctx.deleteSharedPreferences(prefs) }
    }
}
