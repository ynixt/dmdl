package com.ynixt.dmdl.application.config

import com.ynixt.dmdl.domain.repository.ConfigurationStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager = configuration.authenticationManager

    @Bean fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean fun users(store: ConfigurationStore): UserDetailsService =
        UserDetailsService { username ->
            val account = store.settings()?.takeIf { it.username == username } ?: throw UsernameNotFoundException("Unknown account")
            User
                .withUsername(account.username)
                .password(account.passwordHash)
                .roles("ADMIN")
                .build()
        }

    @Bean fun cors(
        @Value("\${application.security.allowed-origins:http://localhost:4200}") origins: String,
    ): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration(
                "/api/**",
                CorsConfiguration().apply {
                    allowedOrigins = origins.split(',').map(String::trim)
                    allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    allowedHeaders = listOf("Content-Type", "Accept")
                    allowCredentials = true
                },
            )
        }

    @Bean fun filter(
        http: HttpSecurity,
        cors: CorsConfigurationSource,
        contexts: SecurityContextRepository,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(cors) }
            .securityContext { it.securityContextRepository(contexts) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/api/auth/login",
                        "/api/auth/logout",
                        "/api/auth/registration",
                        "/api/auth/register",
                        "/actuator/health/**",
                    ).permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
            }.exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType =
                        "application/json"
                    response.writer.write("{\"error\":\"UNAUTHORIZED\"}")
                }
            }
        return http.build()
    }
}
