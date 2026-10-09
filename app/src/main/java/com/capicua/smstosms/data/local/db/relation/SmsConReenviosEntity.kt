// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.capicua.smstosms.data.local.db.entity.ReenvioEntity
import com.capicua.smstosms.data.local.db.entity.SmsEntity

/**
 * Un SMS entrante junto con todos sus reenvíos, resuelto por Room en una sola consulta.
 *
 * La pantalla de inicio necesita las dos cosas a la vez: el mensaje que llegó y qué se hizo con
 * él. Sin esta relación habría que combinar dos flujos en el ViewModel y emparejarlos a mano,
 * con el riesgo de pintar un SMS con los reenvíos de otro durante una actualización.
 */
data class SmsConReenviosEntity(
    @Embedded
    val sms: SmsEntity,

    @Relation(parentColumn = "id", entityColumn = "sms_id")
    val reenvios: List<ReenvioEntity>
)
