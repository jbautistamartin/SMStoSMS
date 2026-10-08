// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

import java.time.Instant

/**
 * Modelo de dominio para una entrada de log.
 */
data class LogEntry(
    val id: Long = 0,

    /** Categoría del evento */
    val tipo: LogTipo,

    /** ID del SMS relacionado, null para eventos de sistema */
    val smsId: String? = null,

    /** Descripción legible del evento */
    val detalle: String,

    /** Código HTTP de la respuesta, null si no aplica */
    val codigoHttp: Int? = null,

    /** Instante en que ocurrió el evento */
    val timestamp: Instant
)