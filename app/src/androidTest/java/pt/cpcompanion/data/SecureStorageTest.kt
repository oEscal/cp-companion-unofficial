package pt.cpcompanion.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = SecureStorage(context)
    private val first = "test-release-storage-first"
    private val second = "test-release-storage-second"

    @After
    fun cleanup() {
        storage.removeDurable(first)
        storage.removeDurable(second)
    }

    @Test
    fun durableValueSurvivesReopeningWithoutPlaintextInPreferences() {
        storage.putDurable(first, "synthetic-ticket-reference")
        val saved = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE).getString(first, null)!!
        assertFalse(saved.contains("synthetic-ticket-reference"))
        assertEquals("synthetic-ticket-reference", SecureStorage(context).get(first))
    }

    @Test
    fun damagedEntryIsRemovedWithoutDeletingOtherEntries() {
        storage.putDurable(first, "synthetic-first")
        storage.putDurable(second, "synthetic-second")
        context.getSharedPreferences("secure_store", Context.MODE_PRIVATE).edit()
            .putString(first, "not-an-encrypted-envelope").commit()
        assertNull(storage.get(first))
        assertFalse(storage.contains(first))
        assertEquals("synthetic-second", storage.get(second))
    }

    @Test
    fun payloadsUseRandomNoncesAndRejectTampering() {
        val firstPayload = storage.encryptPayload("synthetic-payload")
        assertNotEquals(firstPayload, storage.encryptPayload("synthetic-payload"))
        val bytes = Base64.decode(firstPayload, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            storage.decryptPayload(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }
}
