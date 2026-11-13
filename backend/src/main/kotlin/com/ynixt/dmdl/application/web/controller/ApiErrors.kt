package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.model.AddonFailure
import com.ynixt.dmdl.domain.model.DownloadFailure
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(IllegalArgumentException::class, AddonFailure::class, DownloadFailure::class)
    fun invalid(error: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "error" to ((error as? AddonFailure)?.code ?: error.message ?: "Invalid request"),
            ),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidJson(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "error" to "Invalid request fields; submit one movie or one episode using the current API",
            ),
        )
}
