package com.ynixt.dmdl.application.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties(prefix = "application.aria2")
data class Aria2Properties(
    val rpcUrl: String = "http://127.0.0.1:16843/jsonrpc",
    val rpcSecret: String = "change-me",
    val pollInterval: Duration = Duration.ofSeconds(1),
    val progressPublicationInterval: Duration = Duration.ofSeconds(1),
    val applicationStagingRoot: String = "",
    val daemonStagingRoot: String = "/downloads/.dmdl",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(10),
)

@Configuration
@EnableConfigurationProperties(Aria2Properties::class)
class Aria2PropertiesConfig
