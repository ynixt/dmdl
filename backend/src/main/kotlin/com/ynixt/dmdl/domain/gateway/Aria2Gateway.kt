package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.Aria2TransferStatus
import com.ynixt.dmdl.domain.model.Aria2Version

interface Aria2Gateway {
    fun addUri(
        uri: String,
        gid: String,
        directory: String,
        options: Aria2TransferOptions,
    ): String

    fun findStatus(gid: String): Aria2TransferStatus?

    fun connectionSpeeds(gid: String): List<Long>

    fun replaceUris(
        gid: String,
        uri: String,
    )

    fun pause(gid: String)

    fun unpause(gid: String)

    fun changeOptions(
        gid: String,
        options: Aria2TransferOptions,
    )

    fun changeGlobalMaxConcurrentDownloads(
        maxConcurrentDownloads: Int,
        diskCacheMib: Int = 64,
    )

    fun sessionId(): String

    fun forceRemove(gid: String)

    fun removeDownloadResult(gid: String)

    fun version(): Aria2Version
}
