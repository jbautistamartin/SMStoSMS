// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

import java.time.Instant

/**
 * Modelo de dominio para una entrada del registro de auditoría.
 */
data class LogEntry(
    val id: Long = 0,

    /** Categoría del evento. */
    val tipo: LogTipo,

    /** Id del SMS entrante relacionado. Null para eventos de sistema. */
    val smsId: String? = null,

    /** Id del reenvío relacionado, cuando el evento afecta a un envío concreto. */
    val reenvioId: String? = null,

    /** Número destino implicado, para poder leer el log sin cruzar tablas. */
    val destino: String? = null,

    /** Descripción legible del evento. */
    val detalle: String,

    /** Instante en que ocurrió el evento. */
    val timestamp: Instant
)
