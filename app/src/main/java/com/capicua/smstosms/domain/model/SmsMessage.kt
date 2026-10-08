// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

import java.time.Instant

/**
 * Modelo de dominio para un SMS entrante.
 * Representación pura en Kotlin, sin dependencias de Room ni ningún framework.
 */
data class SmsMessage(
    /** UUID generado en el momento de la recepción. Clave de idempotencia para la API. */
    val id: String,

    /** Número de teléfono del remitente tal como llega en la PDU. */
    val telefono: String,

    /** Texto completo del SMS. */
    val mensaje: String,

    /** Instante en que el dispositivo recibió el SMS. */
    val fechaRecepcion: Instant,

    /** true cuando la API corporativa confirmó la recepción con HTTP 2xx. */
    val enviado: Boolean = false,

    /** Instante en que se obtuvo la confirmación de envío. Null si aún no se ha enviado. */
    val fechaEnvio: Instant? = null,

    /** Número acumulado de intentos de envío realizados. */
    val intentos: Int = 0,

    /** Descripción del último error ocurrido. Null si nunca hubo error o ya fue enviado. */
    val ultimoError: String? = null
)