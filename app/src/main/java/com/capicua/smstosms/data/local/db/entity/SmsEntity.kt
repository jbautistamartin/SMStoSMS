// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entidad Room que representa un SMS recibido en la base de datos local.
 *
 * Tabla: [sms]
 *
 * Diseño de columnas:
 * - [id]              UUID generado en recepción. PrimaryKey y clave de idempotencia.
 * - [telefono]        Número remitente (formato E.164 cuando sea posible).
 * - [mensaje]         Cuerpo completo del SMS.
 * - [fechaRecepcion]  Epoch milisegundos — instante de recepción en el dispositivo.
 * - [enviado]         false=pendiente de enviar, true=confirmado por la API.
 * - [fechaEnvio]      Epoch milisegundos — instante de confirmación HTTP 2xx. Null si no enviado.
 * - [intentos]        Contador de intentos de envío acumulados.
 * - [ultimoError]     Descripción del último error. Null si no hubo error o fue enviado con éxito.
 */
@Entity(tableName = "sms")
data class SmsEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "telefono")
    val telefono: String,

    @ColumnInfo(name = "mensaje")
    val mensaje: String,

    @ColumnInfo(name = "fecha_recepcion")
    val fechaRecepcion: Long,

    @ColumnInfo(name = "enviado", defaultValue = "0")
    val enviado: Boolean = false,

    @ColumnInfo(name = "fecha_envio")
    val fechaEnvio: Long? = null,

    @ColumnInfo(name = "intentos", defaultValue = "0")
    val intentos: Int = 0,

    @ColumnInfo(name = "ultimo_error")
    val ultimoError: String? = null
)