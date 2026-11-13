package com.ynixt.dmdl.resource.gateway.addon

import com.sun.net.httpserver.HttpServer
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.AddonResource
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridCacheStatus
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.SourceOutcome
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StremioAddonGatewayTest {
    @Test
    fun `preserves per-service availability from structured data generic hints and explicit labels`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """{"streams":[
                {"name":"AIOStreams","url":"https://aio.example/one","streamData":{"service":{"id":"torbox","cached":true}}},
                {"name":"AIOStreams ⚡","url":"https://aio.example/two","streamData":{"service":{"id":"alldebrid","cached":false}}},
                {"name":"Other addon","url":"https://other.example/three","behaviorHints":{"service":"realdebrid","cached":true}},
                {"name":"Other addon","url":"https://other.example/four","behaviorHints":{"service":"realdebrid","cached":false}},
                {"name":"Torrentio RD+","url":"https://torrentio.strem.fun/resolve/realdebrid/key/hash/five"},
                {"name":"Torrentio AD download","url":"https://torrentio.strem.fun/resolve/alldebrid/key/hash/six"},
                {"name":"TorBox","url":"https://cdn.torbox.app/seven"},
                {"name":"Unknown cached-release.mkv","url":"https://other.example/eight"},
                {"name":"Generic","url":"https://other.example/mixed.mkv","infoHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","cached":true},
                {"name":"Torrent only","infoHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
            ]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val source =
                AddonConfiguration(
                    UUID.randomUUID(),
                    1,
                    "http://127.0.0.1:${server.address.port}/manifest.json",
                    AddonManifest(
                        "fixture",
                        "Fixture",
                        listOf("movie"),
                        listOf(AddonResource("stream", listOf("movie"), emptyList())),
                        emptyList(),
                    ),
                )
            val content = ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "tt1234"), MediaCategory.MOVIE, "Movie")
            val releases = StremioAddonGateway(JsonMapper.builder().build()).releases(source, content).releases
            assertEquals(
                listOf(
                    DebridCacheStatus.CACHED,
                    DebridCacheStatus.UNCACHED,
                    DebridCacheStatus.CACHED,
                    DebridCacheStatus.UNCACHED,
                    DebridCacheStatus.CACHED,
                    DebridCacheStatus.UNCACHED,
                    DebridCacheStatus.UNKNOWN,
                    DebridCacheStatus.UNKNOWN,
                    DebridCacheStatus.CACHED,
                ),
                releases.map { it.cacheStatus },
            )
            assertEquals(DebridProvider.ALL_DEBRID, releases[1].provenance.boundProvider)
            assertEquals(DebridProvider.REAL_DEBRID, releases[2].provenance.boundProvider)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `identifies each provider in a multi-debrid response without binding the addon`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var agent: String? = null
        server.createContext("/") { exchange ->
            agent = exchange.requestHeaders.getFirst("User-Agent")
            val body = """{"streams":[
                {"url":"https://torrentio.strem.fun/resolve/torbox/key/hash/file.mkv"},
                {"url":"https://mediafusion.example/streaming_provider/config/playback/alldebrid/hash"},
                {"url":"https://cdn.real-debrid.com/file.mkv"},
                {"url":"https://aio.example/api/v1/debrid/playback/encrypted/opaque/file.mkv",
                 "streamData":{"type":"debrid","service":{"id":"alldebrid"}}},
                {"url":"https://unknown.example/file.mkv"},
                {"url":"https://aio.example/error.mp4","streamData":{"type":"error"}}
            ]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val gateway = StremioAddonGateway(JsonMapper.builder().build())
            val manifest =
                AddonManifest(
                    "aio",
                    "AIOStreams",
                    listOf("movie"),
                    listOf(AddonResource("stream", listOf("movie"), emptyList())),
                    emptyList(),
                )
            val source = AddonConfiguration(UUID.randomUUID(), 1, "http://127.0.0.1:${server.address.port}/manifest.json", manifest)
            val content = ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "tt4633694"), MediaCategory.MOVIE, "Movie")
            val group = gateway.releases(source, content)
            assertEquals(
                listOf(
                    DebridProvider.TORBOX,
                    DebridProvider.ALL_DEBRID,
                    DebridProvider.REAL_DEBRID,
                    DebridProvider.ALL_DEBRID,
                    null,
                ),
                group.releases.map { it.provenance.boundProvider },
            )
            assertEquals(true, agent?.startsWith("AIO"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `accepts AIOStreams resource objects and preserves configured paths and custom names in stream lookups`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = mutableListOf<String>()
        server.createContext("/") { exchange ->
            calls.add(exchange.requestURI.toASCIIString())
            val body =
                if (exchange.requestURI.path.endsWith("manifest.json")) {
                    """{"id":"com.aiostreams.fixture","name":"AIOStreams","types":["movie","series","anime"],
                    "resources":[{"name":"stream","types":["movie","series","anime"],"idPrefixes":["tt","tmdb:","tvdb:"]}],
                    "catalogs":[]}"""
                } else {
                    """{"streams":[{"name":"AIOStreams TorBox","description":"Original.Release.2160p.HDR.mkv","url":"https://download.example/original.mkv","streamData":{"type":"debrid","service":{"id":"torbox"}}}]}"""
                }
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val gateway = StremioAddonGateway(JsonMapper.builder().build())
            val base = "http://127.0.0.1:${server.address.port}/stremio/user/a%2Fb"
            val manifest = gateway.manifest(base)
            val source = AddonConfiguration(UUID.randomUUID(), 1, "$base/manifest.json?token=a%2Bb", manifest, name = "My AIOStreams")
            val content = ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "tt1234"), MediaCategory.MOVIE, "Fixture")
            val group = gateway.releases(source, content)
            assertEquals("My AIOStreams", group.addonName)
            assertEquals(SourceOutcome.SUCCESS, group.outcome)
            assertEquals("AIOStreams TorBox\nOriginal.Release.2160p.HDR.mkv", group.releases.single().title)
            assertEquals(
                DebridProvider.TORBOX,
                group.releases
                    .single()
                    .provenance.boundProvider,
            )
            assertEquals(
                "My AIOStreams",
                group.releases
                    .single()
                    .provenance.addonName,
            )
            assertEquals(listOf("/stremio/user/a%2Fb/manifest.json", "/stremio/user/a%2Fb/stream/movie/tt1234.json?token=a%2Bb"), calls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reports HTTP and invalid manifest errors without exposing configuration or response bodies`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val status =
                exchange.requestURI.path
                    .split('/')[1]
                    .toInt()
            val body = "private-configuration"
            exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val gateway = StremioAddonGateway(JsonMapper.builder().build())
            mapOf(
                401 to "KZ.ADDON_UNAUTHORIZED",
                403 to "KZ.ADDON_UNAUTHORIZED",
                404 to "KZ.ADDON_NOT_FOUND",
                503 to "KZ.ADDON_HTTP_ERROR",
                200 to "KZ.ADDON_INVALID_MANIFEST",
            ).forEach { (status, code) ->
                val failure =
                    assertFailsWith<AddonFailure> {
                        gateway.manifest("http://127.0.0.1:${server.address.port}/$status/private-configuration/manifest.json")
                    }
                assertEquals(code, failure.code)
                assertFalse(failure.toString().contains("private-configuration"))
            }
        } finally {
            server.stop(0)
        }
    }
}
