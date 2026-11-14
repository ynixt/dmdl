package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.model.AddonCatalog
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataManifestBackfillTest {
    @Test
    fun `backfills manifests stored with extras before option definitions were retained`() {
        current.set(
            original.copy(
                manifest =
                    original.manifest.copy(
                        catalogs =
                            listOf(
                                AddonCatalog(
                                    "popular",
                                    "movie",
                                    "Popular",
                                    false,
                                    listOf(
                                        com.ynixt.dmdl.domain.model
                                            .AddonCatalogExtra("genre", true),
                                    ),
                                ),
                            ),
                    ),
            ),
        )
        assertEquals(updated, service.current()?.manifest)
        assertEquals(updated, service.current()?.manifest)
        verify(exactly = 1) { gateway.manifest(original.manifestUrl) }
    }

    private val store = mockk<ConfigurationStore>()
    private val gateway = mockk<AddonGateway>()
    private val clock = mockk<Clock>()
    private var now = Instant.parse("2026-11-14T12:00:00Z")
    private val original =
        AddonConfiguration(
            UUID.randomUUID(),
            7,
            "https://example/manifest.json",
            AddonManifest("meta", "Meta", emptyList(), emptyList(), listOf(AddonCatalog("popular", "movie", "Popular", true))),
        )
    private val updated = original.manifest.copy(catalogs = original.manifest.catalogs.map { it.copy(extra = emptyList()) })
    private val current = AtomicReference(original)
    private val service = MetadataManifestBackfillImpl(store, gateway, clock)

    init {
        every { store.metadata() } answers { current.get() }
        every { clock.instant() } answers { now }
        every { gateway.manifest(original.manifestUrl) } returns updated
        every { store.replaceMetadataManifest(original.id, 7, updated) } answers {
            current.set(original.copy(manifest = updated))
            true
        }
    }

    @Test
    fun `backfills once and keeps identity and revision`() {
        assertEquals(original.copy(manifest = updated), service.current())
        assertEquals(current.get(), service.current())
        verify(exactly = 1) { gateway.manifest(any()) }
    }

    @Test
    fun `failure leaves stored JSON untouched and retries after one minute`() {
        every { gateway.manifest(any()) } throws AddonFailure("Unavailable")
        assertEquals(original, service.current())
        assertEquals(original, service.current())
        verify(exactly = 1) { gateway.manifest(any()) }
        verify(exactly = 0) { store.replaceMetadataManifest(any(), any(), any()) }
        now = now.plusSeconds(60)
        every { gateway.manifest(any()) } returns updated
        assertEquals(updated, service.current()?.manifest)
        verify(exactly = 2) { gateway.manifest(any()) }
    }

    @Test
    fun `concurrent readers share one manifest fetch`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { gateway.manifest(any()) } answers {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            updated
        }
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val readers = (1..8).map { executor.submit<AddonConfiguration?> { service.current() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            readers.forEach { assertEquals(updated, it.get(5, TimeUnit.SECONDS)?.manifest) }
        }
        verify(exactly = 1) { gateway.manifest(any()) }
    }

    @Test
    fun `a configuration changed during fetch is returned without overwriting it`() {
        val replacement = original.copy(revision = 8)
        every { store.replaceMetadataManifest(any(), any(), any()) } answers {
            current.set(replacement)
            false
        }
        assertEquals(replacement, service.current())
    }
}
