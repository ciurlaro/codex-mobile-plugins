package io.github.ciurlaro.codexmobile.providers.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val MAX_TELEGRAM_FILE_BYTES = 100L * 1024 * 1024

enum class TelegramAuthPrompt { CODE, PASSWORD }
enum class TelegramDisconnectResult { CONFIRMED, INDETERMINATE }
enum class TelegramSource { ARCHIVE, LIVE, BOTH }
enum class TelegramMutationState { SUCCEEDED, FAILED, INDETERMINATE }

sealed interface TelegramAuthEvent {
    data class Prompt(val prompt: TelegramAuthPrompt) : TelegramAuthEvent
    data class Connected(val username: String?) : TelegramAuthEvent
    data class Failed(val message: String) : TelegramAuthEvent
}

data class TelegramStatus(val available: Boolean, val connected: Boolean, val username: String? = null)

data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String,
    val username: String?,
    val chatType: String,
    val isForum: Boolean,
    val isGroup: Boolean,
    val unreadCount: Int,
    val unreadMentionsCount: Int,
)

data class TelegramMessage(
    val channelId: Long,
    val peerTitle: String,
    val username: String?,
    val messageId: Long,
    val date: String,
    val fromId: Long?,
    val fromUsername: String?,
    val fromDisplayName: String?,
    val fromPeerType: String?,
    val fromIsBot: Boolean,
    val text: String,
    val urls: List<String>,
    val media: TelegramMedia?,
    val topicId: Long?,
    val source: String,
)

data class TelegramMedia(
    val type: String,
    val fileId: Int,
    val fileName: String?,
    val mimeType: String?,
    val size: Long?,
)

data class TelegramMessages(
    val source: String,
    val messages: List<TelegramMessage>,
    val hasMore: Boolean = false,
    val nextBeforeId: Long? = null,
)

data class TelegramContact(
    val id: Long,
    val username: String?,
    val displayName: String,
    val phoneNumber: String?,
    val isBot: Boolean,
)

data class TelegramMutationOutcome(val state: TelegramMutationState, val message: String)

data class TelegramTextSend(
    val callKey: String,
    val to: String,
    val message: String,
    val parseMode: String,
    val topic: Long?,
    val replyTo: Long?,
    val silent: Boolean,
    val disablePreview: Boolean,
)

sealed interface TelegramRequest {
    data class ListChats(val query: String?, val limit: Int) : TelegramRequest
    data class ListMessages(
        val chat: String,
        val limit: Int,
        val source: TelegramSource,
        val beforeId: Long?,
        val afterId: Long?,
    ) : TelegramRequest
    data class SearchMessages(
        val query: String,
        val chat: String?,
        val limit: Int,
        val source: TelegramSource,
        val after: String?,
        val before: String?,
    ) : TelegramRequest
    data class SearchContacts(val query: String, val limit: Int) : TelegramRequest
    data class DownloadMedia(val chat: String, val messageId: Long, val outputPath: String) : TelegramRequest
    data class SendText(val value: TelegramTextSend) : TelegramRequest
    data class SendFile(
        val callKey: String,
        val to: String,
        val path: String,
        val caption: String?,
        val parseMode: String,
        val topic: Long?,
        val replyTo: Long?,
        val silent: Boolean,
        val forceDocument: Boolean,
    ) : TelegramRequest
}

fun parseTelegramRequest(tool: String, args: JsonObject, callKey: String): TelegramRequest = when (tool) {
    "telegram_list_chats" -> {
        args.requireOnly("query", "limit")
        TelegramRequest.ListChats(args.stringOrNull("query"), args.int("limit", 20, 1, 50))
    }
    "telegram_list_messages" -> {
        args.requireOnly("chat", "limit", "source", "beforeId", "afterId")
        TelegramRequest.ListMessages(
            args.string("chat"), args.int("limit", 50, 1, 100), args.source(),
            args.longOrNull("beforeId"), args.longOrNull("afterId"),
        )
    }
    "telegram_search_messages" -> {
        args.requireOnly("query", "chat", "limit", "source", "after", "before")
        TelegramRequest.SearchMessages(
            args.string("query"), args.stringOrNull("chat"), args.int("limit", 50, 1, 100), args.source(),
            args.stringOrNull("after"), args.stringOrNull("before"),
        )
    }
    "telegram_search_contacts" -> {
        args.requireOnly("query", "limit")
        TelegramRequest.SearchContacts(args.string("query"), args.int("limit", 20, 1, 50))
    }
    "telegram_download_media" -> {
        args.requireOnly("chat", "messageId", "outputPath")
        TelegramRequest.DownloadMedia(args.string("chat"), args.long("messageId", 1), args.string("outputPath"))
    }
    "telegram_send_text" -> {
        args.requireOnly("to", "message", "parseMode", "topic", "replyTo", "silent", "disablePreview")
        TelegramRequest.SendText(
            TelegramTextSend(
                callKey, args.string("to"), args.string("message"), args.parseMode(),
                args.longOrNull("topic"), args.longOrNull("replyTo"),
                args.boolean("silent", false), args.boolean("disablePreview", false),
            ),
        )
    }
    "telegram_send_file" -> {
        args.requireOnly("to", "path", "caption", "parseMode", "topic", "replyTo", "silent", "forceDocument")
        TelegramRequest.SendFile(
            callKey, args.string("to"), args.string("path"), args.stringOrNull("caption"), args.parseMode(),
            args.longOrNull("topic"), args.longOrNull("replyTo"),
            args.boolean("silent", false), args.boolean("forceDocument", false),
        )
    }
    else -> error("Unknown Telegram tool")
}

