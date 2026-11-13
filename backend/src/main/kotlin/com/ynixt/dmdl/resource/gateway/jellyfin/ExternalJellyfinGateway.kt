package com.ynixt.dmdl.resource.gateway.jellyfin

import com.ynixt.dmdl.domain.gateway.JellyfinGateway
import com.ynixt.dmdl.domain.model.JellyfinSettings
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class ExternalJellyfinGateway : JellyfinGateway {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun test(settings: JellyfinSettings) {
        send(settings, "GET", "/System/Info")
    }

    override fun refresh(settings: JellyfinSettings) {
        require(settings.enabled)
        send(settings, "POST", "/Library/Refresh")
    }

    private fun send(
        settings: JellyfinSettings,
        method: String,
        path: String,
    ) {
        val base = runCatching { URI(requireNotNull(settings.url)) }.getOrNull()
        require(
            base != null &&
                base.scheme in setOf("http", "https") &&
                !base.host.isNullOrBlank() &&
                base.userInfo == null &&
                base.query == null &&
                base.fragment == null,
        ) { "Enter a valid external Jellyfin HTTP(S) base URL" }
        require(!settings.token.isNullOrBlank()) { "Enter an existing Jellyfin API token" }
        val request =
            HttpRequest
                .newBuilder(
                    URI(base.toString().trimEnd('/') + path),
                ).timeout(Duration.ofSeconds(15))
                .header("X-Emby-Token", settings.token)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (
                _: Exception,
            ) {
                throw IllegalArgumentException("Jellyfin is unreachable; check the server address and connectivity")
            }
        require(response.statusCode() in 200..299) { "Jellyfin returned HTTP ${response.statusCode()}; check the server and API token" }
    }
}
