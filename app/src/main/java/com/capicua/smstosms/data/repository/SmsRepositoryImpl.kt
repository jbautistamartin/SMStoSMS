// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.capicua.smstosms.data.local.db.dao.SmsDao
import com.capicua.smstosms.data.local.db.entity.SmsEntity
import com.capicua.smstosms.domain.model.SmsMessage
import com.capicua.smstosms.util.Constants
import com.capicua.smstosms.worker.SmsDispatchWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación de [SmsRepository].
 *
 * Responsabilidades:
 * 1. Delegar operaciones de base de datos a [SmsDao].
 * 2. Traducir entre [SmsEntity] (capa de datos) y [SmsMessage] (capa de dominio).
 * 3. Gestionar la cola de WorkManager para el despacho de mensajes.
 *
 * Es un [Singleton]: una única instancia comparte la conexión a Room y WorkManager.
 */
@Singleton
class SmsRepositoryImpl @Inject constructor(
    private val smsDao: SmsDao,
    private val workManager: WorkManager
) : SmsRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    override suspend fun guardar(sms: SmsMessage) {
        smsDao.insertar(sms.aEntidad())
    }

    override suspend fun marcarComoEnviado(id: String, fechaEnvio: Instant) {
        smsDao.marcarEnviado(id, fechaEnvio.toEpochMilli())
    }

    override suspend fun registrarError(id: String, error: String) {
        smsDao.registrarIntento(id, error)
    }

    override fun encolarEnvio(smsId: String) {
        // Sin constraint de red: WorkManager ejecuta el worker en cuanto puede.
        // Si no hay red en ese momento, OkHttp lanza IOException → Result.retry()
        // con backoff exponencial. Esto es más fiable que NET_CAPABILITY_INTERNET,
        // capability que Android no asigna en redes locales o corporativas aunque
        // el servidor destino sea perfectamente alcanzable.
        val datos = workDataOf(Constants.WORKER_KEY_SMS_ID to smsId)

        val solicitud = OneTimeWorkRequestBuilder<SmsDispatchWorker>()
            .setInputData(datos)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,   // reintentos cada ~30 s, sin crecimiento exponencial
                Constants.WORKER_INITIAL_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()

        // KEEP: si ya existe un worker para este SMS (p. ej. reintento en curso), no lo duplica.
        workManager.enqueueUniqueWork(
            "${Constants.WORKER_DISPATCH_TAG}_$smsId",
            ExistingWorkPolicy.KEEP,
            solicitud
        )
    }

    override suspend fun limpiarEnviadosAntiguos(antesDeEpochMs: Long) {
        smsDao.eliminarEnviadosAnterioresA(antesDeEpochMs)
    }

    override suspend fun limpiarTodos() {
        smsDao.eliminarTodos()
    }

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    override fun observarTodos(): Flow<List<SmsMessage>> =
        smsDao.observarTodos().map { lista -> lista.map { it.aDominio() } }

    override fun observarContadorPendientes(): Flow<Int> =
        smsDao.observarContadorPendientes()

    override fun observarContadorTotal(): Flow<Int> =
        smsDao.observarContadorTotal()

    // ── Lectura puntual ───────────────────────────────────────────────────────

    override suspend fun obtenerPorId(id: String): SmsMessage? =
        smsDao.obtenerPorId(id)?.aDominio()

    override suspend fun obtenerPendientes(): List<SmsMessage> =
        smsDao.obtenerPendientes().map { it.aDominio() }

    // ── Mappers ───────────────────────────────────────────────────────────────

    /**
     * Convierte el modelo de dominio a entidad de Room.
     * Los [Instant] se almacenan como epoch milisegundos (Long).
     */
    private fun SmsMessage.aEntidad() = SmsEntity(
        id = id,
        telefono = telefono,
        mensaje = mensaje,
        fechaRecepcion = fechaRecepcion.toEpochMilli(),
        enviado = enviado,
        fechaEnvio = fechaEnvio?.toEpochMilli(),
        intentos = intentos,
        ultimoError = ultimoError
    )

    /**
     * Convierte una entidad de Room al modelo de dominio.
     * Los Long de epoch se convierten de vuelta a [Instant].
     */
    private fun SmsEntity.aDominio() = SmsMessage(
        id = id,
        telefono = telefono,
        mensaje = mensaje,
        fechaRecepcion = Instant.ofEpochMilli(fechaRecepcion),
        enviado = enviado,
        fechaEnvio = fechaEnvio?.let { Instant.ofEpochMilli(it) },
        intentos = intentos,
        ultimoError = ultimoError
    )
}