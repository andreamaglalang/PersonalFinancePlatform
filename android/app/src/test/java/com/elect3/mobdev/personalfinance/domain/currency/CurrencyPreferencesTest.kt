package com.elect3.mobdev.personalfinance.domain.currency
import org.junit.Assert.*
import org.junit.Test
import com.elect3.mobdev.personalfinance.domain.model.*
import java.math.BigDecimal
import java.time.*
import java.util.UUID
class CurrencyPreferencesTest {
    private class Memory: CurrencyPreferenceStorage {
        val data = mutableMapOf<String,String>(); var fail = false
        override fun read(key: String) = data[key]
        override fun write(key: String,value: String): Boolean { if(fail) return false; data[key]=value; return true }
    }
    @Test fun defaultsAndPersistence() {
        val disk=Memory(); val first=AccountCurrencyPreferences(disk,"https://api.example.test/",1)
        assertEquals(CurrencyPreferences(),first.preferences.value)
        val changed=CurrencyPreferences("USD",false,true)
        assertTrue(first.set(changed))
        assertEquals(changed,AccountCurrencyPreferences(disk,"https://api.example.test/",1).preferences.value)
    }
    @Test fun userAndOriginIsolation() {
        val disk=Memory(); AccountCurrencyPreferences(disk,"https://api.example.test/",1).set(CurrencyPreferences("JPY"))
        assertEquals("PHP",AccountCurrencyPreferences(disk,"https://api.example.test/",2).preferences.value.defaultCurrency)
        assertEquals("PHP",AccountCurrencyPreferences(disk,"https://staging.example.test/",1).preferences.value.defaultCurrency)
    }
    @Test fun failedSaveAndUnsupportedCurrencyNeverReportSuccess() {
        val disk=Memory(); val prefs=AccountCurrencyPreferences(disk,"https://api.example.test/",1)
        assertFalse(prefs.set(CurrencyPreferences("XYZ"))); assertTrue(disk.data.isEmpty())
        disk.fail=true; assertFalse(prefs.set(CurrencyPreferences("EUR"))); assertEquals("PHP",prefs.preferences.value.defaultCurrency)
    }
    @Test fun corruptRecordFallsBackSafely() {
        val disk=Memory();val prefs=AccountCurrencyPreferences(disk,"https://api.example.test/",1);prefs.set(CurrencyPreferences("USD"))
        disk.data.keys.toList().forEach{disk.data[it]="1|XYZ|garbage|garbage"}
        assertEquals(CurrencyPreferences(),AccountCurrencyPreferences(disk,"https://api.example.test/",1).preferences.value)
    }
    @Test fun historicalDisplayUsesStoredRoundedSnapshotWithoutRepricing() {
        val date=LocalDate.parse("2020-01-02")
        val row=Transaction(1,UUID.randomUUID(),TransactionType.Expense,1,"Food",BigDecimal("10.25"),"USD",BigDecimal("56.12345679"),date,BigDecimal("575.27"),date,null,Instant.EPOCH,null,null)
        val original=historicalAmountDisplay(row,true)
        assertEquals(BigDecimal("10.25"),original.primary);assertEquals(BigDecimal("575.27"),original.secondary)
        val base=historicalAmountDisplay(row,false)
        assertEquals(BigDecimal("575.27"),base.primary);assertEquals("PHP",base.primaryCurrency);assertNull(base.secondary)
    }
}
