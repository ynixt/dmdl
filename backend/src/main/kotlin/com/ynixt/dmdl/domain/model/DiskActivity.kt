package com.ynixt.dmdl.domain.model

/** Container-wide disk counters for the disk that holds a staging directory. */
data class DiskActivity(
    val availability: DiskActivityAvailability,
    /** Cumulative bytes this container wrote to that disk; null when unavailable. */
    val writtenBytes: Long? = null,
    /** Page-cache bytes of this container waiting to reach any disk; null when unavailable. */
    val pendingWriteBytes: Long? = null,
)
