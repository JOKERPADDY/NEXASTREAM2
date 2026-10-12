package com.nexastream.app.providers

import android.util.Log
import com.google.gson.JsonParser
import java.net.URLDecoder

internal object CricfyM3UParser {
    private const val TAG = "CricfyM3UParser"
    private val attributePattern = Regex("""([A-Za-z0-9_-]+)=("(?:[^"\\]|\\.)*"|'[^']*'|[^,\s]+)""")

    fun parse(content: String): List<CricfyChannel> {
        val channels = mutableListOf<CricfyChannel>()
        var title = ""
        var logo = ""
        var group = ""
        var userAgent: String? = null
        var cookie: String? = null
        var referer: String? = null
        var licenseString: String? = null
        var headers = linkedMapOf<String, String>()

        fun addHeader(name: String, value: String) {
            val headerName = name.trim()
            when (headerName.lowercase()) {
                "user-agent", "http-user-agent" -> userAgent = value
                "cookie", "http-cookie" -> cookie = value
                "referer", "referrer", "http-referrer" -> referer = value
                else -> if (headerName.isNotBlank()) headers[headerName] = value
            }
        }

        fun reset() {
            title = ""
            logo = ""
            group = ""
            userAgent = null
            cookie = null
            referer = null
            licenseString = null
            headers = linkedMapOf()
        }

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = findUnquotedComma(line)
                    val attributesPart = if (comma == -1) line else line.substring(0, comma)
                    val attributes = attributePattern.findAll(attributesPart).associate { match ->
                        match.groupValues[1] to match.groupValues[2]
                            .removeSurrounding("\"")
                            .removeSurrounding("'")
                    }
                    title = if (comma >= 0) {
                        line.substring(comma + 1).trim().ifBlank { attributes["tvg-name"].orEmpty() }
                    } else {
                        attributes["tvg-name"].orEmpty().ifBlank { "Unknown Channel" }
                    }
                    logo = attributes["tvg-logo"].orEmpty()
                    group = attributes["group-title"].orEmpty()
                    attributes.forEach { (name, value) ->
                        if (name.startsWith("http-", ignoreCase = true)) addHeader(name, value)
                    }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    group = line.substringAfter(':').trim()
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    parseHeaderOption(line.substringAfter(':'))?.let { (name, value) ->
                        addHeader(name, value)
                    }
                }

                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    try {
                        JsonParser.parseString(line.substringAfter(':')).asJsonObject
                            .entrySet()
                            .forEach { (name, value) ->
                                if (value.isJsonPrimitive) addHeader(name, value.asString)
                            }
                    } catch (error: Exception) {
                        Log.w(TAG, "Ignoring malformed EXTHTTP headers", error)
                    }
                }

                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=", ignoreCase = true) -> {
                    licenseString = line.substringAfter('=').trim()
                }

                !line.startsWith('#') -> {
                    val streamUrl = line.substringBefore('|').trim()
                    if (streamUrl.startsWith("http://", true) ||
                        streamUrl.startsWith("https://", true)
                    ) {
                        line.substringAfter('|', "")
                            .split('&')
                            .filter(String::isNotBlank)
                            .forEach { parameter ->
                                val separator = parameter.indexOf('=')
                                if (separator > 0) {
                                    val name = decodeParameter(parameter.substring(0, separator))
                                    val value = decodeParameter(parameter.substring(separator + 1))
                                    addHeader(name, value)
                                }
                            }

                        if (title.isNotBlank()) {
                            channels += CricfyChannel(
                                title = title,
                                url = streamUrl,
                                logo = logo.ifBlank { null },
                                group = group.ifBlank { null },
                                userAgent = userAgent?.ifBlank { null },
                                cookie = cookie?.ifBlank { null },
                                referer = referer?.ifBlank { null },
                                licenseString = licenseString?.ifBlank { null },
                                headers = headers.toMap(),
                            )
                        }
                    }
                    reset()
                }
            }
        }

        return channels
    }

    private fun parseHeaderOption(option: String): Pair<String, String>? {
        val separator = option.indexOf('=')
        if (separator <= 0) return null
        return option.substring(0, separator).trim() to option.substring(separator + 1).trim()
    }

    private fun decodeParameter(value: String): String =
        try {
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Invalid URL-encoded playlist header", error)
            value
        }

    private fun findUnquotedComma(line: String): Int {
        var quote: Char? = null
        var escaped = false
        line.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' && quote != null -> escaped = true
                quote == null && (character == '"' || character == '\'') -> quote = character
                character == quote -> quote = null
                character == ',' && quote == null -> return index
            }
        }
        return -1
    }
}
