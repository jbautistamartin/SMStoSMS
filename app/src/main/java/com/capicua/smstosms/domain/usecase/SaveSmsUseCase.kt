// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.usecase

import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.domain.model.SmsMessage
import javax.inject.Inject

/**
 * Persiste un SMS nuevo en la base de datos local y encola su envío a la API.
 * Es la única entrada de mensajes al sistema: primero guarda, luego despacha.
 */
class SaveSmsUseCase @Inject constructor(
    private val repository: SmsRepository
) {
    suspend operator fun invoke(sms: SmsMessage) {
        repository.guardar(sms)
        repository.encolarEnvio(sms.id)
    }
}