package com.ynixt.dmdl.domain.model

import com.ynixt.dmdl.domain.gateway.AddonGateway
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationValidationTest {
    @Test
    fun `transfer bounds include provider concurrency and daemon resource limits`() {
        val defaults = Aria2TransferOptions()
        val organization =
            OrganizationSettings(
                categories =
                    OrganizationSettings.defaults().mapValues { (_, category) ->
                        category.copy(destinations = emptyList(), defaultDestinationId = null)
                    },
            )
        val settings = ApplicationSettings("test", "hash", organization = organization)
        validation.settings(settings, storageCheck = {})
        listOf(
            defaults.copy(maxConnectionPerServer = 17),
            defaults.copy(timeoutSeconds = 0),
            defaults.copy(lowestSpeedLimitEnabled = true, lowestSpeedLimitKib = -1),
            defaults.copy(diskCacheMib = 4097),
            defaults.copy(maxFileNotFound = -1),
            defaults.copy(maxConcurrentPerDebrid = 2),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { validation.settings(settings.copy(transfer = invalid), storageCheck = {}) }
        }
        validation.settings(
            settings.copy(maxConcurrentDownloads = 2, transfer = defaults.copy(maxConcurrentPerDebrid = 2)),
            storageCheck = {},
        )
    }

    @Test
    fun `minimum connection speed is range checked only when enabled`() {
        val organization =
            OrganizationSettings(
                categories =
                    OrganizationSettings.defaults().mapValues { (_, category) ->
                        category.copy(destinations = emptyList(), defaultDestinationId = null)
                    },
            )
        val settings = ApplicationSettings("test", "hash", organization = organization)
        val defaults = Aria2TransferOptions()

        listOf(0, 1048577).forEach { kib ->
            assertFailsWith<IllegalArgumentException> {
                validation.settings(
                    settings.copy(transfer = defaults.copy(lowestSpeedLimitEnabled = true, lowestSpeedLimitKib = kib)),
                    storageCheck = {},
                )
            }
        }
        listOf(
            defaults.copy(lowestSpeedLimitEnabled = false, lowestSpeedLimitKib = 0),
            defaults.copy(lowestSpeedLimitEnabled = false, lowestSpeedLimitKib = -1),
            defaults.copy(lowestSpeedLimitEnabled = true, lowestSpeedLimitKib = 80),
            defaults.copy(lowestSpeedLimitEnabled = true, lowestSpeedLimitKib = 1),
            defaults.copy(lowestSpeedLimitEnabled = true, lowestSpeedLimitKib = 1048576),
        ).forEach { valid -> validation.settings(settings.copy(transfer = valid), storageCheck = {}) }
    }

    private val gateway = mockk<AddonGateway>()
    private val validation = ConfigurationValidation(gateway, mockk())
    private val manifest =
        AddonManifest(
            "com.aiostreams.fixture",
            "AIOStreams",
            listOf("movie", "series", "anime"),
            listOf(AddonResource("stream", listOf("movie", "series", "anime"), listOf("tt", "tmdb:", "tvdb:"))),
            emptyList(),
        )

    @Test
    fun `normalizes configuration and installation links while preserving raw path and query`() {
        val expected = "https://addon.example/stremio/user/a%2Fb/manifest.json?token=a%2Bb"
        listOf(
            " https://addon.example/stremio/user/a%2Fb?token=a%2Bb ",
            "https://addon.example/stremio/user/a%2Fb/?token=a%2Bb",
            "stremio://addon.example/stremio/user/a%2Fb/manifest.json?token=a%2Bb",
            expected,
        ).forEach { assertEquals(expected, normalizeAddonManifestUrl(it)) }
    }

    @Test
    fun `rejects malformed links with safe error codes`() {
        listOf("", "relative/path", "ftp://addon.example", "https://user:secret@addon.example", "https://addon.example/#secret").forEach {
            assertEquals("KZ.ADDON_INVALID_URL", assertFailsWith<AddonFailure> { normalizeAddonManifestUrl(it) }.code)
        }
    }

    @Test
    fun `uses custom display name and keeps the manifest supplied name`() {
        every { gateway.manifest("https://addon.example/config/manifest.json") } returns manifest
        val addon = validation.addon("https://addon.example/config", false, name = " My AIOStreams ")
        assertEquals("My AIOStreams", addon.name)
        assertEquals("AIOStreams", addon.manifest.name)
        assertEquals("https://addon.example/config/manifest.json", addon.manifestUrl)
        assertEquals("AIOStreams", validation.addon(addon.manifestUrl, false, name = " ").name)
        assertEquals("KZ.ADDON_METADATA_INCOMPATIBLE", assertFailsWith<AddonFailure> { validation.addon(addon.manifestUrl, true) }.code)
    }
}
