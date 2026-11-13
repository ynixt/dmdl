package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.DownloadRequest

interface FileFinalizer {
    fun finalize(
        request: DownloadRequest,
        saveCheckpoint: (DownloadRequest) -> Unit,
    ): DownloadRequest

    fun cleanupCompleted(request: DownloadRequest)

    fun cleanupCancelled(request: DownloadRequest)

    fun checkStorage(root: String)
}
