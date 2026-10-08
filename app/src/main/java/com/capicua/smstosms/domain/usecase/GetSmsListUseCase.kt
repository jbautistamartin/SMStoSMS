// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.usecase

import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.domain.model.SmsConReenvios
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Devuelve un flujo reactivo con todos los SMS almacenados y sus reenvíos, más recientes
 * primero. Cada elemento lleva el mensaje que llegó y lo que se hizo con él.
 */
class GetSmsListUseCase @Inject constructor(
    private val repository: SmsRepository
) {
    operator fun invoke(): Flow<List<SmsConReenvios>> = repository.observarTodosConReenvios()
}