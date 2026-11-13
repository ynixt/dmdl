package com.ynixt.dmdl.domain.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Aria2GidTest {
    @Test
    fun `gid is stable non-zero lowercase hexadecimal with aria2 length`() {
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val gid = Aria2Gid.from(id)

        assertEquals(gid, Aria2Gid.from(id))
        assertTrue(gid.matches(Regex("[0-9a-f]{16}")))
        assertNotEquals("0000000000000000", gid)
    }

    @Test
    fun `representative full UUID inputs produce distinct gids`() {
        val first = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val second = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")

        assertNotEquals(Aria2Gid.from(first), Aria2Gid.from(second))
    }
}
