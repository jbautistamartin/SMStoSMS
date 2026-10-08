// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.domain.model.Regla
import kotlinx.coroutines.flow.Flow

/**
 * Contrato del repositorio de reglas de reenvío.
 *
 * Todas las lecturas devuelven las reglas ordenadas por prioridad ascendente.
 */
interface ReglaRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    /**
     * Inserta una regla nueva al final de la lista, asignándole el siguiente `orden` libre.
     * Devuelve el id asignado.
     */
    suspend fun crear(regla: Regla): Long

    suspend fun actualizar(regla: Regla)

    suspend fun eliminar(id: Long)

    /** Activa o desactiva una regla sin borrarla. */
    suspend fun cambiarActiva(id: Long, activa: Boolean)

    /** Reasigna la prioridad de todas las reglas según el orden de [idsEnOrden]. */
    suspend fun reordenar(idsEnOrden: List<Long>)

    suspend fun eliminarTodas()

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    fun observarTodas(): Flow<List<Regla>>

    fun observarContadorActivas(): Flow<Int>

    // ── Lectura puntual ──────────────────────────────────────────────────────

    /** Reglas activas, ya ordenadas. Es lo que consume el evaluador. */
    suspend fun obtenerActivas(): List<Regla>

    suspend fun obtenerTodas(): List<Regla>

    suspend fun obtenerPorId(id: Long): Regla?

    /** Destinos de las reglas activas, sin repetir. Para la protección antibucle. */
    suspend fun obtenerDestinosActivos(): List<String>
}
