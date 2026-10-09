// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.capicua.smstosms.data.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Envía un SMS y espera la confirmación del operador.
 *
 * ## Por qué hay que esperar
 * [SmsManager.sendMultipartTextMessage] retorna inmediatamente, antes de que el mensaje haya
 * salido del dispositivo. El resultado llega después, como un broadcast dirigido al
 * `PendingIntent` que se pasa por cada parte. Si el worker no esperase ese broadcast, todo
 * parecería enviado y los fallos se perderían en silencio: el reenvío quedaría marcado como
 * correcto sin que nadie hubiera recibido nada.
 *
 * Por eso [enviar] suspende hasta que todas las partes han reportado, o hasta que se agota
 * [AppConfig.timeoutEnvioSegundos].
 *
 * ## Mensajes largos
 * Un SMS de más de 160 caracteres GSM-7 se fragmenta. [SmsManager.divideMessage] hace el corte
 * respetando la codificación, y cada fragmento se tarifica y se confirma por separado. Si una
 * parte falla y otra no, el reenvío se considera fallido **completo**: se reintenta entero,
 * aceptando que el destino pueda ver duplicada la parte que sí llegó. La alternativa —dar por
 * bueno un mensaje truncado— es peor.
 *
 * ## Seguridad del receptor
 * El broadcast lo emite el sistema en nombre de la propia aplicación, a través del
 * `PendingIntent`, así que el receptor se registra como `RECEIVER_NOT_EXPORTED`: ninguna otra
 * aplicación puede falsificar una confirmación de envío. La acción lleva un UUID por envío, de
 * modo que dos reenvíos simultáneos no se confunden sus respuestas.
 */
