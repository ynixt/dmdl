package com.ynixt.dmdl.domain.model

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Aria2TransferOptionsTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `new installations disable the per-connection minimum speed and wait 10 seconds between retries`() {
        val defaults = Aria2TransferOptions()
        assertFalse(defaults.lowestSpeedLimitEnabled)
        assertEquals(50, defaults.lowestSpeedLimitKib)
        assertEquals(10, defaults.retryWaitSeconds)
    }

    @Test
    fun `stored transfer JSON without the toggle reads as disabled and keeps its stored values`() {
        val stored =
            """
            {"split":8,"maxConnectionPerServer":8,"minSplitSizeMib":64,"maxTries":5,"retryWaitSeconds":0,
             "fileAllocation":"FALLOC","ipv6Enabled":true,"lowestSpeedLimitKib":50,"diskCacheMib":64,
             "timeoutSeconds":60,"maxFileNotFound":2,"maxConcurrentPerDebrid":1}
            """.trimIndent()

        val options = json.readValue(stored, Aria2TransferOptions::class.java)

        assertFalse(options.lowestSpeedLimitEnabled)
        assertEquals(50, options.lowestSpeedLimitKib)
        assertEquals(0, options.retryWaitSeconds)
    }

    @Test
    fun `toggle survives a JSON round trip`() {
        val options = Aria2TransferOptions(lowestSpeedLimitEnabled = true, lowestSpeedLimitKib = 120)
        val restored = json.readValue(json.writeValueAsString(options), Aria2TransferOptions::class.java)
        assertTrue(restored.lowestSpeedLimitEnabled)
        assertEquals(options, restored)
    }
}
