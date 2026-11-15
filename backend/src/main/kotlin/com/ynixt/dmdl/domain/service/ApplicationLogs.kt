package com.ynixt.dmdl.domain.service

import com.ynixt.dmdl.domain.model.LogFilter
import com.ynixt.dmdl.domain.model.LogPage

interface ApplicationLogs {
    fun history(
        filter: LogFilter,
        before: String? = null,
        limit: Int = 200,
    ): LogPage

    fun replay(
        filter: LogFilter,
        cursor: String?,
        limit: Int = 200,
    ): LogPage
}
