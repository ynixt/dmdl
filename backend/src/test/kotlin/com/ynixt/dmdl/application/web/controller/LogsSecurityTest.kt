package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.config.SecurityConfig
import com.ynixt.dmdl.domain.model.LogFilter
import com.ynixt.dmdl.domain.model.LogPage
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.service.ApplicationLogs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.MapSession
import org.springframework.session.MapSessionRepository
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertTrue

@WebMvcTest(LogsController::class)
@Import(SecurityConfig::class, LogsSecurityTest.Sessions::class)
@EnableWebSecurity
class LogsSecurityTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var controller: LogsController

    @Autowired lateinit var repository: MapSessionRepository

    @MockitoBean lateinit var store: ConfigurationStore

    @TestConfiguration
    class Sessions {
        @Bean fun sessions() = MapSessionRepository(ConcurrentHashMap())

        @Bean fun logs(): ApplicationLogs =
            object : ApplicationLogs {
                override fun history(
                    filter: LogFilter,
                    before: String?,
                    limit: Int,
                ) = LogPage(emptyList(), "cursor", null, false, emptyList())

                override fun replay(
                    filter: LogFilter,
                    cursor: String?,
                    limit: Int,
                ) = history(filter, null, limit)
            }
    }

    private fun session(): MockHttpSession {
        val session = MockHttpSession()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated("fixture", "hash", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
        repository.save(MapSession(session.id))
        return session
    }

    @Test
    fun `history and events require a session and diagnostics never cache`() {
        listOf("/api/logs", "/api/logs/events").forEach { path ->
            mvc
                .perform(
                    get(path),
                ).andExpect(
                    status().isUnauthorized,
                ).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        }
        mvc.perform(get("/api/logs").session(session())).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun `invalid filter values are rejected without reflecting input`() {
        mvc.perform(get("/api/logs").session(session()).param("source", "private-secret")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/logs").session(session()).param("levels", "private-secret")).andExpect(status().isBadRequest)
    }

    @Test
    fun `logout and expiry close existing subscriptions and expired clients cannot reconnect`() {
        val session = session()
        val result =
            mvc
                .perform(get("/api/logs/events").session(session()).param("cursor", "cursor"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
        val liveSession = result.request.getSession(false)!!
        repository.deleteById(liveSession.id)
        controller.checkSessions()
        result.getAsyncResult(3000)
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk)
        session.invalidate()
        mvc.perform(get("/api/logs/events")).andExpect(status().isUnauthorized)

        val expiring = session()
        val stream = mvc.perform(get("/api/logs/events").session(expiring)).andExpect(request().asyncStarted()).andReturn()
        val expiringId = stream.request.getSession(false)!!.id
        val persisted = repository.findById(expiringId)!!
        persisted.maxInactiveInterval = Duration.ofSeconds(1)
        persisted.lastAccessedTime = Instant.now().minusSeconds(2)
        repository.save(persisted)
        controller.checkSessions()
        stream.getAsyncResult(3000)
        assertTrue(repository.findById(expiringId) == null)
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk)
    }
}
