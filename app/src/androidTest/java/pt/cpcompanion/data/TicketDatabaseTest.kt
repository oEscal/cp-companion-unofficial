package pt.cpcompanion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.cpcompanion.data.db.CpDatabase
import pt.cpcompanion.data.db.TicketEntity

@RunWith(AndroidJUnit4::class)
class TicketDatabaseTest {
    private lateinit var database: CpDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun rowsAreUpdatedAndDeletedIndependently() = runBlocking {
        val dao = database.ticketDao()
        dao.upsert(TicketEntity("first", "cipher-a", 1L))
        dao.upsert(TicketEntity("second", "cipher-b", 2L))
        dao.upsert(TicketEntity("first", "cipher-c", 3L))

        val rows = dao.getAll().associateBy { it.id }
        assertEquals(2, rows.size)
        assertEquals("cipher-c", rows.getValue("first").encryptedPayload)

        dao.delete("first")
        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().id == "second")
    }
}
