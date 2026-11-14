package com.ynixt.dmdl.resource.persistence

import com.ynixt.dmdl.application.config.SqliteDatabaseConfig
import com.ynixt.dmdl.application.config.TransactionConfig
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.resource.persistence.service.JpaConfigurationStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SqliteDatabaseConfig::class, TransactionConfig::class, JpaConfigurationStore::class, JpaConfigurationStoreTest.JsonConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaConfigurationStoreTest {
    @Autowired lateinit var store: JpaConfigurationStore

    @TestConfiguration
    class JsonConfig {
        @Bean fun json(): ObjectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    }

    @Test
    fun `replace creates reorders removes and preserves explicitly supplied revisions`() {
        assertTrue(store.createAccount(ApplicationSettings("fixture", "hash")))
        val manifest = AddonManifest("fixture", "Fixture", emptyList(), emptyList(), emptyList())
        val first = AddonConfiguration(UUID.randomUUID(), 7, "https://first/manifest.json", manifest)
        val second = first.copy(id = UUID.randomUUID(), manifestUrl = "https://second/manifest.json", revision = 3)
        val removed = first.copy(id = UUID.randomUUID(), manifestUrl = "https://removed/manifest.json")
        store.replaceSearchAddons(listOf(first, second, removed))
        assertEquals(listOf(0, 1, 2), store.searchAddons().map { it.position })
        val created = first.copy(id = UUID.randomUUID(), revision = 1, manifestUrl = "https://new/manifest.json")
        store.replaceSearchAddons(listOf(second.copy(enabled = false), created, first.copy(name = "Renamed")))
        val saved = store.searchAddons()
        assertEquals(listOf(second.id, created.id, first.id), saved.map { it.id })
        assertEquals(listOf(3L, 1L, 7L), saved.map { it.revision })
        assertEquals(listOf(0, 1, 2), saved.map { it.position })
        assertEquals(false, saved.first().enabled)
        store.saveMetadata(first)
        store.saveMetadata(first.copy(name = "Renamed metadata"))
        assertEquals(7L, store.metadata()?.revision)
        store.replaceSearchAddons(emptyList())
        assertTrue(store.searchAddons().isEmpty())
        val revision = store.settings()!!.configurationRevision
        val updatedManifest = manifest.copy(name = "Backfilled")
        assertTrue(store.replaceMetadataManifest(first.id, 7, updatedManifest))
        assertEquals(updatedManifest, store.metadata()?.manifest)
        assertEquals(7L, store.metadata()?.revision)
        assertEquals(revision, store.settings()?.configurationRevision)
        assertEquals(false, store.replaceMetadataManifest(UUID.randomUUID(), 7, manifest))
        assertEquals(false, store.replaceMetadataManifest(first.id, 6, manifest))
        assertEquals(updatedManifest, store.metadata()?.manifest)
        assertEquals(revision, store.settings()?.configurationRevision)
    }

    companion object {
        private val database = Files.createTempDirectory("settings-tabs-").resolve("configuration.db")

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("application.database.path") { database.toString() }
        }
    }
}