fun telegramChatsJson(chats: List<TelegramChat>): String = buildJsonObject {
    put("returned", chats.size)
    put("chats", buildJsonArray { chats.forEach { add(it.json()) } })
}.toString()

fun TelegramMessages.jsonString(): String = buildJsonObject {
    put("source", source)
    put("returned", messages.size)
    put("hasMore", hasMore)
    put("messages", buildJsonArray { messages.forEach { add(it.json()) } })
    nextBeforeId?.let { put("nextBeforeId", it) }
}.toString()

fun telegramContactsJson(contacts: List<TelegramContact>): String = buildJsonObject {
    put("returned", contacts.size)
    put("contacts", buildJsonArray { contacts.forEach { add(it.json()) } })
}.toString()

data class TelegramMutationResult(val success: Boolean, val message: String)

fun TelegramMutationOutcome.result(operation: String): TelegramMutationResult = when (state) {
    TelegramMutationState.SUCCEEDED -> TelegramMutationResult(true, message)
    TelegramMutationState.FAILED -> TelegramMutationResult(false, message.ifBlank { "$operation failed" })
    TelegramMutationState.INDETERMINATE -> TelegramMutationResult(
        false,
        "$operation outcome is indeterminate; inspect Telegram before deciding what to do next.",
    )
}

private fun JsonObject.requireOnly(vararg allowed: String) {
    require(keys.all { it in allowed }) { "Unexpected tool argument" }
}

private fun JsonObject.string(name: String, default: String? = null): String =
    stringOrNull(name) ?: default ?: error("Missing $name")

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.int(name: String, default: Int, minimum: Int, maximum: Int): Int =
    (get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull ?: default).also {
        require(it in minimum..maximum) { "$name is out of range" }
    }

private fun JsonObject.long(name: String, minimum: Long): Long =
    (get(name)?.jsonPrimitive?.longOrNull ?: error("Missing $name")).also {
        require(it >= minimum) { "$name is out of range" }
    }

private fun JsonObject.longOrNull(name: String): Long? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull?.also {
        require(it > 0) { "$name is out of range" }
    }

private fun JsonObject.source(): TelegramSource = when (string("source", "both")) {
    "archive" -> TelegramSource.ARCHIVE
    "live" -> TelegramSource.LIVE
    "both" -> TelegramSource.BOTH
    else -> error("Unsupported Telegram source")
}

private fun JsonObject.parseMode(): String = string("parseMode", "none").also {
    require(it in setOf("none", "markdown", "html")) { "Unsupported Telegram parse mode" }
}

private fun TelegramChat.json() = buildJsonObject {
    put("id", id); put("type", type); put("title", title); nullable("username", username)
    put("chatType", chatType); put("isForum", isForum); put("isGroup", isGroup)
    put("unreadCount", unreadCount); put("unreadMentionsCount", unreadMentionsCount)
}

private fun TelegramMessage.json() = buildJsonObject {
    put("channelId", channelId); put("peerTitle", peerTitle); nullable("username", username)
    put("messageId", messageId); put("date", date); nullable("fromId", fromId)
    nullable("fromUsername", fromUsername); nullable("fromDisplayName", fromDisplayName)
    nullable("fromPeerType", fromPeerType); put("fromIsBot", fromIsBot); put("text", text)
    put("urls", JsonArray(urls.map(::JsonPrimitive))); put("media", media?.json() ?: JsonNull)
    nullable("topicId", topicId); put("source", source)
}

private fun TelegramMedia.json() = buildJsonObject {
    put("type", type); put("fileId", fileId); nullable("fileName", fileName)
    nullable("mimeType", mimeType); nullable("size", size)
}

private fun TelegramContact.json() = buildJsonObject {
    put("id", id); nullable("username", username); put("displayName", displayName)
    nullable("phoneNumber", phoneNumber); put("isBot", isBot)
}

private fun JsonObjectBuilder.nullable(name: String, value: String?) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)
private fun JsonObjectBuilder.nullable(name: String, value: Long?) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)
