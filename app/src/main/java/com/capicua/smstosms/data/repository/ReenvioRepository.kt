// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.domain.model.Reenvio
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Contrato del repositorio de reenvíos.
 *
 * Un reenvío es la unidad de despacho: el worker de envío trabaja sobre su id, no sobre el
 * id del SMS entrante, porque un mismo SMS puede tener varios destinos.
 */
interface ReenvioRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    suspend fun guardar(reenvio: Reenvio)

    /** Inserta de golpe todos los reenvíos derivados de un mismo SMS. */
    suspend fun guardarVarios(reenvios: List<Reenvio>)

    /** Cierra el reenvío con éxito: estado ENVIADO, fecha de envío y error limpiado. */
    suspend fun marcarComoEnviado(id: String, fechaEnvio: Instant)

    /** Suma un intento y guarda el error, dejando el reenvío pendiente de reintento. */
    suspend fun registrarError(id: String, error: String)

    /** Cierra el reenvío como fallo permanente: no se volverá a intentar. */
    suspend fun marcarComoFallido(id: String, error: String)

    /** Purga reenvíos ya enviados anteriores a la fecha de corte de retención. */
    suspend fun limpiarEnviadosAntiguos(antesDeEpochMs: Long)

    suspend fun limpiarTodos()

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    fun observarTodos(): Flow<List<Reenvio>>

    /** Reenvíos de un SMS concreto, para mostrar su estado por destino. */
    fun observarPorSms(smsId: String): Flow<List<Reenvio>>

    fun observarContadorPendientes(): Flow<Int>

    // ── Lectura puntual ──────────────────────────────────────────────────────

    suspend fun obtenerPorId(id: String): Reenvio?

    suspend fun obtenerPorSms(smsId: String): List<Reenvio>

    /** Pendientes en orden FIFO. Lo consume el rescate de huérfanos. */
    suspend fun obtenerPendientes(): List<Reenvio>

    /**
     * Cuántos reenvíos se han creado desde [desde].
     * Lo usa el límite por minuto de la protección antibucle.
     */
    suspend fun contarCreadosDesde(desde: Instant): Int
}
