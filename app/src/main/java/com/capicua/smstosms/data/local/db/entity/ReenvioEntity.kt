// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad Room para la tabla de reenvíos: un envío a un número destino concreto.
 *
 * Tabla: [reenvios]
 *
 * Es la unidad de despacho. Un SMS entrante puede generar varias filas si varias reglas casan
 * con él, y cada una lleva su propio [estado], [intentos] y [ultimoError].
 *
 * ## Claves ajenas
 * - [smsId] → `sms.id` con `CASCADE`: borrar un SMS se lleva sus reenvíos por delante.
 * - [reglaId] → `reglas.id` con `SET_NULL`: borrar una regla no destruye el historial de lo
 *   que ya se envió. Por eso [nombreRegla] guarda una copia del nombre.
 *
 * [textoFinal] se congela al evaluar las reglas: editar la plantilla después no altera lo que
 * ya estaba encolado.
 */
@Entity(
    tableName = "reenvios",
    foreignKeys = [
        ForeignKey(
            entity = SmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["sms_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ReglaEntity::class,
            parentColumns = ["id"],
            childColumns = ["regla_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sms_id"]),
        Index(value = ["regla_id"]),
        Index(value = ["estado"])
    ]
)
data class ReenvioEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "sms_id")
    val smsId: String,

    @ColumnInfo(name = "regla_id")
    val reglaId: Long? = null,

    @ColumnInfo(name = "nombre_regla")
    val nombreRegla: String,

    @ColumnInfo(name = "destino")
    val destino: String,

    @ColumnInfo(name = "texto_final")
    val textoFinal: String,

    /** Nombre de la constante de `EstadoReenvio`. */
    @ColumnInfo(name = "estado")
    val estado: String,

    @ColumnInfo(name = "intentos", defaultValue = "0")
    val intentos: Int = 0,

    @ColumnInfo(name = "ultimo_error")
    val ultimoError: String? = null,

    /** Epoch milisegundos — instante en que se creó el reenvío. */
    @ColumnInfo(name = "fecha_creacion")
    val fechaCreacion: Long,

    /** Epoch milisegundos — instante en que se confirmó el envío. Null si no se envió. */
    @ColumnInfo(name = "fecha_envio")
    val fechaEnvio: Long? = null,

    @ColumnInfo(name = "partes", defaultValue = "1")
    val partes: Int = 1
)
