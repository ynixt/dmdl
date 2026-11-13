package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.MetadataDetails
import com.ynixt.dmdl.domain.model.ReleaseGroup
import com.ynixt.dmdl.domain.model.ReleasePage
import com.ynixt.dmdl.domain.model.SourceOutcome
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.service.DiscoveryService
import com.ynixt.dmdl.resource.cache.DiscoveryCache
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Service
class DiscoveryServiceImpl(
    private val configuration: ConfigurationStore,
    private val gateway: AddonGateway,
    private val cache: DiscoveryCache,
    private val json: ObjectMapper,
) : DiscoveryService {
    override fun search(query: DiscoveryQuery): List<MetadataDetails> {
        val source = requireNotNull(configuration.metadata()) { "Configure exactly one metadata addon before searching" }
        val normalized = query.copy(query = query.query.trim())
        val key = DiscoveryCache.key("search-v2", source.id.toString(), source.revision, json.writeValueAsString(normalized))
        val cached = cache.lookup(key)
        cached.value?.let { return json.readValue(it, object : TypeReference<List<MetadataDetails>>() {}) }
        return gateway.search(source, normalized).also {
            cache.put(key, json.writeValueAsString(it), DiscoveryCache.RESULTS_TTL, cached.generation)
        }
    }

    override fun details(identity: ContentIdentity): MetadataDetails {
        val source = requireNotNull(configuration.metadata()) { "Configure a metadata addon" }
        require(source.id == identity.sourceId && source.revision == identity.sourceRevision) { "Metadata source changed; search again" }
        val canonical = identity.copy(videoId = null, mappedIds = emptyMap())
        val key = DiscoveryCache.key("meta-v2", source.id.toString(), source.revision, json.writeValueAsString(canonical))
        val cached = cache.lookup(key)
        cached.value?.let { return json.readValue(it, MetadataDetails::class.java) }
        return gateway.details(source, canonical).also {
            cache.put(key, json.writeValueAsString(it), DiscoveryCache.METADATA_TTL, cached.generation)
        }
    }

    override fun content(
        resourceType: String,
        contentId: String,
        season: Int?,
        episode: Int?,
        category: MediaCategory?,
    ): ContentSnapshot {
        require(resourceType in setOf("movie", "series", "anime"))
        val source = requireNotNull(configuration.metadata()) { "Configure a metadata addon" }
        val item = details(ContentIdentity(source.id, source.revision, resourceType, contentId))
        if (item.identity.resourceType == "movie") {
            require(season == null && episode == null && (category == null || category == MediaCategory.MOVIE))
            return ContentSnapshot(item.identity, MediaCategory.MOVIE, item.title, item.year, posterUrl = item.posterUrl)
        }
        val selectedCategory = category ?: if (item.identity.resourceType == "anime") MediaCategory.ANIME else MediaCategory.SERIES
        require(selectedCategory != MediaCategory.MOVIE)
        val video =
            requireNotNull(item.videos.singleOrNull { it.season == season && it.episode == episode }) {
                "Select one current metadata episode"
            }
        return ContentSnapshot(
            item.identity.copy(videoId = video.id),
            selectedCategory,
            item.title,
            item.year,
            video.season,
            video.episode,
            video.title,
            item.posterUrl,
        )
    }

    override fun releasePage(
        content: ContentSnapshot,
        addonId: UUID,
        page: Int,
        forceRefresh: Boolean,
    ): ReleasePage {
        require(page > 0)
        val source =
            requireNotNull(configuration.searchAddons().singleOrNull { it.id == addonId && it.enabled }) {
                "KZ.RELEASE_SOURCE_UNAVAILABLE"
            }
        val group = sourceReleases(source, content, forceRefresh)
        val size = 20
        val total = group.releases.size
        val current = page.coerceIn(1, maxOf(1, (total + size - 1) / size))
        val offset = (current - 1) * size
        return ReleasePage(
            group.copy(releases = group.releases.subList(offset, minOf(offset + size, total))),
            current,
            size,
            total,
            offset + size < total,
        )
    }

    override fun releases(
        content: ContentSnapshot,
        forceRefresh: Boolean,
    ): List<ReleaseGroup> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures =
                configuration.searchAddons().filter { it.enabled }.map { source ->
                    executor.submit(Callable { sourceReleases(source, content, forceRefresh) })
                }
            futures.map { it.get() }
        }

    private fun sourceReleases(
        source: AddonConfiguration,
        content: ContentSnapshot,
        forceRefresh: Boolean,
    ): ReleaseGroup {
        val identity = content.identity.copy(mappedIds = content.identity.mappedIds.toSortedMap())
        val query = json.writeValueAsString(listOf(identity, content.category, content.season, content.episode))
        val key = DiscoveryCache.key("releases-v4", source.id.toString(), source.revision, query)
        val cached = cache.lookup(key, forceRefresh)
        cached.value?.let { return json.readValue(it, ReleaseGroup::class.java).copy(position = source.position) }
        return gateway.releases(source, content).also {
            if (it.outcome != SourceOutcome.ERROR) {
                cache.put(key, json.writeValueAsString(it), DiscoveryCache.RESULTS_TTL, cached.generation)
            }
        }
    }
}
