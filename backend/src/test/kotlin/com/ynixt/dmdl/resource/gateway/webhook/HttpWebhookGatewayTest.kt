package com.ynixt.dmdl.resource.gateway.webhook

import com.sun.net.httpserver.HttpServer
import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.WebhookHeader
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpWebhookGatewayTest {
    private val received = CopyOnWriteArrayList<String>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ok") { exchange ->
                received += "${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("X-Token")} " +
                    exchange.requestBody.readAllBytes().decodeToString()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            createContext("/missing") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/ok")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/slow") { exchange ->
                Thread.sleep(1500)
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val gateway = HttpWebhookGateway(Duration.ofSeconds(1), Duration.ofMillis(500))

    @AfterTest
    fun stop() = server.stop(0)

    private fun request(
        path: String,
        body: String? = null,
    ) = RenderedWebhookRequest("POST", base + path, listOf(WebhookHeader("X-Token", "secret")), body)

    @Test
    fun `2xx is success and the request carries method headers and body`() {
        val response = gateway.send(request("/ok?a=1", "hello"))
        assertTrue(response.successful)
        assertEquals(204, response.status)
        assertNull(response.error)
        assertEquals(listOf("POST /ok?a=1 secret hello"), received)
    }

    @Test
    fun `other statuses fail and redirects are not followed`() {
        assertEquals(404 to "KZ.WEBHOOK_HTTP_STATUS", gateway.send(request("/missing")).let { it.status to it.error })
        val redirect = gateway.send(request("/redirect"))
        assertFalse(redirect.successful)
        assertEquals(302, redirect.status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `timeouts and unreachable hosts become safe codes without the URL`() {
        assertEquals("KZ.WEBHOOK_TIMEOUT", gateway.send(request("/slow")).error)
        server.stop(0)
        val unreachable = gateway.send(request("/ok"))
        assertEquals("KZ.WEBHOOK_CONNECTION_FAILED", unreachable.error)
        assertFalse(unreachable.toString().contains("secret") || unreachable.toString().contains("127.0.0.1"))
        assertEquals(
            "KZ.WEBHOOK_REQUEST_INVALID",
            gateway.send(request("/ok").copy(headers = listOf(WebhookHeader("Bad", "a\u0000b")))).error,
        )
    }
}
