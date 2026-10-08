// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.data.local.db.dao.LogDao
import com.capicua.smstosms.data.local.db.entity.LogEntity
import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepositoryImpl @Inject constructor(
    private val logDao: LogDao
) : LogRepository {

    override suspend fun insertar(entry: LogEntry) {
        logDao.insertar(entry.aEntidad())
    }

    override fun observarRecientes(limite: Int): Flow<List<LogEntry>> =
        logDao.observarRecientes(limite).map { lista -> lista.map { it.aDominio() } }

    override fun observarPorTipo(tipo: LogTipo, limite: Int): Flow<List<LogEntry>> =
        logDao.observarPorTipo(tipo.name, limite).map { lista -> lista.map { it.aDominio() } }

    override suspend fun obtenerTodosParaExportar(): List<LogEntry> =
        logDao.obtenerTodosParaExportar().map { it.aDominio() }

    override suspend fun eliminarAntiguos(antes: Instant) {
        logDao.eliminarAnterioresA(antes.toEpochMilli())
    }

    override fun observarTotal(): Flow<Int> = logDao.observarTotal()

    override suspend fun limpiarTodo() = logDao.limpiarTodo()

    private fun LogEntry.aEntidad() = LogEntity(
        id = id,
        tipo = tipo.name,
        smsId = smsId,
        detalle = detalle,
        codigoHttp = codigoHttp,
        timestamp = timestamp.toEpochMilli()
    )

    private fun LogEntity.aDominio() = LogEntry(
        id = id,
        tipo = LogTipo.valueOf(tipo),
        smsId = smsId,
        detalle = detalle,
        codigoHttp = codigoHttp,
        timestamp = Instant.ofEpochMilli(timestamp)
    )
}