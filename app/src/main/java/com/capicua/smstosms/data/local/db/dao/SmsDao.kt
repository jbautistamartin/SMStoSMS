// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.capicua.smstosms.data.local.db.entity.SmsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object para la tabla [sms].
 *
 * Convenciones:
 * - Las funciones suspendidas se ejecutan en el dispatcher de Room (IO).
 * - Las funciones que devuelven [Flow] emiten automáticamente cuando la tabla cambia.
 * - Nunca se sobreescribe un SMS existente ([OnConflictStrategy.ABORT]); el id es inmutable.
 * - Los estados se reciben como texto (el `name` de `EstadoSms`) desde el repositorio.
 */
@Dao
interface SmsDao {

    // ── Escritura ─────────────────────────────────────────────────────────────

    /**
     * Inserta un SMS nuevo.
     * Falla con excepción si ya existe un registro con el mismo [id], lo que previene
     * duplicados si el broadcast del sistema llegara dos veces.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(sms: SmsEntity)

    /** Cambia el estado de tramitación y limpia el motivo de descarte. */
    @Query("UPDATE sms SET estado = :estado, motivo_descarte = NULL WHERE id = :id")
    suspend fun actualizarEstado(id: String, estado: String)

    /** Marca el SMS como descartado anotando por qué. */
    @Query("UPDATE sms SET estado = :estadoDescartado, motivo_descarte = :motivo WHERE id = :id")
    suspend fun marcarDescartado(id: String, motivo: String, estadoDescartado: String)

    /**
     * Elimina SMS ya tramitados recibidos antes de [antesDeMs].
     * Las claves ajenas en CASCADE se llevan sus reenvíos por delante.
     * Los SMS en estado PENDIENTE no se tocan: aún tienen trabajo que hacer.
     */
    @Query("DELETE FROM sms WHERE estado != :estadoPendiente AND fecha_recepcion < :antesDeMs")
    suspend fun eliminarTramitadosAnterioresA(antesDeMs: Long, estadoPendiente: String)

    /** Elimina todos los registros de la tabla, y con ellos sus reenvíos. */
    @Query("DELETE FROM sms")
    suspend fun eliminarTodos()

    // ── Lectura reactiva (Flow) ───────────────────────────────────────────────

    /** Lista completa, más recientes primero. */
    @Query("SELECT * FROM sms ORDER BY fecha_recepcion DESC")
    fun observarTodos(): Flow<List<SmsEntity>>

    /** SMS recibidos pero aún sin evaluar. Para el indicador de la pantalla de inicio. */
    @Query("SELECT COUNT(*) FROM sms WHERE estado = :estadoPendiente")
    fun observarContadorPendientes(estadoPendiente: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms")
    fun observarContadorTotal(): Flow<Int>

    // ── Lectura puntual (suspend) ─────────────────────────────────────────────

    @Query("SELECT * FROM sms WHERE id = :id")
    suspend fun obtenerPorId(id: String): SmsEntity?

    /**
     * SMS pendientes de evaluar, en orden de llegada (FIFO).
     * Lo consume el rescate de huérfanos: son mensajes que se persistieron pero cuya
     * evaluación no llegó a completarse.
     */
    @Query("SELECT * FROM sms WHERE estado = :estadoPendiente ORDER BY fecha_recepcion ASC")
    suspend fun obtenerPendientes(estadoPendiente: String): List<SmsEntity>

    /** Los [limite] SMS más recientes. */
    @Query("SELECT * FROM sms ORDER BY fecha_recepcion DESC LIMIT :limite")
    suspend fun obtenerUltimos(limite: Int): List<SmsEntity>
}
