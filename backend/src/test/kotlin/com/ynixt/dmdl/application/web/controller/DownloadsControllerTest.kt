package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.model.CategoryOrganization
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.JellyfinNotification
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.NotificationState
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.domain.model.SpeedSample
import com.ynixt.dmdl.domain.repository.NotificationStore
import com.ynixt.dmdl.domain.service.DownloadService
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadsControllerTest {
    @Test
    fun `catalog endpoint forwards addon options separately from paging and refresh controls`() {
        every { discovery.catalog("movie", "popular", 20, true, mapOf("genre" to "Action & Adventure")) } returns emptyList()
        assertEquals(
            emptyList(),
            controller.catalog(
                "movie",
                "popular",
                20,
                true,
                mapOf(
                    "genre" to "Action & Adventure",
                    "skip" to "20",
                    "forceRefresh" to "true",
                ),
            ),
        )
    }

    @Test
    fun `catalog endpoint forwards page and refresh and maps validation errors to bad requests`() {
        every { discovery.catalog("movie", "popular", 20, true) } returns emptyList()
        assertEquals(emptyList(), controller.catalog("movie", "popular", 20, true))
        every { discovery.catalog("movie", "search", 0, false) } throws IllegalArgumentException("Select a browsable catalog")
        every { discovery.catalog("movie", "popular", -1, false) } throws IllegalArgumentException("Invalid skip")
        val mvc =
            org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(ApiErrors())
                .build()
        listOf(
            "/api/discovery/catalogs/movie/search",
            "/api/discovery/catalogs/movie/popular?skip=-1",
            "/api/discovery/catalogs/movie/popular?skip=invalid",
        ).forEach { path ->
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(path),
                ).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status()
                        .isBadRequest,
                )
        }
    }

    private val downloads = mockk<DownloadService>()
    private val discovery = mockk<com.ynixt.dmdl.domain.service.DiscoveryService>()
    private val configuration = mockk<com.ynixt.dmdl.domain.repository.ConfigurationStore>()
    private val notifications = mockk<NotificationStore>()
    private val controller =
        DownloadsController(downloads, mockk(), notifications, mockk(), discovery, configuration)

    @AfterTest
    fun close() = controller.closeStreams()

    @Test
    fun `stream sends a snapshot then changes once and heartbeats without repeating finished downloads`() {
        val now = Instant.parse("2026-11-14T14:40:00Z")
        val active =
            DownloadRequest(
                UUID.randomUUID(),
                ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "movie:1"), MediaCategory.MOVIE, "Movie"),
                ReleaseSnapshot(
                    "Release",
                    ReleaseProvenance(UUID.randomUUID(), 1, "Source", 0, 0),
                    ReleaseReference(ReleaseReferenceType.PROVIDER_HTTP, "https://fixture.example/movie.mkv"),
                ),
                null,
                OrganizationSnapshot(1, "/movies", CategoryOrganization("{title}")),
                state = DownloadState.TRANSFERRING,
                createdAt = now,
                updatedAt = now,
            )
        val finished = active.copy(id = UUID.randomUUID(), state = DownloadState.COMPLETED, completedAt = now)
        val records = AtomicReference(listOf(active, finished))
        val notification = AtomicReference<JellyfinNotification?>(null)
        every { downloads.history() } answers { records.get() }
        every { notifications.find(any()) } answers {
            notification.get()?.takeIf { it.requestId == firstArg<UUID>() }
        }
        val mvc = MockMvcBuilders.standaloneSetup(controller).build()
        val response = mvc.perform(get("/api/downloads/events")).andReturn().response

        fun awaitContent(condition: (String) -> Boolean): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val content = response.contentAsString
                if (condition(content)) return content
                Thread.sleep(10)
            }
            error("Expected stream event was not sent: ${response.contentAsString}")
        }

        val initial = awaitContent { it.contains("event:heartbeat") }
        assertEquals(1, initial.split("event:downloads\n").size - 1)
        assertTrue(initial.contains(active.id.toString()))
        assertTrue(initial.contains(finished.id.toString()))

        val completed = active.copy(state = DownloadState.COMPLETED, completedAt = now.plusSeconds(60))
        records.set(listOf(completed, finished))
        val completion = awaitContent { it.contains("event:download-updates") }
        val update = completion.substringAfter("event:download-updates")
        assertTrue(update.contains(active.id.toString()))
        assertTrue(update.contains("2026-11-14T14:41:00Z"))
        assertTrue(!update.contains(finished.id.toString()))
        val heartbeatCount = completion.split("event:heartbeat").size
        val idle = awaitContent { it.split("event:heartbeat").size > heartbeatCount }
        assertEquals(1, idle.split("event:download-updates").size - 1)

        notification.set(JellyfinNotification(completed.id, NotificationState.SENT, attempts = 1))
        val notified = awaitContent { it.contains("\"state\":\"SENT\"") }
        assertEquals(2, notified.split("event:download-updates").size - 1)

        records.set(listOf(finished))
        val removed = awaitContent { it.contains("event:downloads-removed") }
        assertTrue(removed.substringAfter("event:downloads-removed").contains(active.id.toString()))
        assertEquals(1, removed.split("event:downloads\n").size - 1)

        val reconnected = mvc.perform(get("/api/downloads/events")).andReturn().response
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!reconnected.contentAsString.contains(finished.id.toString()) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(reconnected.contentAsString.contains("event:downloads\n"))
        assertTrue(reconnected.contentAsString.contains(finished.id.toString()))
        assertTrue(!reconnected.contentAsString.contains(active.id.toString()))
    }

    @Test
    fun `speed history returns samples oldest first`() {
        val id = UUID.randomUUID()
        val samples =
            listOf(
                SpeedSample(Instant.parse("2026-11-14T14:41:00Z"), 4_000_000, 8),
                SpeedSample(Instant.parse("2026-11-14T14:41:30Z"), 1_000_000, 1),
            )
        every { downloads.speedHistory(id) } returns samples
        val response = controller.speedHistory(id)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(samples, response.body)
    }

    @Test
    fun `speed history of a download without samples is an empty list`() {
        val id = UUID.randomUUID()
        every { downloads.speedHistory(id) } returns emptyList()
        assertEquals(emptyList(), controller.speedHistory(id).body)
    }

    @Test
    fun `speed history of an unknown download is not found`() {
        val id = UUID.randomUUID()
        every { downloads.speedHistory(id) } returns null
        val response = controller.speedHistory(id)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun `metadata endpoint parses the kind and forwards lookups with refresh`() {
        val identity =
            com.ynixt.dmdl.domain.model
                .ContentIdentity(UUID.randomUUID(), 7, "series", "kitsu:1")
        val metadata =
            com.ynixt.dmdl.domain.model
                .MetadataDetails(identity, "Anime")
        every { discovery.lookup(com.ynixt.dmdl.domain.model.MediaKind.SHOW, "kitsu:1", true) } returns metadata
        assertEquals(metadata, controller.meta("show", "kitsu:1", true))
        assertEquals(com.ynixt.dmdl.domain.model.MediaCategory.ANIME, metadata.category)
        kotlin.test.assertFailsWith<IllegalArgumentException> { controller.meta("anime", "id") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { controller.meta("other", "id") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { controller.meta("movie", " ") }
    }
}
