package com.ynixt.dmdl.application.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("application.storage")
data class StorageProperties(
    val root: String = "./media",
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties::class)
class StoragePropertiesConfig
