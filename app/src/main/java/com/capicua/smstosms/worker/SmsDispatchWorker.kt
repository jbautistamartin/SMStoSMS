// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.capicua.smstosms.data.config.ConfigDataStore
import com.capicua.smstosms.data.repository.LogRepository
import com.capicua.smstosms.data.repository.ReenvioRepository
import com.capicua.smstosms.data.sms.ResultadoEnvio
import com.capicua.smstosms.data.sms.SmsSender
import com.capicua.smstosms.domain.model.EstadoReenvio
import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import com.capicua.smstosms.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant

/**
 * Worker que reenvía un único [com.capicua.smstosms.domain.model.Reenvio] a su número destino.
 *
 * Trabaja sobre el id del reenvío, no sobre el del SMS: un mismo mensaje entrante puede tener
 * varios destinos y cada uno se reintenta por separado.
 *
 * ## Estrategia de reintentos
 * | Resultado                            | Acción WorkManager | Estado en BD                |
 * |--------------------------------------|--------------------|-----------------------------|
 * | Todas las partes `RESULT_OK`         | `Result.success()` | ENVIADO, fechaEnvio         |
 * | Sin servicio / radio apagada         | `Result.retry()`   | intentos++, ultimoError     |
 * | Fallo genérico del operador          | `Result.retry()`   | intentos++, ultimoError     |
 * | Sin confirmación dentro del plazo    | `Result.retry()`   | intentos++, ultimoError     |
 * | PDU nula / destino inválido / permiso| `Result.failure()` | FALLIDO, ultimoError        |
 * | Máximo de reintentos alcanzado       | `Result.failure()` | FALLIDO, ultimoError        |
 *
 * ## Idempotencia
 * Es de cliente, como en la versión HTTP: al principio de cada intento se comprueba el estado
 * en la base de datos y, si ya está [EstadoReenvio.ENVIADO], se devuelve éxito sin tocar la
 * radio. Aquí importa más que antes, porque un SMS duplicado cuesta dinero y lo ve una persona.
 */
@HiltWorker
class SmsDispatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reenvioRepository: ReenvioRepository,
    private val logRepository: LogRepository,
    private val smsSender: SmsSender,
    private val configDataStore: ConfigDataStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reenvioId = inputData.getString(Constants.WORKER_KEY_REENVIO_ID)
            ?: return Result.failure().also {
                Timber.e("SmsDispatchWorker: sin reenvioId en inputData")
            }

        val config = configDataStore.config.first()

        // ── Obtener el reenvío ────────────────────────────────────────────────
        val reenvio = reenvioRepository.obtenerPorId(reenvioId)
            ?: return Result.failure().also {
                Timber.e("SmsDispatchWorker[$reenvioId]: reenvío no encontrado en BD")
            }

        // ── Idempotencia: ya enviado ──────────────────────────────────────────
        if (reenvio.estado == EstadoReenvio.ENVIADO) {
            Timber.d("SmsDispatchWorker[$reenvioId]: ya enviado, omitiendo")
            return Result.success()
        }

        // ── Fallo permanente previo: no insistir ──────────────────────────────
        if (reenvio.estado == EstadoReenvio.FALLIDO) {
            Timber.d("SmsDispatchWorker[$reenvioId]: marcado como fallido, no se reintenta")
            return Result.failure()
        }

        // ── Guardia: máximo de reintentos ─────────────────────────────────────
        if (runAttemptCount >= config.maxReintentos) {
            val error = "Máximo de ${config.maxReintentos} reintentos alcanzado"
            Timber.w("SmsDispatchWorker[$reenvioId]: $error")
            reenvioRepository.marcarComoFallido(reenvioId, error)
            registrar(LogTipo.ERROR, reenvio.smsId, reenvioId, reenvio.destino, error)
            return Result.failure()
        }

        Timber.d(
            "SmsDispatchWorker[$reenvioId]: intento ${runAttemptCount + 1}/${config.maxReintentos} " +
                "→ ${reenvio.destino}"
        )

        val resultado = smsSender.enviar(
            destino = reenvio.destino,
            texto = reenvio.textoFinal,
            subscriptionId = config.subscriptionId,
            timeoutSegundos = config.timeoutEnvioSegundos
        )

        return when (resultado) {
            is ResultadoEnvio.Enviado -> {
                reenvioRepository.marcarComoEnviado(reenvioId, Instant.now())
                registrar(
                    LogTipo.SMS_REENVIADO, reenvio.smsId, reenvioId, reenvio.destino,
                    "Reenviado a ${reenvio.destino}" +
                        if (resultado.partes > 1) " en ${resultado.partes} partes" else ""
                )
                Timber.i("SmsDispatchWorker[$reenvioId]: reenviado a ${reenvio.destino}")
                Result.success()
            }

            is ResultadoEnvio.ErrorPermanente -> {
                val error = "Fallo permanente: ${resultado.motivo}"
                reenvioRepository.marcarComoFallido(reenvioId, error)
                registrar(LogTipo.ERROR, reenvio.smsId, reenvioId, reenvio.destino, error)
                Timber.e("SmsDispatchWorker[$reenvioId]: $error")
                Result.failure()
            }

            is ResultadoEnvio.ErrorTransitorio -> {
                val error = "Fallo transitorio: ${resultado.motivo}"
                reenvioRepository.registrarError(reenvioId, error)
                registrar(LogTipo.ERROR, reenvio.smsId, reenvioId, reenvio.destino, error)
                Timber.w("SmsDispatchWorker[$reenvioId]: $error, reintentando")
                Result.retry()
            }
        }
    }

    private suspend fun registrar(
        tipo: LogTipo,
        smsId: String,
        reenvioId: String,
        destino: String,
        detalle: String
    ) {
        logRepository.insertar(
            LogEntry(
                tipo = tipo,
                smsId = smsId,
                reenvioId = reenvioId,
                destino = destino,
                detalle = detalle,
                timestamp = Instant.now()
            )
        )
    }
}
