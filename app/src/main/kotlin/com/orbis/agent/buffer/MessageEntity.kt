package com.orbis.agent.buffer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a pending MQTT message stored locally
 * when the broker is unreachable. Messages are flushed in order
 * when connectivity is restored.
 */
@Entity(tableName = "pending_messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "topic")
    val topic: String,

    @ColumnInfo(name = "payload")
    val payload: String,

    @ColumnInfo(name = "qos")
    val qos: Int = 1,

    @ColumnInfo(name = "retained")
    val retained: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
