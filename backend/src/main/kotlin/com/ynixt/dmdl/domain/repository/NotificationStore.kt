package com.ynixt.dmdl.domain.repository

import com.ynixt.dmdl.domain.model.JellyfinNotification
import com.ynixt.dmdl.domain.model.NotificationState
import java.util.UUID

interface NotificationStore {
    fun find(id: UUID): JellyfinNotification?

    fun pending(): List<UUID>

    fun retry(id: UUID): Boolean

    fun claim(id: UUID): Int?

    fun finish(
        id: UUID,
        attempt: Int,
        state: NotificationState,
        error: String?,
    )
}
