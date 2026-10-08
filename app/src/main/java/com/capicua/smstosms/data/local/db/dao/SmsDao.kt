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
 * - Nunca se sobreescribe un SMS existente ([OnConflictStrategy.ABORT]); el ID es inmutable.
 */
@Dao
interface SmsDao {

    // ── Escritura ─────────────────────────────────────────────────────────────

    /**
     * Inserta un nuevo SMS.
     * Falla con excepción si ya existe un registro con el mismo [id] (previene duplicados).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(sms: SmsEntity)

    /**
     * Marca el SMS como enviado con éxito y registra el instante de confirmación.
     * Limpia [ultimo_error] porque el envío fue exitoso.
     */
    @Query("""
        UPDATE sms
        SET enviado      = 1,
            fecha_envio  = :fechaEnvioMs,
            ultimo_error = NULL
        WHERE id = :id
    """)
    suspend fun marcarEnviado(id: String, fechaEnvioMs: Long)

    /**
     * Incrementa el contador de intentos y guarda el mensaje de error del último intento fallido.
     * No modifica [enviado] — el Worker decide si reintenta o abandona.
     */
    @Query("""
        UPDATE sms
        SET intentos     = intentos + 1,
            ultimo_error = :error
        WHERE id = :id
    """)
    suspend fun registrarIntento(id: String, error: String)

    /**
     * Elimina mensajes ya enviados cuya [fecha_envio] sea anterior a [antesDeMs].
     * Usar para la limpieza periódica según la política de retención.
     */
    @Query("DELETE FROM sms WHERE enviado = 1 AND fecha_envio < :antesDeMs")
    suspend fun eliminarEnviadosAnterioresA(antesDeMs: Long)

    // ── Lectura reactiva (Flow) ───────────────────────────────────────────────

    /**
     * Emite la lista completa de SMS cada vez que cambia la tabla, ordenados
     * por fecha de recepción descendente (más reciente primero).
     */
    @Query("SELECT * FROM sms ORDER BY fecha_recepcion DESC")
    fun observarTodos(): Flow<List<SmsEntity>>

    /**
     * Emite el número de SMS pendientes de envío ([enviado] = false).
     * Útil para el badge / contador en la UI.
     */
    @Query("SELECT COUNT(*) FROM sms WHERE enviado = 0")
    fun observarContadorPendientes(): Flow<Int>

    /**
     * Emite el total de SMS almacenados.
     */
    @Query("SELECT COUNT(*) FROM sms")
    fun observarContadorTotal(): Flow<Int>

    // ── Lectura puntual (suspend) ─────────────────────────────────────────────

    /**
     * Devuelve un SMS por su [id], o null si no existe.
     * Usado por los Workers para obtener el payload antes del envío.
     */
    @Query("SELECT * FROM sms WHERE id = :id")
    suspend fun obtenerPorId(id: String): SmsEntity?

    /**
     * Devuelve todos los SMS cuyo campo [enviado] es false, ordenados por
     * fecha de recepción ascendente (primero en entrar, primero en salir).
     * Usado por el [HealthMonitorWorker] para re-encolar mensajes atascados.
     */
    @Query("SELECT * FROM sms WHERE enviado = 0 ORDER BY fecha_recepcion ASC")
    suspend fun obtenerPendientes(): List<SmsEntity>

    /**
     * Devuelve los [limite] SMS más recientes.
     * Útil para la pantalla de logs sin cargar toda la tabla.
     */
    @Query("SELECT * FROM sms ORDER BY fecha_recepcion DESC LIMIT :limite")
    suspend fun obtenerUltimos(limite: Int): List<SmsEntity>

    /** Elimina todos los registros de la tabla. */
    @Query("DELETE FROM sms")
    suspend fun eliminarTodos()
}