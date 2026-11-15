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
    /** aria2 `--conf-path` with startup-only options; written by the backend, read by start-aria2.sh. */
    val startupOptionsFile: String = "",
    /** Marker that tells the start-aria2.sh supervisor to start aria2 again after it exits. */
    val restartMarkerFile: String = "",
    val restartTimeout: Duration = Duration.ofSeconds(60),
)

@Configuration
@EnableConfigurationProperties(Aria2Properties::class)
class Aria2PropertiesConfig
