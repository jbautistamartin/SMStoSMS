// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.domain.model.SmsMessage
import com.capicua.smstosms.domain.model.SmsStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [SmsRepository].
 *
 * NOTE: These are placeholder tests. Full integration tests with an in-memory
 * Room database will be implemented in the next phase.
 */
class SmsRepositoryTest {

    private fun buildTestSms(
        id: String = UUID.randomUUID().toString(),
        sender: String = "+1234567890",
        body: String = "Test message",
        status: SmsStatus = SmsStatus.PENDING
    ): SmsMessage = SmsMessage(
        id = id,
        sender = sender,
        body = body,
        receivedAt = Instant.now(),
        status = status,
        retryCount = 0,
        createdAt = Instant.now()
    )

    @Test
    fun `SmsMessage domain model holds correct data`() {
        val sms = buildTestSms(sender = "+34600000000", body = "Hello SMStoSMS")
        assertEquals("+34600000000", sms.sender)
        assertEquals("Hello SMStoSMS", sms.body)
        assertEquals(SmsStatus.PENDING, sms.status)
        assertEquals(0, sms.retryCount)
        assertNotNull(sms.id)
    }

    @Test
    fun `SmsMessage status transitions are valid enum values`() {
        SmsStatus.entries.forEach { status ->
            val sms = buildTestSms(status = status)
            assertEquals(status, sms.status)
        }
    }
}