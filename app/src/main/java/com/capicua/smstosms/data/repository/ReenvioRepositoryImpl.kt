// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.data.local.db.dao.ReenvioDao
import com.capicua.smstosms.data.local.db.entity.ReenvioEntity
import com.capicua.smstosms.domain.model.EstadoReenvio
import com.capicua.smstosms.domain.model.Reenvio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación de [ReenvioRepository].
 *
 * Es el único sitio que conoce la representación de [EstadoReenvio] en la base de datos:
 * se guarda el `name` del enum como texto, igual que hace `LogRepositoryImpl` con los tipos
 * de log. Las consultas del DAO reciben ese nombre desde aquí.
 */
@Singleton
class ReenvioRepositoryImpl @Inject constructor(
    private val reenvioDao: ReenvioDao
) : ReenvioRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    override suspend fun guardar(reenvio: Reenvio) {
        reenvioDao.insertar(reenvio.aEntidad())
    }

    override suspend fun guardarVarios(reenvios: List<Reenvio>) {
        reenvioDao.insertarVarios(reenvios.map { it.aEntidad() })
    }

    override suspend fun marcarComoEnviado(id: String, fechaEnvio: Instant) {
        reenvioDao.marcarEnviado(id, fechaEnvio.toEpochMilli(), EstadoReenvio.ENVIADO.name)
    }

    override suspend fun registrarError(id: String, error: String) {
        reenvioDao.registrarIntento(id, error)
    }

    override suspend fun marcarComoFallido(id: String, error: String) {
        reenvioDao.marcarFallido(id, error, EstadoReenvio.FALLIDO.name)
    }

    override suspend fun limpiarEnviadosAntiguos(antesDeEpochMs: Long) {
        reenvioDao.eliminarEnviadosAnterioresA(antesDeEpochMs, EstadoReenvio.ENVIADO.name)
    }

    override suspend fun limpiarTodos() {
        reenvioDao.eliminarTodos()
    }

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    override fun observarTodos(): Flow<List<Reenvio>> =
        reenvioDao.observarTodos().map { lista -> lista.map { it.aDominio() } }

    override fun observarPorSms(smsId: String): Flow<List<Reenvio>> =
        reenvioDao.observarPorSms(smsId).map { lista -> lista.map { it.aDominio() } }

    override fun observarContadorPendientes(): Flow<Int> =
        reenvioDao.observarContadorPendientes(EstadoReenvio.PENDIENTE.name)

    // ── Lectura puntual ──────────────────────────────────────────────────────

    override suspend fun obtenerPorId(id: String): Reenvio? =
        reenvioDao.obtenerPorId(id)?.aDominio()

    override suspend fun obtenerPorSms(smsId: String): List<Reenvio> =
        reenvioDao.obtenerPorSms(smsId).map { it.aDominio() }

    override suspend fun obtenerPendientes(): List<Reenvio> =
        reenvioDao.obtenerPendientes(EstadoReenvio.PENDIENTE.name).map { it.aDominio() }

    override suspend fun contarCreadosDesde(desde: Instant): Int =
        reenvioDao.contarCreadosDesde(desde.toEpochMilli())

    // ── Mappers ───────────────────────────────────────────────────────────────

    private fun Reenvio.aEntidad() = ReenvioEntity(
        id = id,
        smsId = smsId,
        reglaId = reglaId,
        nombreRegla = nombreRegla,
        destino = destino,
        textoFinal = textoFinal,
        estado = estado.name,
        intentos = intentos,
        ultimoError = ultimoError,
        fechaCreacion = fechaCreacion.toEpochMilli(),
        fechaEnvio = fechaEnvio?.toEpochMilli(),
        partes = partes
    )

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
}
