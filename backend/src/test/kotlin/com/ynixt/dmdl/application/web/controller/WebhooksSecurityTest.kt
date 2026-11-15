package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.config.SecurityConfig
import com.ynixt.dmdl.domain.model.RenderedWebhookRequest
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookHeader
import com.ynixt.dmdl.domain.model.WebhookListInvalid
import com.ynixt.dmdl.domain.model.WebhookResponse
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.service.WebhookService
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(WebhooksController::class)
@Import(SecurityConfig::class)
@EnableWebSecurity
class WebhooksSecurityTest {
    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var webhooks: WebhookService

    @MockitoBean lateinit var configuration: ConfigurationStore

    private val hook =
        Webhook(
            UUID.randomUUID(),
            "Home Assistant",
            "POST",
            "https://ha.local/api/webhook/secret-id",
            listOf(WebhookHeader("Authorization", "Bearer secret")),
            events = setOf(WebhookEvent.COMPLETED),
        )

    private fun session(): MockHttpSession {
        val session = MockHttpSession()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated("fixture", "hash", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
        return session
    }

    @Test
    fun `unauthenticated clients cannot read or change webhooks`() {
        mvc.perform(get("/api/webhooks")).andExpect(status().isUnauthorized)
        mvc
            .perform(put("/api/webhooks").contentType(MediaType.APPLICATION_JSON).content("[]"))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(
                post("/api/downloads/${UUID.randomUUID()}/webhooks/dispatch").contentType(MediaType.APPLICATION_JSON).content("{}"),
            ).andExpect(status().isUnauthorized)
        verify(webhooks, never()).list()
    }

    @Test
    fun `authenticated users read secrets with no store headers and public event keys`() {
        `when`(webhooks.list()).thenReturn(listOf(hook))
        mvc
            .perform(get("/api/webhooks").session(session()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$[0].url").value(hook.url))
            .andExpect(jsonPath("$[0].headers[0].value").value("Bearer secret"))
            .andExpect(jsonPath("$[0].events[0]").value("download.completed"))
    }

    @Test
    fun `saving reports field errors by index and unknown events`() {
        val body =
            """[{"name":"A","url":"https://a","events":["download.completed"]},{"name":"B","url":"https://b","events":["download.exploded"]}]"""
        mvc
            .perform(put("/api/webhooks").session(session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("KZ.WEBHOOKS_INVALID"))
            .andExpect(jsonPath("$.errors['webhooks.1.events']").value("KZ.WEBHOOK_EVENT_UNKNOWN"))
        verify(webhooks, never()).replace(anyList())

        `when`(webhooks.replace(anyList())).thenThrow(WebhookListInvalid(mapOf("webhooks.0.body" to "KZ.WEBHOOK_TEMPLATE_INVALID")))
        mvc
            .perform(
                put("/api/webhooks")
                    .session(session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""[{"name":"A","url":"https://a","manual":true}]"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors['webhooks.0.body']").value("KZ.WEBHOOK_TEMPLATE_INVALID"))
    }

    @Test
    fun `preview and test use the draft and report the result`() {
        `when`(webhooks.preview(any(Webhook::class.java) ?: hook, eq(null)))
            .thenReturn(RenderedWebhookRequest("POST", "https://draft/x", listOf(WebhookHeader("A", "1")), "body"))
        mvc
            .perform(
                post("/api/webhooks/preview")
                    .session(session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"webhook":{"name":"Draft","url":"https://draft/{{event}}"}}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value("https://draft/x"))
            .andExpect(jsonPath("$.body").value("body"))
        `when`(
            webhooks.test(any(Webhook::class.java) ?: hook, eq(WebhookEvent.FAILED)),
        ).thenReturn(WebhookResponse(404, "KZ.WEBHOOK_HTTP_STATUS"))
        mvc
            .perform(
                post("/api/webhooks/test")
                    .session(session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"webhook":{"name":"Draft","url":"https://draft"},"event":"download.failed"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.status").value(404))
        mvc
            .perform(
                post("/api/webhooks/test")
                    .session(session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"webhook":{"name":"Draft","url":"https://draft"},"event":"download.nope"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.event").value("KZ.WEBHOOK_EVENT_UNKNOWN"))
    }

    @Test
    fun `dispatch and resend map results to HTTP statuses`() {
        val download = UUID.randomUUID()
        val delivery = UUID.randomUUID()
        val expected =
            mapOf(
                WebhookActionResult.OK to 202,
                WebhookActionResult.NOT_FOUND to 404,
                WebhookActionResult.INELIGIBLE to 400,
                WebhookActionResult.CONFLICT to 409,
            )
        expected.forEach { (result, code) ->
            `when`(webhooks.dispatch(download, listOf(hook.id))).thenReturn(result)
            mvc
                .perform(
                    post("/api/downloads/$download/webhooks/dispatch")
                        .session(session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"webhookIds":["${hook.id}"]}"""),
                ).andExpect(status().`is`(code))
            `when`(webhooks.resend(download, delivery)).thenReturn(result)
            mvc
                .perform(post("/api/downloads/$download/deliveries/$delivery/resend").session(session()))
                .andExpect(status().`is`(code))
        }
    }
}
