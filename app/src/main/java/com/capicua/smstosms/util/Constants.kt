// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.util

/**
 * Constantes de infraestructura.
 *
 * Aquí solo vive lo que no tiene sentido que el operador ajuste. Los parámetros editables
 * —reintentos, intervalo de backoff, timeout de envío, límite por minuto— están en
 * `AppConfig` y se leen de DataStore: duplicarlos aquí fue justo el origen del error que
 * arrastraba la versión HTTP, donde el intervalo configurado nunca se aplicaba.
 */
object Constants {

    // WorkManager — claves de input data
    const val WORKER_KEY_REENVIO_ID = "reenvio_id"

    // WorkManager — nombres únicos de trabajo
    const val WORKER_DISPATCH_TAG       = "sms_dispatch"
    const val WORKER_HEALTH_TAG         = "health_monitor"
    const val WORKER_ORPHAN_RESCUE_TAG  = "orphan_rescue"

    // HealthMonitor (limpieza periódica; WorkManager no admite menos de 15 min)
    const val HEALTH_MONITOR_INTERVAL_MINUTES = 15L

    // OrphanRescue (se auto-encadena cada N segundos para bajar del mínimo de 15 min)
    const val ORPHAN_RESCUE_DELAY_SECONDS = 30L

    // Retención de datos
    const val RETENTION_DELIVERED_DAYS = 30L
    const val RETENTION_LOGS_DAYS      = 30L

    // Notificación del foreground service
    const val NOTIFICATION_CHANNEL_ID    = "smstosms_channel"
    const val NOTIFICATION_CHANNEL_NAME  = "SMStoSMS"
    const val NOTIFICATION_ID_FOREGROUND = 1001

    // FileProvider authority (para exportar el registro a un fichero compartible)
    const val FILE_PROVIDER_AUTHORITY = "com.capicua.smstosms.provider"
}
