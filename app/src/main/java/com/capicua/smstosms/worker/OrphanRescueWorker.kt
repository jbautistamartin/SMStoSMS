// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capicua.smstosms.data.repository.ReenvioRepository
import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.domain.usecase.ProcesarSmsEntranteUseCase
import com.capicua.smstosms.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Worker de rescate: recoge lo que se quedó a medias.
 *
 * Se auto-encadena cada [Constants.ORPHAN_RESCUE_DELAY_SECONDS] segundos para compensar que
 * WorkManager no admite trabajos periódicos de menos de 15 minutos. Así, cualquier cosa que
 * quede colgada —por cierre abrupto del proceso, fallo al encolar o reinicio del dispositivo—
 * se retoma en menos de medio minuto.
 *
 * Tiene dos cosas que rescatar, porque ahora el camino tiene dos tramos:
 *
 * 1. **SMS sin tramitar.** Mensajes persistidos en estado PENDIENTE cuya evaluación de reglas
 *    no llegó a completarse. Se vuelven a tramitar; el caso de uso es idempotente, así que
 *    reprocesar no duplica reenvíos.
 * 2. **Reenvíos sin worker.** Reenvíos pendientes que no tienen trabajo vivo en WorkManager.
 *    Se reencolan.
 *
 * No limpia datos: eso es del [HealthMonitorWorker].
 */
@HiltWorker
class OrphanRescueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val smsRepository: SmsRepository,
    private val reenvioRepository: ReenvioRepository,
    private val procesarSms: ProcesarSmsEntranteUseCase,
    private val colaDeEnvios: ColaDeEnvios,
    private val workManager: WorkManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        rescatarSmsSinTramitar()
        rescatarReenviosSinWorker()
        programarSiguiente()
        return Result.success()
    }

    /**
     * Retoma los SMS que se guardaron pero cuyas reglas nunca se evaluaron.
     *
     * Cada uno se procesa en su propio try: un mensaje con un problema puntual no debe impedir
     * que los demás salgan adelante.
     */
    private suspend fun rescatarSmsSinTramitar() {
        val pendientes = smsRepository.obtenerPendientes()
        if (pendientes.isEmpty()) return

        var tramitados = 0
        for (sms in pendientes) {
            try {
                procesarSms.procesar(sms)
                tramitados++
            } catch (e: Exception) {
                Timber.e(e, "OrphanRescueWorker: no se pudo tramitar el SMS ${sms.id}")
            }
        }
        Timber.i("OrphanRescueWorker: $tramitados de ${pendientes.size} SMS pendientes tramitados")
    }

    /** Reencola los reenvíos pendientes que se quedaron sin trabajo en WorkManager. */
    private suspend fun rescatarReenviosSinWorker() {
        val pendientes = reenvioRepository.obtenerPendientes()
        if (pendientes.isEmpty()) return

        var reencolados = 0
        for (reenvio in pendientes) {
            if (!colaDeEnvios.tieneTrabajoActivo(reenvio.id)) {
                colaDeEnvios.encolar(reenvio.id)
                reencolados++
                Timber.d(
                    "OrphanRescueWorker: reencolado reenvío ${reenvio.id} → ${reenvio.destino} " +
                        "(intentos: ${reenvio.intentos})"
                )
            }
        }

        if (reencolados > 0) {
            Timber.i(
                "OrphanRescueWorker: $reencolados reenvío(s) reencolado(s) " +
                    "de ${pendientes.size} pendientes"
            )
        }
    }

    /**
     * Programa la siguiente iteración del bucle.
     *
     * REPLACE: si ya hubiera uno encolado —por ejemplo tras un doble arranque— lo sustituye,
     * para que la cola no acumule copias del mismo rescate.
     */
    private fun programarSiguiente() {
        val siguiente = OneTimeWorkRequestBuilder<OrphanRescueWorker>()
            .setInitialDelay(Constants.ORPHAN_RESCUE_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            Constants.WORKER_ORPHAN_RESCUE_TAG,
            ExistingWorkPolicy.REPLACE,
            siguiente
        )
    }
}
