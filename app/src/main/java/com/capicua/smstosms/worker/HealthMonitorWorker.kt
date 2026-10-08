// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.capicua.smstosms.data.repository.LogRepository
import com.capicua.smstosms.data.repository.ReenvioRepository
import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Worker periódico de limpieza.
 *
 * Ejecuta cada [Constants.HEALTH_MONITOR_INTERVAL_MINUTES] minutos y purga, por este orden:
 * 1. Reenvíos ya confirmados más antiguos que la política de retención.
 * 2. SMS ya tramitados más antiguos que esa misma política. La clave ajena en CASCADE se lleva
 *    por delante los reenvíos que quedaran colgando de ellos.
 * 3. Entradas de log más antiguas que su propia retención.
 *
 * Los SMS pendientes y los reenvíos sin confirmar no se tocan nunca, por antiguos que sean:
 * siguen teniendo trabajo pendiente y borrarlos sería perder un mensaje.
 *
 * El rescate de lo que se queda atascado lo hace el [OrphanRescueWorker], cada 30 segundos.
 */
@HiltWorker
class HealthMonitorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val smsRepository: SmsRepository,
    private val reenvioRepository: ReenvioRepository,
    private val logRepository: LogRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d("HealthMonitorWorker: inicio")

        val corteDatos = Instant.now()
            .minus(Constants.RETENTION_DELIVERED_DAYS, ChronoUnit.DAYS)

        // Primero los reenvíos enviados, luego los SMS tramitados: así los contadores de la
        // pantalla de inicio no muestran un SMS sin ninguno de sus reenvíos.
        reenvioRepository.limpiarEnviadosAntiguos(corteDatos.toEpochMilli())
        smsRepository.limpiarTramitadosAntiguos(corteDatos.toEpochMilli())

        val corteLogs = Instant.now()
            .minus(Constants.RETENTION_LOGS_DAYS, ChronoUnit.DAYS)
        logRepository.eliminarAntiguos(corteLogs)

        Timber.d("HealthMonitorWorker: completado")
        return Result.success()
    }
}
