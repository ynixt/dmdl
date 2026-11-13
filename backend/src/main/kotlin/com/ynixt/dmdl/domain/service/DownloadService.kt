package com.ynixt.dmdl.domain.service

import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DownloadControl
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadWakeUp
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import java.util.UUID

interface DownloadService {
    fun history(): List<DownloadRequest>

    fun removeFinishedFromHistory(id: UUID? = null): Int

    fun find(id: UUID): DownloadRequest?

    fun submit(
        content: ContentSnapshot,
        release: ReleaseSnapshot,
        destinationId: UUID,
    ): DownloadRequest

    fun control(
        id: UUID,
        generation: Long,
        action: DownloadControl,
    ): DownloadRequest

    fun execute(message: DownloadWakeUp)

    fun reconcileStartup()

    fun drain()
}
