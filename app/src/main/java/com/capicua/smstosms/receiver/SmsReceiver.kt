// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage as TelephonySms
import com.capicua.smstosms.domain.model.SmsMessage
import com.capicua.smstosms.service.SmsIngestionService
import timber.log.Timber
import java.time.Instant
import java.util.UUID

/**
 * Receptor del broadcast del sistema para SMS entrantes.
 *
 * ## Ciclo de vida
 * [onReceive] se ejecuta en el **hilo principal** con un timeout máximo de ~10 s.
 * Por ello, toda la lógica aquí debe ser rápida y síncrona:
 *   1. Parsear PDUs (microsegundos).
 *   2. Construir el modelo de dominio.
 *   3. Delegar la persistencia al [SmsIngestionService] (Foreground Service).
 *
 * ## Multipart SMS (SMS largos)
 * Un SMS de más de 160 caracteres se fragmenta en varias PDUs, pero Android garantiza
 * (desde API 19) que todos los fragmentos llegan juntos en **un único broadcast**.
 * [Telephony.Sms.Intents.getMessagesFromIntent] los extrae ya ordenados y los
 * concatenamos en orden para obtener el texto completo.
 *
 * ## Seguridad
 * El receiver declara `android:permission="android.permission.BROADCAST_SMS"` en el
 * Manifest, de modo que solo el proceso del sistema (radio/teléfono) puede dispararlo.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Verificar acción — protección extra ante broadcasts no deseados.
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Timber.w("SmsReceiver: acción inesperada '${intent.action}', ignorado")
            return
        }

        // ── 1. Extraer todas las partes del SMS (PDUs) ────────────────────────
        //
        // getMessagesFromIntent() maneja internamente tanto formato 3GPP (GSM)
        // como 3GPP2 (CDMA) y devuelve los fragmentos ordenados por índice.
        val partes: Array<TelephonySms> =
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.takeIf { it.isNotEmpty() }
                ?: run {
                    Timber.w("SmsReceiver: intent sin PDUs, ignorado")
                    return
                }

        Timber.d("SmsReceiver: recibidos ${partes.size} fragmento(s)")

        // ── 2. Número remitente ───────────────────────────────────────────────
        //
        // displayOriginatingAddress devuelve el número formateado (p.ej. "+34600000000").
        // Como fallback usamos originatingAddress (sin formato internacional).
        // Leemos del primer fragmento; todos los fragmentos tienen el mismo remitente.
        val telefono: String = partes.first().displayOriginatingAddress
            ?.takeIf { it.isNotBlank() }
            ?: partes.first().originatingAddress
                ?.takeIf { it.isNotBlank() }
            ?: "desconocido".also {
                Timber.w("SmsReceiver: no se pudo obtener el número remitente")
            }

        // ── 3. Fecha y hora de recepción ──────────────────────────────────────
        //
        // timestampMillis viene de la PDU del centro de mensajería (SMSC).
        // Es el instante en que el SMSC procesó el mensaje, que puede diferir
        // ligeramente del instante de recepción en el dispositivo.
        val timestampMs: Long = partes.first().timestampMillis

        // ── 4. Texto completo (multipart) ─────────────────────────────────────
        //
        // Concatenamos los cuerpos de todos los fragmentos en orden.
        // Para SMS simples (1 PDU), esto devuelve simplemente messageBody.
        // Para SMS largos (N PDUs), reconstruimos el texto completo sin separadores.
        val mensaje: String = partes
            .joinToString(separator = "") { parte -> parte.messageBody.orEmpty() }
            .also { texto ->
                if (texto.isBlank()) {
                    Timber.w("SmsReceiver: mensaje vacío de '$telefono', ignorado")
                    return
                }
            }

        Timber.i(
            "SmsReceiver: SMS de '$telefono' | ${partes.size} parte(s) | " +
                "${mensaje.length} chars | timestamp=$timestampMs"
        )

        // ── 5. Crear modelo de dominio ────────────────────────────────────────
        //
        // El UUID es la clave de idempotencia: si el Worker reintenta el envío HTTP,
        // la API corporativa puede detectar y descartar duplicados usando este ID.
        val sms = SmsMessage(
            id             = UUID.randomUUID().toString(),
            telefono       = telefono,
            mensaje        = mensaje,
            fechaRecepcion = Instant.ofEpochMilli(timestampMs)
        )

        // ── 6. Delegar persistencia al Foreground Service ─────────────────────
        //
        // startForegroundService() garantiza que el proceso permanezca vivo hasta
        // que el servicio llame startForeground() (obligatorio en < 5 s).
        val serviceIntent = SmsIngestionService.crearIntent(context, sms)
        context.startForegroundService(serviceIntent)
    }
}