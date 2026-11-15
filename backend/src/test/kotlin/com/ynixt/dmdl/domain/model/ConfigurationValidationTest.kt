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
            defaults.copy(autoSaveIntervalSeconds = 601),
            defaults.copy(autoSaveIntervalSeconds = -1),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { validation.settings(settings.copy(transfer = invalid), storageCheck = {}) }
        }
        validation.settings(
            settings.copy(maxConcurrentDownloads = 2, transfer = defaults.copy(maxConcurrentPerDebrid = 2)),
            storageCheck = {},
        )
        listOf(0, 600).forEach { interval ->
            validation.settings(settings.copy(transfer = defaults.copy(autoSaveIntervalSeconds = interval)), storageCheck = {})
        }
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
        assertEquals("KZ.ADDON_MISSING_CATALOG_META", assertFailsWith<AddonFailure> { validation.addon(addon.manifestUrl, true) }.code)
    }

    private fun declaring(
        vararg names: String,
        catalogs: List<AddonCatalog> = emptyList(),
    ) = manifest.copy(resources = names.map { AddonResource(it, emptyList(), emptyList()) }, catalogs = catalogs)

    private fun roleCode(
        manifest: AddonManifest,
        metadata: Boolean,
    ): String? = runCatching { validation.requireRole(manifest, metadata) }.exceptionOrNull()?.let { (it as AddonFailure).code }

    @Test
    fun `metadata role requires catalog and meta and reports each missing combination`() {
        assertEquals(null, roleCode(declaring("catalog", "meta"), true))
        assertEquals(null, roleCode(declaring("catalog", "meta", "stream", "subtitles"), true))
        assertEquals("KZ.ADDON_MISSING_CATALOG", roleCode(declaring("meta"), true))
        assertEquals("KZ.ADDON_MISSING_META", roleCode(declaring("catalog"), true))
        assertEquals("KZ.ADDON_MISSING_CATALOG_META", roleCode(declaring(), true))
        assertEquals("KZ.ADDON_MISSING_CATALOG_META", roleCode(declaring("stream"), true))
    }

    @Test
    fun `search role requires stream regardless of other resources or declared types`() {
        assertEquals(null, roleCode(declaring("stream"), false))
        assertEquals(null, roleCode(manifest.copy(resources = listOf(AddonResource("stream", listOf("tv"), emptyList()))), false))
        assertEquals(null, roleCode(declaring("catalog", "meta", "stream"), false))
        assertEquals("KZ.ADDON_MISSING_STREAM", roleCode(declaring("catalog", "meta"), false))
        assertEquals("KZ.ADDON_MISSING_STREAM", roleCode(declaring(), false))
    }

    @Test
    fun `registration eligibility depends only on declared resources, not catalog extras`() {
        val url = "https://metadata.example/manifest.json"
        val genreSkip =
            AddonCatalog(
                "top",
                "movie",
                "Top",
                false,
                listOf(AddonCatalogExtra("genre", options = listOf("Drama")), AddonCatalogExtra("skip", options = emptyList())),
            )
        listOf(declaring("catalog", "meta", catalogs = listOf(genreSkip)), declaring("catalog", "meta")).forEach { candidate ->
            every { gateway.manifest(url) } returns candidate
            assertEquals(candidate, validation.addon(url, true).manifest)
        }
        every { gateway.manifest(url) } returns declaring("stream")
        val search = validation.addon(url, false)
        assertEquals(listOf("stream"), search.manifest.resources.map { it.name })
        every { gateway.manifest(url) } returns declaring("meta", catalogs = listOf(genreSkip.copy(searchable = true)))
        assertEquals("KZ.ADDON_MISSING_CATALOG", assertFailsWith<AddonFailure> { validation.addon(url, true) }.code)
    }
}
