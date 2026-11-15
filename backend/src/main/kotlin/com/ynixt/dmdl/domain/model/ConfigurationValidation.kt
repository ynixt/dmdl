package com.ynixt.dmdl.domain.model

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
import java.net.URI
import java.util.UUID

class ConfigurationValidation(
    private val addons: AddonGateway,
    private val finalizer: FileFinalizer,
) {
    fun addon(
        url: String,
        metadata: Boolean,
        position: Int = 0,
        enabled: Boolean = true,
        name: String? = null,
    ): AddonConfiguration {
        val manifestUrl = normalizeAddonManifestUrl(url)
        val manifest = addons.manifest(manifestUrl)
        val compatible =
            if (metadata) {
                manifest.resources.any { it.name == "meta" } && manifest.catalogs.any { it.searchable }
            } else {
                manifest.resources.any {
                    it.name == "stream" &&
                        (it.types.isEmpty() || it.types.any { type -> type in setOf("movie", "series", "anime") })
                }
            }
        if (!compatible) {
            throw AddonFailure(
                "Manifest does not support the required resources",
                if (metadata) "KZ.ADDON_METADATA_INCOMPATIBLE" else "KZ.ADDON_SEARCH_INCOMPATIBLE",
            )
        }
        return AddonConfiguration(
            UUID.randomUUID(),
            1,
            manifestUrl,
            manifest,
            enabled,
            position,
            name?.trim()?.takeIf(String::isNotBlank) ?: manifest.name,
        )
    }

    fun settings(
        settings: ApplicationSettings,
        storageCheck: (String) -> Unit = finalizer::checkStorage,
    ) {
        OrganizationRenderer().validate(settings.organization, storageCheck)
        StreamBadgeRules.parse(settings.streamBadgeJson)
        val transfer = settings.transfer
        require(
            transfer.split in 1..16 && transfer.maxConnectionPerServer in 1..16 && transfer.minSplitSizeMib in 1..1024 &&
                transfer.maxTries in 0..100 &&
                transfer.retryWaitSeconds in 0..600 &&
                (!transfer.lowestSpeedLimitEnabled || transfer.lowestSpeedLimitKib in 1..1048576) &&
                transfer.diskCacheMib in 0..4096 && transfer.timeoutSeconds in 1..600 &&
                transfer.maxFileNotFound in 0..100 && transfer.maxConcurrentPerDebrid in 1..settings.maxConcurrentDownloads &&
                transfer.autoSaveIntervalSeconds in 0..600,
        ) { "Invalid aria2 transfer settings" }
        require(settings.locale in setOf("en", "en-US", "pt-BR")) { "Unsupported language" }
        jellyfin(settings.jellyfin)
    }

    fun jellyfin(settings: JellyfinSettings) {
        if (!settings.enabled) return
        require(!settings.url.isNullOrBlank() && !settings.token.isNullOrBlank()) { "Enabled Jellyfin requires a URL and token" }
        val uri = URI(settings.url)
        require(
            uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null,
        ) {
            "Invalid Jellyfin base URL"
        }
    }
}
