package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.AnimeIndexGateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.AnimeVerdict
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.MediaKind
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
    fun `catalog options apply defaults validate choices and isolate caches by selection`() {
        val f = CatalogFixture()
        val genre =
            f.pageable.copy(
                extra =
                    listOf(
                        com.ynixt.dmdl.domain.model
                            .AddonCatalogExtra("genre", true, listOf("Action & Adventure", "Drama"), "Drama"),
                        com.ynixt.dmdl.domain.model
                            .AddonCatalogExtra("skip", options = emptyList()),
                    ),
            )
        f.source = f.source.copy(manifest = f.source.manifest.copy(catalogs = listOf(genre)))
        val drama = mapOf("genre" to "Drama")
        val action = mapOf("genre" to "Action & Adventure")
        every { f.gateway.catalog(f.source, genre, 0, drama) } returnsMany listOf(f.old, f.fresh)
        every { f.gateway.catalog(f.source, genre, 0, action) } returns f.old
        every { f.gateway.catalog(f.source, genre, 20, drama) } returns emptyList()
        assertEquals(f.old, f.service.catalog("movie", "popular"))
        assertEquals(f.old, f.service.catalog("movie", "popular", extras = drama))
        assertEquals(f.old, f.service.catalog("movie", "popular", extras = action))
        assertEquals(f.fresh, f.service.catalog("movie", "popular", forceRefresh = true, extras = drama))
        assertEquals(f.fresh, f.service.catalog("movie", "popular", extras = drama))
        assertEquals(f.old, f.service.catalog("movie", "popular", extras = action))
        assertEquals(emptyList(), f.service.catalog("movie", "popular", 20, extras = drama))
        verify(exactly = 2) { f.gateway.catalog(f.source, genre, 0, drama) }
        verify(exactly = 1) { f.gateway.catalog(f.source, genre, 0, action) }
        verify(exactly = 1) { f.gateway.catalog(f.source, genre, 20, drama) }
        assertFailsWith<IllegalArgumentException> { f.service.catalog("movie", "popular", extras = mapOf("genre" to "unknown")) }
        assertFailsWith<IllegalArgumentException> { f.service.catalog("movie", "popular", extras = mapOf("genre" to "")) }
        assertFailsWith<IllegalArgumentException> { f.service.catalog("movie", "popular", extras = mapOf("unknown" to "value")) }
    }

    @Test
    fun `required catalog options use the first choice when no default exists and optional options can be cleared`() {
        val f = CatalogFixture()
        val genre =
            f.plain.copy(
                extra =
                    listOf(
                        com.ynixt.dmdl.domain.model
                            .AddonCatalogExtra("genre", true, listOf("Action", "Drama")),
                    ),
            )
        f.source = f.source.copy(manifest = f.source.manifest.copy(catalogs = listOf(genre)))
        every { f.gateway.catalog(f.source, genre, 0, mapOf("genre" to "Action")) } returns f.old
        assertEquals(f.old, f.service.catalog("movie", "plain"))
        val optional =
            genre.copy(
                extra =
                    listOf(
                        com.ynixt.dmdl.domain.model
                            .AddonCatalogExtra("genre", false, listOf("Action", "Drama"), "Drama"),
                    ),
            )
        f.source = f.source.copy(manifest = f.source.manifest.copy(catalogs = listOf(optional)))
        every { f.gateway.catalog(f.source, optional, 0, emptyMap()) } returns f.fresh
        assertEquals(f.fresh, f.service.catalog("movie", "plain", extras = mapOf("genre" to "")))
    }

    private class CatalogFixture {
        val configuration = mockk<ConfigurationStore>()
        val gateway = mockk<AddonGateway>()
        val cache = mockk<DiscoveryCache>()
        val backfill = mockk<com.ynixt.dmdl.domain.service.MetadataManifestBackfill>()
        val values = ConcurrentHashMap<String, String>()
        val pageable =
            com.ynixt.dmdl.domain.model.AddonCatalog(
                "popular",
                "movie",
                "Popular",
                true,
                listOf(
                    com.ynixt.dmdl.domain.model
                        .AddonCatalogExtra("skip"),
                ),
            )
        val plain = pageable.copy(id = "plain", extra = emptyList())
        val searchOnly =
            pageable.copy(
                id = "search",
                extra =
                    listOf(
                        com.ynixt.dmdl.domain.model
                            .AddonCatalogExtra("search", true),
                    ),
            )
        var source =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://example/manifest.json",
                AddonManifest("meta", "Meta", emptyList(), emptyList(), listOf(pageable, plain, searchOnly)),
            )
        val old = listOf(MetadataDetails(ContentIdentity(source.id, 1, "movie", "tt1"), "Old"))
        val fresh = listOf(old.single().copy(title = "Fresh"))
        val service =
            DiscoveryServiceImpl(
                configuration,
                backfill,
                gateway,
                cache,
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                mockk(),
            )

        init {
            every { configuration.metadata() } answers { source }
            every { backfill.current() } answers { source }
            every { cache.lookup(any(), any()) } answers {
                if (secondArg<Boolean>()) values.remove(firstArg<String>())
                DiscoveryCache.Lookup(values[firstArg()], "generation")
            }
            every { cache.put(any(), any(), any(), any()) } answers { values[firstArg()] = secondArg() }
        }
    }

    @Test
    fun `catalog caches pages and empty outcomes and refresh replaces only the requested entry`() {
        val f = CatalogFixture()
        every { f.gateway.catalog(f.source, f.pageable, 0) } returnsMany listOf(f.old, f.fresh)
        every { f.gateway.catalog(f.source, f.pageable, 20) } returns emptyList()
        every { f.gateway.catalog(f.source, f.plain, 0) } returns f.old
        assertEquals(f.old, f.service.catalog("movie", "popular"))
        assertEquals(f.old, f.service.catalog("movie", "popular"))
        f.service.catalog("movie", "plain")
        assertEquals(emptyList(), f.service.catalog("movie", "popular", 20))
        assertEquals(emptyList(), f.service.catalog("movie", "popular", 20))
        assertEquals(f.fresh, f.service.catalog("movie", "popular", forceRefresh = true))
        assertEquals(f.fresh, f.service.catalog("movie", "popular"))
        assertEquals(f.old, f.service.catalog("movie", "plain"))
        verify(exactly = 2) { f.gateway.catalog(f.source, f.pageable, 0) }
        verify(exactly = 1) { f.gateway.catalog(f.source, f.pageable, 20) }
        verify(exactly = 1) { f.gateway.catalog(f.source, f.plain, 0) }
        verify(exactly = 4) { f.cache.put(match { it.contains("catalog-v1") }, any(), DiscoveryCache.RESULTS_TTL, "generation") }
    }

    @Test
    fun `catalog failures remain retryable and invalid selections never call the gateway`() {
        val f = CatalogFixture()
        every { f.gateway.catalog(any(), any(), any()) } throws
            com.ynixt.dmdl.domain.model
                .AddonFailure("Unavailable")
        repeat(2) { assertFailsWith<com.ynixt.dmdl.domain.model.AddonFailure> { f.service.catalog("movie", "popular") } }
        verify(exactly = 0) { f.cache.put(any(), any(), any(), any()) }
        assertFailsWith<IllegalArgumentException> { f.service.catalog("movie", "search") }
        assertFailsWith<IllegalArgumentException> { f.service.catalog("movie", "plain", 1) }
        assertFailsWith<IllegalArgumentException> { f.service.catalog("movie", "missing") }
        assertFailsWith<IllegalArgumentException> { f.service.catalog("movie", "popular", -1) }
        verify(exactly = 2) { f.gateway.catalog(any(), any(), any()) }
    }

    @Test
    fun `forced search replaces only its query and catalog cache`() {
        val f = CatalogFixture()
        val query = DiscoveryQuery("movie", "popular", "dune")
        val otherCatalog = query.copy(catalogId = "plain")
        val otherQuery = query.copy(query = "other")
        every { f.gateway.search(f.source, any()) } returns f.old
        f.service.search(query)
        f.service.search(otherCatalog)
        f.service.search(otherQuery)
        every { f.gateway.search(f.source, query) } returns f.fresh
        assertEquals(f.fresh, f.service.search(query, true))
        assertEquals(f.fresh, f.service.search(query))
        assertEquals(f.old, f.service.search(otherCatalog))
        assertEquals(f.old, f.service.search(otherQuery))
        verify(exactly = 4) { f.gateway.search(any(), any()) }
    }

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
            DiscoveryServiceImpl(
                configuration,
                mockk(),
                gateway,
                cache,
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                mockk(),
            )
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
            DiscoveryServiceImpl(
                configuration,
                mockk(),
                gateway,
                cache,
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                mockk(),
            )
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
            DiscoveryServiceImpl(
                configuration,
                mockk(),
                gateway,
                cache,
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                mockk<AnimeIndexGateway> { every { lookup(any()) } returns AnimeVerdict.UNKNOWN },
            )
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
        verify(exactly = 1) { cache.put(match { it.contains("meta-v5") }, any(), DiscoveryCache.METADATA_TTL, any()) }
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

    @Test
    fun `forced metadata refresh replaces canonical cache and subsequent visits use it`() {
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
                "https://example/manifest.json",
                AddonManifest("meta", "Meta", emptyList(), emptyList(), emptyList()),
            )
        every { configuration.metadata() } returns source
        val identity = ContentIdentity(source.id, source.revision, "series", "tt123")
        every { gateway.details(source, any()) } returnsMany
            listOf(
                MetadataDetails(identity, "Old", videos = listOf(MetadataVideo("one", 1, 1))),
                MetadataDetails(identity, "New", videos = listOf(MetadataVideo("one", 1, 1), MetadataVideo("two", 2, 1))),
            )
        val service =
            DiscoveryServiceImpl(
                configuration,
                mockk(),
                gateway,
                cache,
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                mockk(),
            )
        assertEquals("Old", service.details(identity).title)
        assertEquals("Old", service.details(identity).title)
        verify(exactly = 1) { gateway.details(source, any()) }
        assertEquals(2, service.details(identity, true).videos.size)
        assertEquals("New", service.details(identity).title)
        verify(exactly = 2) { gateway.details(source, any()) }
        verify(exactly = 1) { cache.lookup(match { it.contains("meta-v5") }, true) }
    }

    @Test
    fun `series and anime categories share one episode release cache`() {
        val configuration = mockk<ConfigurationStore>()
        val gateway = mockk<AddonGateway>()
        val cache = mockk<DiscoveryCache>()
        val values = ConcurrentHashMap<String, String>()
        every { cache.lookup(any(), any()) } answers { DiscoveryCache.Lookup(values[firstArg()], "") }
        every { cache.put(any(), any(), any(), any()) } answers { values[firstArg()] = secondArg() }
        val source =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://example/manifest.json",
                AddonManifest("stream", "Stream", emptyList(), emptyList(), emptyList()),
            )
        every { configuration.searchAddons() } returns listOf(source)
        val content =
            ContentSnapshot(
                ContentIdentity(UUID.randomUUID(), 1, "series", "tt123", videoId = "ep"),
                MediaCategory.SERIES,
                "Show",
                season = 1,
                episode = 1,
            )
        every { gateway.releases(source, any()) } returns ReleaseGroup(source.id, source.name, 0, SourceOutcome.EMPTY)
        val service =
            DiscoveryServiceImpl(
                configuration,
                mockk(),
                gateway,
                cache,
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                mockk(),
            )
        assertEquals(
            service.releasePage(content, source.id, 1, false),
            service.releasePage(content.copy(category = MediaCategory.ANIME), source.id, 1, false),
        )
        verify(exactly = 1) { gateway.releases(source, any()) }
        verify(exactly = 1) { cache.put(match { it.contains("releases-v5") }, any(), any(), any()) }
    }

    private class ClassificationFixture {
        val configuration = mockk<ConfigurationStore>()
        val gateway = mockk<AddonGateway>()
        val cache = mockk<DiscoveryCache>()
        val animeIndex = mockk<AnimeIndexGateway>()
        val values = ConcurrentHashMap<String, String>()
        val ttls = ConcurrentHashMap<String, java.time.Duration>()
        val source =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://example/manifest.json",
                AddonManifest(
                    "meta",
                    "Meta",
                    listOf("movie", "series", "anime"),
                    listOf(
                        com.ynixt.dmdl.domain.model
                            .AddonResource("meta", listOf("movie", "series", "anime"), emptyList()),
                    ),
                    emptyList(),
                ),
            )
        val service: DiscoveryServiceImpl

        init {
            every { cache.lookup(any(), any()) } answers {
                if (secondArg<Boolean>()) values.remove(firstArg<String>())
                DiscoveryCache.Lookup(values[firstArg()], "")
            }
            every { cache.put(any(), any(), any(), any()) } answers {
                values[firstArg()] = secondArg()
                ttls[firstArg()] = thirdArg()
            }
            every { configuration.metadata() } returns source
            service =
                DiscoveryServiceImpl(
                    configuration,
                    mockk(),
                    gateway,
                    cache,
                    JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                    animeIndex,
                )
        }

        fun title(
            type: String,
            id: String,
            mapped: Map<String, String> = emptyMap(),
        ) = MetadataDetails(ContentIdentity(source.id, source.revision, type, id, mappedIds = mapped), "Title")

        fun identity(
            type: String,
            id: String,
        ) = ContentIdentity(source.id, source.revision, type, id)
    }

    @Test
    fun `kitsu verdict overrides the addon type and is cached by outcome`() {
        val fixture = ClassificationFixture()
        every { fixture.gateway.details(any(), match { it.contentId == "tt2560140" }) } returns
            fixture.title("series", "tt2560140", mapOf("tvdb" to "267440", "imdb" to "tt2560140"))
        every { fixture.gateway.details(any(), match { it.contentId == "tt0903747" }) } returns
            fixture.title("anime", "tt0903747", mapOf("tvdb" to "81189"))
        every { fixture.animeIndex.lookup(mapOf("tvdb" to "267440")) } returns AnimeVerdict.ANIME
        every { fixture.animeIndex.lookup(mapOf("tvdb" to "81189")) } returns AnimeVerdict.NOT_ANIME

        assertEquals(MediaCategory.ANIME, fixture.service.details(fixture.identity("series", "tt2560140")).category)
        assertEquals(MediaCategory.ANIME, fixture.service.details(fixture.identity("series", "tt2560140")).category)
        verify(exactly = 1) { fixture.animeIndex.lookup(mapOf("tvdb" to "267440")) }
        // The addon calls it `anime`, but Kitsu has no mapping: Kitsu wins.
        assertEquals(MediaCategory.SERIES, fixture.service.details(fixture.identity("anime", "tt0903747")).category)
        val ttls =
            fixture.ttls
                .filterKeys { it.contains("kitsu-v1") }
                .values
                .toSet()
        assertEquals(setOf(DiscoveryCache.KITSU_ANIME_TTL, DiscoveryCache.KITSU_SERIES_TTL), ttls)
    }

    @Test
    fun `unknown verdicts fall back to the addon type and are retried`() {
        val fixture = ClassificationFixture()
        every { fixture.gateway.details(any(), any()) } returns fixture.title("anime", "tt1", mapOf("tvdb" to "1"))
        every { fixture.animeIndex.lookup(any()) } returns AnimeVerdict.UNKNOWN

        assertEquals(MediaCategory.ANIME, fixture.service.details(fixture.identity("anime", "tt1")).category)
        assertEquals(MediaCategory.ANIME, fixture.service.details(fixture.identity("anime", "tt1")).category)
        verify(exactly = 2) { fixture.animeIndex.lookup(any()) }
        verify(exactly = 0) { fixture.cache.put(match { it.contains("kitsu-v1") }, any(), any(), any()) }
    }

    @Test
    fun `forced refresh re-asks kitsu while kitsu ids movies and id-less titles never ask`() {
        val fixture = ClassificationFixture()
        every { fixture.gateway.details(any(), match { it.contentId == "tt9" }) } returns
            fixture.title("series", "tt9", mapOf("tvdb" to "9"))
        every { fixture.gateway.details(any(), match { it.contentId == "kitsu:7442" }) } returns
            fixture.title("series", "kitsu:7442", mapOf("kitsu" to "7442", "tvdb" to "267440"))
        every { fixture.gateway.details(any(), match { it.contentId == "tt5311514" }) } returns
            fixture.title("movie", "tt5311514", mapOf("tvdb" to "197"))
        every { fixture.gateway.details(any(), match { it.contentId == "tt0" }) } returns
            fixture.title("series", "tt0", mapOf("imdb" to "tt0", "tmdb" to "1"))
        every { fixture.animeIndex.lookup(mapOf("tvdb" to "9")) } returnsMany listOf(AnimeVerdict.NOT_ANIME, AnimeVerdict.ANIME)

        assertEquals(MediaCategory.SERIES, fixture.service.details(fixture.identity("series", "tt9")).category)
        assertEquals(MediaCategory.ANIME, fixture.service.details(fixture.identity("series", "tt9"), true).category)
        assertEquals(MediaCategory.ANIME, fixture.service.details(fixture.identity("series", "tt9")).category)
        verify(exactly = 2) { fixture.animeIndex.lookup(mapOf("tvdb" to "9")) }

        assertEquals(MediaCategory.ANIME, fixture.service.details(fixture.identity("series", "kitsu:7442")).category)
        assertEquals(MediaCategory.MOVIE, fixture.service.details(fixture.identity("movie", "tt5311514")).category)
        assertEquals(MediaCategory.SERIES, fixture.service.details(fixture.identity("series", "tt0")).category)
        verify(exactly = 2) { fixture.animeIndex.lookup(any()) }
    }

    @Test
    fun `lookup asks kitsu with the route id while the addon answers`() {
        val fixture = ClassificationFixture()
        val kitsuAsked = java.util.concurrent.CountDownLatch(1)
        every { fixture.animeIndex.lookup(mapOf("tvdb" to "267440")) } answers {
            kitsuAsked.countDown()
            AnimeVerdict.NOT_ANIME
        }
        every { fixture.animeIndex.lookup(mapOf("mal" to "16498")) } returns AnimeVerdict.ANIME
        every { fixture.gateway.details(any(), match { it.contentId == "tvdb:267440" }) } answers {
            // Only returns once Kitsu was asked concurrently; a sequential implementation would time out here.
            check(kitsuAsked.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Kitsu was not asked in parallel" }
            fixture.title("series", "tt2560140", mapOf("tvdb" to "267440", "mal" to "16498", "imdb" to "tt2560140"))
        }

        assertEquals(MediaCategory.ANIME, fixture.service.lookup(MediaKind.SHOW, "tvdb:267440").category)
        // The route id is asked once; only the id the metadata revealed needs a second query.
        verify(exactly = 1) { fixture.animeIndex.lookup(mapOf("tvdb" to "267440")) }
        verify(exactly = 1) { fixture.animeIndex.lookup(mapOf("mal" to "16498")) }
        // details() of the same title reuses both cached verdicts.
        fixture.service.details(fixture.identity("series", "tvdb:267440"))
        verify(exactly = 2) { fixture.animeIndex.lookup(any()) }
    }

    @Test
    fun `an anime route id skips the second kitsu query`() {
        val fixture = ClassificationFixture()
        every { fixture.animeIndex.lookup(mapOf("tvdb" to "1")) } returns AnimeVerdict.ANIME
        every { fixture.gateway.details(any(), any()) } returns fixture.title("series", "tvdb:1", mapOf("tvdb" to "1", "mal" to "2"))

        assertEquals(MediaCategory.ANIME, fixture.service.lookup(MediaKind.SHOW, "tvdb:1").category)
        verify(exactly = 1) { fixture.animeIndex.lookup(any()) }
    }

    @Test
    fun `lookup asks the addon type that answers for a show`() {
        val fixture = ClassificationFixture()
        every { fixture.animeIndex.lookup(any()) } returns AnimeVerdict.UNKNOWN
        every { fixture.gateway.details(any(), match { it.resourceType == "series" && it.contentId == "tt1" }) } returns
            fixture.title("series", "tt1")
        every { fixture.gateway.details(any(), match { it.resourceType == "series" && it.contentId == "kitsu:2" }) } throws
            com.ynixt.dmdl.domain.model
                .AddonFailure("Metadata item is unavailable")
        every { fixture.gateway.details(any(), match { it.resourceType == "anime" && it.contentId == "kitsu:2" }) } returns
            fixture.title("anime", "kitsu:2")
        every { fixture.gateway.details(any(), match { it.resourceType == "movie" }) } returns fixture.title("movie", "tt3")

        assertEquals(
            "series",
            fixture.service
                .lookup(MediaKind.SHOW, "tt1")
                .identity.resourceType,
        )
        assertEquals(
            "anime",
            fixture.service
                .lookup(MediaKind.SHOW, "kitsu:2")
                .identity.resourceType,
        )
        assertEquals(MediaCategory.MOVIE, fixture.service.lookup(MediaKind.MOVIE, "tt3").category)
        verify(exactly = 0) { fixture.gateway.details(any(), match { it.resourceType == "anime" && it.contentId == "tt1" }) }
        every { fixture.gateway.details(any(), match { it.contentId == "missing" }) } throws
            com.ynixt.dmdl.domain.model
                .AddonFailure("Metadata item is unavailable")
        assertFailsWith<com.ynixt.dmdl.domain.model.AddonFailure> { fixture.service.lookup(MediaKind.SHOW, "missing") }
    }
}
