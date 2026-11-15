package com.ynixt.dmdl.domain.model

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadProgressJsonTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `progress stored before per-connection speeds were dropped is still readable`() {
        val stored =
            """
            {"completedBytes":42615963648,"totalBytes":86548666187,"speedBytesPerSecond":25783641,"allocation":null,
             "connections":8,"connectionSpeedsBytesPerSecond":[3032763,3179311,3134551,3181830,3113115,3204004,3249397,3185539]}
            """.trimIndent()

        val progress = json.readValue(stored, DownloadProgress::class.java)

        assertEquals(42615963648, progress.completedBytes)
        assertEquals(8, progress.connections)
        assertNull(progress.phase)
        assertNull(progress.averageBytesPerSecond)
    }
}
