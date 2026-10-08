// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.domain.model.EstadoSms
import com.capicua.smstosms.domain.model.SmsConReenvios
import com.capicua.smstosms.domain.model.SmsMessage
import kotlinx.coroutines.flow.Flow

/**
 * Contrato del repositorio de SMS entrantes.
 *
 * Es la única fuente de verdad para los mensajes recibidos. No sabe nada de envíos: la cola de
 * despacho y su estado son responsabilidad de [ReenvioRepository].
 *
 * Todas las operaciones de escritura son atómicas a nivel de base de datos.
 * La UI solo debe interactuar con los métodos que devuelven [Flow].
 */
interface SmsRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    /**
     * Persiste un SMS nuevo en estado [EstadoSms.PENDIENTE].
     * Lanza excepción si ya existe un SMS con el mismo [SmsMessage.id].
     */
    suspend fun guardar(sms: SmsMessage)

    /** Cambia el estado de tramitación del SMS. */
    suspend fun actualizarEstado(id: String, estado: EstadoSms)

    /** Marca el SMS como [EstadoSms.DESCARTADO] anotando el motivo. */
    suspend fun marcarDescartado(id: String, motivo: String)

    /**
     * Elimina los SMS ya tramitados recibidos antes de [antesDeEpochMs].
     * Llamado periódicamente por el monitor de salud según la política de retención.
     * Los pendientes se conservan siempre.
     */
    suspend fun limpiarTramitadosAntiguos(antesDeEpochMs: Long)

    /** Elimina todos los SMS y, en cascada, sus reenvíos. */
    suspend fun limpiarTodos()

    // ── Lectura reactiva (Flow) ───────────────────────────────────────────────

    /** Todos los SMS, más recientes primero. Se actualiza al cambiar cualquier fila. */
    fun observarTodos(): Flow<List<SmsMessage>>

    /**
     * Todos los SMS con sus reenvíos, más recientes primero.
     * Es lo que consume la pantalla de inicio: el mensaje y lo que se hizo con él.
     */
    fun observarTodosConReenvios(): Flow<List<SmsConReenvios>>

    /** Número de SMS recibidos y aún sin evaluar. */
    fun observarContadorPendientes(): Flow<Int>

    /** Total de SMS almacenados. */
    fun observarContadorTotal(): Flow<Int>

    // ── Lectura puntual ───────────────────────────────────────────────────────

    suspend fun obtenerPorId(id: String): SmsMessage?

    /**
     * SMS pendientes de evaluar, en orden de llegada.
     * Lo consume el rescate de huérfanos.
     */
    suspend fun obtenerPendientes(): List<SmsMessage>
}
