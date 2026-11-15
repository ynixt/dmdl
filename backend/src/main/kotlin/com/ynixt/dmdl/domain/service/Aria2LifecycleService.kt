package com.ynixt.dmdl.domain.service

import com.ynixt.dmdl.domain.model.Aria2Status

/** Restarting the embedded aria2 and keeping its startup-only options in sync with the saved settings. */
interface Aria2LifecycleService {
    fun status(): Aria2Status

    /** Starts a clean restart in the background; throws [com.ynixt.dmdl.domain.model.Aria2RestartInProgress] when one is running. */
    fun restart()

    fun restarting(): Boolean

    /** Grows on every completed restart so callers can re-apply global options aria2 lost. */
    fun restarts(): Long

    fun syncStartupOptions()
}
