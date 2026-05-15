package com.orbis.agent.buffer

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [MessageEntity].
 * Provides CRUD operations on the pending_messages table.
 */
@Dao
interface MessageDao {

    /**
     * Insert a new pending message into the local buffer.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    /**
     * Returns the 50 oldest pending messages ordered by creation time.
     * Used for batched flush operations.
     */
    @Query("SELECT * FROM pending_messages ORDER BY created_at ASC LIMIT 50")
    suspend fun getOldest50(): List<MessageEntity>

    /**
     * Delete a specific message after it has been successfully published.
     */
    @Delete
    suspend fun delete(message: MessageEntity)

    /**
     * Returns the total count of pending messages in the buffer.
     */
    @Query("SELECT COUNT(*) FROM pending_messages")
    suspend fun count(): Int

    /**
     * Delete all pending messages (used during factory reset).
     */
    @Query("DELETE FROM pending_messages")
    suspend fun deleteAll()
}
