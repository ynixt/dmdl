package com.ynixt.dmdl.resource.gateway.kitsu

import com.sun.net.httpserver.HttpServer
import com.ynixt.dmdl.application.config.KitsuProperties
import com.ynixt.dmdl.domain.model.AnimeVerdict
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class KitsuAnimeIndexGatewayTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = CopyOnWriteArrayList<Map<String, String>>()

    /** Mappings Kitsu holds, as (site, externalId); returned for any listed site x listed id, like Kitsu. */
    private var mappings: List<Pair<String, String>> = emptyList()
    private var status = 200
    private var delayMillis = 0L
    private var pageSize = 20

    init {
        server.createContext("/api/edge/mappings") { exchange ->
            val params =
                exchange.requestURI.rawQuery.split("&").associate {
                    val (name, value) = it.split("=", limit = 2)
                    URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
                }
            requests += params
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val sites = params.getValue("filter[externalSite]").split(",")
            val ids = params.getValue("filter[externalId]").split(",")
            val offset = params["page[offset]"]?.toInt() ?: 0
            val matches = mappings.filter { (site, id) -> site in sites && id in ids }
            val page = matches.drop(offset).take(pageSize)
            val next =
                if (offset + pageSize < matches.size) {
                    "\"http://127.0.0.1:${server.address.port}/api/edge/mappings?" + exchange.requestURI.rawQuery +
                        "&page%5Boffset%5D=${offset + pageSize}\""
                } else {
                    "null"
                }
            val data =
                page.joinToString(",") { (site, id) ->
                    """{"type":"mappings","attributes":{"externalSite":"$site","externalId":"$id"}}"""
                }
            val body = if (status == 200) """{"data":[$data],"links":{"next":$next}}""" else """{"errors":[{}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun gateway() =
        KitsuAnimeIndexGateway(
            KitsuProperties("http://127.0.0.1:${server.address.port}/api/edge/", Duration.ofSeconds(1), Duration.ofMillis(500)),
            JsonMapper.builder().build(),
        )

    @Test
    fun `asks every site and id in one query`() {
        mappings = listOf("anidb" to "9541")
        val verdict = gateway().lookup(mapOf("tvdb" to "267440", "anilist" to "16498", "mal" to "16498", "anidb" to "9541"))
        assertEquals(AnimeVerdict.ANIME, verdict)
        assertEquals(1, requests.size)
        assertEquals(
            listOf("myanimelist/anime", "anilist/anime", "anidb", "thetvdb/series", "thetvdb"),
            requests.single().getValue("filter[externalSite]").split(","),
        )
        assertEquals(listOf("16498", "9541", "267440"), requests.single().getValue("filter[externalId]").split(","))
    }

    @Test
    fun `accepts the alternative TVDB site name`() {
        mappings = listOf("thetvdb" to "262954")
        assertEquals(AnimeVerdict.ANIME, gateway().lookup(mapOf("tvdb" to "262954")))
    }

    @Test
    fun `ignores cross matches between another site and another id`() {
        // Kitsu would return MAL 9541 because both "myanimelist/anime" and "9541" were listed, but our MAL id is 1.
        mappings = listOf("myanimelist/anime" to "9541")
        assertEquals(AnimeVerdict.NOT_ANIME, gateway().lookup(mapOf("mal" to "1", "anidb" to "9541")))
    }

    @Test
    fun `follows pages before concluding`() {
        pageSize = 2
        mappings = listOf("myanimelist/anime" to "5", "myanimelist/anime" to "5", "thetvdb" to "5")
        assertEquals(AnimeVerdict.ANIME, gateway().lookup(mapOf("tvdb" to "5", "mal" to "6")))
        assertEquals(2, requests.size)
    }

    @Test
    fun `answers not anime when Kitsu has no matching mapping`() {
        assertEquals(AnimeVerdict.NOT_ANIME, gateway().lookup(mapOf("tvdb" to "81189")))
    }

    @Test
    fun `failures and timeouts are unknown`() {
        status = 500
        assertEquals(AnimeVerdict.UNKNOWN, gateway().lookup(mapOf("tvdb" to "1")))
        status = 200
        delayMillis = 1_500
        assertEquals(AnimeVerdict.UNKNOWN, gateway().lookup(mapOf("tvdb" to "81189")))
    }

    @Test
    fun `makes no request without ids it can ask with`() {
        assertEquals(AnimeVerdict.UNKNOWN, gateway().lookup(mapOf("imdb" to "tt1", "tmdb" to "1")))
        assertEquals(AnimeVerdict.UNKNOWN, gateway().lookup(emptyMap()))
        assertEquals(emptyList(), requests)
    }
}
