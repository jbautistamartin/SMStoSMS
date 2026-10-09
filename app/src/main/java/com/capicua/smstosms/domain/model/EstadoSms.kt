// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

/**
 * Estado de un SMS entrante respecto a la evaluación de reglas.
 *
 * Es un estado de **tramitación**, no de envío: el resultado de cada envío vive en los
 * [Reenvio] que la evaluación generó, porque un mismo SMS puede tener varios destinos.
 */
enum class EstadoSms {
    /** Recibido y persistido, pero las reglas todavía no se han evaluado. */
    PENDIENTE,

    /** Evaluado: al menos una regla casó y se crearon sus reenvíos. */
    PROCESADO,

    /** Evaluado y ninguna regla casó. No hay nada que reenviar; no es un error. */
    SIN_REGLA,

    /** Bloqueado por una protección: bucle de reenvío detectado o límite de frecuencia. */
    DESCARTADO
}
