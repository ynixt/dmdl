package com.ynixt.dmdl.resource.gateway.aria2

import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component("aria2")
class Aria2HealthIndicator(
    private val aria2Gateway: Aria2Gateway,
) : HealthIndicator {
    override fun health(): Health =
        runCatching { aria2Gateway.version() }
            .fold(
                onSuccess = { version -> Health.up().withDetail("version", version.version).build() },
                onFailure = { error -> Health.down().withDetail("error", error.javaClass.simpleName).build() },
            )
}
