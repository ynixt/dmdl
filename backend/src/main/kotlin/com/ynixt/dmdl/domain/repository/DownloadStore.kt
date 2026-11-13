package com.ynixt.dmdl.domain.repository

import com.ynixt.dmdl.domain.model.DownloadEvent
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import java.util.UUID

interface DownloadStore {
    fun insert(request: DownloadRequest)

    fun find(id: UUID): DownloadRequest?

    fun history(visibleOnly: Boolean = false): List<DownloadRequest>

    fun removeFinishedFromHistory(id: UUID?): Int

    fun events(id: UUID): List<DownloadEvent>

    fun reserveNext(owner: UUID): DownloadRequest?

    fun startOnce(
        id: UUID,
        generation: Long,
    ): Boolean

    fun checkpoint(request: DownloadRequest): Boolean

    fun changeState(
        id: UUID,
        generation: Long,
        state: DownloadState,
    ): DownloadRequest?

    fun recordCancellationCleanup(
        id: UUID,
        generation: Long,
        error: String?,
    ): Boolean

    fun recoverOwnership(
        id: UUID,
        generation: Long,
    ): Boolean
}
