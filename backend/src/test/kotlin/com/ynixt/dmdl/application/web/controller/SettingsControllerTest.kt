package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.gateway.JellyfinGateway
import com.ynixt.dmdl.domain.model.AddonCatalog
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.AddonResource
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.JellyfinSettings
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SettingsControllerTest {
    @Test
    fun `addon JSON exposes catalog browsing and paging flags`() {
        metadata =
            first.copy(
                manifest =
                    manifest.copy(
                        catalogs =
                            listOf(
                                AddonCatalog(
                                    "pages",
                                    "movie",
                                    "Pages",
                                    true,
                                    listOf(
                                        com.ynixt.dmdl.domain.model
                                            .AddonCatalogExtra("skip"),
                                        com.ynixt.dmdl.domain.model
                                            .AddonCatalogExtra("genre", true, listOf("Action", "Drama"), "Drama"),
                                    ),
                                ),
                            ),
                    ),
            )
        val mapper =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .addModule(
                    tools.jackson.module.kotlin.KotlinModule
                        .Builder()
                        .build(),
                ).build()
        val response = mapper.readTree(mapper.writeValueAsString(controller.listAddons()))
        val catalog = response["metadata"]["manifest"]["catalogs"][0]
        assertEquals(true, catalog["browsable"].asBoolean())
        assertEquals(true, catalog["pageable"].asBoolean())
        assertEquals("Drama", catalog["extra"][1]["default"].asString())
        assertEquals("Action", catalog["extra"][1]["options"][0].asString())
    }

    private val store = mockk<ConfigurationStore>(relaxed = true)
    private val addons = mockk<AddonGateway>()
    private val jellyfin = mockk<JellyfinGateway>(relaxed = true)
    private val backfill = mockk<com.ynixt.dmdl.domain.service.MetadataManifestBackfill>()
    private val controller =
        SettingsController(store, addons, jellyfin, StorageProperties(), mockk<FileFinalizer>(relaxed = true), backfill)
    private val manifest =
        AddonManifest(
            "fixture",
            "Fixture",
            listOf("movie"),
            listOf(AddonResource("stream", emptyList(), emptyList()), AddonResource("meta", emptyList(), emptyList())),
            listOf(AddonCatalog("movies", "movie", "Movies", true)),
        )
    private val first = AddonConfiguration(UUID.randomUUID(), 7, "https://first/manifest.json", manifest)
    private val second = AddonConfiguration(UUID.randomUUID(), 3, "https://second/manifest.json", manifest, position = 1)
    private var search = listOf(first, second)
    private var metadata = first
    private var settings = ApplicationSettings("fixture", "hash", jellyfin = JellyfinSettings(false, "https://jellyfin", "stored-token"))

    init {
        every { store.searchAddons() } answers { search }
        every { store.metadata() } answers { metadata }
        every { backfill.current() } answers { metadata }
        every { store.settings() } answers { settings }
        every { store.replaceSearchAddons(any()) } answers { search = firstArg() }
        every { store.saveMetadata(any()) } answers { metadata = firstArg() }
        every { store.saveSettings(any()) } answers { settings = firstArg() }
    }

    private fun input(addon: AddonConfiguration) = SearchAddonInput(addon.id, addon.manifestUrl, addon.enabled, addon.name)

    @Test
    fun `toggle and normalized URL saves never fetch a manifest or change revisions`() {
        controller.replaceSearch(listOf(input(first).copy(manifestUrl = "stremio://first/", enabled = false), input(second)))
        assertEquals(listOf(7L, 3L), search.map { it.revision })
        assertEquals(false, search.first().enabled)
        verify(exactly = 0) { addons.manifest(any()) }
    }

    @Test
    fun `URL change bumps only its revision and a new addon starts at one`() {
        every { addons.manifest(any()) } returns manifest
        controller.replaceSearch(
            listOf(input(first).copy(manifestUrl = "https://changed"), input(second), SearchAddonInput(manifestUrl = "https://new")),
        )
        assertEquals(listOf(8L, 3L, 1L), search.map { it.revision })
        verify(exactly = 2) { addons.manifest(any()) }
    }

    @Test
    fun `whole list reorders removes and returns the addon view`() {
        val response = controller.replaceSearch(listOf(input(second)))
        assertEquals(listOf(second.id), search.map { it.id })
        assertEquals(0, search.single().position)
        assertEquals(controller.listAddons(), response)
        verify(exactly = 0) { addons.manifest(any()) }
    }

    @Test
    fun `invalid row is indexed and persists nothing`() {
        every { addons.manifest(any()) } throws AddonFailure("secret must not escape", "KZ.ADDON_SEARCH_INCOMPATIBLE")
        val error =
            assertFailsWith<SettingsValidationFailure> {
                controller.replaceSearch(listOf(input(first), input(second).copy(manifestUrl = "https://bad")))
            }
        assertEquals("KZ.ADDONS_INVALID", error.code)
        assertEquals(mapOf("search.1.manifestUrl" to "KZ.ADDON_SEARCH_INCOMPATIBLE"), error.errors)
        assertEquals(listOf(first, second), search)
        verify(exactly = 0) { store.replaceSearchAddons(any()) }
    }

    @Test
    fun `all disabled unknown ids and duplicate ids are rejected without persistence`() {
        assertEquals(
            "KZ.ADDON_SEARCH_REQUIRED",
            assertFailsWith<SettingsValidationFailure> {
                controller.replaceSearch(listOf(input(first).copy(enabled = false)))
            }.errors["search"],
        )
        assertEquals(
            "KZ.INVALID_CONFIGURATION",
            assertFailsWith<SettingsValidationFailure> {
                controller.replaceSearch(listOf(input(first).copy(id = UUID.randomUUID())))
            }.errors["search.0.manifestUrl"],
        )
        assertEquals(
            "KZ.INVALID_CONFIGURATION",
            assertFailsWith<SettingsValidationFailure> {
                controller.replaceSearch(listOf(input(first), input(first)))
            }.errors["search.1.manifestUrl"],
        )
        verify(exactly = 0) { store.replaceSearchAddons(any()) }
    }

    @Test
    fun `metadata rename keeps revision while URL change bumps it and blank URL fails`() {
        controller.metadata(AddonInput(manifestUrl = "https://first", name = "Renamed"))
        assertEquals(7, metadata.revision)
        assertEquals("Renamed", metadata.name)
        verify(exactly = 0) { addons.manifest(any()) }
        every { addons.manifest("https://changed/manifest.json") } returns manifest
        controller.metadata(AddonInput(manifestUrl = "https://changed"))
        assertEquals(8, metadata.revision)
        assertEquals(first.id, metadata.id)
        assertEquals(
            "KZ.ADDON_INVALID_URL",
            assertFailsWith<SettingsValidationFailure> {
                controller.metadata(AddonInput(manifestUrl = " "))
            }.errors["metadata.manifestUrl"],
        )
    }

    @Test
    fun `settings expose the token and blank clears it`() {
        assertEquals("stored-token", (controller.settings()["jellyfin"] as Map<*, *>)["token"])
        controller.update(SettingsInput(jellyfin = JellyfinSettings(false, "https://jellyfin", "")))
        assertNull(settings.jellyfin.token)
    }

    @Test
    fun `enabled Jellyfin needs both fields and test never falls back to stored token`() {
        val error =
            assertFailsWith<SettingsValidationFailure> {
                controller.update(SettingsInput(jellyfin = JellyfinSettings(true, "", "")))
            }
        assertEquals(setOf("jellyfin.url", "jellyfin.token"), error.errors.keys)
        assertEquals("stored-token", settings.jellyfin.token)
        assertEquals(
            "KZ.JELLYFIN_TOKEN_REQUIRED",
            assertFailsWith<SettingsValidationFailure> {
                controller.testJellyfin(JellyfinSettings(true, "https://jellyfin", ""))
            }.errors["jellyfin.token"],
        )
        verify(exactly = 0) { jellyfin.test(any()) }
        val draft = JellyfinSettings(true, "https://draft", "draft-token")
        controller.testJellyfin(draft)
        verify { jellyfin.test(draft) }
    }
}
