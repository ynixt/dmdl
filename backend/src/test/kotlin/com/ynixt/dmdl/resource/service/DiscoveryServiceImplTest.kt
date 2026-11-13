package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.MetadataDetails
import com.ynixt.dmdl.domain.model.MetadataVideo
import com.ynixt.dmdl.domain.model.ReleaseGroup
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.domain.model.SourceOutcome
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.resource.cache.DiscoveryCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DiscoveryServiceImplTest {
    @Test
    fun `pages one source in groups of twenty without loading others and refresh clamps the last page`() {
        val configuration = mockk<ConfigurationStore>()
        val gateway = mockk<AddonGateway>()
        val cache = mockk<DiscoveryCache>()
        val values = ConcurrentHashMap<String, String>()
        every { cache.lookup(any(), any()) } answers {
            if (secondArg<Boolean>()) values.remove(firstArg<String>())
            DiscoveryCache.Lookup(values[firstArg()], "")
        }
        every { cache.put(any(), any(), any(), any()) } answers { values[firstArg()] = secondArg() }
        val source =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://example.com/manifest.json",
                AddonManifest("one", "One", emptyList(), emptyList(), emptyList()),
            )
        val other = source.copy(id = UUID.randomUUID(), position = 1)
        every { configuration.searchAddons() } returns listOf(source, other)
        val content = ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "tmdb:1396"), MediaCategory.MOVIE, "Movie")
        val releases =
            (0..44).map { index ->
                ReleaseSnapshot(
                    "Release $index",
                    ReleaseProvenance(source.id, 1, source.name, 0, index),
                    ReleaseReference(ReleaseReferenceType.INFO_HASH, index.toString(16).padStart(40, '0')),
                )
            }
        every { gateway.releases(source, content) } returns ReleaseGroup(source.id, source.name, 0, SourceOutcome.SUCCESS, releases)
        val service =
            DiscoveryServiceImpl(configuration, gateway, cache, JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        val pages = (1..3).map { service.releasePage(content, source.id, it, false) }
        assertEquals(listOf(20, 20, 5), pages.map { it.group.releases.size })
        assertEquals(
            listOf(0, 20, 40),
            pages.map {
                it.group.releases
                    .first()
                    .provenance.resultPosition
            },
        )
        assertEquals(releases, pages.flatMap { it.group.releases })
        assertFalse(pages.last().hasNext)
        verify(exactly = 1) { gateway.releases(source, content) }
        verify(exactly = 0) { gateway.releases(other, any()) }
        every { gateway.releases(source, content) } returns ReleaseGroup(source.id, source.name, 0, SourceOutcome.SUCCESS, releases.take(5))
        val refreshed = service.releasePage(content, source.id, 3, true)
        assertEquals(1, refreshed.page)
        assertEquals(5, refreshed.total)
        assertEquals(
            5,
            service
                .releasePage(content, source.id, 1, false)
                .group.releases.size,
        )
        assertFailsWith<IllegalArgumentException> { service.releasePage(content, source.id, 0, false) }
        assertFailsWith<IllegalArgumentException> { service.releasePage(content, UUID.randomUUID(), 1, false) }
        every { gateway.releases(source, content) } returnsMany
            listOf(
                ReleaseGroup(source.id, source.name, 0, SourceOutcome.ERROR),
                ReleaseGroup(source.id, source.name, 0, SourceOutcome.SUCCESS, releases),
            )
        assertEquals(SourceOutcome.ERROR, service.releasePage(content, source.id, 1, true).group.outcome)
        assertEquals(45, service.releasePage(content, source.id, 1, false).total)
    }

    @Test
    fun `reconstructs compact movie and episode identities from current metadata including specials`() {
        val configuration = mockk<ConfigurationStore>()
        val gateway = mockk<AddonGateway>()
        val cache = mockk<DiscoveryCache>(relaxed = true)
        every { cache.lookup(any(), any()) } returns DiscoveryCache.Lookup()
        val source =
            AddonConfiguration(
                UUID.randomUUID(),
                11,
                "https://example.com/manifest.json",
                AddonManifest("metadata", "Metadata", emptyList(), emptyList(), emptyList()),
            )
        every { configuration.metadata() } returns source
        every { gateway.details(source, any()) } answers {
            MetadataDetails(
                secondArg(),
                "Current title",
                2024,
                videos = listOf(MetadataVideo("opaque:special", 0, 1, "Special"), MetadataVideo("opaque:episode", 2, 7, "Episode")),
            )
        }
        val service =
            DiscoveryServiceImpl(configuration, gateway, cache, JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        val movie = service.content("movie", "tmdb:1396", null, null, null)
        assertEquals(source.id, movie.identity.sourceId)
        assertEquals(11, movie.identity.sourceRevision)
        assertEquals(MediaCategory.MOVIE, movie.category)
        val special = service.content("series", "tvdb:81189", 0, 1, MediaCategory.ANIME)
        assertEquals("opaque:special", special.identity.videoId)
        assertEquals(MediaCategory.ANIME, special.category)
        assertEquals(0, special.season)
        assertFailsWith<IllegalArgumentException> { service.content("series", "tvdb:81189", 2, 99, null) }
        assertFailsWith<IllegalArgumentException> { service.content("movie", "tmdb:1396", 2, 7, null) }
    }

    @Test
    fun `failed sources remain retryable and forced refresh replaces cached empty outcomes`() {
        val configuration = mockk<ConfigurationStore>()
        val gateway = mockk<AddonGateway>()
        val cache = mockk<DiscoveryCache>()
        val values = ConcurrentHashMap<String, String>()
        every { cache.lookup(any(), any()) } answers {
            if (secondArg<Boolean>()) values.remove(firstArg<String>())
            DiscoveryCache.Lookup(values[firstArg()], "")
        }
        every { cache.put(any(), any(), any(), any()) } answers { values[firstArg()] = secondArg() }
        val sources =
            (0..1).map { index ->
                AddonConfiguration(
                    UUID.randomUUID(),
                    1,
                    "https://example.com/$index/manifest.json",
                    AddonManifest("$index", "Source $index", emptyList(), emptyList(), emptyList()),
                    position = index,
                )
            }
        every { configuration.searchAddons() } returns sources
        val content = ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "opaque:movie"), MediaCategory.MOVIE, "Movie")
        sources.forEach { source ->
            every { gateway.releases(source, content) } returnsMany
                listOf(
                    ReleaseGroup(
                        source.id,
                        source.name,
                        source.position,
                        if (source.position ==
                            0
                        ) {
                            SourceOutcome.ERROR
                        } else {
                            SourceOutcome.EMPTY
                        },
                    ),
                    ReleaseGroup(source.id, source.name, source.position, SourceOutcome.SUCCESS),
                )
        }
        val service =
            DiscoveryServiceImpl(configuration, gateway, cache, JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        assertEquals(listOf(SourceOutcome.ERROR, SourceOutcome.EMPTY), service.releases(content).map { it.outcome })
        assertEquals(listOf(SourceOutcome.SUCCESS, SourceOutcome.EMPTY), service.releases(content).map { it.outcome })
        verify(exactly = 2) { gateway.releases(sources[0], content) }
        verify(exactly = 1) { gateway.releases(sources[1], content) }
        assertEquals(listOf(SourceOutcome.SUCCESS, SourceOutcome.SUCCESS), service.releases(content, true).map { it.outcome })
        assertEquals(listOf(SourceOutcome.SUCCESS, SourceOutcome.SUCCESS), service.releases(content).map { it.outcome })
        verify(exactly = 3) { gateway.releases(sources[0], content) }
        verify(exactly = 2) { gateway.releases(sources[1], content) }
    }

    @Test
    fun `caches trimmed catalog searches and canonical metadata with distinct lifetimes and source revisions`() {
        val configuration = mockk<ConfigurationStore>()
        val gateway = mockk<AddonGateway>()
        val cache = mockk<DiscoveryCache>()
        val values = ConcurrentHashMap<String, String>()
        every { cache.lookup(any(), any()) } answers { DiscoveryCache.Lookup(values[firstArg()], "") }
        every { cache.put(any(), any(), any(), any()) } answers { values[firstArg()] = secondArg() }
        var source =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://example.com/manifest.json",
                AddonManifest("metadata", "Metadata", emptyList(), emptyList(), emptyList()),
            )
        every { configuration.metadata() } answers { source }
        every { gateway.search(any(), any()) } answers {
            listOf(MetadataDetails(ContentIdentity(source.id, source.revision, "series", "tvdb:81189"), "Series"))
        }
        every { gateway.details(any(), any()) } answers { MetadataDetails(secondArg(), "Series", description = "Synopsis") }
        val service =
            DiscoveryServiceImpl(configuration, gateway, cache, JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        val query = DiscoveryQuery("series", "series-search", "Breaking Bad")
        val identity = service.search(query).single().identity
        service.search(query.copy(query = " Breaking Bad "))
        verify(exactly = 1) { gateway.search(any(), any()) }
        service.search(query.copy(catalogId = "another-catalog"))
        verify(exactly = 2) { gateway.search(any(), any()) }
        assertEquals("Synopsis", service.details(identity).description)
        service.details(identity.copy(videoId = "episode", mappedIds = mapOf("tvdb" to "81189")))
        verify(exactly = 1) { gateway.details(any(), any()) }
        verify(exactly = 2) { cache.put(match { it.contains("search-v2") }, any(), DiscoveryCache.RESULTS_TTL, any()) }
        verify(exactly = 1) { cache.put(match { it.contains("meta-v2") }, any(), DiscoveryCache.METADATA_TTL, any()) }
        source = source.copy(revision = 2)
        service.search(query)
        service.details(identity.copy(sourceRevision = 2))
        verify(exactly = 3) { gateway.search(any(), any()) }
        verify(exactly = 2) { gateway.details(any(), any()) }
        source = source.copy(id = UUID.randomUUID())
        service.search(query)
        service.details(ContentIdentity(source.id, source.revision, "series", "tvdb:81189"))
        verify(exactly = 4) { gateway.search(any(), any()) }
        verify(exactly = 3) { gateway.details(any(), any()) }
    }
}
