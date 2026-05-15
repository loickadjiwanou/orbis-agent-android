package com.orbis.agent.repository

import android.util.Log
import com.orbis.agent.buffer.MessageDao
import com.orbis.agent.buffer.MessageEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing offline MQTT message buffering.
 * Messages are stored locally when the broker is unreachable
 * and flushed to MQTT when connectivity is restored.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao
) {
    companion object {
        private const val TAG = "MessageRepository"
    }

    /**
     * Saves a message to the local buffer for later delivery.
     * @param topic The MQTT topic to publish to.
     * @param payload The JSON payload string.
     * @param qos The Quality of Service level (default 1).
     * @param retained Whether the message should be retained (default false).
     */
    suspend fun saveOfflineMessage(
        topic: String,
        payload: String,
        qos: Int = 1,
        retained: Boolean = false
    ) {
        val entity = MessageEntity(
            topic = topic,
            payload = payload,
            qos = qos,
            retained = retained
        )
        messageDao.insert(entity)
        Log.d(TAG, "Saved offline message for topic: $topic")
    }

    /**
     * Returns up to 50 of the oldest pending messages from the local buffer.
     */
    suspend fun getOfflineMessages(): List<MessageEntity> {
        return messageDao.getOldest50()
    }

    /**
     * Deletes a specific message from the buffer after successful delivery.
     */
    suspend fun deleteMessage(entity: MessageEntity) {
        messageDao.delete(entity)
    }

    /**
     * Returns the total count of pending messages in the local buffer.
     */
    suspend fun getPendingCount(): Int {
        return messageDao.count()
    }

    /**
     * Flushes all buffered offline messages to MQTT.
     * Processes messages in batches of 50, deleting each after successful publish.
     * Uses a function reference to avoid circular dependency with MqttManager.
     *
     * @param publishFn Suspend function that publishes a message by topic/payload/qos/retained.
     */
    suspend fun flushToMqtt(
        publishFn: suspend (topic: String, payload: String, qos: Int, retained: Boolean) -> Boolean
    ) {
        var batch = messageDao.getOldest50()
        var totalFlushed = 0

        while (batch.isNotEmpty()) {
            for (message in batch) {
                val success = try {
                    publishFn(message.topic, message.payload, message.qos, message.retained)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to flush message to MQTT: ${e.message}")
                    false
                }
                if (success) {
                    messageDao.delete(message)
                    totalFlushed++
                } else {
                    // Stop flushing if MQTT publish fails (broker may be down again)
                    Log.w(TAG, "Stopping flush after $totalFlushed messages - publish failed")
                    return
                }
            }
            batch = messageDao.getOldest50()
        }

        if (totalFlushed > 0) {
            Log.i(TAG, "Flushed $totalFlushed offline messages to MQTT")
        }
    }
}
