package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.WebhookGateway
import com.ynixt.dmdl.domain.model.DeliveryState
import com.ynixt.dmdl.domain.model.DeliveryTrigger
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookContext
import com.ynixt.dmdl.domain.model.WebhookDelivery
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookListInvalid
import com.ynixt.dmdl.domain.model.WebhookResponse
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.domain.repository.WebhookDeliveryStore
import com.ynixt.dmdl.domain.repository.WebhookStore
import com.ynixt.dmdl.resource.gateway.webhook.MustacheWebhookRenderer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebhookServiceImplTest {
    private val webhooks = mockk<WebhookStore>(relaxed = true)
    private val deliveries = mockk<WebhookDeliveryStore>(relaxed = true)
    private val downloads = mockk<DownloadStore>()
    private val sent = CopyOnWriteArrayList<RenderedWebhookRequest>()
    private var answer: (RenderedWebhookRequest) -> WebhookResponse = { WebhookResponse(204, null) }
    private val gateway =
        object : WebhookGateway {
            override fun send(request: RenderedWebhookRequest): WebhookResponse {
                sent += request
                return answer(request)
            }
        }
    private val service = WebhookServiceImpl(webhooks, deliveries, downloads, MustacheWebhookRenderer(), gateway)
    private val hook = Webhook(UUID.randomUUID(), "Hook", "POST", "https://example.com/{{event}}", events = setOf(WebhookEvent.COMPLETED))
    private val download = WebhookContext.sample(DownloadState.PAUSED)

    @AfterTest
    fun stop() = service.stop()

    private fun delivery(
        event: WebhookEvent?,
        webhookId: UUID? = hook.id,
    ) = WebhookDelivery(
        UUID.randomUUID(),
        download.id,
        webhookId,
        hook.name,
        if (event == null) DeliveryTrigger.MANUAL else DeliveryTrigger.AUTOMATIC,
        event?.key,
        DeliveryState.PENDING,
        createdAt = Instant.now(),
    )

    @Test
    fun `deliveries keep their recorded event and failures are recorded once without retry`() {
        val paused = delivery(WebhookEvent.PAUSED)
        val completed = delivery(WebhookEvent.COMPLETED)
        val manual = delivery(null)
        every { webhooks.find(hook.id) } returns hook
        every { downloads.find(download.id) } returns download
        every { deliveries.claim(any()) } returns 1
        answer = { if (it.url.endsWith("download.paused")) WebhookResponse(503, "KZ.WEBHOOK_HTTP_STATUS") else WebhookResponse(204, null) }
        listOf(paused, completed, manual).forEach(service::deliver)
        assertEquals(
            listOf("https://example.com/download.paused", "https://example.com/download.completed", "https://example.com/"),
            sent.map { it.url },
        )
        verify(exactly = 1) { deliveries.finish(paused.id, 1, false, 503, "KZ.WEBHOOK_HTTP_STATUS") }
        verify(exactly = 1) { deliveries.finish(completed.id, 1, true, 204, any()) }
        verify(exactly = 1) { deliveries.finish(manual.id, 1, true, 204, any()) }
        verify(exactly = 3) { deliveries.claim(any()) }
    }

    @Test
    fun `an unclaimable delivery is not sent and missing targets fail safely`() {
        val stale = delivery(WebhookEvent.COMPLETED)
        every { deliveries.claim(stale.id) } returns null
        service.deliver(stale)
        assertTrue(sent.isEmpty())
        verify(exactly = 0) { deliveries.finish(stale.id, any(), any(), any(), any()) }

        val orphan = delivery(WebhookEvent.COMPLETED, webhookId = null)
        every { deliveries.claim(orphan.id) } returns 4
        service.deliver(orphan)
        verify { deliveries.finish(orphan.id, 4, false, null, "KZ.WEBHOOK_DELETED") }

        val disabled = delivery(WebhookEvent.COMPLETED)
        every { deliveries.claim(disabled.id) } returns 1
        every { webhooks.find(hook.id) } returns hook.copy(enabled = false)
        service.deliver(disabled)
        verify { deliveries.finish(disabled.id, 1, false, null, "KZ.WEBHOOK_DISABLED") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the sender drains pending deliveries in order on its own thread`() {
        val first = delivery(WebhookEvent.COMPLETED)
        val second = delivery(null)
        every { webhooks.find(hook.id) } returns hook
        every { downloads.find(download.id) } returns download
        every { deliveries.pending() } returnsMany listOf(listOf(first, second), emptyList())
        every { deliveries.claim(any()) } returns 1
        val threads = CopyOnWriteArrayList<String>()
        answer = {
            threads += Thread.currentThread().name
            WebhookResponse(204, null)
        }
        service.dispatchPending()
        val deadline = System.nanoTime() + 5_000_000_000
        while (sent.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf("https://example.com/download.completed", "https://example.com/"), sent.map { it.url })
        assertTrue(threads.all { it == "webhook-sender" })
    }

    @Test
    fun `replace validates the whole list before saving`() {
        val errors =
            assertFailsWith<WebhookListInvalid> {
                service.replace(
                    listOf(
                        hook,
                        hook.copy(id = UUID.randomUUID(), name = " hook "),
                        hook.copy(id = UUID.randomUUID(), name = "Silent", events = emptySet(), manual = false),
                        hook.copy(id = UUID.randomUUID(), name = "Broken", url = "{{#x}}"),
                    ),
                )
            }.errors
        assertEquals(
            mapOf(
                "webhooks.1.name" to "KZ.WEBHOOK_NAME_DUPLICATE",
                "webhooks.2.events" to "KZ.WEBHOOK_TRIGGER_REQUIRED",
                "webhooks.3.url" to "KZ.WEBHOOK_TEMPLATE_INVALID",
            ),
            errors,
        )
        verify(exactly = 0) { webhooks.replace(any()) }
        service.replace(
            listOf(hook.copy(name = " Trimmed "), hook.copy(id = UUID.randomUUID(), name = "Off", events = emptySet(), enabled = false)),
        )
        verify { webhooks.replace(match { it.first().name == "Trimmed" && it.size == 2 }) }
    }

    @Test
    fun `test sends one real request from the draft and records nothing`() {
        val response = service.test(hook.copy(url = "https://draft.example/{{event}}"), WebhookEvent.FAILED)
        assertEquals(204, response.status)
        assertEquals(listOf("https://draft.example/download.failed"), sent.map { it.url })
        verify(exactly = 0) { deliveries.claim(any()) }
        verify(exactly = 0) { deliveries.finish(any(), any(), any(), any(), any()) }
        assertEquals("https://draft.example/", service.preview(hook.copy(url = "https://draft.example/{{event}}"), null).url)
    }

    @Test
    fun `manual dispatch and resend wake the sender only when queued`() {
        every { deliveries.dispatch(download.id, listOf(hook.id)) } returns WebhookActionResult.INELIGIBLE
        assertEquals(WebhookActionResult.INELIGIBLE, service.dispatch(download.id, listOf(hook.id)))
        verify(exactly = 0) { deliveries.pending() }
        service.recoverInterrupted()
        verify { deliveries.recoverInterrupted("KZ.WEBHOOK_INTERRUPTED") }
    }
}
