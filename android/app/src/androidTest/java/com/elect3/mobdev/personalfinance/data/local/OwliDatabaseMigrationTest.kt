package com.elect3.mobdev.personalfinance.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.data.local.room.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Only a unique synthetic test database; never opens or clears production owli-local.db. */
@RunWith(AndroidJUnit4::class)
class OwliDatabaseMigrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="owli-migration-test-"+UUID.randomUUID().toString()+".db"
    private var migrated:OwliDatabase?=null
    @After fun close(){
        migrated?.close()
        check(name.startsWith("owli-migration-test-"))
        context.deleteDatabase(name)
    }
    private fun snapshot(cursor:Cursor):List<List<String?>> = cursor.use{
        val rows=mutableListOf<List<String?>>()
        while(it.moveToNext()) rows+= (0 until it.columnCount).map{n->if(it.isNull(n))null else it.getString(n)}
        rows
    }
    @Test fun roomOneToThreePreservesEveryTransactionFieldAndValidatesNewSchema()=runBlocking{
        val legacy=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        listOf(
            "CREATE TABLE IF NOT EXISTS `local_transactions` (`accountId` INTEGER NOT NULL, `clientId` TEXT NOT NULL, `transactionId` INTEGER, `type` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `categoryName` TEXT, `amount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `amountBase` TEXT, `transactionDate` TEXT NOT NULL, `description` TEXT, `syncState` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `clientId`))",
            "CREATE INDEX IF NOT EXISTS `index_local_transactions_accountId_syncState` ON `local_transactions` (`accountId`, `syncState`)",
            "CREATE INDEX IF NOT EXISTS `index_local_transactions_accountId_transactionDate` ON `local_transactions` (`accountId`, `transactionDate`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '1e991d167b2339fb2f7503a05dd3b023')"
        ).forEach{legacy.execSQL(it)}
        val states=listOf("Pending","Rejected","Synced")
        states.forEachIndexed{index,state->
            val row=ContentValues().apply{
                put("accountId",if(index==2)2 else 1);put("clientId","00000000-0000-0000-0000-00000000000"+(index+1))
                if(state=="Synced")put("transactionId",501) else putNull("transactionId")
                put("type","Expense");put("categoryId",7);put("categoryName","Synthetic")
                put("amount","12.34");put("currencyCode","PHP")
                if(state=="Synced")put("amountBase","12.34") else putNull("amountBase")
                put("transactionDate","2026-11-19");put("description","Synthetic migration fixture")
                put("syncState",state);put("updatedAtEpochMs",index.toLong())
            }
            legacy.insertOrThrow("local_transactions",null,row)
        }
        legacy.version=1
        val before=snapshot(legacy.rawQuery("SELECT * FROM local_transactions ORDER BY accountId,clientId",null))
        legacy.close()
        val db=Room.databaseBuilder(context,OwliDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
        migrated=db
        val after=snapshot(db.openHelper.writableDatabase.query("SELECT * FROM local_transactions ORDER BY accountId,clientId"))
        assertEquals(before,after)
        assertEquals(listOf("Pending","Rejected"),db.transactions().history(1,100).map{it.syncState}.sorted())
        assertEquals("Synced",db.transactions().history(2,100).single().syncState)
        assertTrue(db.categories().read(1,null,true).isEmpty())
        // 2 -> 3 adds an empty pending_requests table that Room validated against schema v3 on open.
        assertTrue(db.pendingRequests().paths("ns#1").isEmpty())
        db.pendingRequests().upsert(PendingRequestEntity("ns#1","recurring-bills/7/payments","{\"clientId\":\"c\"}",1))
        assertEquals(listOf("recurring-bills/7/payments"),db.pendingRequests().paths("ns#1"))
        assertTrue(db.pendingRequests().paths("ns#2").isEmpty())
    }
}
