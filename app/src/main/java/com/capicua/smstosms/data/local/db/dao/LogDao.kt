// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.capicua.smstosms.data.local.db.entity.LogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(log: LogEntity)

    /** Todos los logs más recientes primero, limitados para no saturar UI */
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT :limite")
    fun observarRecientes(limite: Int = 500): Flow<List<LogEntity>>

    /** Filtrar por tipo */
    @Query("SELECT * FROM log_entries WHERE tipo = :tipo ORDER BY timestamp DESC LIMIT :limite")
    fun observarPorTipo(tipo: String, limite: Int = 500): Flow<List<LogEntity>>

    /** Todos para exportar (sin límite) */
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC")
    suspend fun obtenerTodosParaExportar(): List<LogEntity>

    /** Eliminar logs anteriores a una fecha para controlar el tamaño */
    @Query("DELETE FROM log_entries WHERE timestamp < :antesDeMs")
    suspend fun eliminarAnterioresA(antesDeMs: Long)

    @Query("SELECT COUNT(*) FROM log_entries")
    fun observarTotal(): Flow<Int>

    /** Elimina todos los logs */
    @Query("DELETE FROM log_entries")
    suspend fun limpiarTodo()
}