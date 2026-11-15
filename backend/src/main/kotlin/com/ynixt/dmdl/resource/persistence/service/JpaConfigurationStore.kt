package com.ynixt.dmdl.resource.persistence.service

import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.ConfigurationConflict
import com.ynixt.dmdl.domain.model.FinalizationChecks
import com.ynixt.dmdl.domain.model.JellyfinSettings
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.PreparedSetup
import com.ynixt.dmdl.domain.model.StreamBadgeRules
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.resource.persistence.entity.ApplicationSettingsEntity
import com.ynixt.dmdl.resource.persistence.entity.MetadataAddonEntity
import com.ynixt.dmdl.resource.persistence.entity.SearchAddonEntity
import com.ynixt.dmdl.resource.persistence.repository.ApplicationSettingsRepository
import com.ynixt.dmdl.resource.persistence.repository.MetadataAddonRepository
import com.ynixt.dmdl.resource.persistence.repository.SearchAddonRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Repository
class JpaConfigurationStore(
    private val settingsRepository: ApplicationSettingsRepository,
    private val metadataRepository: MetadataAddonRepository,
    private val addons: SearchAddonRepository,
    private val json: ObjectMapper,
    transaction: TransactionTemplate,
) : ConfigurationStore {
    private val writes = BoundedSqliteWrites(transaction)

    override fun settings(): ApplicationSettings? =
        settingsRepository.findById(1).orElse(null)?.let {
            ApplicationSettings(
                it.username,
                it.passwordHash,
                it.locale,
                it.maxConcurrentDownloads,
                json.readValue(it.organizationJson, OrganizationSettings::class.java),
                json.readValue(it.transferOptionsJson, Aria2TransferOptions::class.java),
                it.onboardingCompleted,
                JellyfinSettings(it.jellyfinEnabled, it.jellyfinUrl, it.jellyfinToken),
                it.configurationRevision,
                it.setupSubmissionId?.let(UUID::fromString),
                it.setupFingerprint,
                it.streamBadgeJson ?: StreamBadgeRules.defaultJson,
                // Rows saved before the finalization checks existed read the defaults.
                it.finalizationJson?.let { value -> json.readValue(value, FinalizationChecks::class.java) } ?: FinalizationChecks(),
            )
        }

    override fun createAccount(settings: ApplicationSettings): Boolean =
        writes.run {
            if (settingsRepository.existsById(1)) return@run false
            saveSettingsRow(settings)
            settingsRepository.flush()
            true
        }

    override fun saveSettings(settings: ApplicationSettings) {
        writes.run { saveSettingsRow(settings) }
    }

    override fun completeSetup(setup: PreparedSetup) {
        writes.run {
            val current = requireNotNull(settings())
            if (current.setupSubmissionId == setup.submissionId) {
                if (current.setupFingerprint !=
                    setup.fingerprint
                ) {
                    throw ConfigurationConflict("Submission identifier was already used for different configuration")
                }
                return@run
            }
            if (current.onboardingCompleted || current.configurationRevision != setup.expectedRevision) {
                throw ConfigurationConflict("Configuration changed. Reload setup before submitting again")
            }
            saveMetadata(setup.metadata.copy(revision = (metadata()?.revision ?: 0) + 1))
            replaceSearchAddons(setup.search.map { it.copy(revision = 1) })
            saveSettingsRow(
                setup.settings.copy(
                    onboardingCompleted = true,
                    setupSubmissionId = setup.submissionId,
                    setupFingerprint = setup.fingerprint,
                ),
            )
            settingsRepository.flush()
        }
    }

    private fun saveSettingsRow(settings: ApplicationSettings) {
        val now = Instant.now().toString()
        val created = settingsRepository.findById(1).orElse(null)?.createdAt ?: now
        settingsRepository.save(
            ApplicationSettingsEntity(
                1,
                settings.username,
                settings.passwordHash,
                settings.locale,
                settings.maxConcurrentDownloads,
                settings.organization.revision,
                json.writeValueAsString(settings.organization),
                json.writeValueAsString(settings.transfer),
                settings.onboardingCompleted,
                settings.jellyfin.enabled,
                settings.jellyfin.url,
                settings.jellyfin.token,
                created,
                now,
                (settingsRepository.findById(1).orElse(null)?.configurationRevision ?: -1) + 1,
                settings.setupSubmissionId?.toString(),
                settings.setupFingerprint,
                settings.streamBadgeJson,
                json.writeValueAsString(settings.finalization),
            ),
        )
    }

    override fun metadata(): AddonConfiguration? =
        metadataRepository.findById(1).orElse(null)?.let {
            AddonConfiguration(
                UUID.fromString(it.id),
                it.revision,
                it.manifestUrl,
                json.readValue(it.manifestJson, AddonManifest::class.java),
                name = it.name,
            )
        }

    override fun saveMetadata(configuration: AddonConfiguration) {
        writes.run {
            metadataRepository.save(
                MetadataAddonEntity(
                    1,
                    configuration.id.toString(),
                    configuration.revision,
                    configuration.name,
                    configuration.manifestUrl,
                    json.writeValueAsString(configuration.manifest),
                ),
            )
            settings()?.let(::saveSettingsRow)
        }
    }

    override fun replaceMetadataManifest(
        id: UUID,
        revision: Long,
        manifest: AddonManifest,
    ): Boolean = writes.run { metadataRepository.replaceManifest(id.toString(), revision, json.writeValueAsString(manifest)) == 1 }

    override fun searchAddons(): List<AddonConfiguration> =
        addons.findAllByOrderByDisplayOrderAscIdAsc().map {
            AddonConfiguration(
                UUID.fromString(it.id),
                it.revision,
                it.manifestUrl,
                json.readValue(it.manifestJson, AddonManifest::class.java),
                it.enabled,
                it.displayOrder,
                it.name,
            )
        }

    override fun replaceSearchAddons(configurations: List<AddonConfiguration>) {
        writes.run {
            val ids = configurations.map { it.id.toString() }.toSet()
            addons.findAll().filter { it.id !in ids }.forEach(addons::delete)
            configurations.forEachIndexed { position, configuration ->
                addons.save(
                    SearchAddonEntity(
                        configuration.id.toString(),
                        configuration.revision,
                        configuration.name,
                        configuration.manifestUrl,
                        json.writeValueAsString(configuration.manifest),
                        configuration.enabled,
                        position,
                    ),
                )
            }
            settings()?.let(::saveSettingsRow)
        }
    }
}
