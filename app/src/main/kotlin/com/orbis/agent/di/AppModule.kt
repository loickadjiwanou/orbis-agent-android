package com.orbis.agent.di

import android.content.Context
import com.orbis.agent.buffer.LocalDatabase
import com.orbis.agent.buffer.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module providing application-scoped dependencies.
 * Provides Room database and DAO instances.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the singleton Room database instance.
     */
    @Provides
    @Singleton
    fun provideLocalDatabase(@ApplicationContext context: Context): LocalDatabase {
        return LocalDatabase.getInstance(context)
    }

    /**
     * Provides the MessageDao from the Room database.
     */
    @Provides
    @Singleton
    fun provideMessageDao(database: LocalDatabase): MessageDao {
        return database.messageDao()
    }
}
