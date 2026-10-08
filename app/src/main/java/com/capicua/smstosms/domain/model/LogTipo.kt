// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

enum class LogTipo {
    /** SMS recibido por la SIM y persistido en base de datos local */
    SMS_RECIBIDO,

    /** SMS enviado con éxito a la API corporativa (HTTP 2xx) */
    SMS_ENVIADO,

    /** Error transitorio o permanente al intentar enviar un SMS */
    ERROR,

    /** Evento del sistema: inicio de app, reboot, health check */
    SISTEMA
}