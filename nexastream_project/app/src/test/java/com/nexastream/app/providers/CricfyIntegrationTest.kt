package com.nexastream.app.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CricfyIntegrationTest {

    @Test
    fun testParseProviderListValidJson() {
        val json = """
            [
                {
                    "title": "JIOTV+",
                    "image": "https://example.com/logo.png",
                    "catLink": "https://example.com/playlist.m3u"
                },
                {
                    "title": "Invalid Category",
                    "catLink": "null"
                }
            ]
        """.trimIndent()

        val providers = CricfyProviderClient.parseProviderList(json)
        assertNotNull(providers)
        assertEquals(1, providers.size)
        assertEquals("JIOTV+", providers[0].title)
        assertEquals("https://example.com/playlist.m3u", providers[0].categoryUrl)
    }
}
