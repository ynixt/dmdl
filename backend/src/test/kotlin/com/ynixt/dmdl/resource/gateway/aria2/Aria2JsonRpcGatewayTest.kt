package com.ynixt.dmdl.resource.gateway.aria2

import com.sun.net.httpserver.HttpServer
import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.domain.enum.Aria2FileAllocation
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Aria2JsonRpcGatewayTest {
    @Test
    fun `maps connection count to both connection and split with transfer limits`() {
        var requestBody = ""
        withServer(
            responder = { body ->
                requestBody = body
                """{"jsonrpc":"2.0","id":"1","result":"abc"}"""
            },
        ) { gateway ->
            gateway.addUri(
                "https://example.test/video",
                "abc",
                "/downloads/abc",
                Aria2TransferOptions(
                    split = 6,
                    maxConnectionPerServer = 2,
                    minSplitSizeMib = 32,
                    maxTries = 9,
                    retryWaitSeconds = 7,
                    fileAllocation = Aria2FileAllocation.NONE,
                    ipv6Enabled = false,
                    lowestSpeedLimitEnabled = true,
                    lowestSpeedLimitKib = 100,
                    timeoutSeconds = 45,
                    maxFileNotFound = 3,
                ),
            )
        }

        assertTrue(requestBody.contains("\"split\":\"2\""))
        assertTrue(requestBody.contains("\"max-connection-per-server\":\"2\""))
        assertTrue(requestBody.contains("\"min-split-size\":\"32M\""))
        assertTrue(requestBody.contains("\"max-tries\":\"9\""))
        assertTrue(requestBody.contains("\"retry-wait\":\"7\""))
        assertTrue(requestBody.contains("\"file-allocation\":\"none\""))
        assertTrue(requestBody.contains("\"disable-ipv6\":\"true\""))
        assertTrue(requestBody.contains("\"lowest-speed-limit\":\"100K\""))
        assertTrue(requestBody.contains("\"timeout\":\"45\""))
        assertTrue(requestBody.contains("\"max-file-not-found\":\"3\""))
    }

    @Test
    fun `sends no per-connection minimum speed when the limit is disabled`() {
        var requestBody = ""
        withServer(
            responder = { body ->
                requestBody = body
                """{"jsonrpc":"2.0","id":"1","result":"abc"}"""
            },
        ) { gateway ->
            gateway.addUri(
                "https://example.test/video",
                "abc",
                "/downloads/abc",
                Aria2TransferOptions(lowestSpeedLimitEnabled = false, lowestSpeedLimitKib = 80),
            )
        }

        assertTrue(requestBody.contains("aria2.addUri"))
        assertTrue(requestBody.contains("\"lowest-speed-limit\":\"0\""))
        assertTrue(!requestBody.contains("80K"))
    }

    @Test
    fun `sends the configured per-connection minimum speed when the limit is enabled`() {
        val requests = mutableListOf<String>()
        withServer(
            responder = { body ->
                requests += body
                """{"jsonrpc":"2.0","id":"1","result":"OK"}"""
            },
        ) { gateway ->
            val options = Aria2TransferOptions(lowestSpeedLimitEnabled = true, lowestSpeedLimitKib = 80)
            gateway.addUri("https://example.test/video", "abc", "/downloads/abc", options)
            gateway.changeOptions("abc", options)
        }

        assertTrue(requests.any { it.contains("aria2.addUri") && it.contains("\"lowest-speed-limit\":\"80K\"") })
        assertTrue(requests.any { it.contains("aria2.changeOption") && it.contains("\"lowest-speed-limit\":\"80K\"") })
    }

    @Test
    fun `authenticates session and global option calls`() {
        val requests = mutableListOf<String>()
        withServer(
            responder = { body ->
                requests += body
                if (body.contains("aria2.getSessionInfo")) {
                    """{"jsonrpc":"2.0","id":"1","result":{"sessionId":"session-1"}}"""
                } else {
                    """{"jsonrpc":"2.0","id":"1","result":"OK"}"""
                }
            },
        ) { gateway ->
            assertEquals("session-1", gateway.sessionId())
            gateway.changeGlobalMaxConcurrentDownloads(3, 128)
        }

        assertTrue(requests.all { it.contains("token:test-secret") })
        assertTrue(requests.any { it.contains("aria2.changeGlobalOption") && it.contains("\"max-concurrent-downloads\":\"3\"") })
        assertTrue(requests.any { it.contains("\"disk-cache\":\"128M\"") })
    }

    @Test
    fun `sends token first and maps selected tellStatus fields`() {
        var requestBody = ""
        withServer(
            responder = { body ->
                requestBody = body
                """{"jsonrpc":"2.0","id":"1","result":{"gid":"abc","status":"active","totalLength":"100","completedLength":"25","downloadSpeed":"5","connections":"2","dir":"/downloads/.dmdl/queue-abc","files":[{"path":"/downloads/.dmdl/queue-abc/a.mkv","length":"100"}]}}"""
            },
        ) { gateway ->
            val status = gateway.findStatus("abc")!!
            assertEquals(100, status.totalLength)
            assertEquals(25, status.completedLength)
            assertEquals(2, status.connections)
            assertTrue(requestBody.contains("\"params\":[\"token:test-secret\",\"abc\""))
            assertTrue(requestBody.contains("\"totalLength\""))
            assertTrue(requestBody.contains("\"connections\""))
        }
    }

    @Test
    fun `optional connection counts tolerate missing malformed and negative values`() {
        listOf(null, "bad", "-1", "99999999999999").forEach { value ->
            val field = value?.let { ",\"connections\":\"$it\"" }.orEmpty()
            withServer({
                """{"result":{"gid":"abc","status":"active","totalLength":"100","completedLength":"25","downloadSpeed":"5"$field}}"""
            }) { gateway ->
                assertNull(gateway.findStatus("abc")!!.connections)
            }
        }
        withServer({
            """{"result":{"gid":"abc","status":"active","totalLength":"100","completedLength":"25","downloadSpeed":"5","connections":"0"}}"""
        }) { gateway -> assertEquals(0, gateway.findStatus("abc")!!.connections) }
        listOf("null", "{}", "[]", "true", "1.5").forEach { value ->
            withServer({
                """{"result":{"gid":"abc","status":"active","totalLength":"100","completedLength":"25","downloadSpeed":"5","connections":$value}}"""
            }) { gateway -> assertNull(gateway.findStatus("abc")!!.connections) }
        }
    }

    @Test
    fun `treats unknown gid as absent and rejects JSON-RPC and malformed numeric responses`() {
        withServer({ """{"jsonrpc":"2.0","id":"1","error":{"code":1,"message":"GID is not found"}}""" }) { gateway ->
            assertNull(gateway.findStatus("missing"))
        }
        withServer({ """{"jsonrpc":"2.0","id":"1","error":{"code":1,"message":"authentication failed"}}""" }) { gateway ->
            val error = assertFailsWith<Aria2RpcException> { gateway.version() }
            assertTrue(error.message.orEmpty().contains("authentication failed"))
            assertTrue(!error.message.orEmpty().contains("test-secret"))
        }
        withServer({
            """{"jsonrpc":"2.0","id":"1","result":{"gid":"abc","status":"active","totalLength":"bad","completedLength":"0","downloadSpeed":"0","dir":"/x","files":[]}}"""
        }) { gateway ->
            assertFailsWith<Aria2RpcException> { gateway.findStatus("abc") }
        }
    }

    @Test
    fun `translates HTTP failure and request timeout without leaking connection details`() {
        withServer(
            responder = { "unavailable" },
            status = 503,
        ) { gateway ->
            val error = assertFailsWith<Aria2RpcException> { gateway.version() }
            assertTrue(error.message.orEmpty().contains("HTTP 503"))
        }
        withServer(
            responder = {
                Thread.sleep(200)
                """{"jsonrpc":"2.0","id":"1","result":{"version":"1.37.0","enabledFeatures":[]}}"""
            },
            requestTimeout = Duration.ofMillis(25),
        ) { gateway ->
            val error = assertFailsWith<Aria2RpcException> { gateway.version() }
            assertTrue(error.message.orEmpty().contains("request failed"))
        }
    }

    private fun withServer(
        responder: (String) -> String,
        status: Int = 200,
        requestTimeout: Duration = Duration.ofSeconds(1),
        block: (Aria2JsonRpcGateway) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/jsonrpc") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val response = responder(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            block(
                Aria2JsonRpcGateway(
                    Aria2Properties(
                        rpcUrl = "http://127.0.0.1:${server.address.port}/jsonrpc",
                        rpcSecret = "test-secret",
                        requestTimeout = requestTimeout,
                    ),
                    ObjectMapper(),
                ),
            )
        } finally {
            server.stop(0)
        }
    }
}
