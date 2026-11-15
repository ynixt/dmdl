package com.ynixt.dmdl.domain.model

/** Integrity checks run while a completed transfer is placed in its destination. */
data class FinalizationChecks(
    /** SHA-256 of the downloaded file. */
    val hashDownloadedFile: Boolean = true,
    /** Re-read a copy made to another filesystem and compare it with the downloaded file. */
    val verifyCopy: Boolean = false,
    /** Hash the final file and compare it with the downloaded file. */
    val verifyPlacedFile: Boolean = false,
)

enum class FinalizationStep {
    HASHING_DOWNLOAD,
    COPYING,
    VERIFYING_COPY,
    VERIFYING_PLACED_FILE,
    FLUSHING,
    PLACING,
}

/** What finalization is doing; byte counts are absent for steps that do not read or write the file. */
data class FinalizationProgress(
    val step: FinalizationStep,
    val processedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
)
