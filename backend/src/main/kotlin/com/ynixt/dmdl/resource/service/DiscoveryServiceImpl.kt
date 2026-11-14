package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.AnimeIndexGateway
import com.ynixt.dmdl.domain.model.ANIME_INDEX_PROVIDERS
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.AnimeVerdict
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.MediaKind
import com.ynixt.dmdl.domain.model.MetadataDetails
import com.ynixt.dmdl.domain.model.ReleaseGroup
import com.ynixt.dmdl.domain.model.ReleasePage
import com.ynixt.dmdl.domain.model.SourceOutcome
import com.ynixt.dmdl.domain.model.hasKitsuId
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.service.DiscoveryService
import com.ynixt.dmdl.domain.service.MetadataManifestBackfill
import com.ynixt.dmdl.resource.cache.DiscoveryCache
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Service
class DiscoveryServiceImpl(
    private val configuration: ConfigurationStore,
    private val manifestBackfill: MetadataManifestBackfill,
    private val gateway: AddonGateway,
    private val cache: DiscoveryCache,
    private val json: ObjectMapper,
    private val animeIndex: AnimeIndexGateway,
) : DiscoveryService {
    private val background = Executors.newVirtualThreadPerTaskExecutor()

    override fun search(
        query: DiscoveryQuery,
        forceRefresh: Boolean,
    ): List<MetadataDetails> {
        val source = requireNotNull(configuration.metadata()) { "Configure exactly one metadata addon before searching" }
        val normalized = query.copy(query = query.query.trim())
        val key = DiscoveryCache.key("search-v2", source.id.toString(), source.revision, json.writeValueAsString(normalized))
        val cached = cache.lookup(key, forceRefresh)
        cached.value?.let { return json.readValue(it, object : TypeReference<List<MetadataDetails>>() {}) }
        return gateway.search(source, normalized).also {
            cache.put(key, json.writeValueAsString(it), DiscoveryCache.RESULTS_TTL, cached.generation)
        }
    }

    override fun catalog(
        type: String,
        id: String,
        skip: Int,
        forceRefresh: Boolean,
        extras: Map<String, String>,
    ): List<MetadataDetails> {
        require(skip >= 0) { "Catalog skip must be non-negative" }
        val source = requireNotNull(manifestBackfill.current()) { "Configure a metadata addon" }
        val catalog =
            requireNotNull(source.manifest.catalogs.singleOrNull { it.type == type && it.id == id && it.browsable() }) {
                "Select a browsable catalog"
            }
        require(skip == 0 || catalog.pageable()) { "This catalog does not support paging" }
        val selectable = catalog.extra.orEmpty().filter { !it.options.isNullOrEmpty() && it.name !in setOf("search", "skip") }
        require(extras.keys.all { name -> selectable.any { it.name == name } }) { "Unsupported catalog parameter" }
        val selected =
            selectable
                .mapNotNull { extra ->
                    val value =
                        if (extras.containsKey(extra.name)) {
                            extras[extra.name]?.takeIf(String::isNotBlank)
                        } else {
                            extra.default?.takeIf { it in extra.options.orEmpty() }
                                ?: if (extra.isRequired) extra.options?.firstOrNull() else null
                        }
                    require(!extra.isRequired || value != null) { "Select a value for ${extra.name}" }
                    require(value == null || value in extra.options.orEmpty()) { "Invalid value for ${extra.name}" }
                    value?.let { extra.name to it }
                }.toMap()
                .toSortedMap()
        val selectionKey = if (selected.isEmpty()) "" else ":${json.writeValueAsString(selected)}"
        val key = DiscoveryCache.key("catalog-v1", source.id.toString(), source.revision, "$type:$id:$skip$selectionKey")
        val cached = cache.lookup(key, forceRefresh)
        cached.value?.let { return json.readValue(it, object : TypeReference<List<MetadataDetails>>() {}) }
        return gateway.catalog(source, catalog, skip, selected).also {
            cache.put(key, json.writeValueAsString(it), DiscoveryCache.RESULTS_TTL, cached.generation)
        }
    }

    override fun details(
        identity: ContentIdentity,
        forceRefresh: Boolean,
    ): MetadataDetails {
        val metadata = metadata(identity, forceRefresh)
        return classify(metadata, forceRefresh, routeIds(identity.contentId), null)
    }

    override fun lookup(
        kind: MediaKind,
        contentId: String,
        forceRefresh: Boolean,
    ): MetadataDetails {
        val source = requireNotNull(configuration.metadata()) { "Configure a metadata addon" }
        val candidates = if (kind == MediaKind.MOVIE) listOf("movie") else listOf("series", "anime")
        // Addons disagree on whether anime is `series` or `anime`; ask with the types this addon serves for the id.
        val types = candidates.filter { source.manifest.supports("meta", it, contentId) }.ifEmpty { candidates.take(1) }
        val routeIds = if (kind == MediaKind.SHOW) routeIds(contentId) else emptyMap()
        // A `tvdb:`/`mal:`... route id is already enough to ask Kitsu, so it runs while the addon answers.
        val early = routeIds.takeIf { it.isNotEmpty() }?.let { CompletableFuture.supplyAsync({ verdict(it, forceRefresh) }, background) }
        var failure: RuntimeException? = null
        for (type in types) {
            try {
                val metadata = metadata(ContentIdentity(source.id, source.revision, type, contentId), forceRefresh)
                return classify(metadata, forceRefresh, routeIds, early)
            } catch (error: AddonFailure) {
                failure = error
            }
        }
        throw failure!!
    }

    private fun metadata(
        identity: ContentIdentity,
        forceRefresh: Boolean,
    ): MetadataDetails {
        val source = requireNotNull(configuration.metadata()) { "Configure a metadata addon" }
        require(source.id == identity.sourceId && source.revision == identity.sourceRevision) { "Metadata source changed; search again" }
        val canonical = identity.copy(videoId = null, mappedIds = emptyMap())
        val key = DiscoveryCache.key("meta-v5", source.id.toString(), source.revision, json.writeValueAsString(canonical))
        val cached = cache.lookup(key, forceRefresh)
        return cached.value?.let { json.readValue(it, MetadataDetails::class.java) }
            ?: gateway.details(source, canonical).also {
                cache.put(key, json.writeValueAsString(it), DiscoveryCache.METADATA_TTL, cached.generation)
            }
    }

    /**
     * Overrides the metadata-only classification with Kitsu's verdict when the metadata has no Kitsu id.
     * Ids from the requested route id form one group (askable before the metadata arrives); ids only the
     * metadata reveals form a second group, asked only when the first group did not prove an anime.
     */
    private fun classify(
        metadata: MetadataDetails,
        forceRefresh: Boolean,
        routeIds: Map<String, String>,
        early: CompletableFuture<AnimeVerdict>?,
    ): MetadataDetails {
        if (metadata.identity.resourceType == "movie" || hasKitsuId(metadata.identity)) return metadata
        val first =
            routeIds.takeIf { it.isNotEmpty() }?.let { ids ->
                early?.let { runCatching { it.join() }.getOrNull() } ?: verdict(ids, forceRefresh)
            }
        val rest = providerIds(metadata.identity.mappedIds).filter { (provider, id) -> routeIds[provider] != id }
        val second = if (first == AnimeVerdict.ANIME || rest.isEmpty()) null else verdict(rest, forceRefresh)
        val verdicts = listOfNotNull(first, second)
        val verdict =
            when {
                AnimeVerdict.ANIME in verdicts -> AnimeVerdict.ANIME
                verdicts.isNotEmpty() && verdicts.all { it == AnimeVerdict.NOT_ANIME } -> AnimeVerdict.NOT_ANIME
                else -> AnimeVerdict.UNKNOWN
            }
        return when (verdict) {
            AnimeVerdict.ANIME -> metadata.copy(category = MediaCategory.ANIME)
            AnimeVerdict.NOT_ANIME -> metadata.copy(category = MediaCategory.SERIES)
            AnimeVerdict.UNKNOWN -> metadata
        }
    }

    /** One cached Kitsu verdict per id set; failures are not cached. */
    private fun verdict(
        ids: Map<String, String>,
        forceRefresh: Boolean,
    ): AnimeVerdict {
        val sorted = ids.toSortedMap()
        val key = DiscoveryCache.key("kitsu-v1", "kitsu", 0, json.writeValueAsString(sorted))
        val cached = cache.lookup(key, forceRefresh)
        cached.value?.let { value -> runCatching { AnimeVerdict.valueOf(value) }.getOrNull()?.let { return it } }
        return animeIndex.lookup(sorted).also {
            when (it) {
                AnimeVerdict.ANIME -> cache.put(key, it.name, DiscoveryCache.KITSU_ANIME_TTL, cached.generation)
                AnimeVerdict.NOT_ANIME -> cache.put(key, it.name, DiscoveryCache.KITSU_SERIES_TTL, cached.generation)
                AnimeVerdict.UNKNOWN -> Unit
            }
        }
    }

    private fun providerIds(ids: Map<String, String>): Map<String, String> =
        ANIME_INDEX_PROVIDERS.mapNotNull { provider -> ids[provider]?.let { provider to it } }.toMap()

    /** Kitsu-askable ids encoded in a content id such as `tvdb:267440` or `mal:61469`. */
    private fun routeIds(contentId: String): Map<String, String> =
        Regex("^(mal|anilist|anidb|tvdb):([1-9][0-9]*)$").matchEntire(contentId)?.let {
            mapOf(it.groupValues[1] to it.groupValues[2])
        } ?: emptyMap()

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
        val query =
            json.writeValueAsString(
                listOf(
                    identity,
                    if (content.category ==
                        MediaCategory.MOVIE
                    ) {
                        "movie"
                    } else {
                        "episode"
                    },
                    content.season,
                    content.episode,
                ),
            )
        val key = DiscoveryCache.key("releases-v5", source.id.toString(), source.revision, query)
        val cached = cache.lookup(key, forceRefresh)
        cached.value?.let { return json.readValue(it, ReleaseGroup::class.java).copy(position = source.position) }
        return gateway.releases(source, content).also {
            if (it.outcome != SourceOutcome.ERROR) {
                cache.put(key, json.writeValueAsString(it), DiscoveryCache.RESULTS_TTL, cached.generation)
            }
        }
    }
}
