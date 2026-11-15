package com.ynixt.dmdl.resource.gateway.webhook

import com.ynixt.dmdl.domain.gateway.WebhookGateway
import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.WebhookResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** Sends rendered webhooks. Failures become safe codes: the URL and headers can hold secrets and never leave this class. */
@Component
class HttpWebhookGateway internal constructor(
    connectTimeout: Duration,
    private val requestTimeout: Duration,
) : WebhookGateway {
    @Autowired
    constructor() : this(Duration.ofSeconds(5), Duration.ofSeconds(15))

    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun send(request: RenderedWebhookRequest): WebhookResponse {
        val outbound =
            try {
                HttpRequest
                    .newBuilder(URI(request.url))
                    .timeout(requestTimeout)
                    .apply { request.headers.forEach { header(it.name, it.value) } }
                    .method(
                        request.method,
                        request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
                    ).build()
            } catch (_: Exception) {
                return WebhookResponse(null, "KZ.WEBHOOK_REQUEST_INVALID")
            }
        return try {
            val status = client.send(outbound, HttpResponse.BodyHandlers.discarding()).statusCode()
            WebhookResponse(status, if (status in 200..299) null else "KZ.WEBHOOK_HTTP_STATUS")
        } catch (_: HttpTimeoutException) {
            WebhookResponse(null, "KZ.WEBHOOK_TIMEOUT")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            WebhookResponse(null, "KZ.WEBHOOK_INTERRUPTED")
        } catch (_: IOException) {
            WebhookResponse(null, "KZ.WEBHOOK_CONNECTION_FAILED")
        } catch (_: RuntimeException) {
            WebhookResponse(null, "KZ.WEBHOOK_REQUEST_INVALID")
        }
    }
}
