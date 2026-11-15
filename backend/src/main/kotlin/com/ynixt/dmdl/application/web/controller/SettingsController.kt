package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.application.web.dto.StrictRequest
import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.ConfigurationValidation
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.FinalizationChecks
import com.ynixt.dmdl.domain.model.OrganizationRenderer
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.StreamBadgeRules
import com.ynixt.dmdl.domain.model.normalizeAddonManifestUrl
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.service.MetadataManifestBackfill
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import java.util.UUID

data class SettingsInput(
    val locale: String? = null,
    val maxConcurrentDownloads: Int? = null,
    val organization: OrganizationSettings? = null,
    val transfer: Aria2TransferOptions? = null,
    val streamBadgeJson: String? = null,
    val finalization: FinalizationChecks? = null,
) : StrictRequest()

data class AddonInput(
    val manifestUrl: String? = null,
    val enabled: Boolean = true,
    val position: Int = 0,
    val name: String? = null,
) : StrictRequest() {
    override fun toString(): String = "AddonInput([redacted])"
}

class SettingsValidationFailure(
    val errors: Map<String, String>,
    val code: String = "KZ.INVALID_CONFIGURATION",
) : RuntimeException(code)

data class PreviewInput(
    val organization: OrganizationSettings,
    val content: ContentSnapshot,
    val originalFileName: String = "Example.Release.mkv",
    val destinationId: UUID? = null,
) : StrictRequest()

