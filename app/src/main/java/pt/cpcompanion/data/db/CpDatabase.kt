package pt.cpcompanion.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TicketEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CpDatabase : RoomDatabase() {
    abstract fun ticketDao(): TicketDao

    companion object {
        @Volatile private var instance: CpDatabase? = null

        fun get(context: Context): CpDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CpDatabase::class.java,
                "cp-companion.db",
            ).build().also { instance = it }
        }
    }
}
