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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.guava.await
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Worker de rescate de SMS huérfanos.
 *
 * Se auto-encadena cada [Constants.ORPHAN_RESCUE_DELAY_SECONDS] segundos para compensar
 * la limitación de WorkManager (mínimo 15 min para PeriodicWork). Esto garantiza que
 * cualquier SMS que quede pendiente sin Worker activo — por cierre abrupto del proceso,
 * fallo de enqueue o reinicio del dispositivo — sea re-encolado en menos de 30 segundos.
 *
 * No realiza limpieza de datos: esa responsabilidad recae en [HealthMonitorWorker].
 */
@HiltWorker
class OrphanRescueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val smsRepository: SmsRepository,
    private val workManager: WorkManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // ── Re-encolar SMS pendientes sin Worker activo ───────────────────────
        val pendientes = smsRepository.obtenerPendientes()
        var reencolados = 0

        for (sms in pendientes) {
            val nombreUnico = "${Constants.WORKER_DISPATCH_TAG}_${sms.id}"
            val workerInfos = workManager
                .getWorkInfosForUniqueWork(nombreUnico)
                .await()

            val tieneWorkerActivo = workerInfos.any { info ->
                info.state in listOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED
                )
            }

            if (!tieneWorkerActivo) {
                smsRepository.encolarEnvio(sms.id)
                reencolados++
                Timber.d("OrphanRescueWorker: re-encolado SMS ${sms.id} (intentos: ${sms.intentos})")
            }
        }

        if (reencolados > 0) {
            Timber.i("OrphanRescueWorker: $reencolados SMS re-encolados de ${pendientes.size} pendientes")
        }

        // ── Auto-encadenamiento: programar la siguiente ejecución ─────────────
        val siguiente = OneTimeWorkRequestBuilder<OrphanRescueWorker>()
            .setInitialDelay(Constants.ORPHAN_RESCUE_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        // REPLACE: si ya hay uno encolado (p. ej. doble inicio), lo reemplaza
        // para evitar acumulación de workers en la cola.
        workManager.enqueueUniqueWork(
            Constants.WORKER_ORPHAN_RESCUE_TAG,
            ExistingWorkPolicy.REPLACE,
            siguiente
        )

        return Result.success()
    }
}
