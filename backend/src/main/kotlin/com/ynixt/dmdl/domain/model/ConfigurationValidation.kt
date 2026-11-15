package com.ynixt.dmdl.domain.model

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
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
        requireRole(manifest, metadata)
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

    /** Checks the resource declarations a manifest needs for its configured role, without fetching it. */
    fun requireRole(
        manifest: AddonManifest,
        metadata: Boolean,
    ) {
        val declared = manifest.resources.mapTo(mutableSetOf()) { it.name }
        val code =
            if (metadata) {
                when {
                    "catalog" !in declared && "meta" !in declared -> "KZ.ADDON_MISSING_CATALOG_META"
                    "catalog" !in declared -> "KZ.ADDON_MISSING_CATALOG"
                    "meta" !in declared -> "KZ.ADDON_MISSING_META"
                    else -> null
                }
            } else {
                "KZ.ADDON_MISSING_STREAM".takeIf { "stream" !in declared }
            }
        if (code != null) throw AddonFailure("Manifest does not declare the required resources", code)
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
    }
}
