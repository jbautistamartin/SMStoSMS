// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.capicua.smstosms.data.local.db.entity.ReglaEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object para la tabla [reglas].
 *
 * Todas las lecturas ordenan por `orden` ascendente, que es la prioridad de evaluación.
 */
@Dao
interface ReglaDao {

    // ── Escritura ─────────────────────────────────────────────────────────────

    /** Inserta una regla y devuelve el id asignado. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(regla: ReglaEntity): Long

    @Update
    suspend fun actualizar(regla: ReglaEntity)

    @Query("DELETE FROM reglas WHERE id = :id")
    suspend fun eliminarPorId(id: Long)

    @Query("UPDATE reglas SET activa = :activa WHERE id = :id")
    suspend fun cambiarActiva(id: Long, activa: Boolean)

    @Query("UPDATE reglas SET orden = :orden WHERE id = :id")
    suspend fun actualizarOrden(id: Long, orden: Int)

    /**
     * Reasigna el campo `orden` según la posición de cada id en [idsEnOrden].
     * Se ejecuta en una transacción para que la lista nunca quede con órdenes duplicados
     * a medio camino, lo que haría la evaluación no determinista.
     */
    @Transaction
    suspend fun reordenar(idsEnOrden: List<Long>) {
        idsEnOrden.forEachIndexed { posicion, id -> actualizarOrden(id, posicion) }
    }

    @Query("DELETE FROM reglas")
    suspend fun eliminarTodas()

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    @Query("SELECT * FROM reglas ORDER BY orden ASC")
    fun observarTodas(): Flow<List<ReglaEntity>>

    @Query("SELECT COUNT(*) FROM reglas WHERE activa = 1")
    fun observarContadorActivas(): Flow<Int>

    // ── Lectura puntual ──────────────────────────────────────────────────────

    /** Reglas que participan en la evaluación, ya ordenadas por prioridad. */
    @Query("SELECT * FROM reglas WHERE activa = 1 ORDER BY orden ASC")
    suspend fun obtenerActivas(): List<ReglaEntity>

    @Query("SELECT * FROM reglas ORDER BY orden ASC")
    suspend fun obtenerTodas(): List<ReglaEntity>

    @Query("SELECT * FROM reglas WHERE id = :id")
    suspend fun obtenerPorId(id: Long): ReglaEntity?

    /** Mayor `orden` en uso, o -1 si la tabla está vacía. Para añadir al final de la lista. */
    @Query("SELECT COALESCE(MAX(orden), -1) FROM reglas")
    suspend fun ordenMaximo(): Int

    /**
     * Números destino de las reglas activas, sin repetir.
     * Base de la protección antibucle: un SMS cuyo remitente sea uno de estos números
     * viene de un destino nuestro y no debe reenviarse otra vez.
     */
    @Query("SELECT DISTINCT destino FROM reglas WHERE activa = 1")
    suspend fun obtenerDestinosActivos(): List<String>
}
