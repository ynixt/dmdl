package com.ynixt.dmdl.application.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties("application.kitsu")
data class KitsuProperties(
    val baseUrl: String = "https://kitsu.io/api/edge",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(8),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KitsuProperties::class)
class KitsuPropertiesConfig
