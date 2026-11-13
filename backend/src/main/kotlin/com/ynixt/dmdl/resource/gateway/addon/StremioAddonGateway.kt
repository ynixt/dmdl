package com.ynixt.dmdl.resource.gateway.addon

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.model.AddonCatalog
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.AddonResource
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridCacheStatus
import com.ynixt.dmdl.domain.model.DebridProvider
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
import com.ynixt.dmdl.domain.model.normalizeAddonManifestUrl
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class StremioAddonGateway(
    private val json: ObjectMapper,
) : AddonGateway {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override fun manifest(url: String): AddonManifest {
        val root = get(normalizeAddonManifestUrl(url))
        val types = root["types"]?.toList()?.map { it.asString() }.orEmpty()
        val resources =
            root["resources"]
                ?.toList()
                ?.map { node ->
                    if (node.isString) {
                        AddonResource(node.asString(), types, root["idPrefixes"]?.toList()?.map { it.asString() }.orEmpty())
                    } else {
                        AddonResource(
                            text(node, "name"),
                            node["types"]?.toList()?.map { it.asString() } ?: types,
                            node["idPrefixes"]?.toList()?.map { it.asString() }.orEmpty(),
                        )
                    }
                }.orEmpty()
        val catalogs =
            root["catalogs"]
                ?.toList()
                ?.map { node ->
                    AddonCatalog(
                        text(node, "id"),
                        text(node, "type"),
                        node["name"]?.asString() ?: text(node, "id"),
                        node["extra"]?.any { it["name"]?.asString() == "search" } == true ||
                            node["extraSupported"]?.any { it.asString() == "search" } == true,
                    )
                }.orEmpty()
        return AddonManifest(text(root, "id"), text(root, "name"), types, resources, catalogs)
    }

    override fun search(
        source: AddonConfiguration,
        query: DiscoveryQuery,
    ): List<MetadataDetails> {
        val catalog =
            source.manifest.catalogs.singleOrNull { it.id == query.catalogId && it.type == query.resourceType && it.searchable }
                ?: throw AddonFailure("This metadata addon does not advertise the selected searchable catalog")
        val root =
            get(resource(source.manifestUrl, "catalog/${segment(catalog.type)}/${segment(catalog.id)}/search=${segment(query.query)}.json"))
        return root["metas"]?.toList()?.map { metadata(source, catalog.type, it) }.orEmpty()
    }

    override fun details(
        source: AddonConfiguration,
        identity: ContentIdentity,
    ): MetadataDetails {
        if (source.id != identity.sourceId ||
            source.revision != identity.sourceRevision
        ) {
            throw AddonFailure("Metadata source changed; search again")
        }
        if (!source.manifest.supports(
                "meta",
                identity.resourceType,
                identity.contentId,
            )
        ) {
            throw AddonFailure("Metadata addon does not support this identity")
        }
        val root = get(resource(source.manifestUrl, "meta/${segment(identity.resourceType)}/${segment(identity.contentId)}.json"))
        return metadata(source, identity.resourceType, root["meta"] ?: throw AddonFailure("Metadata item is unavailable"))
    }

    override fun releases(
        source: AddonConfiguration,
        content: ContentSnapshot,
    ): ReleaseGroup {
        val type = content.identity.resourceType
        val candidates =
            buildList {
                add(content.identity.videoId ?: content.identity.contentId)
                content.identity.mappedIds["imdb"]?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let {
                    add(if (content.category == MediaCategory.MOVIE) it else "$it:${content.season}:${content.episode}")
                }
            }
        val id =
            candidates.firstOrNull { source.manifest.supports("stream", type, it) }
                ?: return ReleaseGroup(
                    source.id,
                    source.name,
                    source.position,
                    SourceOutcome.INCOMPATIBLE,
                    message = "This addon does not support the selected content namespace",
                )
        return try {
            val streams = get(resource(source.manifestUrl, "stream/${segment(type)}/${segment(id)}.json"))["streams"]
            val releases =
                streams
                    ?.mapIndexedNotNull { index, stream ->
                        if (stream["streamData"]?.get("type")?.asString() in setOf("error", "statistic")) return@mapIndexedNotNull null
                        val url = stream["url"]?.asString()
                        val provider = streamProvider(stream, url)
                        val reference =
                            url?.let {
                                runCatching { ReleaseReference(ReleaseReferenceType.PROVIDER_HTTP, it) }.getOrNull()
                            }
                        reference?.let {
                            ReleaseSnapshot(
                                stream["title"]?.asString()?.takeIf(String::isNotBlank)
                                    ?: listOfNotNull(stream["name"]?.asString(), stream["description"]?.asString())
                                        .filter(String::isNotBlank)
                                        .joinToString("\n")
                                        .takeIf(String::isNotBlank)
                                    ?: "Release ${index + 1}",
                                ReleaseProvenance(
                                    source.id,
                                    source.revision,
                                    source.name,
                                    source.position,
                                    index,
                                    provider,
                                ),
                                it,
                                badgeText =
                                    listOfNotNull(
                                        stream["name"]?.asString(),
                                        stream["title"]?.asString(),
                                        stream["description"]?.asString(),
                                        stream["behaviorHints"]?.get("filename")?.asString(),
                                    ).joinToString(" "),
                                cacheStatus = streamCacheStatus(stream, provider),
                            )
                        }
                    }.orEmpty()
            ReleaseGroup(
                source.id,
                source.name,
                source.position,
                if (releases.isEmpty()) SourceOutcome.EMPTY else SourceOutcome.SUCCESS,
                releases,
            )
        } catch (_: Exception) {
            ReleaseGroup(
                source.id,
                source.name,
                source.position,
                SourceOutcome.ERROR,
                message = "Addon request failed; check its configuration and connectivity",
            )
        }
    }

    private fun metadata(
        source: AddonConfiguration,
        type: String,
        node: JsonNode,
    ): MetadataDetails {
        val id = text(node, "id")
        val mapped =
            buildMap {
                listOf("tmdb", "tvdb", "imdb").forEach { provider ->
                    val value =
                        sequenceOf(
                            node["${provider}_id"],
                            node["${provider}Id"],
                            node[provider],
                            node["external_ids"]?.get("${provider}_id"),
                        ).mapNotNull {
                            it
                                ?.takeIf { value ->
                                    value.isString || value.isIntegralNumber
                                }?.asString()
                                ?.takeIf(String::isNotBlank)
                        }.firstOrNull { if (provider == "imdb") it.matches(Regex("tt[0-9]+")) else it.matches(Regex("[1-9][0-9]*")) }
                    if (value != null) put(provider, value)
                }
                if (id.matches(Regex("tt[0-9]+"))) put("imdb", id)
                Regex("^(tmdb|tvdb):([1-9][0-9]*)$").matchEntire(id)?.let { put(it.groupValues[1], it.groupValues[2]) }
            }
        return MetadataDetails(
            ContentIdentity(source.id, source.revision, node["type"]?.asString() ?: type, id, mappedIds = mapped),
            node["name"]?.asString() ?: node["title"]?.asString() ?: id,
            node["year"]?.asString()?.take(4)?.toIntOrNull() ?: node["releaseInfo"]?.asString()?.take(4)?.toIntOrNull(),
            node["poster"]?.asString(),
            node["description"]?.asString(),
            node["videos"]
                ?.mapNotNull { video ->
                    val season = video["season"]?.asInt(-1) ?: -1
                    val episode = video["episode"]?.asInt(0) ?: 0
                    val videoId = video["id"]?.asString()?.takeIf(String::isNotBlank)
                    if (season < 0 ||
                        episode <= 0 ||
                        videoId == null
                    ) {
                        null
                    } else {
                        MetadataVideo(videoId, season, episode, video["title"]?.asString())
                    }
                }.orEmpty(),
        )
    }

    private fun get(url: String): JsonNode {
        val response =
            try {
                client.send(
                    HttpRequest
                        .newBuilder(URI(url))
                        .timeout(Duration.ofSeconds(15))
                        // AIOStreams exposes per-stream service metadata to compatible AIO clients.
                        .header("User-Agent", "AIO-DMDL/1.0")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
            } catch (_: java.net.http.HttpTimeoutException) {
                throw AddonFailure("Addon request timed out", "KZ.ADDON_TIMEOUT")
            } catch (_: Exception) {
                throw AddonFailure("Addon request failed; check configuration and connectivity")
            }
        if (response.statusCode() !in 200..299) {
            val code =
                when (response.statusCode()) {
                    401, 403 -> "KZ.ADDON_UNAUTHORIZED"
                    404 -> "KZ.ADDON_NOT_FOUND"
                    else -> "KZ.ADDON_HTTP_ERROR"
                }
            throw AddonFailure("Addon returned HTTP ${response.statusCode()}", code)
        }
        return try {
            json.readTree(response.body())
        } catch (_: Exception) {
            throw AddonFailure("Addon returned invalid JSON", "KZ.ADDON_INVALID_MANIFEST")
        }
    }

    private fun resource(
        manifest: String,
        path: String,
    ): String {
        val uri = URI(normalizeAddonManifestUrl(manifest))
        return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.removeSuffix(
            "manifest.json",
        )}$path${uri.rawQuery?.let { "?$it" }.orEmpty()}"
    }

    private fun streamProvider(
        stream: JsonNode,
        url: String?,
    ): DebridProvider? {
        provider(stream["streamData"]?.get("service")?.get("id")?.asString())?.let { return it }
        provider(stream["behaviorHints"]?.get("service")?.asString())?.let { return it }
        val name = stream["name"]?.asString().orEmpty()
        val namedProviders =
            Regex("(?i)\\b(RD(?=\\+)|AD(?=\\+)|TB(?=\\+)|Real[- ]?Debrid|All[- ]?Debrid|TorBox)\\b")
                .findAll(name)
                .mapNotNull { provider(it.value) }
                .distinct()
                .toList()
        namedProviders.singleOrNull()?.let { return it }
        val uri = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val parts =
            uri.path
                .orEmpty()
                .split('/')
                .filter(String::isNotEmpty)
        if (host == "torrentio.strem.fun") {
            return provider(if (parts.firstOrNull() == "resolve") parts.getOrNull(1) else parts.firstOrNull())
        }
        if (parts.firstOrNull() == "streaming_provider" && parts.getOrNull(2) == "playback") return provider(parts.getOrNull(3))
        return when {
            host == "torbox.app" || host.endsWith(".torbox.app") -> DebridProvider.TORBOX
            host == "real-debrid.com" || host.endsWith(".real-debrid.com") -> DebridProvider.REAL_DEBRID
            host == "alldebrid.com" || host.endsWith(".alldebrid.com") -> DebridProvider.ALL_DEBRID
            else -> null
        }
    }

    private fun streamCacheStatus(
        stream: JsonNode,
        service: DebridProvider?,
    ): DebridCacheStatus {
        val cached =
            sequenceOf(
                stream["streamData"]?.get("service")?.get("cached"),
                stream["behaviorHints"]?.get("cached"),
                stream["cached"],
            ).firstOrNull { it?.isBoolean == true }
        if (cached != null) return if (cached.asBoolean()) DebridCacheStatus.CACHED else DebridCacheStatus.UNCACHED
        if (service == null) return DebridCacheStatus.UNKNOWN
        val name = stream["name"]?.asString().orEmpty()
        if (Regex("(?i)\\buncached\\b|\\bdownload\\b|⏳").containsMatchIn(name)) return DebridCacheStatus.UNCACHED
        if (Regex("(?i)\\b(?:RD|AD|TB|TorBox|Real[- ]?Debrid|All[- ]?Debrid)\\+|\\bcached\\b|⚡|🚀").containsMatchIn(name)) {
            return DebridCacheStatus.CACHED
        }
        return DebridCacheStatus.UNKNOWN
    }

    private fun provider(value: String?): DebridProvider? =
        when (
            value
                ?.lowercase()
                ?.replace("-", "")
                ?.replace("_", "")
                ?.replace(" ", "")
        ) {
            "torbox", "tb" -> DebridProvider.TORBOX
            "realdebrid", "rd" -> DebridProvider.REAL_DEBRID
            "alldebrid", "ad" -> DebridProvider.ALL_DEBRID
            else -> null
        }

    private fun text(
        node: JsonNode,
        key: String,
    ): String =
        node[key]?.asString()?.takeIf(String::isNotBlank)
            ?: throw AddonFailure("Addon response is missing $key", "KZ.ADDON_INVALID_MANIFEST")

    private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
