// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

/**
 * Un SMS recibido junto con lo que se hizo con él.
 *
 * Es lo que la pantalla de inicio necesita por fila: el mensaje que llegó, y el resultado de
 * cada reenvío que generó. Un SMS puede no tener ninguno —porque ninguna regla casó o porque
 * una protección lo descartó— y puede tener varios.
 */
data class SmsConReenvios(
    val sms: SmsMessage,
    val reenvios: List<Reenvio> = emptyList()
) {
    /** Reenvíos ya confirmados por el operador. */
    val enviados: Int get() = reenvios.count { it.estado == EstadoReenvio.ENVIADO }

    /** Reenvíos que todavía se están intentando. */
    val pendientes: Int get() = reenvios.count { it.estado == EstadoReenvio.PENDIENTE }

    /** Reenvíos abandonados tras un fallo permanente o agotar los reintentos. */
    val fallidos: Int get() = reenvios.count { it.estado == EstadoReenvio.FALLIDO }

    /** Nombre de la regla que decidió el primer reenvío, o null si no hubo ninguno. */
    val reglaAplicada: String? get() = reenvios.firstOrNull()?.nombreRegla

    /**
     * Resumen del estado para pintar un único indicador en la fila.
     *
     * El orden de las comprobaciones importa: lo que hay que destacar es el problema, así que
     * un fallo gana a un envío correcto aunque el fallo sea de un solo destino.
     */
    val resumen: ResumenSms
        get() = when {
            sms.estado == EstadoSms.DESCARTADO -> ResumenSms.DESCARTADO
            sms.estado == EstadoSms.SIN_REGLA -> ResumenSms.SIN_REGLA
            sms.estado == EstadoSms.PENDIENTE -> ResumenSms.PENDIENTE
            fallidos > 0 -> ResumenSms.FALLIDO
            pendientes > 0 -> ResumenSms.ENVIANDO
            enviados > 0 -> ResumenSms.REENVIADO
            else -> ResumenSms.PENDIENTE
        }
}

/** Estado agregado de un SMS y sus reenvíos, tal como se muestra en la lista. */
enum class ResumenSms {
    /** Recibido, aún sin evaluar. */
    PENDIENTE,

    /** Evaluado y con reenvíos en curso. */
    ENVIANDO,

    /** Todos sus reenvíos se confirmaron. */
    REENVIADO,

    /** Al menos un reenvío se dio por perdido. */
    FALLIDO,

    /** Ninguna regla casó. No es un error. */
    SIN_REGLA,

    /** Bloqueado por una protección antibucle o de frecuencia. */
    DESCARTADO
}
