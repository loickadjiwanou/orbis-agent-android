package com.orbis.agent.buffer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for the Orbis agent local message buffer.
 * Stores pending MQTT messages when the broker is offline.
 * Version 1 - initial schema.
 */
@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LocalDatabase : RoomDatabase() {

    /** Provides access to pending message operations. */
    abstract fun messageDao(): MessageDao

    companion object {
        private const val DB_NAME = "orbis_local.db"

        @Volatile
        private var INSTANCE: LocalDatabase? = null

        /**
         * Returns the singleton database instance, creating it if needed.
         */
        fun getInstance(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
