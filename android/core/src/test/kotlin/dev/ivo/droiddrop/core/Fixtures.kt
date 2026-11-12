package dev.ivo.droiddrop.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

object Fixtures {
    private val dir = File(System.getProperty("droiddrop.fixtures") ?: error("droiddrop.fixtures system property not set"))

    fun load(name: String): JsonObject = Json.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject
}

fun JsonObject.list(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it.jsonObject }
fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toLong()
fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

fun String.hexToBytes(): ByteArray = HexFormat.of().parseHex(this)
fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)

/** Lowercase hex SHA-256 of [data]. */
fun sha(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()
fun sha(text: String): String = sha(text.toByteArray())
