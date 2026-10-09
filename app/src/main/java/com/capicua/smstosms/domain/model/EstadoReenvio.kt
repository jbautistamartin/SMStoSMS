// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

/** Estado del envío de un [Reenvio] a su número destino. */
enum class EstadoReenvio {
    /** Creado y pendiente de enviar, o con un intento fallido que se reintentará. */
    PENDIENTE,

    /** Todas las partes del SMS confirmaron `RESULT_OK` en su `sentIntent`. */
    ENVIADO,

    /** Fallo permanente: no se volverá a intentar. */
    FALLIDO
}
