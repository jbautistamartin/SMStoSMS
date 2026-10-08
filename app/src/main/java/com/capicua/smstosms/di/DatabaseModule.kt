// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.di

import android.content.Context
import androidx.room.Room
import com.capicua.smstosms.data.local.db.SmsDatabase
import com.capicua.smstosms.data.local.db.dao.LogDao
import com.capicua.smstosms.data.local.db.dao.ReenvioDao
import com.capicua.smstosms.data.local.db.dao.ReglaDao
import com.capicua.smstosms.data.local.db.dao.SmsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SmsDatabase =
        Room.databaseBuilder(
            context,
            SmsDatabase::class.java,
            SmsDatabase.NOMBRE_BD
        )
            // El esquema arranca en v1 sin migraciones previas: ver KDoc de SmsDatabase.
            .enableMultiInstanceInvalidation()
            // WAL mode: mayor rendimiento de escritura concurrente y lecturas no bloqueantes
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun provideSmsDao(db: SmsDatabase): SmsDao = db.smsDao()

    @Provides
    fun provideReglaDao(db: SmsDatabase): ReglaDao = db.reglaDao()

    @Provides
    fun provideReenvioDao(db: SmsDatabase): ReenvioDao = db.reenvioDao()

    @Provides
    fun provideLogDao(db: SmsDatabase): LogDao = db.logDao()
}
