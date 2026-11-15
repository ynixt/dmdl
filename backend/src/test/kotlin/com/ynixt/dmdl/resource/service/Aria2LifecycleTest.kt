package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.Aria2RestartInProgress
import com.ynixt.dmdl.domain.model.Aria2RestartState
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Aria2LifecycleTest {
    @TempDir lateinit var root: Path

    private val aria = mockk<Aria2Gateway>(relaxed = true)
    private val configuration = mockk<ConfigurationStore>()
    private val millis = AtomicLong(Instant.parse("2026-11-14T22:00:00Z").toEpochMilli())
    private val clock =
        object : Clock() {
            override fun instant(): Instant = Instant.ofEpochMilli(millis.get())

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this
        }
    private val deferred = mutableListOf<Runnable>()

    private val marker get() = root.resolve("run/aria2-restart-requested")
    private val options get() = root.resolve("aria2/startup-options.conf")

    private fun lifecycle(saved: Int = 300): Aria2Lifecycle {
        every { configuration.settings() } returns
            ApplicationSettings("fixture", "hash", transfer = Aria2TransferOptions(autoSaveIntervalSeconds = saved))
        return Aria2Lifecycle(
            aria,
            configuration,
            Aria2Properties(startupOptionsFile = options.toString(), restartMarkerFile = marker.toString()),
            clock,
            sleeper = { millis.addAndGet(it) },
            launcher = { deferred += it },
        )
    }

    @Test
    fun `restart writes the marker, shuts down cleanly and returns to idle when a new aria2 answers`() {
        val lifecycle = lifecycle()
        val sessions = ArrayDeque(listOf("old", "old", "new"))
        every { aria.sessionId() } answers { sessions.removeFirstOrNull() ?: "new" }
        every { aria.shutdown() } answers { assertTrue(Files.exists(marker), "marker exists before shutdown") }
        lifecycle.restart()
        assertTrue(lifecycle.restarting())
        assertEquals(Aria2RestartState.RESTARTING, lifecycle.status().restartState)
        assertNull(lifecycle.status().runningAutoSaveIntervalSeconds)
        deferred.single().run()
        assertEquals(Aria2RestartState.IDLE, lifecycle.status().restartState)
        assertEquals(1, lifecycle.restarts())
        assertEquals("auto-save-interval=300\n", options.readText())
        verify(exactly = 0) { aria.forceShutdown() }
    }

    @Test
    fun `a second restart while restarting is rejected`() {
        val lifecycle = lifecycle()
        lifecycle.restart()
        assertFailsWith<Aria2RestartInProgress> { lifecycle.restart() }
    }

    @Test
    fun `restart fails after the timeout, removes the marker and allows a new attempt`() {
        val lifecycle = lifecycle()
        every { aria.sessionId() } returns "old" andThenThrows IllegalStateException("refused")
        every { aria.shutdown() } throws IllegalStateException("no answer")
        lifecycle.restart()
        deferred.single().run()
        assertEquals(Aria2RestartState.FAILED, lifecycle.status().restartState)
        assertFalse(Files.exists(marker))
        verifyOrder {
            aria.shutdown()
            aria.forceShutdown()
        }
        lifecycle.restart()
        assertTrue(lifecycle.restarting())
    }

    @Test
    fun `restart is pending while aria2 runs with a different save interval`() {
        val lifecycle = lifecycle(saved = 300)
        every { aria.globalOption("auto-save-interval") } returns "60"
        val status = lifecycle.status()
        assertEquals(60, status.runningAutoSaveIntervalSeconds)
        assertEquals(300, status.savedAutoSaveIntervalSeconds)
        assertTrue(status.restartPending)
        every { aria.globalOption("auto-save-interval") } returns "300"
        assertFalse(lifecycle.status().restartPending)
        every { aria.globalOption(any()) } throws IllegalStateException("aria2 down")
        assertFalse(lifecycle.status().restartPending)
    }

    @Test
    fun `startup options are written atomically and only when they change`() {
        val lifecycle = lifecycle(saved = 120)
        lifecycle.syncStartupOptions()
        assertEquals("auto-save-interval=120\n", options.readText())
        assertFalse(Files.exists(options.resolveSibling("startup-options.conf.tmp")))
        val modified = Files.getLastModifiedTime(options)
        lifecycle.syncStartupOptions()
        assertEquals(modified, Files.getLastModifiedTime(options))
        every { configuration.settings() } returns ApplicationSettings("fixture", "hash")
        lifecycle.syncStartupOptions()
        assertEquals("auto-save-interval=60\n", options.readText())
    }
}
