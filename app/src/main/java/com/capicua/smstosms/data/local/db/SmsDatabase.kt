// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.capicua.smstosms.data.local.db.dao.LogDao
import com.capicua.smstosms.data.local.db.dao.ReenvioDao
import com.capicua.smstosms.data.local.db.dao.ReglaDao
import com.capicua.smstosms.data.local.db.dao.SmsDao
import com.capicua.smstosms.data.local.db.entity.LogEntity
import com.capicua.smstosms.data.local.db.entity.ReenvioEntity
import com.capicua.smstosms.data.local.db.entity.ReglaEntity
import com.capicua.smstosms.data.local.db.entity.SmsEntity

/**
 * Base de datos local de SMStoSMS.
 *
 * ## Por qué el esquema arranca otra vez en la versión 1
 * SMSGateway llegó a la versión 2 con una migración 1→2. Al cambiar el `applicationId` a
 * `com.capicua.smstosms`, Android trata esta aplicación como una app distinta con su propio
 * directorio de datos, y además el fichero pasó a llamarse [NOMBRE_BD]. Ningún dispositivo
 * tiene esta base de datos, así que no hay nada que migrar y la historia de migraciones
 * anterior no aporta nada: se colapsa en un esquema v1 limpio con las cuatro tablas.
 *
 * ## Tablas
 * | Tabla         | Papel                                                              |
 * |---------------|--------------------------------------------------------------------|
 * | `sms`         | Outbox de mensajes entrantes: se persiste antes de intentar nada.  |
 * | `reglas`      | Reglas de reenvío, ordenadas por prioridad.                        |
 * | `reenvios`    | Unidad de despacho: un envío a un destino, con su propio estado.   |
 * | `log_entries` | Registro de auditoría de todos los eventos del sistema.            |
 *
 * ## Migraciones
 * Nunca usar `fallbackToDestructiveMigration()`: perderíamos SMS y reenvíos pendientes.
 *
 * El esquema dejó de ser editable en sitio en cuanto la aplicación se instaló en un teléfono
 * con reglas dentro: cambiar v1 sin migrar hace que Room aborte al abrir la base de datos.
 * Desde entonces, cualquier cambio sube la versión y escribe su `Migration` en [Migraciones].
 *
 * | Versión | Cambio                                        |
 * |---------|-----------------------------------------------|
 * | 1       | Esquema inicial con las cuatro tablas.        |
 * | 2       | `reglas.ignorar_mayusculas`.                  |
 */
@Database(
    entities = [
        SmsEntity::class,
        ReglaEntity::class,
        ReenvioEntity::class,
        LogEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class SmsDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun reglaDao(): ReglaDao
    abstract fun reenvioDao(): ReenvioDao
    abstract fun logDao(): LogDao

    companion object {
        const val NOMBRE_BD = "smstosms.db"
    }
}
