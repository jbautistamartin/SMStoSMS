// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.capicua.smstosms.util.Constants
import com.capicua.smstosms.worker.HealthMonitorWorker
import com.capicua.smstosms.worker.OrphanRescueWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Se ejecuta al arrancar el dispositivo o reinstalar la app.
 * WorkManager persiste sus colas en disco, pero el Worker periódico
 * necesita ser re-programado explícitamente tras reinicios en algunos fabricantes.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return

        Timber.d("BootReceiver: $action — programando workers")
        programarHealthMonitor(context)
        iniciarOrphanRescue(context)
    }

    companion object {
        /** Programa (o reemplaza) el Worker periódico de limpieza (mínimo 15 min). */
        fun programarHealthMonitor(context: Context) {
            val solicitud = PeriodicWorkRequestBuilder<HealthMonitorWorker>(
                Constants.HEALTH_MONITOR_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.WORKER_HEALTH_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                solicitud
            )
        }

        /**
         * Lanza la primera iteración del bucle de rescate de SMS huérfanos.
         * El propio [OrphanRescueWorker] se auto-encadena cada 30 segundos.
         * Se usa KEEP para no interrumpir una ejecución ya en curso.
         */
        fun iniciarOrphanRescue(context: Context) {
            val solicitud = OneTimeWorkRequestBuilder<OrphanRescueWorker>().build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.WORKER_ORPHAN_RESCUE_TAG,
                ExistingWorkPolicy.KEEP,
                solicitud
            )
        }
    }
}