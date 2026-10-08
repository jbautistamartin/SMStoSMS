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
import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import com.capicua.smstosms.util.Constants
import com.capicua.smstosms.util.HttpClientFactory
import com.capicua.smstosms.util.toIsoString
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

/**
 * Worker que envía un único SMS a la API externa mediante GET.
 *
 * La URL se construye desde la plantilla configurada en Settings, sustituyendo:
 *   {telefono}, {mensaje}, {fecha} por los valores del SMS (URL-encoded).
 *
 * ## Estrategia de reintentos
 * | Resultado            | Acción WorkManager | Estado en BD              |
 * |----------------------|--------------------|---------------------------|
 * | HTTP 2xx             | Result.success()   | enviado=true, fechaEnvio  |
 * | HTTP 4xx (≠ 429)     | Result.failure()   | intentos++, ultimoError   |
 * | HTTP 5xx / 429       | Result.retry()     | intentos++, ultimoError   |
 * | Timeout / IOException| Result.retry()     | intentos++, ultimoError   |
 * | Max reintentos       | Result.failure()   | intentos++, ultimoError   |
 */
@HiltWorker
class SmsDispatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val smsRepository: SmsRepository,
    private val logRepository: LogRepository,
    private val okHttpClient: OkHttpClient,
    private val configDataStore: ConfigDataStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val smsId = inputData.getString(Constants.WORKER_KEY_SMS_ID)
            ?: return Result.failure().also {
                Timber.e("SmsDispatchWorker: sin smsId en inputData")
            }

        val config = configDataStore.config.first()

        // ── Guardia: max reintentos ───────────────────────────────────────────
        if (runAttemptCount >= config.maxReintentos) {
            val error = "Máximo de ${config.maxReintentos} reintentos alcanzado"
            Timber.w("SmsDispatchWorker[$smsId]: $error")
            smsRepository.registrarError(smsId, error)
            logRepository.insertar(logEntry(smsId, LogTipo.ERROR, error))
            return Result.failure()
        }

        // ── Guardia: configuración mínima ─────────────────────────────────────
        if (!config.esValida()) {
            val error = "Configuración incompleta: plantilla de URL vacía o sin marcador {mensaje}"
            Timber.e("SmsDispatchWorker[$smsId]: $error")
            logRepository.insertar(logEntry(smsId, LogTipo.ERROR, error))
            return Result.failure()
        }

        // ── Obtener SMS de la BD ──────────────────────────────────────────────
        val sms = smsRepository.obtenerPorId(smsId)
            ?: return Result.failure().also {
                Timber.e("SmsDispatchWorker[$smsId]: SMS no encontrado en BD")
            }

        // ── Idempotencia: ya enviado ──────────────────────────────────────────
        if (sms.enviado) {
            Timber.d("SmsDispatchWorker[$smsId]: ya enviado, omitiendo")
            return Result.success()
        }

        Timber.d("SmsDispatchWorker[$smsId]: intento ${runAttemptCount + 1}/${config.maxReintentos}")

        val url    = config.construirUrl(sms.telefono, sms.mensaje, sms.fechaRecepcion.toIsoString())
        val client = HttpClientFactory.crear(okHttpClient, config)

        return try {
            val codigoHttp = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { it.code }
            }

            when {
                codigoHttp in 200..299 -> {
                    smsRepository.marcarComoEnviado(smsId, Instant.now())
                    logRepository.insertar(logEntry(smsId, LogTipo.SMS_ENVIADO, "HTTP $codigoHttp — OK", codigoHttp))
                    Timber.i("SmsDispatchWorker[$smsId]: enviado correctamente (HTTP $codigoHttp)")
                    Result.success()
                }
                codigoHttp in 400..499 && codigoHttp != 429 -> {
                    val error = "HTTP $codigoHttp (permanente)"
                    smsRepository.registrarError(smsId, error)
                    logRepository.insertar(logEntry(smsId, LogTipo.ERROR, error, codigoHttp))
                    Timber.e("SmsDispatchWorker[$smsId]: fallo permanente — $error")
                    Result.failure()
                }
                else -> {
                    val error = "HTTP $codigoHttp (transitorio)"
                    smsRepository.registrarError(smsId, error)
                    logRepository.insertar(logEntry(smsId, LogTipo.ERROR, error, codigoHttp))
                    Timber.w("SmsDispatchWorker[$smsId]: fallo transitorio, reintentando — $error")
                    Result.retry()
                }
            }

        } catch (e: SocketTimeoutException) {
            val error = "Timeout: ${e.message}"
            smsRepository.registrarError(smsId, error)
            logRepository.insertar(logEntry(smsId, LogTipo.ERROR, error))
            Timber.w("SmsDispatchWorker[$smsId]: timeout, reintentando")
            Result.retry()
        } catch (e: IOException) {
            val error = "Red: ${e.message}"
            smsRepository.registrarError(smsId, error)
            logRepository.insertar(logEntry(smsId, LogTipo.ERROR, error))
            Timber.w("SmsDispatchWorker[$smsId]: error de red, reintentando")
            Result.retry()
        } catch (e: Exception) {
            val error = "Inesperado: ${e.javaClass.simpleName}: ${e.message}"
            smsRepository.registrarError(smsId, error)
            logRepository.insertar(logEntry(smsId, LogTipo.ERROR, error))
            Timber.e(e, "SmsDispatchWorker[$smsId]: excepción inesperada, reintentando")
            Result.retry()
        }
    }

    private fun logEntry(smsId: String, tipo: LogTipo, detalle: String, codigoHttp: Int? = null) =
        LogEntry(tipo = tipo, smsId = smsId, detalle = detalle, codigoHttp = codigoHttp, timestamp = Instant.now())
}
