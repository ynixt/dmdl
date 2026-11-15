package com.ynixt.dmdl

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.Aria2TransferState
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DeliveryState
import com.ynixt.dmdl.domain.model.DeliveryTrigger
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.DownloadControl
import com.ynixt.dmdl.domain.model.DownloadFailure
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.DownloadWakeUp
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.SourceOutcome
import com.ynixt.dmdl.domain.model.StorageDestination
import com.ynixt.dmdl.domain.model.StreamBadgeRules
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookHeader
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.domain.repository.WebhookDeliveryStore
import com.ynixt.dmdl.domain.service.DiscoveryService
import com.ynixt.dmdl.domain.service.DownloadService
import com.ynixt.dmdl.domain.service.WebhookService
import com.ynixt.dmdl.resource.cache.DiscoveryCache
import com.ynixt.dmdl.support.IntegrationEnvironment
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.session.data.redis.RedisIndexedSessionRepository
import tools.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DownloadAcceptanceTest : IntegrationEnvironment() {
    @Autowired lateinit var configuration: ConfigurationStore

    @Autowired lateinit var gateway: AddonGateway

    @Autowired lateinit var discovery: DiscoveryService

    @Autowired lateinit var downloads: DownloadService

    @Autowired lateinit var store: DownloadStore

    @Autowired lateinit var deliveries: WebhookDeliveryStore

    @Autowired lateinit var webhooks: WebhookService

    @Autowired lateinit var aria: Aria2Gateway

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var json: ObjectMapper

    @Autowired lateinit var sessions: RedisIndexedSessionRepository

    @Autowired lateinit var passwords: PasswordEncoder

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var discoveryCache: DiscoveryCache

    @Autowired lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `Redis discovery entries enforce lifetimes refresh generations and addon configuration isolation`() {
        configure()
        val metadata = configuration.metadata()!!
        val query = DiscoveryQuery("movie", "custom-search", "TTL-${UUID.randomUUID()}")
        val catalogCalls = { calls.count { it.contains("/catalog/") } }
        val beforeSearch = catalogCalls()
        val identity = discovery.search(query).single().identity
        discovery.search(query.copy(query = " ${query.query} "))
        assertEquals(beforeSearch + 1, catalogCalls())
        val searchKey = redisTemplate.keys("dmdl:search-v2:${metadata.id}:${metadata.revision}:*").single()
        assertTrue(redisTemplate.getExpire(searchKey) in 86390..86400)

        val beforeDetails = calls.count { it.contains("/meta/") }
        val details = discovery.details(identity)
        discovery.details(identity.copy(mappedIds = mapOf("tmdb" to "1396")))
        assertEquals(beforeDetails + 1, calls.count { it.contains("/meta/") })
        val metadataKey = redisTemplate.keys("dmdl:meta-v5:${metadata.id}:${metadata.revision}:*").single()
        assertTrue(redisTemplate.getExpire(metadataKey) in 259190..259200)
        assertTrue(redisTemplate.opsForValue().get(metadataKey)!!.contains(details.title))
        discovery.details(identity, true)
        assertEquals(beforeDetails + 2, calls.count { it.contains("/meta/") })
        discovery.details(identity)
        assertEquals(beforeDetails + 2, calls.count { it.contains("/meta/") })

        configuration.saveMetadata(
            metadata.copy(
                manifestUrl = "${base()}/metadata-replacement/manifest.json",
                revision =
                    metadata.revision + 1,
            ),
        )
        val replacement = configuration.metadata()!!
        discovery.search(query)
        assertEquals(beforeSearch + 2, catalogCalls())
        assertTrue(discovery.details(identity.copy(sourceRevision = replacement.revision)).title.startsWith("Replacement"))

        val source = configuration.searchAddons().first { it.name == "fast" }
        val content = ContentSnapshot(identity.copy(sourceRevision = replacement.revision), MediaCategory.MOVIE, details.title)
        val streamCalls = { calls.count { it.contains("/fast/stream/") } }
        val beforeReleases = streamCalls()
        discovery.releasePage(content, source.id, 1, false)
        discovery.releasePage(
            content.copy(title = "Updated presentation", posterUrl = "https://example.com/poster.jpg"),
            source.id,
            1,
            false,
        )
        assertEquals(beforeReleases + 1, streamCalls())
        val releaseKey = redisTemplate.keys("dmdl:releases-v5:${source.id}:${source.revision}:*").single()
        assertTrue(redisTemplate.getExpire(releaseKey) in 86390..86400)
        discovery.releasePage(content, source.id, 1, true)
        assertEquals(beforeReleases + 2, streamCalls())
        assertTrue(redisTemplate.getExpire(releaseKey) in 86390..86400)
        configuration.replaceSearchAddons(
            configuration.searchAddons().map {
                if (it.id ==
                    source.id
                ) {
                    it.copy(revision = it.revision + 1)
                } else {
                    it
                }
            },
        )
        discovery.releasePage(content, source.id, 1, false)
        assertEquals(beforeReleases + 3, streamCalls())

        val key = "dmdl:refresh-regression:${UUID.randomUUID()}"
        val original = discoveryCache.lookup(key)
        discoveryCache.put(key, "old", DiscoveryCache.RESULTS_TTL, original.generation)
        val refreshed = discoveryCache.lookup(key, true)
        assertNull(refreshed.value)
        assertNull(redisTemplate.opsForValue().get(key))
        discoveryCache.put(key, "stale in-flight response", DiscoveryCache.RESULTS_TTL, original.generation)
        assertNull(discoveryCache.lookup(key).value)
        discoveryCache.put(key, "fresh", DiscoveryCache.RESULTS_TTL, refreshed.generation)
        assertEquals("fresh", discoveryCache.lookup(key).value)
        discoveryCache.put(key, "late stale response", DiscoveryCache.RESULTS_TTL, original.generation)
        assertEquals("fresh", discoveryCache.lookup(key).value)
        redisTemplate.expire(key, Duration.ZERO)
        assertNull(discoveryCache.lookup(key).value)
        redisTemplate.delete("$key:generation")
    }

    @Test
    @Order(1)
    fun `wizard validates drafts then commits once with rollback retry receipt and multiple destination roots`() {
        val origin = "http://127.0.0.1:${environment.getProperty("local.server.port")}"
        val client = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

        fun post(
            path: String,
            body: Any,
        ): HttpResponse<String> =
            client.send(
                HttpRequest
                    .newBuilder(URI(origin + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, post("/api/auth/login", mapOf("username" to "fixture-admin", "password" to "integration-password")).statusCode())
        val before = configuration.settings()!!
        val categories = OrganizationSettings.defaults(media.toString()).toMutableMap()
        val movie = categories.getValue(MediaCategory.MOVIE)
        val ssd = StorageDestination(UUID.randomUUID(), "SSD", media.resolve("ssd/movies").toString())
        categories[MediaCategory.MOVIE] = movie.copy(destinations = movie.destinations + ssd)
        val organization = OrganizationSettings(categories = categories)
        val draft =
            mapOf(
                "settings" to
                    mapOf(
                        "locale" to "pt-BR",
                        "organization" to organization,
                        "streamBadgeJson" to """{"filters":[{"name":"Setup badge","pattern":".*"}]}""",
                    ),
                "metadataUrl" to "${base()}/metadata/manifest.json",
                "metadataName" to "My metadata",
                "search" to listOf(mapOf("manifestUrl" to "${base()}/slow", "name" to "My search", "enabled" to true)),
            )
        listOf("metadata", "search", "storage").forEach { step ->
            val validation = post("/api/setup/validate", mapOf("step" to step, "draft" to draft))
            assertEquals(200, validation.statusCode(), validation.body())
            assertTrue(json.readTree(validation.body())["valid"].asBoolean(), validation.body())
        }
        assertEquals(before, configuration.settings())
        assertNull(configuration.metadata())
        assertTrue(configuration.searchAddons().isEmpty())

        val request = mapOf("draft" to draft, "expectedRevision" to before.configurationRevision, "submissionId" to UUID.randomUUID())
        val invalid = post("/api/setup/complete", request + ("draft" to draft + ("metadataUrl" to "${base()}/slow/manifest.json")))
        assertEquals(400, invalid.statusCode())
        assertFalse(invalid.body().contains("fixture-TORBOX"))
        assertEquals(before, configuration.settings())

        jdbc.execute(
            "CREATE TRIGGER reject_setup_search BEFORE INSERT ON search_addon BEGIN SELECT RAISE(ABORT, 'fixture persistence failure'); END",
        )
        try {
            assertEquals(500, post("/api/setup/complete", request).statusCode())
            assertEquals(before, configuration.settings())
            assertNull(configuration.metadata())
            assertTrue(configuration.searchAddons().isEmpty())
        } finally {
            jdbc.execute("DROP TRIGGER reject_setup_search")
        }
        val completed = post("/api/setup/complete", request)
        assertEquals(200, completed.statusCode(), completed.body())
        assertTrue(configuration.settings()!!.onboardingCompleted)
        assertEquals(
            "Setup badge",
            StreamBadgeRules
                .parse(configuration.settings()!!.streamBadgeJson)
                .match("Release")
                .single()
                .name,
        )
        assertEquals("My metadata", configuration.metadata()!!.name)
        assertEquals("My search", configuration.searchAddons().single().name)
        assertEquals("${base()}/slow/manifest.json", configuration.searchAddons().single().manifestUrl)
        assertEquals(organization.categories, configuration.settings()!!.organization.categories)
        assertEquals(
            2,
            configuration
                .settings()!!
                .organization.categories
                .getValue(MediaCategory.MOVIE)
                .destinations.size,
        )
        val saved = configuration.settings()
        val sourceIds = configuration.searchAddons().map { it.id }
        assertEquals(200, post("/api/setup/complete", request).statusCode())
        assertEquals(saved, configuration.settings())
        assertEquals(sourceIds, configuration.searchAddons().map { it.id })
        assertEquals(409, post("/api/setup/complete", request + ("submissionId" to UUID.randomUUID())).statusCode())
        assertEquals(409, post("/api/setup/complete", request + ("draft" to draft + ("metadataUrl" to "changed"))).statusCode())
        client.close()
    }

    @Test
    @Order(0)
    fun `fresh installation registers exactly one account then requires login and onboarding`() {
        val origin = "http://127.0.0.1:${environment.getProperty("local.server.port")}"
        val client = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

        fun request(
            path: String,
            body: Any? = null,
            method: String = if (body == null) "GET" else "POST",
        ): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(origin + path)).header("Content-Type", "application/json")
            builder.method(
                method,
                if (body ==
                    null
                ) {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))
                },
            )
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }

        assertNull(configuration.settings(), "Startup must leave account creation to registration")
        assertTrue(json.readTree(request("/api/auth/registration").body())["registrationRequired"].asBoolean())
        assertEquals(401, request("/api/settings").statusCode())
        assertEquals(401, request("/api/auth/login", mapOf("username" to "admin", "password" to "admin")).statusCode())

        listOf(
            mapOf("username" to "  ", "password" to "integration-password"),
            mapOf("username" to "fixture-admin", "password" to "short"),
            mapOf("username" to "fixture-admin", "password" to "é".repeat(40)),
            mapOf("username" to "fixture-admin", "password" to "integration-password", "locale" to "invalid"),
        ).forEach { input -> assertEquals(400, request("/api/auth/register", input).statusCode()) }
        assertNull(configuration.settings())

        val input = mapOf("username" to "fixture-admin", "password" to "secret", "locale" to "pt-BR")
        val start = CountDownLatch(1)
        val attempts =
            (1..2).map {
                CompletableFuture.supplyAsync {
                    start.await()
                    request("/api/auth/register", input).statusCode()
                }
            }
        start.countDown()
        assertEquals(listOf(201, 409), attempts.map { it.get(20, TimeUnit.SECONDS) }.sorted())
        assertFalse(json.readTree(request("/api/auth/registration").body())["registrationRequired"].asBoolean())
        assertEquals(401, request("/api/auth/session").statusCode(), "Registration must require a subsequent login")
        assertEquals(409, request("/api/auth/register", input + ("password" to "replacement-password")).statusCode())
        val account = requireNotNull(configuration.settings())
        assertTrue(passwords.matches("secret", account.passwordHash))
        assertFalse(passwords.matches("replacement-password", account.passwordHash))
        assertFalse(account.onboardingCompleted)
        assertEquals(StreamBadgeRules.defaultJson, account.streamBadgeJson)

        val login = request("/api/auth/login", input.filterKeys { it != "locale" })
        assertEquals(200, login.statusCode())
        assertFalse(json.readTree(login.body())["onboardingCompleted"].asBoolean())
        assertEquals("pt-BR", json.readTree(login.body())["locale"].asString())
        assertEquals(200, request("/api/auth/session").statusCode())
        assertFalse(json.readTree(request("/api/setup").body())["completed"].asBoolean())
        assertEquals(400, request("/api/setup/complete", emptyMap<String, String>()).statusCode())
        assertEquals(
            400,
            request("/api/auth/password", mapOf("currentPassword" to "secret", "newPassword" to "short"), "PUT").statusCode(),
        )
        assertEquals(
            200,
            request("/api/auth/password", mapOf("currentPassword" to "secret", "newPassword" to "change"), "PUT").statusCode(),
        )
        assertTrue(passwords.matches("change", requireNotNull(configuration.settings()).passwordHash))
        assertEquals(
            200,
            request(
                "/api/auth/password",
                mapOf("currentPassword" to "change", "newPassword" to "integration-password"),
                "PUT",
            ).statusCode(),
        )
    }

    @Test
    fun `authenticated setup discovery and previews work through the API`() {
        configure()
        val origin = "http://127.0.0.1:${environment.getProperty("local.server.port")}"
        val cookies = CookieManager()
        val client = HttpClient.newBuilder().cookieHandler(cookies).build()

        fun request(
            path: String,
            body: Any? = null,
            method: String = if (body == null) "GET" else "POST",
        ): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(origin + path)).header("Content-Type", "application/json")
            builder.method(
                method,
                if (body ==
                    null
                ) {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))
                },
            )
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        assertEquals(401, request("/api/settings").statusCode())
        assertEquals(
            200,
            request("/api/auth/login", mapOf("username" to "fixture-admin", "password" to "integration-password")).statusCode(),
        )
        assertEquals(200, request("/api/auth/session").statusCode())
        val sessionCookie = cookies.cookieStore.cookies.single { it.name == "SESSION" }
        val sessionId = String(Base64.getDecoder().decode(sessionCookie.value.trim('"')), Charsets.UTF_8)
        assertTrue(sessions.findById(sessionId) != null, "Authenticated session must be persisted in Redis")
        val settings = request("/api/settings")
        assertEquals(200, settings.statusCode())
        assertFalse(settings.body().contains("integration-password"))
        assertTrue(json.readTree(request("/api/setup").body())["ready"].asBoolean())
        assertEquals(400, request("/api/setup/complete", emptyMap<String, String>()).statusCode())
        val storedSearch = configuration.searchAddons()
        val storedMetadata = configuration.metadata()

        fun addonError(
            path: String,
            body: Any,
        ): Map<String, String> {
            val response = request(path, body, "PUT")
            assertEquals(400, response.statusCode(), response.body())
            assertFalse(response.body().contains("fixture-secret"))
            return json.readTree(response.body())["errors"].properties().associate { it.key to it.value.asString() }
        }
        assertEquals(
            mapOf("search.0.manifestUrl" to "KZ.ADDON_MISSING_STREAM"),
            addonError("/api/addons/search", listOf(mapOf("manifestUrl" to "${base()}/metadata/manifest.json"))),
        )
        assertEquals(
            mapOf("search.${storedSearch.size}.manifestUrl" to "KZ.ADDON_INVALID_MANIFEST"),
            addonError(
                "/api/addons/search",
                storedSearch.map { mapOf("id" to it.id, "manifestUrl" to it.manifestUrl, "enabled" to it.enabled) } +
                    mapOf("manifestUrl" to "${base()}/malformed/manifest.json?token=fixture-secret", "enabled" to false),
            ),
        )
        assertEquals(
            mapOf("metadata.manifestUrl" to "KZ.ADDON_MISSING_META"),
            addonError("/api/addons/metadata", mapOf("manifestUrl" to "${base()}/catalog-only/manifest.json")),
        )
        assertEquals(
            mapOf("metadata.manifestUrl" to "KZ.ADDON_UNAVAILABLE"),
            addonError("/api/addons/metadata", mapOf("manifestUrl" to "http://127.0.0.1:1/fixture-secret/manifest.json")),
        )
        assertEquals(storedSearch, configuration.searchAddons())
        assertEquals(storedMetadata, configuration.metadata())
        val source = configuration.metadata()!!
        val search = discovery.search(DiscoveryQuery("movie", "custom-search", "Example"))
        assertEquals("opaque:movie", search.single().identity.contentId)
        val before = discovery.details(search.single().identity)
        assertEquals("1396", before.identity.mappedIds["tmdb"])
        assertEquals("81189", before.identity.mappedIds["tvdb"])
        assertEquals(
            200,
            request("/api/addons/metadata", mapOf("manifestUrl" to "${base()}/metadata-replacement/manifest.json"), "PUT").statusCode(),
        )
        val replaced = discovery.details(search.single().identity.copy(sourceRevision = source.revision + 1))
        assertTrue(replaced.title.startsWith("Replacement"))
        assertFalse(before.title == replaced.title)
        assertFailsWith<IllegalArgumentException> { discovery.details(before.identity) }
        val existing = configuration.searchAddons()
        val existingAddonIds = existing.map { it.id }.toSet()

        fun searchInput(addon: AddonConfiguration) =
            mapOf(
                "id" to addon.id,
                "manifestUrl" to addon.manifestUrl,
                "name" to addon.name,
                "enabled" to addon.enabled,
            )
        assertEquals(
            200,
            request(
                "/api/addons/search",
                existing.map(::searchInput) + mapOf("manifestUrl" to "${base()}/fast/manifest.json?token=fixture-secret"),
                "PUT",
            ).statusCode(),
        )
        val added = configuration.searchAddons().single { it.id !in existingAddonIds }
        assertEquals(
            added.manifestUrl,
            json
                .readTree(request("/api/addons").body())["search"]
                .first { it["id"].asString() == added.id.toString() }["manifestUrl"]
                .asString(),
        )
        assertEquals(
            200,
            request(
                "/api/addons/search",
                configuration.searchAddons().map { searchInput(if (it.id == added.id) it.copy(enabled = false) else it) },
                "PUT",
            ).statusCode(),
        )
        assertFalse(configuration.searchAddons().single { it.id == added.id }.enabled)
        assertEquals(200, request("/api/addons/search", existing.map(::searchInput), "PUT").statusCode())
        val previewContent = content(MediaCategory.MOVIE)
        val resolvedContent = request("/api/discovery/meta?kind=movie&contentId=opaque%3Amovie")
        assertEquals(200, resolvedContent.statusCode(), resolvedContent.body())
        val selectedSource = configuration.searchAddons().first { it.enabled }
        val releasePage = request("/api/discovery/releases/page?addonId=${selectedSource.id}&page=1", previewContent)
        assertEquals(200, releasePage.statusCode(), releasePage.body())
        val pageNode = json.readTree(releasePage.body())
        assertEquals(20, pageNode["pageSize"].asInt())
        assertTrue(pageNode["group"]["releases"].size() <= 20)
        assertEquals(400, request("/api/discovery/releases/page?addonId=${selectedSource.id}&page=0", previewContent).statusCode())
        val badgeJson = """{"filters":[{"name":"Fixture badge","pattern":".*","tagColor":"#FF112233","tagStyle":"filled"}]}"""
        assertEquals(200, request("/api/settings", mapOf("streamBadgeJson" to badgeJson), "PUT").statusCode())
        assertEquals(badgeJson, configuration.settings()!!.streamBadgeJson)
        assertEquals(badgeJson, json.readTree(request("/api/settings").body())["streamBadgeJson"].asString())
        assertEquals(400, request("/api/settings", mapOf("streamBadgeJson" to "invalid"), "PUT").statusCode())
        assertEquals(badgeJson, configuration.settings()!!.streamBadgeJson)
        assertEquals(
            StreamBadgeRules.defaultJson,
            json.readTree(request("/api/settings/stream-badges/defaults").body())["streamBadgeJson"].asString(),
        )
        val initialReleases = request("/api/discovery/releases", previewContent)
        assertEquals(200, initialReleases.statusCode(), initialReleases.body())
        val releaseNodes = json.readTree(initialReleases.body()).flatMap { it["releases"].toList() }
        assertTrue(releaseNodes.isNotEmpty())
        assertTrue(releaseNodes.all { it["badges"].single()["name"].asString() == "Fixture badge" })
        val beforeRefresh = calls.count { it.contains("/stream/") && !it.contains("/error/") }
        val failedSourceCalls = calls.count { it.contains("/error/stream/") }
        assertEquals(200, request("/api/discovery/releases", previewContent).statusCode())
        assertEquals(beforeRefresh, calls.count { it.contains("/stream/") && !it.contains("/error/") })
        assertEquals(failedSourceCalls + 1, calls.count { it.contains("/error/stream/") })
        assertEquals(200, request("/api/discovery/releases?forceRefresh=true", previewContent).statusCode())
        assertTrue(calls.count { it.contains("/stream/") && !it.contains("/error/") } > beforeRefresh)
        val defaults = request("/api/settings/organization/defaults")
        assertEquals(200, defaults.statusCode())
        val preview =
            request(
                "/api/settings/organization/preview",
                mapOf(
                    "organization" to json.readTree(defaults.body()),
                    "content" to previewContent,
                    "originalFileName" to "Example.Release.mp4",
                ),
            )
        assertEquals(200, preview.statusCode())
        assertTrue(json.readTree(preview.body())["path"].asString().endsWith(".mp4"))
        assertEquals(media.toString(), json.readTree(request("/api/settings").body())["storageRoot"].asString())
        val jellyfinHook =
            mapOf(
                "name" to "Jellyfin",
                "method" to "POST",
                "url" to "${base()}/jellyfin/Library/Refresh",
                "headers" to listOf(mapOf("name" to "Authorization", "value" to "MediaBrowser Token=\"fixture-token\"")),
                "bodyMode" to "NONE",
                "events" to listOf("download.completed"),
            )
        val start = calls.size
        val tested = request("/api/webhooks/test", mapOf("webhook" to jellyfinHook, "event" to "download.completed"))
        assertEquals(200, tested.statusCode(), tested.body())
        assertTrue(json.readTree(tested.body())["ok"].asBoolean(), tested.body())
        assertEquals(listOf("POST /jellyfin/Library/Refresh"), calls.drop(start).filter { it.contains("/jellyfin/") })
        val rejected =
            json.readTree(
                request(
                    "/api/webhooks/test",
                    mapOf(
                        "webhook" to
                            jellyfinHook +
                            ("headers" to listOf(mapOf("name" to "Authorization", "value" to "MediaBrowser Token=\"wrong-token\""))),
                    ),
                ).body(),
            )
        assertFalse(rejected["ok"].asBoolean())
        assertEquals(401, rejected["status"].asInt())
        assertEquals("[]", request("/api/webhooks").body())
        val events =
            client.send(
                HttpRequest.newBuilder(URI("$origin/api/downloads/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        assertEquals(200, events.statusCode())
        events.body().bufferedReader().use { assertEquals("event:downloads", it.readLine()) }
        client.close()
    }

    @Test
    fun `pause local lost-wakeup recovery daemon restart and cancellation preserve request ownership`() {
        configure()
        slowMedia = true
        try {
            val movie = content(MediaCategory.MOVIE)
            val submitted =
                downloads.submit(
                    movie,
                    discovery
                        .releases(movie)
                        .first()
                        .releases
                        .last(),
                    destinationId = destination((movie).category),
                )
            val active = await(submitted.id, DownloadState.TRANSFERRING)
            val gid = active.execution.aria2Gid!!
            waitUntil { aria.findStatus(gid)?.state == Aria2TransferState.ACTIVE }
            val tuning = configuration.settings()!!
            assertEquals(false, tuning.transfer.lowestSpeedLimitEnabled)
            assertEquals("0", rpc("aria2.getOption", listOf(gid))["lowest-speed-limit"].asString())
            configuration.saveSettings(tuning.copy(transfer = tuning.transfer.copy(split = 6, maxConnectionPerServer = 2)))
            downloads.drain()
            assertEquals("2", rpc("aria2.getOption", listOf(gid))["split"].asString())
            assertEquals("2", rpc("aria2.getOption", listOf(gid))["max-connection-per-server"].asString())
            assertEquals("0", rpc("aria2.getOption", listOf(gid))["lowest-speed-limit"].asString())
            val firstPause = downloads.control(active.id, active.execution.generation, DownloadControl.PAUSE)
            waitUntil { aria.findStatus(gid)?.state == Aria2TransferState.PAUSED }
            downloads.control(firstPause.id, firstPause.execution.generation, DownloadControl.RESUME)
            val resumed = await(active.id, DownloadState.TRANSFERRING)
            waitUntil { aria.findStatus(gid)?.state == Aria2TransferState.ACTIVE }
            val paused = downloads.control(resumed.id, resumed.execution.generation, DownloadControl.PAUSE)
            waitUntil { aria.findStatus(gid)?.state == Aria2TransferState.PAUSED }
            val creates = calls.count { it.endsWith("/createtorrent") }
            configuration.saveSettings(
                configuration.settings()!!.copy(maxConcurrentDownloads = 1),
            )
            store.changeState(paused.id, paused.execution.generation, DownloadState.QUEUED)
            val reserved = store.reserveNext(UUID.randomUUID())!!
            assertEquals(paused.id, reserved.id)
            downloads.reconcileStartup()
            assertEquals("1", rpc("aria2.getGlobalOption", emptyList())["max-concurrent-downloads"].asString())
            downloads.execute(DownloadWakeUp(active.id, active.execution.generation))
            waitUntil { aria.findStatus(gid)?.state == Aria2TransferState.ACTIVE }
            aria2.dockerClient.restartContainerCmd(aria2.containerId).exec()
            val completed = await(active.id, DownloadState.COMPLETED)
            assertEquals(gid, completed.execution.aria2Gid)
            assertEquals(active.execution.providerTorrentId, completed.execution.providerTorrentId)
            assertEquals(creates, calls.count { it.endsWith("/createtorrent") })
            val next = content(MediaCategory.MOVIE)
            val cancel =
                downloads.submit(
                    next,
                    discovery
                        .releases(next)
                        .first()
                        .releases
                        .last(),
                    destinationId = destination((next).category),
                )
            val transferring = await(cancel.id, DownloadState.TRANSFERRING)
            val cleanupCalls = calls.size
            downloads.control(transferring.id, transferring.execution.generation, DownloadControl.CANCEL)
            assertEquals(DownloadState.CANCELLED, store.find(cancel.id)?.state)
            assertFalse(calls.drop(cleanupCalls).any { it.contains("/torbox/") })
            assertFalse(calls.drop(cleanupCalls).any { it.contains("/realdebrid/") })
            assertNull(store.find(cancel.id)?.provider)
        } finally {
            slowMedia = false
        }
    }

    @Test
    fun `HTTP failures retry the same task and repeated requests detect destination conflicts`() {
        configure()
        val movie = content(MediaCategory.MOVIE)
        val release =
            discovery
                .releases(movie)
                .first()
                .releases
                .last()
        failMedia.set(1)
        val submitted = downloads.submit(movie, release, destination(movie.category))
        val failed = await(submitted.id, DownloadState.FAILED)
        assertEquals("KZ.HTTP_LINK_FAILED", failed.error)
        downloads.control(failed.id, failed.execution.generation, DownloadControl.RETRY)
        val completed = await(failed.id, DownloadState.COMPLETED)
        assertEquals(failed.execution.aria2Gid, completed.execution.aria2Gid)
        assertNull(completed.provider)
        val repeated = downloads.submit(movie, release, destination(movie.category))
        val conflict = await(repeated.id, DownloadState.FAILED)
        assertTrue(conflict.error!!.contains("Destination conflict"))
        assertTrue(Files.exists(Path.of(completed.execution.finalPath!!)))
        downloads.control(conflict.id, conflict.execution.generation, DownloadControl.CANCEL)
    }

    private fun rpc(
        method: String,
        params: List<Any>,
    ): tools.jackson.databind.JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI("http://${aria2.host}:${aria2.getMappedPort(6800)}/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        json.writeValueAsBytes(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to "fixture",
                                "method" to method,
                                "params" to (listOf("token:fixture-rpc-secret") + params),
                            ),
                        ),
                    ),
                ).build()
        val response = json.readTree(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray()).body())
        assertNull(response["error"])
        return response["result"]!!
    }

    private fun waitUntil(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(100)
        }
        fail("Expected daemon state was not reached")
    }

    @Test
    fun `movie and explicit episode HTTP downloads support optional service metadata`() {
        configure()
        val initialCalls = calls.size
        listOf(MediaCategory.MOVIE, MediaCategory.ANIME).forEach { category ->
            val selected = content(category)
            val groups = discovery.releases(selected)
            assertEquals(listOf("slow", "fast", "error", "imdb"), groups.map { it.addonName })
            assertEquals(listOf("HTTP release", "Ordered release"), groups.first().releases.map { it.title })
            assertEquals(SourceOutcome.ERROR, groups[2].outcome)
            assertEquals(SourceOutcome.INCOMPATIBLE, groups[3].outcome)
            val release = groups.first().releases.first()
            val request = downloads.submit(selected, release, destination(category))
            val completed = await(request.id, DownloadState.COMPLETED)
            assertEquals(DebridProvider.TORBOX, completed.provider)
            assertTrue(Files.isRegularFile(Path.of(completed.execution.finalPath!!)))
            assertEquals(payload.size.toLong(), Files.size(Path.of(completed.execution.finalPath!!)))
            assertEquals(completed.execution.originalFileName, Path.of(completed.execution.finalPath!!).fileName.toString())
            assertTrue(deliveries.forRequest(completed.id).isEmpty())
        }
        assertFalse(calls.drop(initialCalls).any { it.endsWith("/Library/Refresh") || it.contains("/torbox/") })
    }

    @Test fun `webhook failure and explicit resend do not repeat transfer or adopt later naming settings`() {
        configure()
        val old = configuration.settings()!!
        val hook =
            Webhook(
                UUID.randomUUID(),
                "Jellyfin",
                "POST",
                "${base()}/jellyfin/Library/Refresh",
                listOf(WebhookHeader("Authorization", "MediaBrowser Token=\"fixture-token\"")),
                events = setOf(WebhookEvent.REQUESTED, WebhookEvent.COMPLETED),
                manual = true,
            )
        webhooks.replace(listOf(hook))
        refreshStatus = 401
        val content = content(MediaCategory.MOVIE)
        val submitted =
            downloads.submit(
                content,
                discovery
                    .releases(content)
                    .first()
                    .releases
                    .last(),
                destinationId = destination((content).category),
            )
        configuration.saveSettings(
            configuration.settings()!!.copy(
                organization =
                    OrganizationSettings(
                        2,
                        OrganizationSettings.defaults(media.toString()).mapValues { (_, value) ->
                            value.copy(folderTemplate = "Changed - {title}")
                        },
                    ),
            ),
        )
        val completed = await(submitted.id, DownloadState.COMPLETED)
        assertEquals(old.organization.revision, completed.organization.revision)
        assertFalse(
            Path
                .of(completed.execution.finalPath!!)
                .fileName
                .toString()
                .startsWith("Changed"),
        )
        webhooks.dispatchPending()
        waitUntil { deliveries.forRequest(completed.id).none { it.state == DeliveryState.PENDING || it.inProgress } }
        val automatic = deliveries.forRequest(completed.id)
        assertEquals(listOf("download.requested", "download.completed"), automatic.map { it.event })
        assertTrue(automatic.all { it.state == DeliveryState.FAILED && it.httpStatus == 401 })
        val failed = automatic.last()
        val transfers = calls.count { it == "GET /file.mkv" }
        refreshStatus = 204
        assertEquals(WebhookActionResult.OK, webhooks.resend(completed.id, failed.id))
        waitUntil { deliveries.forRequest(completed.id).single { it.id == failed.id }.state == DeliveryState.SENT }
        val resent = deliveries.forRequest(completed.id).single { it.id == failed.id }
        assertEquals("download.completed", resent.event)
        assertEquals(2, resent.attempts)
        assertEquals(WebhookActionResult.INELIGIBLE, webhooks.resend(completed.id, failed.id))
        assertEquals(WebhookActionResult.OK, webhooks.dispatch(completed.id, listOf(hook.id)))
        waitUntil { deliveries.forRequest(completed.id).any { it.trigger == DeliveryTrigger.MANUAL && it.state == DeliveryState.SENT } }
        assertEquals(3, deliveries.forRequest(completed.id).size)
        assertEquals(DownloadState.COMPLETED, store.find(completed.id)?.state)
        assertEquals(transfers, calls.count { it == "GET /file.mkv" })
        val later = content(MediaCategory.MOVIE)
        val newRequest =
            downloads.submit(
                later,
                discovery
                    .releases(later)
                    .first()
                    .releases
                    .last(),
                destinationId = destination((later).category),
            )
        val renamed = await(newRequest.id, DownloadState.COMPLETED)
        assertTrue(
            Path
                .of(renamed.execution.finalPath!!)
                .parent
                .fileName
                .toString()
                .startsWith("Changed"),
        )
        assertEquals(2, renamed.organization.revision)
        webhooks.replace(emptyList())
    }

    private fun destination(category: MediaCategory): UUID =
        requireNotNull(
            configuration
                .settings()!!
                .organization.categories
                .getValue(category)
                .defaultDestinationId,
        )

    @Test
    fun `real transfer advances live API and stream progress and cancellation removes partial disk data`() {
        configure()
        val bytes = ByteArray(4 * 1024 * 1024)
        payload.copyInto(bytes)
        http.createContext("/progress-file.mkv") { exchange ->
            val start =
                exchange.requestHeaders
                    .getFirst("Range")
                    ?.substringAfter("bytes=")
                    ?.substringBefore('-')
                    ?.toIntOrNull() ?: 0
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=progress-fixture.mkv")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (start > 0) exchange.responseHeaders.add("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, (bytes.size - start).toLong())
            runCatching {
                exchange.responseBody.use { output ->
                    var offset = start
                    while (offset < bytes.size) {
                        val size = minOf(8192, bytes.size - offset)
                        output.write(bytes, offset, size)
                        output.flush()
                        offset += size
                        Thread.sleep(10)
                    }
                }
            }
        }
        http.createContext("/progress-addon/stream/") { exchange ->
            val body =
                json.writeValueAsBytes(
                    mapOf(
                        "streams" to
                            listOf(
                                mapOf(
                                    "title" to "Fixture transfer",
                                    "url" to "http://host.testcontainers.internal:${http.address.port}/progress-file.mkv",
                                    "streamData" to mapOf("service" to mapOf("id" to "torbox", "cached" to true)),
                                ),
                            ),
                    ),
                )
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        try {
            configuration.replaceSearchAddons(
                listOf(
                    AddonConfiguration(
                        UUID.randomUUID(),
                        1,
                        "${base()}/progress-addon/manifest.json",
                        gateway.manifest("${base()}/progress-addon/manifest.json"),
                    ),
                ),
            )
            val movie = content(MediaCategory.MOVIE)
            val request =
                downloads.submit(
                    movie,
                    discovery
                        .releases(movie)
                        .single()
                        .releases
                        .single(),
                    destination(MediaCategory.MOVIE),
                )
            val active = await(request.id, DownloadState.TRANSFERRING)
            val allocationTelemetry = Path.of(active.organization.stagingRoot).resolve("aria2-allocation.json")
            assertTrue(Files.isRegularFile(allocationTelemetry))
            val previousTelemetryTime = Files.getLastModifiedTime(allocationTelemetry).toMillis()
            waitUntil { Files.getLastModifiedTime(allocationTelemetry).toMillis() > previousTelemetryTime }
            val directory = Path.of(active.organization.stagingRoot).resolve(active.id.toString())
            val unrelated = Files.createDirectories(directory.parent.resolve("unrelated-cancellation-fixture")).resolve("keep.txt")
            Files.writeString(unrelated, "preserve")
            val origin = "http://127.0.0.1:${environment.getProperty("local.server.port") }"
            HttpClient.newBuilder().cookieHandler(CookieManager()).build().use { client ->
                val login =
                    client.send(
                        HttpRequest
                            .newBuilder(URI("$origin/api/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    json.writeValueAsString(
                                        mapOf("username" to configuration.settings()!!.username, "password" to "integration-password"),
                                    ),
                                ),
                            ).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                assertEquals(200, login.statusCode())
                val events =
                    client.send(
                        HttpRequest.newBuilder(URI("$origin/api/downloads/events")).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream(),
                    )
                assertEquals(200, events.statusCode())
                val body = events.body()
                val reader = body.bufferedReader()
                try {
                    val progressEvent =
                        CompletableFuture.supplyAsync {
                            while (true) {
                                val line = reader.readLine() ?: error("Download event stream closed")
                                if (line.startsWith("data:")) {
                                    val item =
                                        json.readTree(line.removePrefix("data:")).firstOrNull {
                                            it["request"]["id"].asString() ==
                                                active.id.toString()
                                        }
                                    val completed =
                                        item
                                            ?.get("request")
                                            ?.get("progress")
                                            ?.get("completedBytes")
                                            ?.asLong() ?: 0
                                    val connectionCount =
                                        item
                                            ?.get("request")
                                            ?.get("progress")
                                            ?.get("connections")
                                            ?.asInt() ?: 0
                                    if (completed > 0 && connectionCount > 0) return@supplyAsync completed
                                }
                            }
                            @Suppress("UNREACHABLE_CODE")
                            0L
                        }
                    assertTrue(progressEvent.get(10, TimeUnit.SECONDS) > 0)
                } finally {
                    body.close()
                    reader.close()
                }
                val state =
                    client.send(
                        HttpRequest.newBuilder(URI("$origin/api/downloads/${active.id}")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                assertEquals(200, state.statusCode())
                assertTrue(json.readTree(state.body())["request"]["progress"]["completedBytes"].asLong() > 0)
                val liveProgress = json.readTree(state.body())["request"]["progress"]
                assertTrue(liveProgress["connections"].asInt() > 0)
                assertNull(liveProgress["connectionSpeedsBytesPerSecond"])
            }
            assertTrue(Files.exists(directory))
            assertTrue(Files.walk(directory).use { files -> files.anyMatch { Files.isRegularFile(it) && Files.size(it) > 0 } })
            val cancelled = downloads.control(active.id, active.execution.generation, DownloadControl.CANCEL)
            assertEquals(DownloadState.CANCELLED, cancelled.state)
            assertTrue(cancelled.execution.cancellationCleanupCompleted)
            assertNull(cancelled.error)
            assertNull(aria.findStatus(active.execution.aria2Gid!!))
            assertFalse(Files.exists(directory))
            assertEquals("preserve", Files.readString(unrelated))
            downloads.drain()
            assertFalse(Files.exists(directory))
            Files.delete(unrelated)
            Files.delete(unrelated.parent)
        } finally {
            http.removeContext("/progress-file.mkv")
            http.removeContext("/progress-addon/stream/")
        }
    }

    private fun configure() {
        listOf("movies", "series", "anime").forEach { Files.createDirectories(media.resolve(it)) }
        configuration.createAccount(ApplicationSettings("fixture-admin", requireNotNull(passwords.encode("integration-password"))))
        val metadata =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "${base()}/metadata/manifest.json",
                gateway.manifest("${base()}/metadata/manifest.json"),
            )
        configuration.saveMetadata(metadata)
        configuration.replaceSearchAddons(
            listOf("slow", "fast", "error", "imdb").mapIndexed {
                index,
                name,
                ->
                AddonConfiguration(
                    UUID.randomUUID(),
                    1,
                    "${base()}/$name/manifest.json",
                    gateway.manifest("${base()}/$name/manifest.json"),
                    position = index,
                )
            },
        )
        configuration.saveSettings(
            configuration.settings()!!.copy(
                organization = OrganizationSettings(categories = OrganizationSettings.defaults(media.toString())),
            ),
        )
        webhooks.replace(emptyList())
    }

    private fun content(category: MediaCategory): ContentSnapshot {
        val source = configuration.metadata()!!
        val id = "opaque:${UUID.randomUUID().toString().take(8)}"
        val episodic = category != MediaCategory.MOVIE
        val details = discovery.details(ContentIdentity(source.id, source.revision, if (episodic) "series" else "movie", id))
        return ContentSnapshot(
            details.identity.copy(videoId = if (episodic) "$id:special-one" else null),
            category,
            details.title,
            details.year,
            if (episodic) 0 else null,
            if (episodic) 1 else null,
            if (episodic) "Arrival" else null,
        )
    }

    private fun await(
        id: UUID,
        state: DownloadState,
    ): DownloadRequest {
        val end = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < end) {
            val current = store.find(id)!!
            if (current.state == state) return current
            if (current.state == DownloadState.FAILED) fail("Download failed: ${current.error}")
            Thread.sleep(100)
        }
        fail("Request did not reach $state; latest=${store.find(id)?.state}")
    }
}
