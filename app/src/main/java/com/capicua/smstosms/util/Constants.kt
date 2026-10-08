// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.util

object Constants {

    // WorkManager — claves de input data
    const val WORKER_KEY_SMS_ID = "sms_id"

    // WorkManager — nombres únicos de trabajo
    const val WORKER_DISPATCH_TAG       = "sms_dispatch"
    const val WORKER_HEALTH_TAG         = "health_monitor"
    const val WORKER_ORPHAN_RESCUE_TAG  = "orphan_rescue"

    // WorkManager — política de reintentos (overridable desde AppConfig)
    const val WORKER_INITIAL_BACKOFF_SECONDS = 30L
    const val WORKER_MAX_RETRIES             = 10

    // HealthMonitor (limpieza periódica, mínimo WorkManager = 15 min)
    const val HEALTH_MONITOR_INTERVAL_MINUTES = 15L

    // OrphanRescue (re-encola SMS atascados cada N segundos mediante auto-encadenamiento)
    const val ORPHAN_RESCUE_DELAY_SECONDS = 30L

    // Retención de datos
    const val RETENTION_DELIVERED_DAYS = 30L
    const val RETENTION_LOGS_DAYS      = 30L

    // Notificación foreground service
    const val NOTIFICATION_CHANNEL_ID   = "smstosms_channel"
    const val NOTIFICATION_CHANNEL_NAME = "SMStoSMS"
    const val NOTIFICATION_ID_FOREGROUND = 1001

    // FileProvider authority (para exportar logs)
    const val FILE_PROVIDER_AUTHORITY = "com.capicua.smstosms.provider"
}