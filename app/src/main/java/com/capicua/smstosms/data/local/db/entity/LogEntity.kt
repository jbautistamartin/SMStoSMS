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
 * Entidad Room para la tabla de logs de la aplicación.
 * Índice en [smsId] para consultas rápidas por mensaje.
 * Índice en [timestamp] para ordenar y purgar entradas antiguas.
 */
@Entity(
    tableName = "log_entries",
    indices = [
        Index(value = ["sms_id"]),
        Index(value = ["timestamp"])
    ]
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "tipo")
    val tipo: String,

    @ColumnInfo(name = "sms_id")
    val smsId: String? = null,

    @ColumnInfo(name = "detalle")
    val detalle: String,

    @ColumnInfo(name = "codigo_http")
    val codigoHttp: Int? = null,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)