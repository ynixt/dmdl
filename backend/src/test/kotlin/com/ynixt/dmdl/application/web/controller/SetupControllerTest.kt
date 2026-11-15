package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.AddonResource
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupControllerTest {
    @Test
    fun `badge validation remains in the search draft and reports invalid regex`() {
        val draft = SetupDraft(SettingsInput(streamBadgeJson = """{"filters":[{"name":"Broken","pattern":"["}]}"""))
        val result = controller.validate(ValidateSetupInput("search", draft))
        assertEquals("KZ.BADGE_JSON_INVALID", result.errors["search.streamBadgeJson"])
        verify(exactly = 0) { store.saveSettings(any()) }
    }

    private val store = mockk<ConfigurationStore>()
    private val addons = mockk<AddonGateway>()
    private val controller: SetupController

    init {
        every { store.settings() } returns ApplicationSettings("fixture", "hash")
        controller =
            SetupController(
                store,
                addons,
                mockk(),
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .addModule(
                        tools.jackson.module.kotlin.KotlinModule
                            .Builder()
                            .build(),
                    ).build(),
            )
    }

    @Test
    fun `validates a configured addon URL without persisting the draft`() {
        every { addons.manifest("https://torrentio.strem.fun/torbox=draft-token/manifest.json") } returns
            AddonManifest(
                "torrentio",
                "Torrentio",
                listOf("movie"),
                listOf(AddonResource("stream", listOf("movie"), emptyList())),
                emptyList(),
            )
        val draft =
            SetupDraft(
                SettingsInput(),
                search = listOf(SetupSearchInput(manifestUrl = "https://torrentio.strem.fun/torbox=draft-token/manifest.json")),
            )
        assertTrue(controller.validate(ValidateSetupInput("search", draft)).valid)
        verify(exactly = 0) { store.replaceSearchAddons(any()) }
        verify(exactly = 0) { store.completeSetup(any()) }
    }

    @Test
    fun `addon exceptions return safe field errors`() {
        val draft = SetupDraft(SettingsInput())
        every { addons.manifest(any()) } throws IllegalArgumentException("https://source/secret/manifest.json")
        val failed = controller.validate(ValidateSetupInput("metadata", draft.copy(metadataUrl = "https://source/secret/manifest.json")))
        assertFalse(failed.valid)
        assertFalse(failed.toString().contains("secret"))
    }

    @Test
    fun `search configuration links resolve manifests and return actionable safe errors`() {
        every { addons.manifest("https://source/stremio/secret/manifest.json") } throws
            AddonFailure("Do not expose https://source/secret", "KZ.ADDON_NOT_FOUND")
        val draft =
            SetupDraft(
                SettingsInput(),
                search = listOf(SetupSearchInput(manifestUrl = "https://source/stremio/secret", name = "My AIOStreams")),
            )
        val result = controller.validate(ValidateSetupInput("search", draft))
        assertEquals("KZ.ADDON_NOT_FOUND", result.errors["search.0.manifestUrl"])
        assertFalse(result.toString().contains("secret"))
        verify(exactly = 0) { store.replaceSearchAddons(any()) }
    }

    private fun declaring(vararg names: String) =
        AddonManifest("fixture", "Fixture", listOf("movie"), names.map { AddonResource(it, emptyList(), emptyList()) }, emptyList())

    private val emptyOrganization =
        OrganizationSettings(
            categories =
                OrganizationSettings.defaults().mapValues { (_, category) ->
                    category.copy(destinations = emptyList(), defaultDestinationId = null)
                },
        )

    private val completeDraft =
        SetupDraft(
            SettingsInput(organization = emptyOrganization),
            metadataUrl = "https://metadata.example",
            search =
                listOf(
                    SetupSearchInput(manifestUrl = "https://first.example"),
                    SetupSearchInput(manifestUrl = "https://second.example", enabled = false),
                ),
        )

    @Test
    fun `metadata and search steps report missing resources beside their URL fields`() {
        every { addons.manifest("https://metadata.example/manifest.json") } returns declaring("catalog")
        every { addons.manifest("https://first.example/manifest.json") } returns declaring("stream")
        every { addons.manifest("https://second.example/manifest.json") } returns declaring("catalog", "meta")
        assertEquals(
            mapOf("metadata.manifestUrl" to "KZ.ADDON_MISSING_META"),
            controller.validate(ValidateSetupInput("metadata", completeDraft)).errors,
        )
        assertEquals(
            mapOf("search.1.manifestUrl" to "KZ.ADDON_MISSING_STREAM"),
            controller.validate(ValidateSetupInput("search", completeDraft)).errors,
        )
        verify(exactly = 0) { store.completeSetup(any()) }
    }

    @Test
    fun `completion refetches manifests and blocks when one became incompatible after step validation`() {
        every { store.completeSetup(any()) } just Runs
        every { addons.manifest("https://first.example/manifest.json") } returns declaring("stream")
        every { addons.manifest("https://second.example/manifest.json") } returns declaring("stream")
        every { addons.manifest("https://metadata.example/manifest.json") } returnsMany
            listOf(declaring("catalog", "meta"), declaring("catalog"), declaring("catalog", "meta"))
        assertTrue(controller.validate(ValidateSetupInput("metadata", completeDraft)).valid)
        val revision = store.settings()!!.configurationRevision

        val failed = controller.complete(CompleteSetupInput(completeDraft, revision, UUID.randomUUID()))
        assertEquals(400, failed.statusCode.value())
        assertEquals(
            mapOf("error" to "KZ.SETUP_INVALID", "errors" to mapOf("metadata.manifestUrl" to "KZ.ADDON_MISSING_META")),
            failed.body,
        )
        verify(exactly = 0) { store.completeSetup(any()) }

        assertEquals(200, controller.complete(CompleteSetupInput(completeDraft, revision, UUID.randomUUID())).statusCode.value())
        verify(exactly = 1) { store.completeSetup(any()) }
        verify(exactly = 3) { addons.manifest("https://metadata.example/manifest.json") }
    }
}
