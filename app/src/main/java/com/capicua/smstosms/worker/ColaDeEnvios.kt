// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.capicua.smstosms.data.config.ConfigDataStore
import com.capicua.smstosms.util.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Único punto por el que un reenvío entra en la cola de WorkManager.
 *
 * Centralizarlo aquí resuelve un problema que arrastraba la versión HTTP: el intervalo de
 * reintento era editable en la pantalla de configuración pero nunca se usaba, porque el
 * encolado leía una constante. Ahora el backoff sale de [ConfigDataStore], que es lo que el
 * operador cree estar ajustando.
 *
 * No se impone ninguna restricción de red. En la versión HTTP eso era deliberado porque Android
 * no marca como «con internet» las redes locales; ahora es que la red de datos es irrelevante:
 * un SMS sale por la red del operador, y si no hay cobertura el propio envío devuelve
 * `RESULT_ERROR_NO_SERVICE` y el worker reintenta.
 */
@Singleton
class ColaDeEnvios @Inject constructor(
    private val workManager: WorkManager,
    private val configDataStore: ConfigDataStore
) {

    /** Nombre único del trabajo asociado a un reenvío. */
    fun nombreUnico(reenvioId: String): String =
        "${Constants.WORKER_DISPATCH_TAG}_$reenvioId"

    /**
     * Encola el envío de un reenvío.
     *
     * Usa [ExistingWorkPolicy.KEEP]: si ya hay un trabajo para este reenvío —por ejemplo un
     * reintento en curso— no se duplica. Es la primera línea de defensa contra enviar dos
     * veces el mismo SMS.
     */
    suspend fun encolar(reenvioId: String) {
        val config = configDataStore.config.first()

        val solicitud = OneTimeWorkRequestBuilder<SmsDispatchWorker>()
            .setInputData(workDataOf(Constants.WORKER_KEY_REENVIO_ID to reenvioId))
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                config.intervaloReintentoSegundos.toLong(),
                TimeUnit.SECONDS
            )
            .addTag(Constants.WORKER_DISPATCH_TAG)
            .build()

        workManager.enqueueUniqueWork(nombreUnico(reenvioId), ExistingWorkPolicy.KEEP, solicitud)
    }

    /** Encola varios reenvíos, normalmente los derivados de un mismo SMS. */
    suspend fun encolarVarios(reenvioIds: List<String>) {
        reenvioIds.forEach { encolar(it) }
    }

    /**
     * true si el reenvío tiene un trabajo vivo en WorkManager.
     *
     * Lo consulta el rescate de huérfanos para no encolar dos veces lo que ya está en marcha.
     */
    suspend fun tieneTrabajoActivo(reenvioId: String): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(nombreUnico(reenvioId)).await()
        return infos.any { info ->
            info.state in listOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED
            )
        }
    }
}
