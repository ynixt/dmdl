package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.application.web.dto.StrictRequest
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class LoginInput(
    val username: String,
    val password: String,
) : StrictRequest() {
    override fun toString(): String = "LoginInput(username=$username)"
}

data class PasswordInput(
    val currentPassword: String,
    val newPassword: String,
) : StrictRequest() {
    override fun toString(): String = "PasswordInput([redacted])"
}

data class RegisterInput(
    val username: String,
    val password: String,
    val locale: String = "en-US",
) : StrictRequest() {
    override fun toString(): String = "RegisterInput(username=$username)"
}

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authentication: AuthenticationManager,
    private val contexts: SecurityContextRepository,
    private val store: ConfigurationStore,
    private val passwords: PasswordEncoder,
    private val storage: StorageProperties = StorageProperties(),
) {
    @GetMapping("/registration")
    fun registration(): Map<String, Boolean> = mapOf("registrationRequired" to (store.settings() == null))

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @RequestBody input: RegisterInput,
    ): Map<String, String> {
        if (store.settings() != null) throw ResponseStatusException(HttpStatus.CONFLICT, "An account is already registered")
        val username = input.username.trim()
        require(username.length in 3..64) { "Use a username between 3 and 64 characters" }
        require(input.password.length >= 6 && input.password.toByteArray(Charsets.UTF_8).size <= 72) {
            "Use a password with at least 6 characters and at most 72 UTF-8 bytes"
        }
        require(input.locale in setOf("en-US", "pt-BR")) { "Unsupported language" }
        val account =
            ApplicationSettings(
                username,
                requireNotNull(passwords.encode(input.password)),
                locale = input.locale,
                organization = OrganizationSettings(categories = OrganizationSettings.defaults(storage.root)),
            )
        if (!store.createAccount(account)) throw ResponseStatusException(HttpStatus.CONFLICT, "An account is already registered")
        return mapOf("status" to "REGISTERED")
    }

    @PostMapping("/login")
    fun login(
        @RequestBody input: LoginInput,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Map<String, Any> {
        val result =
            try {
                authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username, input.password))
            } catch (
                _: AuthenticationException,
            ) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
            }
        request.getSession(false)?.invalidate()
        request.getSession(true)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = result }
        SecurityContextHolder.setContext(context)
        contexts.saveContext(context, request, response)
        return session(result)
    }

    @GetMapping("/session")
    fun session(authentication: Authentication): Map<String, Any> =
        mapOf(
            "status" to "AUTHENTICATED",
            "username" to authentication.name,
            "locale" to (store.settings()?.locale ?: "en"),
            "onboardingCompleted" to (store.settings()?.onboardingCompleted == true),
        )

    @PostMapping("/logout")
    fun logout(
        authentication: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        SecurityContextLogoutHandler().logout(request, response, authentication)
    }

    @PutMapping("/password")
    fun password(
        @RequestBody input: PasswordInput,
    ) {
        val current = requireNotNull(store.settings())
        require(passwords.matches(input.currentPassword, current.passwordHash)) { "Current password is incorrect" }
        require(input.newPassword.length in 6..72) { "Use a password between 6 and 72 characters" }
        store.saveSettings(current.copy(passwordHash = requireNotNull(passwords.encode(input.newPassword))))
    }
}
