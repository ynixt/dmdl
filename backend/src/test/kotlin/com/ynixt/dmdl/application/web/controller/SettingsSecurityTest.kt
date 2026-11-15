package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.config.SecurityConfig
import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.gateway.AddonGateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(SettingsController::class)
@Import(SecurityConfig::class)
@EnableWebSecurity
class SettingsSecurityTest {
    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var store: ConfigurationStore

    @MockitoBean lateinit var addons: AddonGateway

    @MockitoBean lateinit var storage: StorageProperties

    @MockitoBean lateinit var finalizer: FileFinalizer

    @MockitoBean lateinit var backfill: com.ynixt.dmdl.domain.service.MetadataManifestBackfill

    @Test
    fun `authenticated addon URLs are exposed with no store headers`() {
        val session = MockHttpSession()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated("fixture", "hash", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
        `when`(store.settings()).thenReturn(ApplicationSettings("fixture", "hash"))
        `when`(storage.root).thenReturn("./media")
        val addon =
            AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://addon/secret/manifest.json",
                AddonManifest("fixture", "Fixture", emptyList(), emptyList(), emptyList()),
            )
        `when`(store.metadata()).thenReturn(addon)
        `when`(backfill.current()).thenReturn(addon)
        `when`(store.searchAddons()).thenReturn(listOf(addon))
        mvc
            .perform(get("/api/addons").session(session))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.metadata.manifestUrl").value(addon.manifestUrl))
            .andExpect(jsonPath("$.search[0].manifestUrl").value(addon.manifestUrl))
            .andExpect(jsonPath("$.metadata.urlConfigured").doesNotExist())
        mvc
            .perform(get("/api/settings").session(session))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.jellyfin").doesNotExist())
    }

    @Test
    fun `unauthenticated clients cannot read secrets`() {
        mvc.perform(get("/api/addons")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized)
    }
}
