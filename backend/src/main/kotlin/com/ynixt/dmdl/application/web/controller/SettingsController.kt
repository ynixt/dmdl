package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.application.web.dto.StrictRequest
import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.gateway.JellyfinGateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.ConfigurationValidation
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.JellyfinSettings
import com.ynixt.dmdl.domain.model.OrganizationRenderer
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.StreamBadgeRules
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class SettingsInput(
    val locale: String? = null,
    val maxConcurrentDownloads: Int? = null,
    val organization: OrganizationSettings? = null,
    val transfer: Aria2TransferOptions? = null,
    val jellyfin: JellyfinSettings? = null,
    val streamBadgeJson: String? = null,
) : StrictRequest()

data class AddonInput(
    val manifestUrl: String? = null,
    val enabled: Boolean = true,
    val position: Int = 0,
    val name: String? = null,
) : StrictRequest() {
    override fun toString(): String = "AddonInput([redacted])"
}

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
    private val jellyfin: JellyfinGateway,
    private val storage: StorageProperties,
    private val finalizer: FileFinalizer,
) {
    private val validation = ConfigurationValidation(addons, finalizer)
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
            "onboardingCompleted" to settings.onboardingCompleted,
            "configurationRevision" to settings.configurationRevision,
            "storageRoot" to root(),
            "streamBadgeJson" to settings.streamBadgeJson,
            "jellyfin" to
                mapOf(
                    "enabled" to settings.jellyfin.enabled,
                    "url" to settings.jellyfin.url,
                    "tokenConfigured" to !settings.jellyfin.token.isNullOrBlank(),
                ),
        )
    }

    @PutMapping("/settings")
    fun update(
        @RequestBody input: SettingsInput,
    ): Map<String, Any?> {
        val old = requireNotNull(store.settings())
        val organization = input.organization?.copy(revision = old.organization.revision + 1) ?: old.organization
        val transfer = input.transfer ?: old.transfer
        val notify = input.jellyfin?.let { it.copy(token = it.token?.takeIf(String::isNotBlank) ?: old.jellyfin.token) } ?: old.jellyfin
        val locale = input.locale ?: old.locale
        val updated =
            old.copy(
                locale = locale,
                maxConcurrentDownloads =
                    input.maxConcurrentDownloads ?: old.maxConcurrentDownloads,
                organization = organization,
                transfer = transfer,
                jellyfin = notify,
                streamBadgeJson = input.streamBadgeJson ?: old.streamBadgeJson,
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

    @PostMapping("/settings/jellyfin/test")
    fun testJellyfin(
        @RequestBody input: JellyfinSettings,
    ): Map<String, Boolean> {
        jellyfin.test(input.copy(token = input.token?.takeIf(String::isNotBlank) ?: store.settings()?.jellyfin?.token))
        return mapOf("ok" to true)
    }

    @GetMapping("/addons")
    fun listAddons() =
        mapOf(
            "metadata" to store.metadata()?.let(::publicAddon),
            "search" to store.searchAddons().map(::publicAddon),
        )

    @PutMapping("/addons/metadata")
    fun metadata(
        @RequestBody input: AddonInput,
    ) {
        val old = store.metadata()
        val url = input.manifestUrl?.takeIf(String::isNotBlank) ?: requireNotNull(old).manifestUrl
        val prepared = validation.addon(url, true, name = input.name ?: old?.name)
        store.saveMetadata(prepared.copy(id = old?.id ?: prepared.id, revision = (old?.revision ?: 0) + 1))
    }

    @PostMapping("/addons/search")
    fun createAddon(
        @RequestBody input: AddonInput,
    ) {
        saveAddon(UUID.randomUUID(), input, false)
    }

    @PutMapping("/addons/search/{id}")
    fun updateAddon(
        @PathVariable id: UUID,
        @RequestBody input: AddonInput,
    ) {
        saveAddon(id, input, true)
    }

    @DeleteMapping("/addons/search/{id}")
    fun removeAddon(
        @PathVariable id: UUID,
    ) {
        store.removeSearchAddon(id)
    }

    private fun saveAddon(
        id: UUID,
        input: AddonInput,
        update: Boolean,
    ) {
        val old = store.searchAddons().singleOrNull { it.id == id }
        require(!update || old != null) { "Search addon not found" }
        require(input.position >= 0)
        val url = input.manifestUrl?.takeIf(String::isNotBlank) ?: requireNotNull(old).manifestUrl
        val prepared = validation.addon(url, false, input.position, input.enabled, input.name ?: old?.name)
        store.saveSearchAddon(prepared.copy(id = id, revision = (old?.revision ?: 0) + 1))
    }

    private fun publicAddon(addon: AddonConfiguration): Map<String, Any?> =
        mapOf(
            "id" to addon.id,
            "revision" to addon.revision,
            "name" to addon.name,
            "manifest" to addon.manifest,
            "enabled" to addon.enabled,
            "position" to addon.position,
            "urlConfigured" to true,
        )

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
