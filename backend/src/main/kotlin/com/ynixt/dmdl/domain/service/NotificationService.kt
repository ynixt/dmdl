package com.ynixt.dmdl.domain.service

import java.util.UUID

interface NotificationService {
    fun dispatchPending()

    fun retry(id: UUID)
}
