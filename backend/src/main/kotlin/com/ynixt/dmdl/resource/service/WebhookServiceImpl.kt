package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.WebhookGateway
import com.ynixt.dmdl.domain.gateway.WebhookRenderer
import com.ynixt.dmdl.domain.model.DeliveryTrigger
import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookContext
import com.ynixt.dmdl.domain.model.WebhookDelivery
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookFailure
import com.ynixt.dmdl.domain.model.WebhookListInvalid
import com.ynixt.dmdl.domain.model.WebhookResponse
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.domain.repository.WebhookDeliveryStore
import com.ynixt.dmdl.domain.repository.WebhookStore
import com.ynixt.dmdl.domain.service.WebhookService
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Service
class WebhookServiceImpl(
    private val webhooks: WebhookStore,
    private val deliveries: WebhookDeliveryStore,
    private val downloads: DownloadStore,
    private val renderer: WebhookRenderer,
    private val gateway: WebhookGateway,
) : WebhookService {
    /**
     * One sender thread drains pending deliveries in creation order, which keeps deliveries of the same webhook and
     * download in event order. It is separate from the scheduler so a slow receiver never delays download work.
     */
    private val sender: ExecutorService = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("webhook-sender").factory())
    private val draining = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)

    @PreDestroy
    fun stop() {
        sender.shutdownNow()
    }

    override fun list(): List<Webhook> = webhooks.list()

    override fun replace(webhooks: List<Webhook>): List<Webhook> {
        val errors = linkedMapOf<String, String>()
        val names = mutableSetOf<String>()
        webhooks.forEachIndexed { index, webhook ->
            renderer.validate(webhook).forEach { (field, code) -> errors.putIfAbsent("webhooks.$index.$field", code) }
            if (webhook.name.isNotBlank() && !names.add(webhook.name.trim().lowercase())) {
                errors.putIfAbsent("webhooks.$index.name", "KZ.WEBHOOK_NAME_DUPLICATE")
            }
            if (webhook.enabled && webhook.events.isEmpty() && !webhook.manual) {
                errors.putIfAbsent("webhooks.$index.events", "KZ.WEBHOOK_TRIGGER_REQUIRED")
            }
        }
        if (webhooks.map { it.id }.distinct().size != webhooks.size) errors["webhooks"] = "KZ.WEBHOOKS_INVALID"
        if (errors.isNotEmpty()) throw WebhookListInvalid(errors)
        this.webhooks.replace(webhooks.map { it.copy(name = it.name.trim()) })
        return this.webhooks.list()
    }

    override fun preview(
        webhook: Webhook,
        event: WebhookEvent?,
    ): RenderedWebhookRequest = renderer.render(webhook, sampleContext(event))

    override fun test(
        webhook: Webhook,
        event: WebhookEvent?,
    ): WebhookResponse {
        val errors = renderer.validate(webhook)
        if (errors.isNotEmpty()) throw WebhookListInvalid(errors)
        return gateway.send(renderer.render(webhook, sampleContext(event)))
    }

    override fun dispatch(
        requestId: UUID,
        webhookIds: List<UUID>,
    ): WebhookActionResult = deliveries.dispatch(requestId, webhookIds).also { if (it == WebhookActionResult.OK) dispatchPending() }

    override fun resend(
        requestId: UUID,
        deliveryId: UUID,
    ): WebhookActionResult = deliveries.resend(requestId, deliveryId).also { if (it == WebhookActionResult.OK) dispatchPending() }

    override fun recoverInterrupted() {
        deliveries.recoverInterrupted("KZ.WEBHOOK_INTERRUPTED")
    }

    override fun dispatchPending() {
        rerun.set(true)
        if (!draining.compareAndSet(false, true)) return
        try {
            sender.execute(::drain)
        } catch (_: Exception) {
            draining.set(false)
        }
    }

    private fun drain() {
        try {
            while (rerun.getAndSet(false)) {
                deliveries.pending().forEach(::deliver)
            }
        } finally {
            draining.set(false)
            // A request queued after the last pass but before the flag cleared still gets a pass.
            if (rerun.get()) dispatchPending()
        }
    }

    /** Sends exactly one attempt for a claimed delivery and records only that attempt's outcome. */
    internal fun deliver(delivery: WebhookDelivery) {
        val attempt = deliveries.claim(delivery.id) ?: return
        val outcome =
            try {
                send(delivery)
            } catch (_: Exception) {
                WebhookResponse(null, "KZ.WEBHOOK_REQUEST_INVALID")
            }
        deliveries.finish(delivery.id, attempt, outcome.successful, outcome.status, outcome.error ?: "KZ.WEBHOOK_HTTP_STATUS")
    }

    private fun send(delivery: WebhookDelivery): WebhookResponse {
        val webhook = delivery.webhookId?.let(webhooks::find) ?: return WebhookResponse(null, "KZ.WEBHOOK_DELETED")
        if (!webhook.enabled) return WebhookResponse(null, "KZ.WEBHOOK_DISABLED")
        val request = downloads.find(delivery.requestId) ?: return WebhookResponse(null, "KZ.WEBHOOK_DOWNLOAD_MISSING")
        val event = delivery.event?.let(WebhookEvent::fromKey)
        val rendered =
            try {
                renderer.render(webhook, WebhookContext.of(request, delivery.trigger, event))
            } catch (failure: WebhookFailure) {
                return WebhookResponse(null, failure.code)
            }
        return gateway.send(rendered)
    }

    private fun sampleContext(event: WebhookEvent?) =
        WebhookContext.of(
            WebhookContext.sampleFor(event),
            if (event == null) DeliveryTrigger.MANUAL else DeliveryTrigger.AUTOMATIC,
            event,
        )
}
