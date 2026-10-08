package com.tanasi.retrofit_jsoup.converter

import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

class JsoupConverterFactory private constructor() : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        return if (type == Document::class.java) {
            Converter<ResponseBody, Document> { body ->
                Jsoup.parse(body.string())
            }
        } else {
            null
        }
    }

    companion object {
        fun create(): JsoupConverterFactory = JsoupConverterFactory()
    }
}
