package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.AddonResource
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
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
        controller = SetupController(store, addons, mockk(), mockk(), mockk())
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
        verify(exactly = 0) { store.saveSearchAddon(any()) }
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
        verify(exactly = 0) { store.saveSearchAddon(any()) }
    }
}
