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
import com.ynixt.dmdl.domain.model.RatingSource
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
    fun `merges catalog extras and preserves the missing extras marker for stored manifests`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body =
                """{"id":"fixture","name":"Fixture","catalogs":[
                {"id":"search","type":"movie","extra":[{"name":"search","isRequired":true}]},
                {"id":"genre","type":"series","extra":[{"name":"genre","isRequired":true}]},
                {"id":"legacy","type":"anime","extraRequired":["search"],"extraSupported":["skip"]},
                {"id":"mixed","type":"movie","extra":[{"name":"genre"}],"extraRequired":["genre"]},
                {"id":"pages","type":"movie","extra":[{"name":"search"},{"name":"skip"}]},
                {"id":"plain","type":"series"},
                {"id":"unsupported","type":"channel"},
                {"id":"required-options","type":"movie","extra":[{"name":"genre","options":["Action & Adventure","Drama"],"default":"Drama"},{"name":"skip"}],"extraRequired":["genre"]}
            ]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val mapper =
                JsonMapper
                    .builder()
                    .addModule(
                        tools.jackson.module.kotlin.KotlinModule
                            .Builder()
                            .build(),
                    ).build()
            val catalogs = StremioAddonGateway(mapper).manifest("http://127.0.0.1:${server.address.port}/manifest.json").catalogs
            assertEquals(listOf(false, false, false, false, true, true, false, true), catalogs.map { it.browsable() })
            assertEquals(listOf(false, false, false, false, true, false, false, true), catalogs.map { it.pageable() })
            assertEquals(listOf(true, false, true, false, true, false, false, false), catalogs.map { it.searchable })
            assertEquals(
                listOf("Action & Adventure", "Drama"),
                catalogs
                    .last()
                    .extra
                    ?.first()
                    ?.options,
            )
            assertEquals(
                "Drama",
                catalogs
                    .last()
                    .extra
                    ?.first()
                    ?.default,
            )
            assertEquals(
                catalogs.last(),
                mapper.readValue(mapper.writeValueAsString(catalogs.last()), com.ynixt.dmdl.domain.model.AddonCatalog::class.java),
            )
            val stored =
                mapper.readValue(
                    """{"id":"old","type":"movie","name":"Old","searchable":true}""",
                    com.ynixt.dmdl.domain.model.AddonCatalog::class.java,
                )
            assertEquals(null, stored.extra)
            assertFalse(stored.browsable())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `catalog pages use the base URL and skip path and map metadata identities`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = mutableListOf<String>()
        server.createContext("/") { exchange ->
            calls.add(exchange.requestURI.toASCIIString())
            val bytes = """{"metas":[{"id":"tt123","name":"Title","poster":"https://img/poster"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val catalog =
                com.ynixt.dmdl.domain.model
                    .AddonCatalog("popular movies", "movie", "Movies", false, emptyList())
            val source =
                AddonConfiguration(
                    UUID.randomUUID(),
                    7,
                    "http://127.0.0.1:${server.address.port}/config/manifest.json?key=a%2Bb",
                    AddonManifest("fixture", "Fixture", emptyList(), emptyList(), listOf(catalog)),
                )
            val gateway = StremioAddonGateway(JsonMapper.builder().build())
            val first = gateway.catalog(source, catalog, 0).single()
            assertEquals(ContentIdentity(source.id, 7, "movie", "tt123", mappedIds = mapOf("imdb" to "tt123")), first.identity)
            assertEquals("Title", first.title)
            assertEquals(first, gateway.catalog(source, catalog, 20).single())
            assertEquals(first, gateway.catalog(source, catalog, 20, mapOf("genre" to "Action & Adventure")).single())
            assertEquals(
                listOf(
                    "/config/catalog/movie/popular%20movies.json?key=a%2Bb",
                    "/config/catalog/movie/popular%20movies/skip=20.json?key=a%2Bb",
                    "/config/catalog/movie/popular%20movies/genre=Action%20%26%20Adventure&skip=20.json?key=a%2Bb",
                ),
                calls,
            )
        } finally {
            server.stop(0)
        }
    }

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

    @Test
    fun `parses rich metadata and optional episode fields with description fallback`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """{"meta":{"id":"tt123","type":"series","name":"Show","background":"https://img/background",
                "videos":[
                  {"id":"ep1","season":1,"episode":1,"thumbnail":"https://img/one","overview":"Overview","description":"Ignored","released":"2026-11-01T00:00:00Z"},
                  {"id":"ep2","season":1,"episode":2,"description":"Fallback"},
                  {"id":"special","season":0,"episode":1}
                ]}}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val source =
                AddonConfiguration(
                    UUID.randomUUID(),
                    1,
                    "http://127.0.0.1:${server.address.port}/manifest.json",
                    AddonManifest(
                        "meta",
                        "Meta",
                        listOf("series"),
                        listOf(AddonResource("meta", listOf("series"), emptyList())),
                        emptyList(),
                    ),
                )
            val result =
                StremioAddonGateway(JsonMapper.builder().build())
                    .details(source, ContentIdentity(source.id, source.revision, "series", "tt123"))
            assertEquals("https://img/background", result.background)
            assertEquals("https://img/one", result.videos[0].thumbnail)
            assertEquals("Overview", result.videos[0].overview)
            assertEquals("2026-11-01T00:00:00Z", result.videos[0].released)
            assertEquals("Fallback", result.videos[1].overview)
            assertEquals(null, result.videos[1].thumbnail)
            assertEquals(null, result.videos[1].released)
            assertEquals(null, result.videos[2].overview)
            assertEquals(emptyMap(), result.seasonPosters)
            assertEquals(MediaCategory.SERIES, result.category)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reads AIOMetadata cross references season posters and classifies from metadata alone`() {
        val bodies =
            mapOf(
                "/meta/series/tt2560140.json" to
                    """{"meta":{"id":"tt2560140","type":"series","name":"Attack on Titan","_imdbId":"tt2560140","_tmdbId":"1429",
                    "_tvdbId":"267440","app_extras":{"seasonPosters":{"0":"https://img/s0","1":"https://img/s1","x":"bad","2":null}}}}""",
                "/meta/series/mal:61469.json" to
                    """{"meta":{"id":"mal:61469","type":"series","name":"JoJo","_kitsuId":"49847","_anilistId":"190327",
                    "_anidbId":"19287","_tvdbId":"0","imdb_id":"not-imdb"}}""",
                "/meta/series/kitsu:7442.json" to """{"meta":{"id":"kitsu:7442","type":"series","name":"AoT"}}""",
                "/meta/anime/tt1.json" to """{"meta":{"id":"tt1","type":"anime","name":"Typed anime"}}""",
                "/meta/movie/tt5311514.json" to """{"meta":{"id":"tt5311514","type":"movie","name":"Your Name.","_kitsuId":"11617"}}""",
            )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = (bodies[exchange.requestURI.path] ?: """{}""").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val source =
                AddonConfiguration(
                    UUID.randomUUID(),
                    1,
                    "http://127.0.0.1:${server.address.port}/manifest.json",
                    AddonManifest(
                        "aiom",
                        "AIOMetadata",
                        listOf("movie", "series", "anime"),
                        listOf(AddonResource("meta", listOf("movie", "series", "anime"), emptyList())),
                        emptyList(),
                    ),
                )
            val gateway = StremioAddonGateway(JsonMapper.builder().build())

            fun details(
                type: String,
                id: String,
            ) = gateway.details(source, ContentIdentity(source.id, source.revision, type, id))

            val titan = details("series", "tt2560140")
            assertEquals(mapOf("imdb" to "tt2560140", "tmdb" to "1429", "tvdb" to "267440"), titan.identity.mappedIds)
            assertEquals(mapOf(0 to "https://img/s0", 1 to "https://img/s1"), titan.seasonPosters)
            assertEquals(MediaCategory.SERIES, titan.category)

            val jojo = details("series", "mal:61469")
            assertEquals(
                mapOf("mal" to "61469", "kitsu" to "49847", "anilist" to "190327", "anidb" to "19287"),
                jojo.identity.mappedIds,
            )
            assertEquals(MediaCategory.ANIME, jojo.category)
            assertEquals(mapOf("kitsu" to "7442"), details("series", "kitsu:7442").identity.mappedIds)
            assertEquals(MediaCategory.ANIME, details("series", "kitsu:7442").category)
            assertEquals(MediaCategory.ANIME, details("anime", "tt1").category)
            assertEquals(MediaCategory.MOVIE, details("movie", "tt5311514").category)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reads ratings with the source stated by the addon's imdb link and Cinemeta TMDB ids`() {
        val bodies =
            mapOf(
                // AIOMetadata: IMDb score linked to imdb.com, no episode ratings.
                "/meta/movie/tt0133093.json" to
                    """{"meta":{"id":"tt0133093","type":"movie","name":"Matrix","imdbRating":"8.7","_tmdbId":"603","_tvdbId":"169",
                    "links":[{"name":"Matrix","category":"share","url":"https://www.strem.io/s/movie/matrix"},
                    {"name":"8.7","category":"imdb","url":"https://imdb.com/title/tt0133093"}]}}""",
                // anime-kitsu: Kitsu's score in imdbRating, linked to kitsu.io.
                "/meta/series/kitsu:1.json" to
                    """{"meta":{"id":"kitsu:1","type":"series","name":"Cowboy Bebop","imdbRating":"8.2",
                    "links":[{"name":"8.2","category":"imdb","url":"https://kitsu.io/anime/cowboy-bebop"}],
                    "videos":[{"id":"kitsu:1:1","season":1,"episode":1}]}}""",
                // Cinemeta: moviedb_id as TMDB, numeric and zero episode ratings.
                "/meta/series/tt0903747.json" to
                    """{"meta":{"id":"tt0903747","type":"series","name":"Breaking Bad","imdbRating":"9.5","moviedb_id":1396,
                    "tvdb_id":81189,"links":[{"name":"9.5","category":"IMDb","url":"https://www.imdb.com/title/tt0903747"}],
                    "videos":[{"id":"tt0903747:1:1","season":1,"episode":1,"rating":"7.7"},
                    {"id":"tt0903747:1:2","season":1,"episode":2,"rating":8.1},
                    {"id":"tt0903747:1:3","season":1,"episode":3,"rating":"0"},
                    {"id":"tt0903747:1:4","season":1,"episode":4,"rating":" "},
                    {"id":"tt0903747:1:5","season":1,"episode":5,"rating":"N/A"}]}}""",
                "/meta/series/tt1.json" to
                    """{"meta":{"id":"tt1","type":"series","name":"Unknown host","imdbRating":7.9,"moviedb_id":"5","_tmdbId":"6",
                    "links":[{"category":"imdb","url":"https://ratings.example.com/tt1"}]}}""",
                "/meta/series/tt2.json" to
                    """{"meta":{"id":"tt2","type":"series","name":"Malformed link","imdbRating":"6.5",
                    "links":[{"category":"imdb","url":"not a url"}]}}""",
                "/meta/series/tt3.json" to
                    """{"meta":{"id":"tt3","type":"series","name":"No links","imdbRating":"0","links":"broken"}}""",
                "/meta/series/tt4.json" to """{"meta":{"id":"tt4","type":"series","name":"No rating","imdbRating":"N/A"}}""",
            )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = (bodies[exchange.requestURI.path] ?: """{}""").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val source =
                AddonConfiguration(
                    UUID.randomUUID(),
                    1,
                    "http://127.0.0.1:${server.address.port}/manifest.json",
                    AddonManifest(
                        "meta",
                        "Meta",
                        listOf("movie", "series"),
                        listOf(AddonResource("meta", listOf("movie", "series"), emptyList())),
                        emptyList(),
                    ),
                )
            val gateway = StremioAddonGateway(JsonMapper.builder().build())

            fun details(
                type: String,
                id: String,
            ) = gateway.details(source, ContentIdentity(source.id, source.revision, type, id))

            val matrix = details("movie", "tt0133093")
            assertEquals("8.7", matrix.rating)
            assertEquals(RatingSource.IMDB, matrix.ratingSource)

            val bebop = details("series", "kitsu:1")
            assertEquals("8.2", bebop.rating)
            assertEquals(RatingSource.KITSU, bebop.ratingSource)
            assertEquals(null, bebop.videos.single().rating)

            val breakingBad = details("series", "tt0903747")
            assertEquals("9.5", breakingBad.rating)
            assertEquals(RatingSource.IMDB, breakingBad.ratingSource)
            assertEquals("1396", breakingBad.identity.mappedIds["tmdb"])
            assertEquals(listOf("7.7", "8.1", null, null, null), breakingBad.videos.map { it.rating })

            val unknown = details("series", "tt1")
            assertEquals("7.9", unknown.rating)
            assertEquals(null, unknown.ratingSource)
            assertEquals("6", unknown.identity.mappedIds["tmdb"])

            val malformed = details("series", "tt2")
            assertEquals("6.5", malformed.rating)
            assertEquals(null, malformed.ratingSource)

            assertEquals(null, details("series", "tt3").rating)
            assertEquals(null, details("series", "tt3").ratingSource)
            assertEquals(null, details("series", "tt4").rating)
        } finally {
            server.stop(0)
        }
    }
}
