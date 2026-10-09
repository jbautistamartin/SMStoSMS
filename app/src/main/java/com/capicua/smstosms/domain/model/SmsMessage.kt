// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

import java.time.Instant

/**
 * Modelo de dominio para un SMS entrante.
 * Representación pura en Kotlin, sin dependencias de Room ni ningún framework.
 *
 * Solo describe **lo que llegó** y si ya se ha tramitado. El resultado de cada envío
 * (intentos, errores, fecha de confirmación) vive en los [Reenvio] asociados, porque un SMS
 * puede acabar en varios destinos y cada uno tiene su propia suerte.
 */
data class SmsMessage(
    /** UUID generado en el momento de la recepción. */
    val id: String,

    /** Número de teléfono del remitente tal como llega en la PDU, sin normalizar. */
    val telefono: String,

    /** Texto completo del SMS, con los fragmentos multipart ya concatenados. */
    val mensaje: String,

    /** Instante en que el centro de mensajería procesó el SMS. */
    val fechaRecepcion: Instant,

    /** Situación respecto a la evaluación de reglas. */
    val estado: EstadoSms = EstadoSms.PENDIENTE,

    /** Motivo del descarte cuando [estado] es [EstadoSms.DESCARTADO]. Null en el resto. */
    val motivoDescarte: String? = null
)
