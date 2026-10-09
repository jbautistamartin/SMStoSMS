// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

/** Categoría de un evento del registro de auditoría. */
enum class LogTipo {
    /** SMS recibido por la SIM y persistido en la base de datos local. */
    SMS_RECIBIDO,

    /** Una regla casó con el SMS y se creó su reenvío. */
    REGLA_APLICADA,

    /** Reenvío confirmado por el operador: todas las partes dieron `RESULT_OK`. */
    SMS_REENVIADO,

    /** Ninguna regla casó con el SMS. No hay nada que reenviar. */
    SIN_REGLA,

    /** Reenvío bloqueado por una protección: bucle detectado o límite de frecuencia. */
    BUCLE_EVITADO,

    /** Error transitorio o permanente al intentar reenviar. */
    ERROR,

    /** Evento del sistema: inicio de la app, reinicio del dispositivo, limpieza periódica. */
    SISTEMA
}
