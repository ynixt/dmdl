package com.ynixt.dmdl.application.web.dto

import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class ManualDownloadContractsTest {
    private val mapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    private val identity = ContentIdentity(UUID.randomUUID(), 1, "series", "kitsu:123")
    private val release =
        ReleaseSnapshot(
            "Selected release",
            ReleaseProvenance(UUID.randomUUID(), 1, "Source", 0, 0),
            ReleaseReference(ReleaseReferenceType.PROVIDER_HTTP, "https://addon.example/file.mkv"),
        )

    @Test
    fun `accepts opaque movie identity and season zero episode`() {
        val movie = ContentSnapshot(identity.copy(resourceType = "movie"), MediaCategory.MOVIE, "Movie")
        val special = ContentSnapshot(identity.copy(videoId = "kitsu:123:special-1"), MediaCategory.ANIME, "Anime", season = 0, episode = 1)
        listOf(movie, special).forEach { content ->
            val input = CreateDownloadRequest(content, release, UUID.randomUUID())
            assertEquals(input, mapper.readValue(mapper.writeValueAsString(input), CreateDownloadRequest::class.java))
        }
    }

    @Test
    fun `accepts HTTP links with optional service metadata and rejects torrent-only requests`() {
        assertFailsWith<IllegalArgumentException> { ContentSnapshot(identity, MediaCategory.SERIES, "Series", season = 1) }
        val movie = ContentSnapshot(identity.copy(resourceType = "movie"), MediaCategory.MOVIE, "Movie")
        val destination = UUID.randomUUID()
        val direct = release.copy(provenance = release.provenance.copy(boundProvider = DebridProvider.REAL_DEBRID))
        assertEquals(destination, CreateDownloadRequest(movie, direct, destination).destinationId)
        assertEquals(destination, CreateDownloadRequest(movie, release, destination).destinationId)
        assertFailsWith<IllegalArgumentException> {
            CreateDownloadRequest(
                movie,
                release.copy(reference = ReleaseReference(ReleaseReferenceType.INFO_HASH, "a".repeat(40))),
                destination,
            )
        }
    }
}
