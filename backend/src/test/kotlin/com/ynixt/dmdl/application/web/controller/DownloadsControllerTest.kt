package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.model.SpeedSample
import com.ynixt.dmdl.domain.service.DownloadService
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadsControllerTest {
    private val downloads = mockk<DownloadService>()
    private val controller =
        DownloadsController(downloads, mockk(), mockk(), mockk(), mockk(), mockk())

    @AfterTest
    fun close() = controller.closeStreams()

    @Test
    fun `speed history returns samples oldest first`() {
        val id = UUID.randomUUID()
        val samples =
            listOf(
                SpeedSample(Instant.parse("2026-11-14T14:41:00Z"), 4_000_000, 8),
                SpeedSample(Instant.parse("2026-11-14T14:41:30Z"), 1_000_000, 1),
            )
        every { downloads.speedHistory(id) } returns samples
        val response = controller.speedHistory(id)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(samples, response.body)
    }

    @Test
    fun `speed history of a download without samples is an empty list`() {
        val id = UUID.randomUUID()
        every { downloads.speedHistory(id) } returns emptyList()
        assertEquals(emptyList(), controller.speedHistory(id).body)
    }

    @Test
    fun `speed history of an unknown download is not found`() {
        val id = UUID.randomUUID()
        every { downloads.speedHistory(id) } returns null
        val response = controller.speedHistory(id)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNull(response.body)
    }
}