@RestController
@RequestMapping("/api")
class SettingsController(
    private val store: ConfigurationStore,
    private val addons: AddonGateway,
    private val storage: StorageProperties,
    private val finalizer: FileFinalizer,
    private val manifestBackfill: MetadataManifestBackfill,
) {
    private val validation = ConfigurationValidation(addons, finalizer)
    private val searchValidator = SearchAddonValidator(validation)
    private val renderer = OrganizationRenderer()

    private fun root() =
        Path
            .of(storage.root)
            .toAbsolutePath()
            .normalize()
            .toString()

    @GetMapping("/settings")
    fun settings(): Map<String, Any?> {
        val settings = requireNotNull(store.settings())
        return mapOf(
            "username" to settings.username,
            "locale" to settings.locale,
            "maxConcurrentDownloads" to settings.maxConcurrentDownloads,
            "organization" to settings.organization,
            "transfer" to settings.transfer,
            "finalization" to settings.finalization,
            "onboardingCompleted" to settings.onboardingCompleted,
            "configurationRevision" to settings.configurationRevision,
            "storageRoot" to root(),
            "streamBadgeJson" to settings.streamBadgeJson,
        )
    }

    @PutMapping("/settings")
    fun update(
        @RequestBody input: SettingsInput,
    ): Map<String, Any?> {
        val old = requireNotNull(store.settings())
        val organization = input.organization?.copy(revision = old.organization.revision + 1) ?: old.organization
        val transfer = input.transfer ?: old.transfer
        val locale = input.locale ?: old.locale
        val updated =
            old.copy(
                locale = locale,
                maxConcurrentDownloads =
                    input.maxConcurrentDownloads ?: old.maxConcurrentDownloads,
                organization = organization,
                transfer = transfer,
                streamBadgeJson = input.streamBadgeJson ?: old.streamBadgeJson,
                finalization = input.finalization ?: old.finalization,
            )
        validation.settings(updated)
        store.saveSettings(updated)
        return settings()
    }

    @GetMapping("/settings/stream-badges/defaults")
    fun badgeDefaults(): Map<String, String> = mapOf("streamBadgeJson" to StreamBadgeRules.defaultJson)

    @PostMapping("/settings/organization/preview")
    fun preview(
        @RequestBody input: PreviewInput,
    ): Map<String, Any> {
        renderer.validate(input.organization)
        val category = input.organization.categories.getValue(input.content.category)
        val destination =
            requireNotNull(
                category.destinations.singleOrNull {
                    it.id ==
                        (input.destinationId ?: category.defaultDestinationId)
                },
            ) { "Select a registered destination" }
        val path =
            renderer.render(
                OrganizationSnapshot(input.organization.revision, destination.path, category, destination.id, destination.name),
                input.content,
                input.originalFileName,
            )
        return mapOf("path" to path.toString(), "illustrativeFilename" to true)
    }

    @GetMapping("/settings/organization/defaults")
    fun defaults(): OrganizationSettings {
        val current = requireNotNull(store.settings()).organization
        val defaults = OrganizationSettings.defaults(storage.root)
        return current.copy(
            categories =
                current.categories.mapValues { (category, config) ->
                    config.copy(folderTemplate = defaults.getValue(category).folderTemplate)
                },
        )
    }

    @GetMapping("/addons")
    fun listAddons() =
        mapOf(
            "metadata" to manifestBackfill.current()?.let(::publicAddon),
            "search" to store.searchAddons().map(::publicAddon),
        )

    @PutMapping("/addons/metadata")
    fun metadata(
        @RequestBody input: AddonInput,
    ): Map<String, Map<String, Any?>?> {
        val old = store.metadata()
        val prepared =
            try {
                val url = normalizeAddonManifestUrl(input.manifestUrl.orEmpty())
                if (old != null && url == normalizeAddonManifestUrl(old.manifestUrl)) {
                    validation.requireRole(old.manifest, true)
                    old.copy(manifestUrl = url, name = input.name?.trim()?.takeIf(String::isNotBlank) ?: old.manifest.name)
                } else {
                    validation.addon(url, true, name = input.name ?: old?.name).let {
                        it.copy(id = old?.id ?: it.id, revision = (old?.revision ?: 0) + 1)
                    }
                }
            } catch (error: Exception) {
                throw SettingsValidationFailure(
                    mapOf("metadata.manifestUrl" to ((error as? AddonFailure)?.code ?: "KZ.INVALID_CONFIGURATION")),
                )
            }
        store.saveMetadata(prepared)
        return mapOf("metadata" to publicAddon(prepared))
    }

    @PutMapping("/addons/search")
    fun replaceSearch(
        @RequestBody input: List<SearchAddonInput>,
    ): Map<String, Any?> {
        val (errors, prepared) = searchValidator.prepare(input, store.searchAddons())
        if (errors.isNotEmpty()) throw SettingsValidationFailure(errors, "KZ.ADDONS_INVALID")
        store.replaceSearchAddons(prepared)
        return listAddons()
    }

    private fun publicAddon(addon: AddonConfiguration): Map<String, Any?> =
        mapOf(
            "id" to addon.id,
            "revision" to addon.revision,
            "name" to addon.name,
            "manifest" to
                mapOf(
                    "id" to addon.manifest.id,
                    "name" to addon.manifest.name,
                    "types" to addon.manifest.types,
                    "resources" to addon.manifest.resources,
                    "catalogs" to
                        addon.manifest.catalogs.map { catalog ->
                            mapOf(
                                "id" to catalog.id,
                                "type" to catalog.type,
                                "name" to catalog.name,
                                "searchable" to catalog.searchable,
                                "browsable" to catalog.browsable(),
                                "pageable" to catalog.pageable(),
                                "extra" to catalog.extra,
                            )
                        },
                ),
            "enabled" to addon.enabled,
            "position" to addon.position,
            "manifestUrl" to addon.manifestUrl,
        )

    @ExceptionHandler(SettingsValidationFailure::class)
    fun invalidSettings(error: SettingsValidationFailure): ResponseEntity<*> =
        ResponseEntity.badRequest().body(mapOf("error" to error.code, "errors" to error.errors))

    @GetMapping("/setup")
    fun setup(): Map<String, Any> {
        val settings = store.settings()
        val missing =
            buildList {
                if (store.metadata() == null) add("metadata")
                if (store.searchAddons().none { it.enabled }) add("searchAddons")
                val validStorage =
                    runCatching {
                        renderer.validate(requireNotNull(settings).organization, finalizer::checkStorage)
                    }.isSuccess
                if (!validStorage) add("storage")
            }
        return mapOf("missing" to missing, "ready" to missing.isEmpty(), "completed" to (settings?.onboardingCompleted == true))
    }
}
