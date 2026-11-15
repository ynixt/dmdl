package com.ynixt.dmdl.resource.gateway.webhook

import com.samskivert.mustache.BasicCollector
import com.samskivert.mustache.Escapers
import com.samskivert.mustache.Mustache
import com.samskivert.mustache.MustacheException
import com.ynixt.dmdl.domain.gateway.WebhookRenderer
import com.ynixt.dmdl.domain.model.DeliveryTrigger
import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookBodyMode
import com.ynixt.dmdl.domain.model.WebhookContext
import com.ynixt.dmdl.domain.model.WebhookFailure
import com.ynixt.dmdl.domain.model.WebhookHeader
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Logic-less Mustache rendering. The collector resolves only map keys and list items, so a template can never reach a
 * JVM object's getters or methods. Values are escaped for where they land: URL-encoded in the URL, JSON-escaped in a
 * JSON body, verbatim in headers and other bodies.
 */
@Component
class MustacheWebhookRenderer : WebhookRenderer {
    private val collector =
        object : BasicCollector() {
            override fun <K, V> createFetcherCache(): MutableMap<K, V> = ConcurrentHashMap()
        }

    private fun compiler(escaper: Mustache.Escaper) =
        Mustache
            .compiler()
            .withCollector(collector)
            .withEscaper(escaper)
            .defaultValue("")
            // Lets `{{#error}}…{{/error}}` render only when the value exists.
            .emptyStringIsFalse(true)
            .strictSections(false)

    private val urlCompiler = compiler { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
    private val plainCompiler = compiler(Escapers.NONE)
    private val jsonCompiler = compiler(::jsonEscape)

    override fun render(
        webhook: Webhook,
        context: Map<String, Any?>,
    ): RenderedWebhookRequest {
        val url = execute(urlCompiler, webhook.url, context, "url").trim()
        val parsed = runCatching { URI(url) }.getOrNull()
        if (parsed == null || parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            throw WebhookFailure("url", "KZ.WEBHOOK_URL_INVALID")
        }
        val headers =
            webhook.headers.mapIndexed { index, header ->
                WebhookHeader(
                    header.name.trim(),
                    execute(plainCompiler, header.value, context, "headers.$index.value").replace(lineBreaks, " ").trim(),
                )
            }
        val contentType = headers.lastOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value?.lowercase()
        val json = contentType != null && (contentType.contains("application/json") || contentType.contains("+json"))
        val body =
            if (webhook.bodyMode == WebhookBodyMode.TEMPLATE) {
                execute(if (json) jsonCompiler else plainCompiler, webhook.body.orEmpty(), context, "body")
            } else {
                null
            }
        return RenderedWebhookRequest(webhook.method, url, headers, body)
    }

    override fun validate(webhook: Webhook): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        if (webhook.name.isBlank()) errors["name"] = "KZ.WEBHOOK_NAME_REQUIRED"
        if (webhook.method !in Webhook.methods) errors["method"] = "KZ.WEBHOOK_METHOD_INVALID"
        if (webhook.url.isBlank()) errors["url"] = "KZ.WEBHOOK_URL_REQUIRED"
        webhook.headers.forEachIndexed { index, header ->
            val name = header.name.trim()
            when {
                !headerName.matches(name) -> errors["headers.$index.name"] = "KZ.WEBHOOK_HEADER_NAME_INVALID"
                name.lowercase() in restrictedHeaders -> errors["headers.$index.name"] = "KZ.WEBHOOK_HEADER_RESTRICTED"
            }
        }
        if (webhook.bodyMode == WebhookBodyMode.TEMPLATE && webhook.body.isNullOrBlank()) errors["body"] = "KZ.WEBHOOK_BODY_REQUIRED"
        if (errors.isNotEmpty()) return errors
        // Rendering with sample data for every trigger the webhook can fire catches template and URL errors.
        val samples = webhook.events.map { DeliveryTrigger.AUTOMATIC to it } + (DeliveryTrigger.MANUAL to null)
        for ((trigger, event) in samples) {
            try {
                render(webhook, WebhookContext.of(WebhookContext.sampleFor(event), trigger, event))
            } catch (failure: WebhookFailure) {
                errors.putIfAbsent(failure.field, failure.code)
            }
        }
        return errors
    }

    private fun execute(
        compiler: Mustache.Compiler,
        template: String,
        context: Map<String, Any?>,
        field: String,
    ): String =
        try {
            compiler.compile(template).execute(context)
        } catch (_: MustacheException) {
            throw WebhookFailure(field, "KZ.WEBHOOK_TEMPLATE_INVALID")
        }

    private companion object {
        val lineBreaks = Regex("[\\r\\n]+")
        val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        /** Headers the JDK HTTP client manages itself and refuses to set. */
        val restrictedHeaders = setOf("connection", "content-length", "expect", "host", "upgrade")

        fun jsonEscape(raw: String): String =
            buildString(raw.length + 8) {
                raw.forEach { char ->
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        else -> if (char < ' ' || char == ' ' || char == ' ') append("\\u%04x".format(char.code)) else append(char)
                    }
                }
            }
    }
}
