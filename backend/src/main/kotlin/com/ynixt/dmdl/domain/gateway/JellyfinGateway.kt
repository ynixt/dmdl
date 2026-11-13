package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.JellyfinSettings

interface JellyfinGateway {
    fun test(settings: JellyfinSettings)

    fun refresh(settings: JellyfinSettings)
}
