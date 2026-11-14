package com.ynixt.dmdl.domain.model

import java.net.URI
import java.util.UUID

data class AddonCatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val default: String? = null,
)

data class AddonCatalog(
    val id: String,
    val type: String,
    val name: String,
    val searchable: Boolean,
    val extra: List<AddonCatalogExtra>? = null,
) {
    fun browsable(): Boolean =
        type in setOf("movie", "series", "anime") &&
            extra?.all { !it.isRequired || (it.name !in setOf("search", "skip") && !it.options.isNullOrEmpty()) } == true

    fun pageable(): Boolean = browsable() && extra?.any { it.name == "skip" } == true
}

data class AddonResource(
    val name: String,
    val types: List<String>,
    val idPrefixes: List<String>,
)

data class AddonManifest(
    val id: String,
    val name: String,
    val types: List<String>,
    val resources: List<AddonResource>,
    val catalogs: List<AddonCatalog>,
) {
    fun supports(
        resource: String,
        type: String,
        id: String? = null,
    ): Boolean =
        resources.any {
            it.name == resource &&
                (it.types.isEmpty() || type in it.types) &&
                (id == null || it.idPrefixes.isEmpty() || it.idPrefixes.any(id::startsWith))
        }
}

data class AddonConfiguration(
    val id: UUID,
    val revision: Long,
    val manifestUrl: String,
    val manifest: AddonManifest,
    val enabled: Boolean = true,
    val position: Int = 0,
    val name: String = manifest.name,
) {
    override fun toString(): String = "AddonConfiguration(id=$id, revision=$revision)"
}

class AddonFailure(
    message: String,
    val code: String = "KZ.ADDON_UNAVAILABLE",
) : RuntimeException(message)

fun normalizeAddonManifestUrl(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrNull()
    if (uri == null ||
        uri.scheme?.lowercase() !in setOf("http", "https", "stremio") ||
        uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null
    ) {
        throw AddonFailure("Enter a valid HTTP(S) or Stremio addon URL", "KZ.ADDON_INVALID_URL")
    }
    val scheme = if (uri.scheme.equals("stremio", true)) "https" else uri.scheme.lowercase()
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return "$scheme://${uri.rawAuthority}$manifestPath${uri.rawQuery?.let { "?$it" }.orEmpty()}"
}
