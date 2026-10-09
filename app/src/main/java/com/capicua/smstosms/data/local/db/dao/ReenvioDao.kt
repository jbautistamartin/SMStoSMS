// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.capicua.smstosms.data.local.db.entity.ReenvioEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object para la tabla [reenvios].
 *
 * El estado se almacena como texto (el `name` de `EstadoReenvio`); las consultas que filtran
 * por estado reciben ese nombre desde el repositorio, nunca un entero.
 */
@Dao
interface ReenvioDao {

    // ── Escritura ─────────────────────────────────────────────────────────────

    /** Falla si ya existe un reenvío con el mismo id, lo que previene duplicados. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(reenvio: ReenvioEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertarVarios(reenvios: List<ReenvioEntity>)

    /** Marca el reenvío como enviado y limpia el último error. */
    @Query("""
        UPDATE reenvios
        SET estado       = :estadoEnviado,
            fecha_envio  = :fechaEnvioMs,
            ultimo_error = NULL
        WHERE id = :id
    """)
    suspend fun marcarEnviado(id: String, fechaEnvioMs: Long, estadoEnviado: String)

    /** Incrementa el contador de intentos y guarda el error, sin cambiar el estado. */
    @Query("""
        UPDATE reenvios
        SET intentos     = intentos + 1,
            ultimo_error = :error
        WHERE id = :id
    """)
    suspend fun registrarIntento(id: String, error: String)

    /** Cierra el reenvío como fallo permanente. */
    @Query("UPDATE reenvios SET estado = :estadoFallido, ultimo_error = :error WHERE id = :id")
    suspend fun marcarFallido(id: String, error: String, estadoFallido: String)

    @Query("DELETE FROM reenvios WHERE estado = :estadoEnviado AND fecha_envio < :antesDeMs")
    suspend fun eliminarEnviadosAnterioresA(antesDeMs: Long, estadoEnviado: String)

    @Query("DELETE FROM reenvios")
    suspend fun eliminarTodos()

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    @Query("SELECT * FROM reenvios ORDER BY fecha_creacion DESC")
    fun observarTodos(): Flow<List<ReenvioEntity>>

    @Query("SELECT * FROM reenvios WHERE sms_id = :smsId ORDER BY fecha_creacion ASC")
    fun observarPorSms(smsId: String): Flow<List<ReenvioEntity>>

    @Query("SELECT COUNT(*) FROM reenvios WHERE estado = :estadoPendiente")
    fun observarContadorPendientes(estadoPendiente: String): Flow<Int>

    // ── Lectura puntual ──────────────────────────────────────────────────────

    @Query("SELECT * FROM reenvios WHERE id = :id")
    suspend fun obtenerPorId(id: String): ReenvioEntity?

    @Query("SELECT * FROM reenvios WHERE sms_id = :smsId ORDER BY fecha_creacion ASC")
    suspend fun obtenerPorSms(smsId: String): List<ReenvioEntity>

    /** Pendientes en orden de llegada (FIFO), para el rescate de huérfanos. */
    @Query("SELECT * FROM reenvios WHERE estado = :estadoPendiente ORDER BY fecha_creacion ASC")
    suspend fun obtenerPendientes(estadoPendiente: String): List<ReenvioEntity>

    /**
     * Número de reenvíos creados desde [desdeMs].
     * Base del límite por minuto que evita que un bucle de reenvíos genere una factura.
     */
    @Query("SELECT COUNT(*) FROM reenvios WHERE fecha_creacion >= :desdeMs")
    suspend fun contarCreadosDesde(desdeMs: Long): Int
}
