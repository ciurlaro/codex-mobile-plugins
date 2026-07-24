package io.github.ciurlaro.codexmobile.providers.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TelegramTool(
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutation: Boolean = false,
)

const val TELEGRAM_PLUGIN_ID = "telegram@codex-mobile"
const val TELEGRAM_API_ID_SECRET = "api_id"
const val TELEGRAM_API_HASH_SECRET = "api_hash"

val telegramTools = listOf(
    TelegramTool(TELEGRAM_PLUGIN_ID, "telegram_list_chats", "List bounded Telegram chats.", chatsSchema()),
    TelegramTool(TELEGRAM_PLUGIN_ID, "telegram_list_messages", "List bounded Telegram messages for one chat.", messagesSchema()),
    TelegramTool(TELEGRAM_PLUGIN_ID, "telegram_search_messages", "Search bounded Telegram messages.", searchSchema()),
    TelegramTool(TELEGRAM_PLUGIN_ID, "telegram_search_contacts", "Search bounded Telegram contacts.", contactsSchema()),
    TelegramTool(TELEGRAM_PLUGIN_ID, "telegram_download_media", "Download one Telegram message attachment into the workspace.", downloadSchema(), mutation = true),
    TelegramTool(TELEGRAM_PLUGIN_ID, "telegram_send_text", "Send one Telegram text message with one application submission.", sendTextSchema(), mutation = true),
    TelegramTool(TELEGRAM_PLUGIN_ID, "telegram_send_file", "Send one workspace file through Telegram with one application submission.", sendFileSchema(), mutation = true),
)

private fun chatsSchema() = objectSchema(linkedMapOf(
    "query" to stringSchema(256), "limit" to integerSchema(1, 50),
))
private fun messagesSchema() = objectSchema(linkedMapOf(
    "chat" to stringSchema(256), "limit" to integerSchema(1, 100),
    "source" to enumSchema("archive", "live", "both"),
    "beforeId" to integerSchema(1, Long.MAX_VALUE), "afterId" to integerSchema(1, Long.MAX_VALUE),
), listOf("chat"))
private fun searchSchema() = objectSchema(linkedMapOf(
    "query" to stringSchema(1_000), "chat" to stringSchema(256), "limit" to integerSchema(1, 100),
    "source" to enumSchema("archive", "live", "both"),
    "after" to stringSchema(64), "before" to stringSchema(64),
), listOf("query"))
private fun contactsSchema() = objectSchema(linkedMapOf(
    "query" to stringSchema(256), "limit" to integerSchema(1, 50),
), listOf("query"))
private fun downloadSchema() = objectSchema(linkedMapOf(
    "chat" to stringSchema(256), "messageId" to integerSchema(1, Long.MAX_VALUE),
    "outputPath" to stringSchema(4_096),
), listOf("chat", "messageId", "outputPath"))
private fun sendTextSchema() = objectSchema(linkedMapOf(
    "to" to stringSchema(256), "message" to stringSchema(4_096),
    "parseMode" to enumSchema("none", "markdown", "html"),
    "topic" to integerSchema(1, Long.MAX_VALUE), "replyTo" to integerSchema(1, Long.MAX_VALUE),
    "silent" to booleanSchema(), "disablePreview" to booleanSchema(),
), listOf("to", "message"))
private fun sendFileSchema() = objectSchema(linkedMapOf(
    "to" to stringSchema(256), "path" to stringSchema(4_096), "caption" to stringSchema(1_024),
    "parseMode" to enumSchema("none", "markdown", "html"),
    "topic" to integerSchema(1, Long.MAX_VALUE), "replyTo" to integerSchema(1, Long.MAX_VALUE),
    "silent" to booleanSchema(), "forceDocument" to booleanSchema(),
), listOf("to", "path"))

private fun objectSchema(properties: LinkedHashMap<String, JsonObject>, required: List<String> = emptyList()) =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
private fun stringSchema(maxLength: Int) = buildJsonObject { put("type", "string"); put("maxLength", maxLength) }
private fun integerSchema(minimum: Long, maximum: Long) = buildJsonObject {
    put("type", "integer"); put("minimum", minimum); put("maximum", maximum)
}
private fun booleanSchema() = buildJsonObject { put("type", "boolean") }
private fun enumSchema(vararg values: String) = buildJsonObject {
    put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
}
