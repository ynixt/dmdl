package com.ynixt.dmdl.resource.gateway.kitsu

import com.ynixt.dmdl.application.config.KitsuProperties
import com.ynixt.dmdl.domain.gateway.AnimeIndexGateway
import com.ynixt.dmdl.domain.model.AnimeVerdict
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** Asks Kitsu's public mappings API whether any of a title's ids is mapped to a Kitsu anime. */
@Component
class KitsuAnimeIndexGateway(
    private val properties: KitsuProperties,
    private val json: ObjectMapper,
) : AnimeIndexGateway {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(properties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override fun lookup(ids: Map<String, String>): AnimeVerdict {
        // Exact (site, id) pairs we accept; Kitsu matches every listed site with every listed id.
        val wanted = SITES.flatMap { (provider, sites) -> ids[provider]?.let { id -> sites.map { it to id } }.orEmpty() }.toSet()
        if (wanted.isEmpty()) return AnimeVerdict.UNKNOWN
        val query =
            listOf(
                "filter[externalSite]" to wanted.map { it.first }.distinct().joinToString(","),
                "filter[externalId]" to wanted.map { it.second }.distinct().joinToString(","),
                "page[limit]" to PAGE_LIMIT.toString(),
            ).joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }
        var url: String? = "${properties.baseUrl.trimEnd('/')}/mappings?$query"
        var pages = 0
        while (url != null) {
            if (pages++ == MAX_PAGES) return AnimeVerdict.UNKNOWN
            val page = page(url) ?: return AnimeVerdict.UNKNOWN
            val data = page["data"]?.takeIf { it.isArray } ?: return AnimeVerdict.UNKNOWN
            val found =
                data.any { mapping ->
                    val attributes = mapping["attributes"]
                    (attributes?.get("externalSite")?.asString() to attributes?.get("externalId")?.asString()) in wanted
                }
            if (found) return AnimeVerdict.ANIME
            url = page["links"]?.get("next")?.takeIf { it.isString }?.asString()
        }
        return AnimeVerdict.NOT_ANIME
    }

    /** The parsed page, or null when Kitsu gave no reliable answer. */
    private fun page(url: String): JsonNode? =
        try {
            val response =
                client.send(
                    HttpRequest
                        .newBuilder(URI(url))
                        .timeout(properties.requestTimeout)
                        .header("Accept", "application/vnd.api+json")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
            if (response.statusCode() !in 200..299) {
                log.warn("Kitsu mapping lookup answered HTTP {}", response.statusCode())
                null
            } else {
                json.readTree(response.body())
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (error: Exception) {
            log.warn("Kitsu mapping lookup failed: {}", error.javaClass.simpleName)
            null
        }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val log = LoggerFactory.getLogger(KitsuAnimeIndexGateway::class.java)

        /** Kitsu site names per provider; TVDB is stored under two names. */
        private val SITES =
            listOf(
                "mal" to listOf("myanimelist/anime"),
                "anilist" to listOf("anilist/anime"),
                "anidb" to listOf("anidb"),
                "tvdb" to listOf("thetvdb/series", "thetvdb"),
            )

        /** Kitsu's maximum page size; 5 sites x 4 ids fit in one page unless Kitsu holds duplicates. */
        private const val PAGE_LIMIT = 20
        private const val MAX_PAGES = 3
    }
}
