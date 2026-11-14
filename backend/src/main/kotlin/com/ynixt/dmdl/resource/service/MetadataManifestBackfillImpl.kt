package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.service.MetadataManifestBackfill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class MetadataManifestBackfillImpl(
    private val configuration: ConfigurationStore,
    private val gateway: AddonGateway,
    private val clock: Clock = Clock.systemUTC(),
) : MetadataManifestBackfill {
    private class Attempt {
        var retryAt: Instant = Instant.MIN
        var completed: Boolean = false
    }

    private val attempts = ConcurrentHashMap<Pair<UUID, Long>, Attempt>()
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun current(): AddonConfiguration? {
        val source = configuration.metadata() ?: return null
        if (!needsBackfill(source)) return source
        val attempt = attempts.computeIfAbsent(source.id to source.revision) { Attempt() }
        synchronized(attempt) {
            val current = configuration.metadata() ?: return null
            if (current.id != source.id || current.revision != source.revision) return current
            if (!needsBackfill(current) || attempt.completed || clock.instant().isBefore(attempt.retryAt)) {
                return current
            }
            return try {
                val manifest = gateway.manifest(current.manifestUrl)
                if (configuration.replaceMetadataManifest(current.id, current.revision, manifest)) {
                    attempt.completed = true
                    current.copy(manifest = manifest)
                } else {
                    configuration.metadata()
                }
            } catch (_: Exception) {
                attempt.retryAt = clock.instant().plusSeconds(60)
                logger.warn(
                    "Metadata manifest backfill failed for addon {} revision {}; retrying after cooldown",
                    current.id,
                    current.revision,
                )
                current
            }
        }
    }

    private fun needsBackfill(source: AddonConfiguration): Boolean =
        source.manifest.catalogs.any { catalog -> catalog.extra == null || catalog.extra.any { it.options == null } }
}
