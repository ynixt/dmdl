package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.web.dto.StrictRequest
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookBodyMode
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookFailure
import com.ynixt.dmdl.domain.model.WebhookHeader
import com.ynixt.dmdl.domain.model.WebhookListInvalid
import com.ynixt.dmdl.domain.service.WebhookService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class WebhookHeaderInput(
    val name: String = "",
    val value: String = "",
) : StrictRequest() {
    override fun toString(): String = "WebhookHeaderInput([redacted])"
}

data class WebhookInput(
    val id: UUID? = null,
    val name: String = "",
    val method: String = "POST",
    val url: String = "",
    val headers: List<WebhookHeaderInput> = emptyList(),
    val bodyMode: String = WebhookBodyMode.NONE.name,
    val body: String? = null,
    val events: List<String> = emptyList(),
    val manual: Boolean = false,
    val enabled: Boolean = true,
) : StrictRequest() {
    override fun toString(): String = "WebhookInput(id=$id, [redacted])"
}

/** A draft to preview or test with sample data; a null [event] simulates a manual dispatch. */
data class WebhookDraftInput(
    val webhook: WebhookInput,
    val event: String? = null,
) : StrictRequest() {
    override fun toString(): String = "WebhookDraftInput(event=$event)"
}

data class WebhookDispatchInput(
    val webhookIds: List<UUID> = emptyList(),
) : StrictRequest()

@RestController
@RequestMapping("/api")
class WebhooksController(
    private val webhooks: WebhookService,
) {
    @GetMapping("/webhooks")
    fun list() = webhooks.list().map(::publicWebhook)

    @PutMapping("/webhooks")
    fun replace(
        @RequestBody input: List<WebhookInput>,
    ): List<Map<String, Any?>> {
        val errors = linkedMapOf<String, String>()
        val parsed = input.mapIndexed { index, item -> parse(item, "webhooks.$index.", errors) }
        if (errors.isNotEmpty()) throw WebhookListInvalid(errors)
        return webhooks.replace(parsed).map(::publicWebhook)
    }

    @PostMapping("/webhooks/preview")
    fun preview(
        @RequestBody input: WebhookDraftInput,
    ): Map<String, Any?> {
        val (webhook, event) = draft(input)
        val rendered = webhooks.preview(webhook, event)
        return mapOf(
            "method" to rendered.method,
            "url" to rendered.url,
            "headers" to rendered.headers.map { mapOf("name" to it.name, "value" to it.value) },
            "body" to rendered.body,
        )
    }

    @PostMapping("/webhooks/test")
    fun test(
        @RequestBody input: WebhookDraftInput,
    ): Map<String, Any?> {
        val (webhook, event) = draft(input)
        val response = webhooks.test(webhook, event)
        return mapOf("ok" to response.successful, "status" to response.status, "error" to response.error)
    }

    @PostMapping("/downloads/{id}/webhooks/dispatch")
    fun dispatch(
        @PathVariable id: UUID,
        @RequestBody input: WebhookDispatchInput,
    ): ResponseEntity<*> = action(webhooks.dispatch(id, input.webhookIds), "KZ.WEBHOOK_DISPATCH_INELIGIBLE")

    @PostMapping("/downloads/{id}/deliveries/{deliveryId}/resend")
    fun resend(
        @PathVariable id: UUID,
        @PathVariable deliveryId: UUID,
    ): ResponseEntity<*> = action(webhooks.resend(id, deliveryId), "KZ.WEBHOOK_RESEND_INELIGIBLE")

    @ExceptionHandler(WebhookListInvalid::class)
    fun invalidList(error: WebhookListInvalid): ResponseEntity<*> =
        ResponseEntity.badRequest().body(mapOf("error" to "KZ.WEBHOOKS_INVALID", "errors" to error.errors))

    @ExceptionHandler(WebhookFailure::class)
    fun invalidWebhook(error: WebhookFailure): ResponseEntity<*> =
        ResponseEntity.badRequest().body(mapOf("error" to error.code, "errors" to mapOf(error.field to error.code)))

    private fun action(
        result: WebhookActionResult,
        ineligible: String,
    ): ResponseEntity<*> =
        when (result) {
            WebhookActionResult.OK -> ResponseEntity.accepted().body(mapOf("queued" to true))
            WebhookActionResult.NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "KZ.NOT_FOUND"))
            WebhookActionResult.INELIGIBLE -> ResponseEntity.badRequest().body(mapOf("error" to ineligible))
            WebhookActionResult.CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "KZ.WEBHOOK_DELIVERY_ACTIVE"))
        }

    private fun draft(input: WebhookDraftInput): Pair<Webhook, WebhookEvent?> {
        val errors = linkedMapOf<String, String>()
        val webhook = parse(input.webhook, "", errors)
        val event = input.event?.let { WebhookEvent.fromKey(it) ?: throw WebhookFailure("event", "KZ.WEBHOOK_EVENT_UNKNOWN") }
        if (errors.isNotEmpty()) throw WebhookListInvalid(errors)
        return webhook to event
    }

    private fun parse(
        input: WebhookInput,
        prefix: String,
        errors: MutableMap<String, String>,
    ): Webhook {
        val events =
            input.events.mapNotNull { key ->
                WebhookEvent.fromKey(key).also { if (it == null) errors.putIfAbsent("${prefix}events", "KZ.WEBHOOK_EVENT_UNKNOWN") }
            }
        val bodyMode =
            WebhookBodyMode.entries.singleOrNull { it.name == input.bodyMode }
                ?: WebhookBodyMode.NONE.also { errors.putIfAbsent("${prefix}bodyMode", "KZ.WEBHOOK_BODY_MODE_INVALID") }
        return Webhook(
            input.id ?: UUID.randomUUID(),
            input.name,
            input.method.trim().uppercase(),
            input.url,
            input.headers.map { WebhookHeader(it.name, it.value) },
            bodyMode,
            input.body,
            events.toSet(),
            input.manual,
            input.enabled,
        )
    }

    private fun publicWebhook(webhook: Webhook): Map<String, Any?> =
        mapOf(
            "id" to webhook.id,
            "name" to webhook.name,
            "method" to webhook.method,
            "url" to webhook.url,
            "headers" to webhook.headers.map { mapOf("name" to it.name, "value" to it.value) },
            "bodyMode" to webhook.bodyMode.name,
            "body" to webhook.body,
            "events" to WebhookEvent.entries.filter { it in webhook.events }.map { it.key },
            "manual" to webhook.manual,
            "enabled" to webhook.enabled,
        )
}
