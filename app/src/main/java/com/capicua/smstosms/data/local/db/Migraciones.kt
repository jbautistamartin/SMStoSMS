// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones del esquema.
 *
 * Se escriben a mano y se registran en `DatabaseModule`. Nunca se recurre a
 * `fallbackToDestructiveMigration()`: borraría los SMS y los reenvíos pendientes, que es
 * justamente lo que el patrón outbox existe para no perder.
 */
object Migraciones {

    /**
     * v1 → v2: la regla gana `ignorar_mayusculas`.
     *
     * Se añade con `DEFAULT 0`, de modo que las reglas que ya existen conservan el
     * comportamiento que tenían —distinguir mayúsculas— y nadie se encuentra con que sus
     * reglas empiezan a casar con más mensajes después de actualizar.
     *
     * `NOT NULL` con valor por defecto es lo que SQLite admite en un `ADD COLUMN`, y coincide
     * con el `defaultValue = "0"` declarado en `ReglaEntity`: si no coincidieran, Room abortaría
     * al validar el esquema tras migrar.
     */
    val DE_1_A_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE reglas ADD COLUMN ignorar_mayusculas INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /** Todas las migraciones conocidas, en orden. */
    val TODAS = arrayOf(DE_1_A_2)
}
