// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.domain.model.SmsMessage
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Contrato del repositorio de SMS.
 *
 * Es la única fuente de verdad para los datos de mensajes. Coordina:
 * - La capa de persistencia local (Room / [SmsDatabase])
 * - La cola de despacho (WorkManager)
 *
 * Todas las operaciones de escritura son atómicas a nivel de base de datos.
 * La UI solo debe interactuar con los métodos que devuelven [Flow].
 */
interface SmsRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    /**
     * Persiste un nuevo SMS en la base de datos local.
     * Lanza excepción si ya existe un SMS con el mismo [SmsMessage.id].
     */
    suspend fun guardar(sms: SmsMessage)

    /**
     * Marca el SMS como enviado con éxito.
     * Registra [fechaEnvio] y limpia [ultimoError].
     */
    suspend fun marcarComoEnviado(id: String, fechaEnvio: Instant)

    /**
     * Registra un intento de envío fallido:
     * - Incrementa [SmsMessage.intentos] en 1.
     * - Guarda el [error] en [SmsMessage.ultimoError].
     */
    suspend fun registrarError(id: String, error: String)

    /**
     * Encola un [SmsDispatchWorker] en WorkManager para el SMS indicado.
     * Usa [ExistingWorkPolicy.KEEP] para evitar workers duplicados por el mismo SMS.
     */
    fun encolarEnvio(smsId: String)

    /**
     * Elimina los SMS ya enviados cuya fecha de envío sea anterior a [antesDeEpochMs].
     * Llamado periódicamente por [HealthMonitorWorker] según la política de retención.
     */
    suspend fun limpiarEnviadosAntiguos(antesDeEpochMs: Long)

    /** Elimina todos los SMS de la base de datos. */
    suspend fun limpiarTodos()

    // ── Lectura reactiva (Flow) ───────────────────────────────────────────────

    /**
     * Flujo reactivo con todos los SMS, ordenados por fecha de recepción descendente.
     * Se actualiza automáticamente al insertar o modificar cualquier fila.
     */
    fun observarTodos(): Flow<List<SmsMessage>>

    /**
     * Flujo reactivo con el número de SMS pendientes ([enviado] = false).
     */
    fun observarContadorPendientes(): Flow<Int>

    /**
     * Flujo reactivo con el total de SMS almacenados.
     */
    fun observarContadorTotal(): Flow<Int>

    // ── Lectura puntual ───────────────────────────────────────────────────────

    /**
     * Devuelve el SMS con el [id] indicado, o null si no existe.
     */
    suspend fun obtenerPorId(id: String): SmsMessage?

    /**
     * Devuelve la lista de SMS pendientes de envío ([enviado] = false),
     * ordenados por fecha de recepción ascendente (FIFO).
     */
    suspend fun obtenerPendientes(): List<SmsMessage>
}