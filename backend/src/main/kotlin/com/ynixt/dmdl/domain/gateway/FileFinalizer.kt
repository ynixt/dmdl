package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.FinalizationChecks
import com.ynixt.dmdl.domain.model.FinalizationProgress

interface FileFinalizer {
    /** Verifies and places a completed transfer; [onProgress] reports each step and its byte progress. */
    fun finalize(
        request: DownloadRequest,
        checks: FinalizationChecks = FinalizationChecks(),
        onProgress: (FinalizationProgress) -> Unit = {},
        saveCheckpoint: (DownloadRequest) -> Unit,
    ): DownloadRequest

    fun cleanupCompleted(request: DownloadRequest)

    fun cleanupCancelled(request: DownloadRequest)

    fun checkStorage(root: String)
}
