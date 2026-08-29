package com.example.baitblock

import android.util.Patterns

object UrlExtractor {

    fun extract(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)

        return if (matcher.find()) {
            matcher.group()
        } else {
            null
        }
    }
}