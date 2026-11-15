package com.ynixt.dmdl.resource.gateway.webhook

import com.ynixt.dmdl.domain.model.DeliveryTrigger
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookBodyMode
import com.ynixt.dmdl.domain.model.WebhookContext
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookFailure
import com.ynixt.dmdl.domain.model.WebhookHeader
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MustacheWebhookRendererTest {
    private val renderer = MustacheWebhookRenderer()

    private fun webhook(
        url: String = "https://example.com/hook",
        headers: List<WebhookHeader> = emptyList(),
        body: String? = null,
        events: Set<WebhookEvent> = setOf(WebhookEvent.COMPLETED),
    ) = Webhook(
        UUID.randomUUID(),
        "Hook",
        "POST",
        url,
        headers,
        if (body == null) WebhookBodyMode.NONE else WebhookBodyMode.TEMPLATE,
        body,
        events,
        manual = true,
    )

    private fun context(
        title: String = "Example",
        trigger: DeliveryTrigger = DeliveryTrigger.AUTOMATIC,
        event: WebhookEvent? = WebhookEvent.COMPLETED,
        state: DownloadState = DownloadState.COMPLETED,
    ): Map<String, Any?> {
        val sample = WebhookContext.sample(state)
        return WebhookContext.of(sample.copy(content = sample.content.copy(title = title)), trigger, event)
    }

    @Test
    fun `JSON bodies escape quotes newlines and control characters`() {
        val json = listOf(WebhookHeader("Content-Type", "application/json"))
        val rendered = renderer.render(webhook(headers = json, body = """{"t":"{{title}}"}"""), context("Dr. \"House\"\n\\ \u0001"))
        val parsed = JsonMapper.builder().build().readTree(rendered.body)
        assertEquals("Dr. \"House\"\n\\ \u0001", parsed["t"].asString())
    }

    @Test
    fun `plain bodies are inserted verbatim`() {
        val rendered = renderer.render(webhook(body = "Done: {{title}}"), context("Tom & \"Jerry\""))
        assertEquals("Done: Tom & \"Jerry\"", rendered.body)
    }

    @Test
    fun `query values are URL encoded including non ASCII`() {
        val rendered = renderer.render(webhook(url = "https://ntfy.example/topic?title={{title}}"), context("Tom & Jerry: ação"))
        assertEquals("https://ntfy.example/topic?title=Tom%20%26%20Jerry%3A%20a%C3%A7%C3%A3o", rendered.url)
    }

    @Test
    fun `headers drop line breaks and keep values verbatim`() {
        val rendered =
            renderer.render(webhook(headers = listOf(WebhookHeader(" Title ", "{{title}}"))), context("Line\r\nX-Injected: 1"))
        assertEquals(WebhookHeader("Title", "Line X-Injected: 1"), rendered.headers.single())
    }

    @Test
    fun `missing variables render empty and manual sections work`() {
        val body = "{{#manual}}manual {{download.state}}{{/manual}}{{^manual}}auto {{event}}{{/manual}}|{{nothing}}|{{event}}"
        assertEquals(
            "manual PAUSED||",
            renderer
                .render(
                    webhook(body = body),
                    context(trigger = DeliveryTrigger.MANUAL, event = null, state = DownloadState.PAUSED),
                ).body,
        )
        assertEquals("auto download.completed||download.completed", renderer.render(webhook(body = body), context()).body)
    }

    @Test
    fun `value sections render only when the value exists`() {
        val body = "{{title}}{{#error}} - {{error}}{{/error}}{{#path}} at {{path}}{{/path}}"
        assertEquals(
            "Example - KZ.TRANSFER_FAILED",
            renderer.render(webhook(body = body), context(event = WebhookEvent.FAILED, state = DownloadState.FAILED)).body,
        )
        assertEquals(
            "Example at /media/movies/Example Movie (2026)/Example Movie (2026).mkv",
            renderer.render(webhook(body = body), context()).body,
        )
    }

    @Test
    fun `templates cannot reach JVM object members`() {
        val body = "{{title.class}}|{{title.length}}|{{download.class.name}}|{{manual.class}}"
        assertEquals("|||", renderer.render(webhook(body = body), context()).body)
    }

    @Test
    fun `completion data and failure errors are exposed only when they exist`() {
        val completed = renderer.render(webhook(body = "{{path}}|{{size}}|{{error}}"), context())
        assertEquals("/media/movies/Example Movie (2026)/Example Movie (2026).mkv|4294967296|", completed.body)
        val failed =
            renderer.render(
                webhook(body = "{{path}}|{{size}}|{{error}}"),
                context(event = WebhookEvent.FAILED, state = DownloadState.FAILED),
            )
        assertEquals("||KZ.TRANSFER_FAILED", failed.body)
    }

    @Test
    fun `invalid templates and URLs are reported on their field`() {
        assertEquals("body", assertFailsWith<WebhookFailure> { renderer.render(webhook(body = "{{#manual}}open"), context()) }.field)
        val failure = assertFailsWith<WebhookFailure> { renderer.render(webhook(url = "file:///etc/passwd"), context()) }
        assertEquals("url" to "KZ.WEBHOOK_URL_INVALID", failure.field to failure.code)
        assertEquals(
            mapOf("headers.0.value" to "KZ.WEBHOOK_TEMPLATE_INVALID"),
            renderer.validate(webhook(headers = listOf(WebhookHeader("X", "{{#a}}")))),
        )
    }

    @Test
    fun `validation checks names methods headers and body`() {
        val errors =
            renderer.validate(
                webhook(headers = listOf(WebhookHeader("bad name", "x"), WebhookHeader("Host", "x"))).copy(
                    name = " ",
                    method = "TRACE",
                    url = "",
                    bodyMode = WebhookBodyMode.TEMPLATE,
                    body = "",
                ),
            )
        assertEquals(
            mapOf(
                "name" to "KZ.WEBHOOK_NAME_REQUIRED",
                "method" to "KZ.WEBHOOK_METHOD_INVALID",
                "url" to "KZ.WEBHOOK_URL_REQUIRED",
                "headers.0.name" to "KZ.WEBHOOK_HEADER_NAME_INVALID",
                "headers.1.name" to "KZ.WEBHOOK_HEADER_RESTRICTED",
                "body" to "KZ.WEBHOOK_BODY_REQUIRED",
            ),
            errors,
        )
        assertTrue(renderer.validate(webhook(body = "{{title}}")).isEmpty())
        assertNull(renderer.render(webhook(), context()).body)
    }
}
