package com.ynixt.dmdl.domain.model

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.regex.Pattern

data class StreamBadge(
    val name: String,
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = "",
)

class StreamBadgeRules private constructor(
    private val filters: List<Pair<Pattern, StreamBadge>>,
) {
    fun match(release: ReleaseSnapshot): List<StreamBadge> {
        val availability =
            when (release.cacheStatus) {
                DebridCacheStatus.CACHED -> "⚡ CACHED"
                DebridCacheStatus.UNCACHED -> "⏳ UNCACHED"
                DebridCacheStatus.UNKNOWN -> "CACHE_UNKNOWN"
            }
        return match("${release.badgeText ?: release.title} $availability")
    }

    fun match(text: String): List<StreamBadge> {
        val candidate = text.replace(Regex("\\s+"), " ")
        return filters
            .filter { (pattern, _) -> pattern.matcher(candidate).find() }
            .map { it.second }
            .distinctBy { it.imageURL.ifBlank { it.name } }
    }

    companion object {
        val defaultJson: String by lazy {
            requireNotNull(StreamBadgeRules::class.java.getResourceAsStream("/stream-badges-default.json"))
                .bufferedReader()
                .use { it.readText() }
        }

        private val json = ObjectMapper()

        fun parse(value: String): StreamBadgeRules =
            try {
                val root = json.readTree(value)
                require(root.isObject)
                val packs = root.get("imports")
                val pack =
                    if (packs == null) {
                        root
                    } else {
                        require(packs.isArray)
                        packs.firstOrNull { it.path("isActive").asBoolean(it.path("active").asBoolean(true)) }
                            ?: packs.firstOrNull()
                    }
                val filters = pack?.get("filters")
                require((filters == null && packs?.isEmpty == true) || filters?.isArray == true)
                StreamBadgeRules(filters?.mapNotNull(::compile).orEmpty())
            } catch (_: Exception) {
                throw IllegalArgumentException("KZ.BADGE_JSON_INVALID")
            }

        private fun compile(filter: JsonNode): Pair<Pattern, StreamBadge>? {
            require(filter.isObject)
            val name = filter.path("name").asString("").trim()
            val expression = filter.path("pattern").asString("").trim()
            require(name.isNotEmpty() && expression.isNotEmpty())
            // Some exported JSON encodes a regex word boundary as a backspace.
            val pattern = Pattern.compile(expression.replace("\u0008", "\\b"))
            val image = filter.path("imageURL").asString("")
            if (image.isNotBlank()) {
                val uri = URI(image)
                require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null)
            }
            if (!filter.path("isEnabled").asBoolean(true)) return null
            return pattern to
                StreamBadge(
                    name,
                    image,
                    filter.path("tagColor").asString(""),
                    filter.path("tagStyle").asString(""),
                    filter.path("textColor").asString(""),
                    filter.path("borderColor").asString(""),
                )
        }
    }
}