@Singleton
class SmsSender @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Envía [texto] a [destino] y espera confirmación.
     *
     * @param subscriptionId SIM con la que enviar, o [AppConfig.SIM_POR_DEFECTO] para dejar
     *                       que el sistema use la predeterminada.
     */
    suspend fun enviar(
        destino: String,
        texto: String,
        subscriptionId: Int = AppConfig.SIM_POR_DEFECTO,
        timeoutSegundos: Int = 60
    ): ResultadoEnvio {

        if (!tienePermisoEnviar()) {
            return ResultadoEnvio.ErrorPermanente("falta el permiso SEND_SMS")
        }
        if (destino.isBlank()) {
            return ResultadoEnvio.ErrorPermanente("el destino está vacío")
        }
        if (texto.isEmpty()) {
            return ResultadoEnvio.ErrorPermanente("el texto a enviar está vacío")
        }

        val manager = obtenerSmsManager(subscriptionId)
            ?: return ResultadoEnvio.ErrorPermanente("SmsManager no disponible en este dispositivo")

        val partes = try {
            manager.divideMessage(texto) ?: arrayListOf(texto)
        } catch (e: Exception) {
            Timber.e(e, "SmsSender: no se pudo dividir el mensaje")
            arrayListOf(texto)
        }

        Timber.d("SmsSender: enviando a '$destino' en ${partes.size} parte(s)")

        val timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSegundos.toLong())

        return withTimeoutOrNull(timeoutMs) {
            enviarYEsperar(manager, destino, ArrayList(partes))
        } ?: ResultadoEnvio.ErrorTransitorio(
            "sin confirmación del operador tras $timeoutSegundos s"
        )
    }

    /** Número de partes en que se dividiría [texto], para avisar del coste antes de enviar. */
    fun contarPartes(texto: String): Int {
        if (texto.isEmpty()) return 0
        val manager = obtenerSmsManager(AppConfig.SIM_POR_DEFECTO) ?: return 1
        return try {
            manager.divideMessage(texto)?.size ?: 1
        } catch (e: Exception) {
            1
        }
    }

    /** true si la aplicación tiene concedido el permiso de envío en tiempo de ejecución. */
    fun tienePermisoEnviar(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    // ── Interno ───────────────────────────────────────────────────────────────

    /**
     * Registra el receptor de confirmaciones, lanza el envío y suspende hasta que todas las
     * partes han respondido.
     */
    private suspend fun enviarYEsperar(
        manager: SmsManager,
        destino: String,
        partes: ArrayList<String>
    ): ResultadoEnvio = suspendCancellableCoroutine { continuacion ->

        val accion = "$ACCION_SMS_ENVIADO.${UUID.randomUUID()}"
        val resultados = mutableListOf<Int>()
        var receptorRegistrado = false

        val receptor = object : BroadcastReceiver() {
            override fun onReceive(contextoRecibido: Context?, intent: Intent?) {
                val completo: Boolean
                synchronized(resultados) {
                    if (!continuacion.isActive) return
                    resultados += resultCode
                    completo = resultados.size >= partes.size
                }
                if (!completo) return

                desregistrar(this)
                continuacion.resume(interpretar(resultados.toList(), partes.size))
            }
        }

        fun limpiar() {
            if (receptorRegistrado) {
                desregistrar(receptor)
                receptorRegistrado = false
            }
        }

        try {
            // NOT_EXPORTED: el broadcast lo emite el sistema en nombre de esta app a través
            // del PendingIntent, así que nadie más puede falsificar una confirmación.
            ContextCompat.registerReceiver(
                context,
                receptor,
                IntentFilter(accion),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receptorRegistrado = true

            continuacion.invokeOnCancellation { limpiar() }

            // Un PendingIntent por parte. El requestCode los diferencia: con la misma acción
            // y el mismo código, getBroadcast devolvería el mismo PendingIntent para todas.
            val confirmaciones = ArrayList<PendingIntent>(partes.size)
            for (indice in partes.indices) {
                confirmaciones += PendingIntent.getBroadcast(
                    context,
                    indice,
                    Intent(accion).setPackage(context.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            manager.sendMultipartTextMessage(destino, null, partes, confirmaciones, null)

        } catch (e: IllegalArgumentException) {
            // divideMessage devolvió algo inválido, o el destino no es un número aceptable.
            limpiar()
            if (continuacion.isActive) {
                continuacion.resume(ResultadoEnvio.ErrorPermanente("destino no válido: ${e.message}"))
            }
        } catch (e: SecurityException) {
            limpiar()
            if (continuacion.isActive) {
                continuacion.resume(ResultadoEnvio.ErrorPermanente("permiso denegado: ${e.message}"))
            }
        } catch (e: Exception) {
            limpiar()
            if (continuacion.isActive) {
                continuacion.resume(
                    ResultadoEnvio.ErrorTransitorio("${e.javaClass.simpleName}: ${e.message}")
                )
            }
        }
    }

    private fun desregistrar(receptor: BroadcastReceiver) {
        // Puede lanzar si el receptor ya se había desregistrado (carrera entre el timeout y
        // la última confirmación). No es un error que deba propagarse.
        runCatching { context.unregisterReceiver(receptor) }
    }

    /**
     * Traduce los códigos de resultado de todas las partes en un único [ResultadoEnvio].
     *
     * | Código                        | Interpretación |
     * |-------------------------------|----------------|
     * | `RESULT_OK` en todas          | Enviado |
     * | `RESULT_ERROR_NO_SERVICE`     | Transitorio — sin cobertura |
     * | `RESULT_ERROR_RADIO_OFF`      | Transitorio — radio apagada o modo avión |
     * | `RESULT_ERROR_GENERIC_FAILURE`| Transitorio — el operador no concreta el motivo |
     * | `RESULT_ERROR_NULL_PDU`       | Permanente — el mensaje es inválido |
     * | Cualquier otro                | Transitorio, por prudencia |
     */
    private fun interpretar(resultados: List<Int>, partesEsperadas: Int): ResultadoEnvio {
        val fallos = resultados.filter { it != Activity.RESULT_OK }

        if (fallos.isEmpty()) {
            return ResultadoEnvio.Enviado(partesEsperadas)
        }

        val detalle = if (partesEsperadas > 1) {
            " (${fallos.size} de $partesEsperadas partes)"
        } else {
            ""
        }

        return when {
            fallos.any { it == SmsManager.RESULT_ERROR_NULL_PDU } ->
                ResultadoEnvio.ErrorPermanente("PDU nula$detalle")

            fallos.any { it == SmsManager.RESULT_ERROR_NO_SERVICE } ->
                ResultadoEnvio.ErrorTransitorio("sin servicio de red$detalle")

            fallos.any { it == SmsManager.RESULT_ERROR_RADIO_OFF } ->
                ResultadoEnvio.ErrorTransitorio("radio apagada$detalle")

            fallos.any { it == SmsManager.RESULT_ERROR_GENERIC_FAILURE } ->
                ResultadoEnvio.ErrorTransitorio("fallo genérico del operador$detalle")

            else ->
                ResultadoEnvio.ErrorTransitorio("código de error ${fallos.first()}$detalle")
        }
    }

    /**
     * Obtiene el [SmsManager] adecuado.
     *
     * A partir de API 31 se usa el servicio del sistema y, si hay una SIM elegida,
     * `createForSubscriptionId`. Por debajo hay que recurrir a los métodos estáticos, que están
     * marcados como obsoletos pero son los únicos disponibles con `minSdk 26`.
     */
    @Suppress("DEPRECATION")
    private fun obtenerSmsManager(subscriptionId: Int): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != AppConfig.SIM_POR_DEFECTO) {
                base?.createForSubscriptionId(subscriptionId)
            } else {
                base
            }
        } else {
            if (subscriptionId != AppConfig.SIM_POR_DEFECTO) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "SmsSender: no se pudo obtener SmsManager (subId=$subscriptionId)")
        null
    }

    companion object {
        /** Prefijo de la acción del broadcast de confirmación. Se le añade un UUID por envío. */
        private const val ACCION_SMS_ENVIADO = "com.capicua.smstosms.SMS_ENVIADO"
    }
}
