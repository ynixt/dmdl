package com.ynixt.dmdl.domain.service

import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookResponse
import java.util.UUID

interface WebhookService {
    fun list(): List<Webhook>

    /** Validates every webhook and replaces the stored list, or throws WebhookListInvalid without changing anything. */
    fun replace(webhooks: List<Webhook>): List<Webhook>

    /** Renders a draft with sample data for [event], or for a manual dispatch when [event] is null. */
    fun preview(
        webhook: Webhook,
        event: WebhookEvent?,
    ): RenderedWebhookRequest

    /** Sends one real request from a draft with sample data; records nothing. */
    fun test(
        webhook: Webhook,
        event: WebhookEvent?,
    ): WebhookResponse

    fun dispatch(
        requestId: UUID,
        webhookIds: List<UUID>,
    ): WebhookActionResult

    fun resend(
        requestId: UUID,
        deliveryId: UUID,
    ): WebhookActionResult

    /** Fails attempts interrupted by a previous shutdown; call once at startup before sending. */
    fun recoverInterrupted()

    /** Asks the sender to drain pending deliveries; returns immediately. */
    fun dispatchPending()
}
