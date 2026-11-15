package com.ynixt.dmdl.domain.model

import java.util.UUID

data class ApplicationSettings(
    val username: String,
    val passwordHash: String,
    val locale: String = "en",
    val maxConcurrentDownloads: Int = 1,
    val organization: OrganizationSettings = OrganizationSettings(),
    val transfer: Aria2TransferOptions = Aria2TransferOptions(),
    val onboardingCompleted: Boolean = false,
    val configurationRevision: Long = 0,
    val setupSubmissionId: UUID? = null,
    val setupFingerprint: String? = null,
    val streamBadgeJson: String = StreamBadgeRules.defaultJson,
    val finalization: FinalizationChecks = FinalizationChecks(),
) {
    init {
        require(maxConcurrentDownloads in 1..32)
    }

    override fun toString(): String = "ApplicationSettings(username=$username)"
}
