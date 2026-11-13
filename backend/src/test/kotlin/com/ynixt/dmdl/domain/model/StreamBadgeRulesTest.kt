package com.ynixt.dmdl.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamBadgeRulesTest {
    @Test
    fun `Fusion filters receive structured debrid availability indicators`() {
        val release =
            ReleaseSnapshot(
                "Release",
                ReleaseProvenance(java.util.UUID.randomUUID(), 1, "Source", 0, 0),
                ReleaseReference(ReleaseReferenceType.INFO_HASH, "a".repeat(40)),
            )
        val rules =
            StreamBadgeRules.parse(
                """{"filters":[
            {"name":"Lightning","pattern":"⚡"},
            {"name":"Pending","pattern":"⏳"},
            {"name":"Unknown","pattern":"CACHE_UNKNOWN"}
        ]}""",
            )
        assertEquals(listOf("Lightning"), rules.match(release.copy(cacheStatus = DebridCacheStatus.CACHED)).map { it.name })
        assertEquals(listOf("Pending"), rules.match(release.copy(cacheStatus = DebridCacheStatus.UNCACHED)).map { it.name })
        assertEquals(listOf("Unknown"), rules.match(release).map { it.name })
    }

    @Test
    fun `supplied default matches release metadata including exported codec boundaries`() {
        val names =
            StreamBadgeRules
                .parse(StreamBadgeRules.defaultJson)
                .match("The Last of Us (2023) S02E07 (2160p MAX WEB-DL H265 DV HDR DDP Atmos 5.1 English - HONE).mkv")
                .map { it.name }
        assertTrue(names.contains("H265 HEVC"), names.toString())
        assertTrue(names.contains("WebDL"), names.toString())
        assertTrue(names.size >= 7, names.toString())
    }

    @Test
    fun `uses active import with ordered unique enabled filters`() {
        val rules =
            StreamBadgeRules.parse(
                """{"imports":[
              {"isActive":false,"filters":[{"name":"Inactive","pattern":".*"}]},
              {"isActive":true,"groups":[{"id":"video","name":"Video"}],"filters":[
                {"name":"First","pattern":"(?i)hevc","imageURL":"https://example.com/hevc.png","groupId":"video"},
                {"name":"Duplicate","pattern":".*","imageURL":"https://example.com/hevc.png"},
                {"name":"Disabled","pattern":".*","isEnabled":false},
                {"name":"Second","pattern":"(?i)(?=.*hdr)(?!.*sdr)","tagColor":"#FF112233","tagStyle":"filled and bordered"}
              ]}]}""",
            )
        val badges = rules.match("HEVC\nHDR")
        assertEquals(listOf("First", "Second"), badges.map { it.name })
        assertEquals("#FF112233", badges.last().tagColor)
        assertEquals("filled and bordered", badges.last().tagStyle)
    }

    @Test
    fun `accepts direct filters first import fallback and empty configuration`() {
        assertEquals(
            "Direct",
            StreamBadgeRules
                .parse("""{"filters":[{"name":"Direct","pattern":".*"}],"groups":[]}""")
                .match("Release")
                .single()
                .name,
        )
        assertEquals(
            "First",
            StreamBadgeRules
                .parse("""{"imports":[{"isActive":false,"filters":[{"name":"First","pattern":".*"}]}]}""")
                .match("Release")
                .single()
                .name,
        )
        assertTrue(StreamBadgeRules.parse("""{"imports":[]}""").match("Release").isEmpty())
    }

    @Test
    fun `rejects invalid payload regex and image URLs with localized feedback`() {
        listOf(
            "invalid",
            "[]",
            "{}",
            """{"filters":{}}""",
            """{"filters":[{"name":"Broken","pattern":"["}]}""",
            """{"filters":[{"name":"Unsafe","pattern":".*","imageURL":"javascript:alert(1)"}]}""",
        ).forEach { value ->
            assertEquals("KZ.BADGE_JSON_INVALID", assertFailsWith<IllegalArgumentException> { StreamBadgeRules.parse(value) }.message)
        }
    }
}
