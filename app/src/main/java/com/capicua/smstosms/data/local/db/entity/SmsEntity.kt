// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad Room que representa un SMS recibido.
 *
 * Tabla: [sms]
 *
 * Es el outbox: la fila se escribe **antes** de intentar cualquier reenvío, de modo que ningún
 * mensaje se pierde aunque el proceso muera justo después de recibirlo.
 *
 * Solo guarda lo que llegó y su estado de tramitación. Los contadores de intentos, los errores
 * y las fechas de confirmación viven en la tabla `reenvios`, una fila por destino: un mismo SMS
 * puede reenviarse a varios números y cada envío tiene su propio resultado.
 *
 * - [id]              UUID generado en recepción.
 * - [telefono]        Número remitente tal como llega en la PDU. Puede ser alfanumérico.
 * - [mensaje]         Cuerpo completo, con los fragmentos multipart concatenados.
 * - [fechaRecepcion]  Epoch milisegundos, según el centro de mensajería.
 * - [estado]          Nombre de la constante de `EstadoSms`. Indexado: el rescate de huérfanos
 *                     consulta los pendientes en cada pasada.
 * - [motivoDescarte]  Por qué se descartó, cuando el estado es DESCARTADO.
 */
@Entity(
    tableName = "sms",
    indices = [
        Index(value = ["estado"]),
        Index(value = ["fecha_recepcion"])
    ]
)
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

    @ColumnInfo(name = "estado")
    val estado: String,

    @ColumnInfo(name = "motivo_descarte")
    val motivoDescarte: String? = null
)
