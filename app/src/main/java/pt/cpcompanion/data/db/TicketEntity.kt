package pt.cpcompanion.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The passenger payload remains AES-GCM encrypted; Room only sees the opaque ciphertext. */
@Entity(tableName = "tickets")
data class TicketEntity(
    @PrimaryKey val id: String,
    val encryptedPayload: String,
    val updatedAtEpochMillis: Long,
)
