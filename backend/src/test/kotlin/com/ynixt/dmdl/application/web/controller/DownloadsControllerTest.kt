package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.model.SpeedSample
import com.ynixt.dmdl.domain.service.DownloadService
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    private val controller =
        DownloadsController(downloads, mockk(), mockk(), mockk(), discovery, configuration)

    @AfterTest
    fun close() = controller.closeStreams()

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
