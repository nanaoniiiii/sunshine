package com.alkaidlab.sunshine.service

import com.google.gson.JsonParser

/** No reflective generic TypeToken: works with the minified release APK. */
object ConfigRequest {
    fun parse(json: String): Map<String, String> {
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "Configuration must be an object" }
        return root.asJsonObject.entrySet().associate { (key, value) ->
            require(value.isJsonPrimitive) { "Configuration values must be scalar" }
            key to value.asString
        }
    }
}
