package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.data.local.room.MIGRATION_1_2
import com.elect3.mobdev.personalfinance.data.local.room.MIGRATION_1_2_SQL
import com.elect3.mobdev.personalfinance.data.local.room.MIGRATION_2_3
import com.elect3.mobdev.personalfinance.data.local.room.MIGRATION_2_3_SQL
import com.google.gson.JsonParser
import java.io.File
import org.junit.Test
import org.junit.Assert.*

/** Definition only; real Room preservation is tested by instrumentation, not claimed here. */
class OwliMigrationDefinitionTest {
    @Test fun explicitOneToTwoMigrationOnlyAddsCategoryTableAndIndex(){
        assertEquals(1,MIGRATION_1_2.startVersion);assertEquals(2,MIGRATION_1_2.endVersion)
        assertEquals(2,MIGRATION_1_2_SQL.size)
        val sql=MIGRATION_1_2_SQL.joinToString(" ")
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"));assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS"))
        assertFalse(sql.contains("local_transactions",ignoreCase=true))
        assertFalse(Regex("\\b(DROP|DELETE|ALTER|UPDATE)\\b",RegexOption.IGNORE_CASE).containsMatchIn(sql))
    }
    @Test fun explicitTwoToThreeMigrationOnlyAddsPendingRequestsMatchingExportedSchema(){
        assertEquals(2,MIGRATION_2_3.startVersion);assertEquals(3,MIGRATION_2_3.endVersion)
        val sql=MIGRATION_2_3_SQL.single()
        assertFalse(Regex("\\b(DROP|DELETE|ALTER|UPDATE|INSERT)\\b",RegexOption.IGNORE_CASE).containsMatchIn(sql))
        assertFalse(sql.contains("local_transactions")||sql.contains("local_categories"))
        // The migration must create exactly what Room generated for schema v3 (Room validates this on open).
        val schema=JsonParser.parseString(File("schemas/com.elect3.mobdev.personalfinance.data.local.room.OwliDatabase/3.json").readText()).asJsonObject
        val entity=schema.getAsJsonObject("database").getAsJsonArray("entities").map{it.asJsonObject}.single{it.get("tableName").asString=="pending_requests"}
        assertEquals(entity.get("createSql").asString.replace("`\${TABLE_NAME}`","`pending_requests`"),sql)
        assertNull(entity.get("indices"))
        // Existing tables are unchanged between v2 and v3.
        val v2=JsonParser.parseString(File("schemas/com.elect3.mobdev.personalfinance.data.local.room.OwliDatabase/2.json").readText()).asJsonObject
        fun tables(o:com.google.gson.JsonObject)=o.getAsJsonObject("database").getAsJsonArray("entities").map{it.asJsonObject}.filter{it.get("tableName").asString!="pending_requests"}
        assertEquals(tables(v2),tables(schema))
    }
}
