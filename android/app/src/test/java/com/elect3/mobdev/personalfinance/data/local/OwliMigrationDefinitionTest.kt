package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.data.local.room.MIGRATION_1_2
import com.elect3.mobdev.personalfinance.data.local.room.MIGRATION_1_2_SQL
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
}
