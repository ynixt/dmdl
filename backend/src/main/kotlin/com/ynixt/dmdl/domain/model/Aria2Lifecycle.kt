package com.ynixt.dmdl.domain.model

import java.time.Instant

enum class Aria2RestartState {
    IDLE,
    RESTARTING,
    FAILED,
}

data class Aria2Status(
    val restartState: Aria2RestartState,
    val restartStartedAt: Instant?,
    /** `auto-save-interval` aria2 is running with; null while aria2 does not answer. */
    val runningAutoSaveIntervalSeconds: Int?,
    val savedAutoSaveIntervalSeconds: Int,
    val restartPending: Boolean,
)

class Aria2RestartInProgress : RuntimeException("aria2 is already restarting")
