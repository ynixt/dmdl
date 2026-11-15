package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.model.Aria2RestartInProgress
import com.ynixt.dmdl.domain.model.Aria2Status
import com.ynixt.dmdl.domain.service.Aria2LifecycleService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/aria2")
class Aria2Controller(
    private val lifecycle: Aria2LifecycleService,
) {
    @GetMapping("/status")
    fun status(): Aria2Status = lifecycle.status()

    /** Starts a clean aria2 restart; poll [status] until it leaves RESTARTING. */
    @PostMapping("/restart")
    fun restart(): ResponseEntity<Aria2Status> =
        try {
            lifecycle.restart()
            ResponseEntity.status(HttpStatus.ACCEPTED).body(lifecycle.status())
        } catch (_: Aria2RestartInProgress) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(lifecycle.status())
        }
}
