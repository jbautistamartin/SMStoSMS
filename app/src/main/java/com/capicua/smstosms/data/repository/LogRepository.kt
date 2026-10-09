// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface LogRepository {

    /** Inserta una entrada de log. Operación rápida, no bloquea el Worker. */
    suspend fun insertar(entry: LogEntry)

    /** Flujo reactivo de las N entradas más recientes */
    fun observarRecientes(limite: Int = 500): Flow<List<LogEntry>>

    /** Flujo reactivo filtrado por tipo */
    fun observarPorTipo(tipo: LogTipo, limite: Int = 500): Flow<List<LogEntry>>

    /** Todas las entradas para exportar a fichero */
    suspend fun obtenerTodosParaExportar(): List<LogEntry>

    /** Elimina entradas anteriores a [antes] para controlar el tamaño de la BD */
    suspend fun eliminarAntiguos(antes: Instant)

    /** Total de entradas de log */
    fun observarTotal(): Flow<Int>

    /** Elimina todos los logs */
    suspend fun limpiarTodo()
}