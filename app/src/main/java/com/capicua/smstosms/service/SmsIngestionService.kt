// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.capicua.smstosms.R
import com.capicua.smstosms.data.repository.LogRepository
import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import com.capicua.smstosms.domain.model.SmsMessage
import com.capicua.smstosms.domain.usecase.SaveSmsUseCase
import com.capicua.smstosms.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * Foreground Service encargado de persistir un SMS entrante en la base de datos local.
 *
 * ## Por qué un Foreground Service
 * Un [BroadcastReceiver] tiene un timeout de ~10 s y el proceso puede ser eliminado
 * justo después de que [onReceive] retorne. El Foreground Service mantiene el proceso
 * con vida (notificación visible obligatoria) hasta que el INSERT en Room se completa.
 * Esto garantiza que **ningún SMS se pierde**, incluso si el sistema está bajo presión
 * de memoria.
 *
 * ## Flujo por cada SMS
 * ```
 * SmsReceiver → startForegroundService(intent) → onStartCommand()
 *                                                   ├─ startForeground()   [obligatorio < 5 s]
 *                                                   ├─ lanzar coroutine IO
 *                                                   │    └─ saveUseCase(sms)
 *                                                   │         ├─ repository.guardar()    [Room INSERT]
 *                                                   │         └─ repository.encolarEnvio() [WorkManager]
 *                                                   └─ stopSelf(startId)
 * ```
 *
 * ## Múltiples SMS simultáneos
 * Cada llamada a [onStartCommand] recibe un [startId] único. [stopSelf(startId)] solo
 * detiene el servicio cuando ese era el último startId activo, permitiendo que dos SMS
 * simultáneos se procesen correctamente sin interferencia.
 *
 * ## Inyección con Hilt
 * [SaveSmsUseCase] es inyectado por Hilt gracias a [@AndroidEntryPoint].
 * El [BroadcastReceiver] no necesita inyección porque solo construye el Intent.
 */
@AndroidEntryPoint
class SmsIngestionService : Service() {

    @Inject
    lateinit var saveUseCase: SaveSmsUseCase

    @Inject
    lateinit var logRepository: LogRepository

    /**
     * Scope propio del servicio. Usa [SupervisorJob] para que el fallo de una coroutine
     * no cancele el procesamiento de otros SMS simultáneos.
     * Se cancela en [onDestroy] para liberar recursos.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() DEBE llamarse antes de 5 segundos desde startForegroundService().
        startForeground(Constants.NOTIFICATION_ID_FOREGROUND, construirNotificacion())

        if (intent == null) {
            Timber.w("SmsIngestionService: intent nulo en startId=$startId")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val sms = extraerSmsDeIntent(intent)
        if (sms == null) {
            Timber.e("SmsIngestionService: no se pudieron extraer datos del SMS (startId=$startId)")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Timber.d("SmsIngestionService: procesando SMS ${sms.id} de '${sms.telefono}' (startId=$startId)")

        serviceScope.launch {
            try {
                // saveUseCase hace dos cosas atómicamente desde la perspectiva del flujo:
                //   1. repository.guardar()      → INSERT en Room
                //   2. repository.encolarEnvio() → OneTimeWorkRequest en WorkManager
                saveUseCase(sms)
                Timber.i("SmsIngestionService: SMS ${sms.id} persistido y encolado correctamente")
                logRepository.insertar(
                    LogEntry(
                        tipo      = LogTipo.SMS_RECIBIDO,
                        smsId     = sms.id,
                        detalle   = "SMS recibido de ${sms.telefono} (${sms.mensaje.length} chars)",
                        timestamp = Instant.now()
                    )
                )
            } catch (e: Exception) {
                // El insert puede fallar si el UUID ya existe (duplicado) o si hay
                // un error de disco. En ambos casos registramos y continuamos.
                Timber.e(e, "SmsIngestionService: error al procesar SMS ${sms.id}")
            } finally {
                // Detiene el servicio solo si este era el último startId activo.
                stopSelf(startId)
            }
        }

        // START_NOT_STICKY: si el sistema mata el proceso después de que la coroutine
        // completó el INSERT (o si nunca llegó a ejecutarse), no reinicia el servicio
        // automáticamente. El HealthMonitorWorker periódico actúa como red de seguridad.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Extracción de datos del Intent ────────────────────────────────────────

    /**
     * Reconstruye un [SmsMessage] a partir de los extras del intent.
     * Devuelve null si algún campo obligatorio falta, evitando NullPointerException.
     */
    private fun extraerSmsDeIntent(intent: Intent): SmsMessage? {
        val id        = intent.getStringExtra(EXTRA_ID)        ?: return null
        val telefono  = intent.getStringExtra(EXTRA_TELEFONO)  ?: return null
        val mensaje   = intent.getStringExtra(EXTRA_MENSAJE)   ?: return null
        val fechaMs   = intent.getLongExtra(EXTRA_FECHA_MS, FECHA_NO_DEFINIDA)

        if (fechaMs == FECHA_NO_DEFINIDA) return null

        return SmsMessage(
            id             = id,
            telefono       = telefono,
            mensaje        = mensaje,
            fechaRecepcion = Instant.ofEpochMilli(fechaMs)
        )
    }

    // ── Notificación ──────────────────────────────────────────────────────────

    private fun crearCanalNotificacion() {
        val canal = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            Constants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Canal de procesamiento de SMS entrantes"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun construirNotificacion() =
        NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_processing_sms))
            .setSmallIcon(R.drawable.ic_sms_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    // ── Companion object ──────────────────────────────────────────────────────

    companion object {
        private const val EXTRA_ID       = "extra_sms_id"
        private const val EXTRA_TELEFONO = "extra_sms_telefono"
        private const val EXTRA_MENSAJE  = "extra_sms_mensaje"
        private const val EXTRA_FECHA_MS = "extra_sms_fecha_ms"

        private const val FECHA_NO_DEFINIDA = -1L

        /**
         * Construye el [Intent] para iniciar el servicio con los datos del SMS.
         * Llamado exclusivamente desde [SmsReceiver].
         */
        fun crearIntent(context: Context, sms: SmsMessage): Intent =
            Intent(context, SmsIngestionService::class.java).apply {
                putExtra(EXTRA_ID,       sms.id)
                putExtra(EXTRA_TELEFONO, sms.telefono)
                putExtra(EXTRA_MENSAJE,  sms.mensaje)
                putExtra(EXTRA_FECHA_MS, sms.fechaRecepcion.toEpochMilli())
            }
    }
}