// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.domain.model.SmsMessage
import com.capicua.smstosms.domain.usecase.GetSmsListUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getSmsListUseCase: GetSmsListUseCase,
    private val smsRepository: SmsRepository
) : ViewModel() {

    /** Lista reactiva de todos los SMS, más recientes primero. */
    val smsList: Flow<List<SmsMessage>> = getSmsListUseCase()

    fun limpiarTodos() {
        viewModelScope.launch {
            smsRepository.limpiarTodos()
        }
    }
}
