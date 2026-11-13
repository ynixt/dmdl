package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.MetadataDetails
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.ReleaseGroup
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.domain.model.SourceOutcome
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.domain.service.DiscoveryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadProviderOwnershipTest {
    @TempDir lateinit var root: Path

    @Test
    fun `accepts an addon-authorized AllDebrid link using the server-authorized result service`() {
        val configuration = mockk<ConfigurationStore>()
        val store = mockk<DownloadStore>(relaxed = true)
        val discovery = mockk<DiscoveryService>()
        val defaults = OrganizationSettings.defaults(root.toString())
        val originalCategory = defaults.getValue(MediaCategory.MOVIE)
        val category = originalCategory.copy(destinations = originalCategory.destinations.map { it.copy(path = root.toString()) })
        val organization = OrganizationSettings(categories = defaults + (MediaCategory.MOVIE to category))
        val settings = ApplicationSettings("fixture", "hash", organization = organization)
        val identity = ContentIdentity(UUID.randomUUID(), 1, "movie", "tt4633694")
        val content = ContentSnapshot(identity, MediaCategory.MOVIE, "Movie")
        val source =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://aio.example/manifest.json",
                AddonManifest("aio", "AIOStreams", emptyList(), emptyList(), emptyList()),
            )
        val release =
            ReleaseSnapshot(
                "AllDebrid release",
                ReleaseProvenance(source.id, source.revision, source.name, 0, 0, DebridProvider.ALL_DEBRID),
                ReleaseReference(ReleaseReferenceType.PROVIDER_HTTP, "https://aio.example/playback/opaque/file.mkv"),
            )
        every { configuration.settings() } returns settings
        every { configuration.searchAddons() } returns listOf(source)
        every { discovery.details(any()) } returns MetadataDetails(identity, "Movie")
        every { discovery.releases(any()) } returns listOf(ReleaseGroup(source.id, source.name, 0, SourceOutcome.SUCCESS, listOf(release)))
        every { store.history() } returns emptyList()
        every { store.reserveNext(any()) } returns null
        every { store.find(any()) } returns null
        val saved = mutableListOf<DownloadRequest>()
        every { store.insert(capture(saved)) } returns Unit
        val service =
            DownloadServiceImpl(
                store,
                configuration,
                discovery,
                mockk<Aria2Gateway>(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                StorageProperties(),
                Aria2Properties(),
            )
        val destinationId = requireNotNull(category.defaultDestinationId)
        service.submit(content, release, destinationId)
        assertEquals(DebridProvider.ALL_DEBRID, saved.last().provider)
        service.submit(content, release.copy(provenance = release.provenance.copy(boundProvider = null)), destinationId)
        verify(exactly = 2) { store.insert(any()) }
        val unidentified = release.copy(provenance = release.provenance.copy(boundProvider = null))
        every { discovery.releases(any()) } returns
            listOf(ReleaseGroup(source.id, source.name, 0, SourceOutcome.SUCCESS, listOf(unidentified)))
        service.submit(content, unidentified, destinationId)
        assertEquals(null, saved.last().provider)
        val torrent = release.copy(reference = ReleaseReference(ReleaseReferenceType.INFO_HASH, "a".repeat(40)))
        every { discovery.releases(any()) } returns listOf(ReleaseGroup(source.id, source.name, 0, SourceOutcome.SUCCESS, listOf(torrent)))
        assertFailsWith<IllegalArgumentException> { service.submit(content, torrent, destinationId) }
        verify(exactly = 3) { store.insert(any()) }
    }
}
