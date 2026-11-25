package com.elect3.mobdev.personalfinance.data.auth

import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.data.remote.*
import java.time.Instant
import java.util.UUID
import java.security.KeyStore
import org.junit.Assert.*
import org.junit.Test

/** Separate fixture namespace/key/file. Never accesses the production session or contacts an auth API. */
class ProtectedSessionStoreDeviceTest {
    private fun payload() = AuthPayload("fixture-access-never-valid", "f".repeat(86), Instant.now().plusSeconds(900), Instant.now().plusSeconds(3600), AuthUser(999,"Fixture","fixture@example.test","PHP",listOf("User")))
    private fun testStore(test: (ProtectedSessionStore, () -> ProtectedSessionStore, java.io.File) -> Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val purpose="fixture-"+UUID.randomUUID();val namespace="https://fixture.invalid/"
        val before=context.noBackupFilesDir.listFiles().orEmpty().map{it.name}.toSet()
        val store=ProtectedSessionStore(context,namespace,purpose)
        try {
            store.write(StoredSession(payload()))
            val file=context.noBackupFilesDir.listFiles().orEmpty().single{it.name !in before && it.name.startsWith("owli-session-")}
            test(store,{ProtectedSessionStore(context,namespace,purpose)},file)
        } finally { store.clear() }
    }
    @Test fun encryptedRoundTripSurvivesOwnerRecreationAndKeyIsNotExportable() = testStore { _, reopen, file ->
        val restored=reopen().read()!!
        assertTrue(restored.payload.accessToken=="fixture-access-never-valid")
        assertTrue(restored.payload.refreshToken=="f".repeat(86))
        val suffix=file.name.removePrefix("owli-session-").removeSuffix(".bin")
        val key=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey("owli.session.v1.$suffix",null)
        assertNotNull(key);assertNull(key.encoded)
        val contents=file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(contents.contains("fixture-access-never-valid"));assertFalse(contents.contains("f".repeat(86)))
    }
    @Test fun newWritesUseFreshCiphertextAndPreservePendingJournal() = testStore { store,reopen,file ->
        val first=file.readBytes();store.write(StoredSession(payload(),true))
        assertFalse(first.contentEquals(file.readBytes()));assertTrue(reopen().read()!!.renewalUncertain)
    }
    @Test fun tamperedCiphertextFailsClosed() = testStore { _,reopen,file ->
        val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertTrue(runCatching{reopen().read()}.isFailure)
    }
    @Test fun clearErasesRestorationWithoutTouchingOtherAppFiles() = testStore { store,reopen,file ->
        store.clear();assertFalse(file.exists());assertNull(reopen().read())
    }
}
