// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.data.local.db.dao.SmsDao
import com.capicua.smstosms.data.local.db.entity.ReenvioEntity
import com.capicua.smstosms.data.local.db.entity.SmsEntity
import com.capicua.smstosms.data.local.db.relation.SmsConReenviosEntity
import com.capicua.smstosms.domain.model.EstadoReenvio
import com.capicua.smstosms.domain.model.EstadoSms
import com.capicua.smstosms.domain.model.Reenvio
import com.capicua.smstosms.domain.model.SmsConReenvios
import com.capicua.smstosms.domain.model.SmsMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación de [SmsRepository].
 *
 * Responsabilidades:
 * 1. Delegar las operaciones de base de datos a [SmsDao].
 * 2. Traducir entre [SmsEntity] (capa de datos) y [SmsMessage] (capa de dominio).
 *
 * Es el único sitio que conoce cómo se representa [EstadoSms] en la base de datos: se guarda
 * el `name` del enum como texto, igual que los tipos de log.
 */
@Singleton
class SmsRepositoryImpl @Inject constructor(
    private val smsDao: SmsDao
) : SmsRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    override suspend fun guardar(sms: SmsMessage) {
        smsDao.insertar(sms.aEntidad())
    }

    override suspend fun actualizarEstado(id: String, estado: EstadoSms) {
        smsDao.actualizarEstado(id, estado.name)
    }

    override suspend fun marcarDescartado(id: String, motivo: String) {
        smsDao.marcarDescartado(id, motivo, EstadoSms.DESCARTADO.name)
    }

    override suspend fun limpiarTramitadosAntiguos(antesDeEpochMs: Long) {
        smsDao.eliminarTramitadosAnterioresA(antesDeEpochMs, EstadoSms.PENDIENTE.name)
    }

    override suspend fun limpiarTodos() {
        smsDao.eliminarTodos()
    }

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    override fun observarTodos(): Flow<List<SmsMessage>> =
        smsDao.observarTodos().map { lista -> lista.map { it.aDominio() } }

    override fun observarTodosConReenvios(): Flow<List<SmsConReenvios>> =
        smsDao.observarTodosConReenvios().map { lista -> lista.map { it.aDominio() } }

    override fun observarContadorPendientes(): Flow<Int> =
        smsDao.observarContadorPendientes(EstadoSms.PENDIENTE.name)

    override fun observarContadorTotal(): Flow<Int> =
        smsDao.observarContadorTotal()

    // ── Lectura puntual ───────────────────────────────────────────────────────

    override suspend fun obtenerPorId(id: String): SmsMessage? =
        smsDao.obtenerPorId(id)?.aDominio()

    override suspend fun obtenerPendientes(): List<SmsMessage> =
        smsDao.obtenerPendientes(EstadoSms.PENDIENTE.name).map { it.aDominio() }

    // ── Mappers ───────────────────────────────────────────────────────────────

    /** Los [Instant] se almacenan como epoch milisegundos. */
    private fun SmsMessage.aEntidad() = SmsEntity(
        id = id,
        telefono = telefono,
        mensaje = mensaje,
        fechaRecepcion = fechaRecepcion.toEpochMilli(),
        estado = estado.name,
        motivoDescarte = motivoDescarte
    )

    private fun SmsConReenviosEntity.aDominio() = SmsConReenvios(
        sms = sms.aDominio(),
        // Orden de creación: con varias reglas que casan, la primera es la de mayor prioridad.
        reenvios = reenvios.sortedBy { it.fechaCreacion }.map { it.aDominio() }
    )

    /**
     * Mapper local de reenvíos.
     *
     * Duplica el de `ReenvioRepositoryImpl` a propósito: hacerlo público allí para reutilizarlo
     * aquí expondría un detalle de persistencia en la frontera del repositorio, y son nueve
     * líneas sin lógica.
     */
    private fun ReenvioEntity.aDominio() = Reenvio(
        id = id,
        smsId = smsId,
        reglaId = reglaId,
        nombreRegla = nombreRegla,
        destino = destino,
        textoFinal = textoFinal,
        estado = EstadoReenvio.valueOf(estado),
        intentos = intentos,
        ultimoError = ultimoError,
        fechaCreacion = Instant.ofEpochMilli(fechaCreacion),
        fechaEnvio = fechaEnvio?.let { Instant.ofEpochMilli(it) },
        partes = partes
    )

    private fun SmsEntity.aDominio() = SmsMessage(
        id = id,
        telefono = telefono,
        mensaje = mensaje,
        fechaRecepcion = Instant.ofEpochMilli(fechaRecepcion),
        estado = EstadoSms.valueOf(estado),
        motivoDescarte = motivoDescarte
    )
}
