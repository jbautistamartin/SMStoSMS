// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.capicua.smstosms.data.local.db.dao.LogDao
import com.capicua.smstosms.data.local.db.dao.SmsDao
import com.capicua.smstosms.data.local.db.entity.LogEntity
import com.capicua.smstosms.data.local.db.entity.SmsEntity

@Database(
    entities = [SmsEntity::class, LogEntity::class],
    version = 2,
    exportSchema = true
)
abstract class SmsDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun logDao(): LogDao

    companion object {
        const val NOMBRE_BD = "smstosms.db"

        /**
         * Migración 1→2: añade la tabla log_entries.
         * Nunca usar fallbackToDestructiveMigration() — perderíamos SMS pendientes.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `log_entries` (
                        `id`         INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tipo`       TEXT    NOT NULL,
                        `sms_id`     TEXT,
                        `detalle`    TEXT    NOT NULL,
                        `codigo_http` INTEGER,
                        `timestamp`  INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_log_entries_sms_id` ON `log_entries` (`sms_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_log_entries_timestamp` ON `log_entries` (`timestamp`)")
            }
        }
    }
}