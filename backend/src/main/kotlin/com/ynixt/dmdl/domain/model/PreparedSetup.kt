package com.ynixt.dmdl.domain.model

import java.util.UUID

data class PreparedSetup(
    val settings: ApplicationSettings,
    val metadata: AddonConfiguration,
    val search: List<AddonConfiguration>,
    val expectedRevision: Long,
    val submissionId: UUID,
    val fingerprint: String,
) {
    override fun toString(): String = "PreparedSetup(submissionId=$submissionId)"
}

class ConfigurationConflict(
    message: String,
) : RuntimeException(message)
