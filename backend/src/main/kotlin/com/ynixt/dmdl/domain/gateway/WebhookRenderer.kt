package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookResponse

interface WebhookRenderer {
    /** Renders a webhook with a template context; throws WebhookFailure naming the field that failed. */
    fun render(
        webhook: Webhook,
        context: Map<String, Any?>,
    ): RenderedWebhookRequest

    /** Field errors (field to code) for a webhook definition; empty when it can be saved. */
    fun validate(webhook: Webhook): Map<String, String>
}

interface WebhookGateway {
    fun send(request: RenderedWebhookRequest): WebhookResponse
}
