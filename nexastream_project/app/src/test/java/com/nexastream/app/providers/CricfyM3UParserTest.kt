package com.nexastream.app.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CricfyM3UParserTest {

    @Test
    fun `parses channel metadata and Kodi headers`() {
        val channel = CricfyM3UParser.parse(
            """
                #EXTM3U
                #EXTINF:-1 tvg-name="Fallback" tvg-logo="https://img.test/logo.png" group-title="Sports, HD",Live, HD
                #EXTVLCOPT:http-user-agent=VLC-Agent
                #EXTVLCOPT:http-referrer=https://referrer.test/
                #EXTHTTP:{"cookie":"session=abc","X-Token":"value"}
                #KODIPROP:inputstream.adaptive.license_key=https://license.test/clearkey
                https://stream.test/live.m3u8|User-Agent=Pipe-Agent&Origin=https%3A%2F%2Forigin.test
            """.trimIndent(),
        ).single()

        assertEquals("Live, HD", channel.title)
        assertEquals("https://stream.test/live.m3u8", channel.url)
        assertEquals("https://img.test/logo.png", channel.logo)
        assertEquals("Sports, HD", channel.group)
        assertEquals("Pipe-Agent", channel.userAgent)
        assertEquals("session=abc", channel.cookie)
        assertEquals("https://referrer.test/", channel.referer)
        assertEquals("https://license.test/clearkey", channel.licenseString)
        assertEquals(
            mapOf("X-Token" to "value", "Origin" to "https://origin.test"),
            channel.headers,
        )
    }

    @Test
    fun `isolates playlist headers between streams and uses tvg name as fallback`() {
        val channels = CricfyM3UParser.parse(
            """
                #EXTINF:-1 tvg-name="First Channel" group-title="News",
                https://stream.test/one.m3u8|Cookie=one%3D1
                #EXTINF:-1,Second Channel
                https://stream.test/two.m3u8
            """.trimIndent(),
        )

        assertEquals("First Channel", channels[0].title)
        assertEquals("one=1", channels[0].cookie)
        assertNull(channels[1].cookie)
        assertEquals(emptyMap<String, String>(), channels[1].headers)
    }
}
