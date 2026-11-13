package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.web.dto.StrictRequest
import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.gateway.JellyfinGateway
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.ConfigurationConflict
import com.ynixt.dmdl.domain.model.ConfigurationValidation
import com.ynixt.dmdl.domain.model.JellyfinSettings
import com.ynixt.dmdl.domain.model.PreparedSetup
import com.ynixt.dmdl.domain.model.StreamBadgeRules
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.UUID

data class SetupSearchInput(
    val manifestUrl: String = "",
    val enabled: Boolean = true,
    val name: String = "",
) : StrictRequest() {
    override fun toString(): String = "SetupSearchInput([redacted])"
}

data class SetupDraft(
    val settings: SettingsInput,
    val metadataUrl: String = "",
    val search: List<SetupSearchInput> = emptyList(),
    val metadataName: String = "",
) : StrictRequest() {
    override fun toString(): String = "SetupDraft([redacted])"
}

data class ValidateSetupInput(
    val step: String,
    val draft: SetupDraft,
) : StrictRequest() {
    override fun toString(): String = "ValidateSetupInput(step=$step)"
}

data class CompleteSetupInput(
    val draft: SetupDraft,
    val expectedRevision: Long,
    val submissionId: UUID,
) : StrictRequest() {
    override fun toString(): String = "CompleteSetupInput(submissionId=$submissionId)"
}

data class SetupValidationResult(
    val errors: Map<String, String>,
) {
    val valid: Boolean get() = errors.isEmpty()
}

@RestController
@RequestMapping("/api/setup")
class SetupController(
    private val store: ConfigurationStore,
    addons: AddonGateway,
    private val jellyfin: JellyfinGateway,
    private val finalizer: FileFinalizer,
    private val json: ObjectMapper,
) {
    private val validation = ConfigurationValidation(addons, finalizer)

    @PostMapping("/validate")
    fun validate(
        @RequestBody input: ValidateSetupInput,
    ): SetupValidationResult {
        require(input.step in setOf("metadata", "search", "storage", "jellyfin")) { "Unknown setup step" }
        return prepare(input.draft, input.step).first
    }

    @PostMapping("/complete")
    fun complete(
        @RequestBody input: CompleteSetupInput,
    ): ResponseEntity<*> {
        val fingerprint =
            MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        val current = requireNotNull(store.settings())
        if (current.setupSubmissionId == input.submissionId) {
            if (current.setupFingerprint != fingerprint) return conflict()
            return ResponseEntity.ok(mapOf("completed" to true))
        }
        if (current.onboardingCompleted || current.configurationRevision != input.expectedRevision) return conflict()
        val (result, prepared) = prepare(input.draft, "complete")
        if (!result.valid) return ResponseEntity.badRequest().body(mapOf("error" to "KZ.SETUP_INVALID", "errors" to result.errors))
        requireNotNull(prepared)
        store.completeSetup(
            PreparedSetup(
                prepared.settings,
                requireNotNull(prepared.metadata),
                prepared.search,
                input.expectedRevision,
                input.submissionId,
                fingerprint,
            ),
        )
        return ResponseEntity.ok(mapOf("completed" to true))
    }

    @ExceptionHandler(ConfigurationConflict::class)
    fun conflict(): ResponseEntity<*> = ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "KZ.CONFIGURATION_CHANGED"))

    private data class ValidatedDraft(
        val settings: ApplicationSettings,
        val metadata: AddonConfiguration?,
        val search: List<AddonConfiguration>,
    )

    private fun prepare(
        draft: SetupDraft,
        step: String,
    ): Pair<SetupValidationResult, ValidatedDraft?> {
        val errors = linkedMapOf<String, String>()

        fun check(
            field: String,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (error: Exception) {
                errors[field] =
                    when {
                        error is AddonFailure -> error.code
                        field == "search.streamBadgeJson" -> "KZ.BADGE_JSON_INVALID"
                        else -> "KZ.INVALID_CONFIGURATION"
                    }
            }
        }
        val old = requireNotNull(store.settings())
        val input = draft.settings
        var settings: ApplicationSettings? = null
        var metadata: AddonConfiguration? = null
        val search = mutableListOf<AddonConfiguration>()
        val all = step == "complete"
        if (all || step == "metadata") {
            check("metadata.manifestUrl") {
                metadata = validation.addon(draft.metadataUrl, true, name = draft.metadataName)
            }
        }
        if (all || step == "search") {
            check("search.streamBadgeJson") {
                StreamBadgeRules.parse(input.streamBadgeJson ?: old.streamBadgeJson)
            }
            check("search") {
                if (draft.search.none { it.enabled }) throw AddonFailure("Enable a search addon", "KZ.ADDON_SEARCH_REQUIRED")
            }
            draft.search.forEachIndexed { index, source ->
                check("search.$index.manifestUrl") {
                    search.add(validation.addon(source.manifestUrl, false, index, source.enabled, source.name))
                }
            }
        }
        if (all || step == "storage") {
            check("storage") {
                settings =
                    old.copy(
                        locale = input.locale ?: old.locale,
                        maxConcurrentDownloads = input.maxConcurrentDownloads ?: old.maxConcurrentDownloads,
                        organization = requireNotNull(input.organization).copy(revision = old.organization.revision + 1),
                        transfer = input.transfer ?: old.transfer,
                        jellyfin = input.jellyfin ?: JellyfinSettings(),
                        streamBadgeJson = input.streamBadgeJson ?: old.streamBadgeJson,
                    )
                val value = requireNotNull(settings)
                value.organization.categories.forEach { (category, config) ->
                    check(
                        "storage.$category.defaultDestinationId",
                    ) {
                        require(
                            if (config.destinations.isEmpty()) {
                                config.defaultDestinationId == null
                            } else {
                                config.destinations.any {
                                    it.id ==
                                        config.defaultDestinationId
                                }
                            },
                        )
                    }
                    config.destinations.forEachIndexed { index, destination ->
                        check("storage.$category.destinations.$index.path") {
                            require(
                                java.nio.file.Path
                                    .of(destination.path)
                                    .isAbsolute && destination.name.isNotBlank(),
                            )
                            finalizer.checkStorage(destination.path)
                        }
                    }
                }
                validation.settings(value.copy(jellyfin = JellyfinSettings()), storageCheck = {})
            }
        }
        if (all || step == "jellyfin") {
            check("jellyfin") {
                val value = input.jellyfin ?: JellyfinSettings()
                validation.jellyfin(value)
                if (value.enabled) jellyfin.test(value)
            }
        }
        return SetupValidationResult(errors) to settings?.let { ValidatedDraft(it, metadata, search) }
    }
}
