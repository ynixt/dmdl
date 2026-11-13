package com.ynixt.dmdl.domain.model

import com.ynixt.dmdl.domain.enum.Aria2FileAllocation
import java.security.MessageDigest
import java.util.UUID

enum class Aria2TransferState {
    ACTIVE,
    WAITING,
    PAUSED,
    COMPLETE,
    ERROR,
    REMOVED,
    UNKNOWN,
}

data class Aria2TransferOptions(
    val split: Int = 8,
    val maxConnectionPerServer: Int = 8,
    val minSplitSizeMib: Int = 64,
    val maxTries: Int = 5,
    val retryWaitSeconds: Int = 0,
    val fileAllocation: Aria2FileAllocation = Aria2FileAllocation.FALLOC,
    val ipv6Enabled: Boolean = true,
    val lowestSpeedLimitKib: Int = 50,
    val diskCacheMib: Int = 64,
    val timeoutSeconds: Int = 60,
    val maxFileNotFound: Int = 2,
    val maxConcurrentPerDebrid: Int = 1,
)

data class Aria2TransferFile(
    val path: String,
    val length: Long,
)

data class AllocationProgress(
    val allocatedBytes: Long,
    val totalBytes: Long,
    val secondsRemaining: Long? = null,
)

data class Aria2TransferStatus(
    val gid: String,
    val state: Aria2TransferState,
    val totalLength: Long,
    val completedLength: Long,
    val downloadSpeed: Long,
    val errorCode: String?,
    val errorMessage: String?,
    val directory: String,
    val files: List<Aria2TransferFile>,
    val connections: Int? = null,
)

data class Aria2Version(
    val version: String,
    val enabledFeatures: List<String>,
)

object Aria2Gid {
    private val zero = Regex("0{16}")

    fun from(requestId: UUID): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(requestId.toString().toByteArray(Charsets.UTF_8))
        val gid = digest.take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return if (zero.matches(gid)) "0000000000000001" else gid
    }
}
