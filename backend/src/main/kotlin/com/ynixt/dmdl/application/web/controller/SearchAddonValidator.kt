package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.web.dto.StrictRequest
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.ConfigurationValidation
import com.ynixt.dmdl.domain.model.normalizeAddonManifestUrl
import java.util.UUID

data class SearchAddonInput(
    val id: UUID? = null,
    val manifestUrl: String = "",
    val enabled: Boolean = true,
    val name: String? = null,
) : StrictRequest() {
    override fun toString(): String = "SearchAddonInput([redacted])"
}

class SearchAddonValidator(
    private val validation: ConfigurationValidation,
) {
    fun prepare(
        sources: List<SearchAddonInput>,
        existing: List<AddonConfiguration>? = null,
    ): Pair<Map<String, String>, List<AddonConfiguration>> {
        val errors = linkedMapOf<String, String>()
        val prepared = mutableListOf<AddonConfiguration>()
        val stored = existing.orEmpty().associateBy { it.id }
        val seen = mutableSetOf<UUID>()
        if (sources.none { it.enabled }) errors["search"] = "KZ.ADDON_SEARCH_REQUIRED"
        sources.forEachIndexed { index, source ->
            try {
                val old = source.id?.let(stored::get)
                require(source.id == null || (old != null && seen.add(source.id)))
                val url = normalizeAddonManifestUrl(source.manifestUrl)
                val configuration =
                    if (old != null && url == normalizeAddonManifestUrl(old.manifestUrl)) {
                        old.copy(
                            manifestUrl = url,
                            name = source.name?.trim()?.takeIf(String::isNotBlank) ?: old.manifest.name,
                            enabled = source.enabled,
                            position = index,
                        )
                    } else {
                        validation.addon(url, false, index, source.enabled, source.name).let {
                            it.copy(id = old?.id ?: it.id, revision = (old?.revision ?: 0) + 1)
                        }
                    }
                prepared.add(configuration)
            } catch (error: Exception) {
                errors["search.$index.manifestUrl"] = (error as? AddonFailure)?.code ?: "KZ.INVALID_CONFIGURATION"
            }
        }
        return errors to prepared
    }
}
