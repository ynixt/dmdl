package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.AllocationProgress

interface AllocationProgressSource {
    fun find(gid: String): AllocationProgress?
}
