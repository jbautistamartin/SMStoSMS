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
 * Ejecuta cada [Constants.HEALTH_MONITOR_INTERVAL_MINUTES] minutos y:
 * 1. Limpia SMS enviados más antiguos que la política de retención
 * 2. Limpia logs más antiguos que la política de retención
 *
 * El re-encolo de SMS huérfanos lo gestiona [OrphanRescueWorker] (cada 30 s).
 */
@HiltWorker
class HealthMonitorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val smsRepository: SmsRepository,
    private val logRepository: LogRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d("HealthMonitorWorker: inicio")

        // ── 1. Limpiar SMS enviados antiguos ──────────────────────────────────
        val corteRetencionSms = Instant.now()
            .minus(Constants.RETENTION_DELIVERED_DAYS, ChronoUnit.DAYS)
        smsRepository.limpiarEnviadosAntiguos(corteRetencionSms.toEpochMilli())

        // ── 2. Limpiar logs antiguos ──────────────────────────────────────────
        val corteLogs = Instant.now()
            .minus(Constants.RETENTION_LOGS_DAYS, ChronoUnit.DAYS)
        logRepository.eliminarAntiguos(corteLogs)

        Timber.d("HealthMonitorWorker: completado")
        return Result.success()
    }
}