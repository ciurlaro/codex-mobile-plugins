package io.github.ciurlaro.codexmobile.providers.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class TelegramMcpBackend : McpBackend {
    private val hostWorkspace = Path.of(System.getenv("CODEX_MCP_HOST_WORKSPACE") ?: "/workspace").toAbsolutePath().normalize()
    private val workspace = Path.of(System.getenv("CODEX_MCP_WORKSPACE") ?: "/workspace").toRealPath()
    private val telegram = TelegramIntegration(telegramStateDirectory())

    override suspend fun execute(tool: String, arguments: JsonObject): McpResult = when (tool) {
        "telegram_list_chats" -> listChats(arguments)
        "telegram_list_messages" -> listMessages(arguments)
        "telegram_search_messages" -> searchMessages(arguments)
        "telegram_search_contacts" -> searchContacts(arguments)
        "telegram_download_media" -> downloadMedia(arguments)
        "telegram_send_text" -> sendText(arguments)
        "telegram_send_file" -> sendFile(arguments)
        else -> error("Unknown Telegram tool")
    }

    override fun close() = telegram.close()

    private fun listChats(args: JsonObject): McpResult {
        args.requireTelegramOnly("query", "limit")
        val chats = telegram.listChats(args.telegramStringOrNull("query"), args.telegramInt("limit", 20, 1, 50))
        return McpResult.text(JSONObject().put("returned", chats.size).put("chats", JSONArray(chats.map(TelegramChat::json))).toString())
    }

    private fun listMessages(args: JsonObject): McpResult {
        args.requireTelegramOnly("chat", "limit", "source", "beforeId", "afterId")
        return McpResult.text(telegram.listMessages(
            args.telegramString("chat"),
            args.telegramInt("limit", 50, 1, 100),
            args.source(),
            args.telegramLongOrNull("beforeId"),
            args.telegramLongOrNull("afterId"),
        ).json().toString())
    }

    private fun searchMessages(args: JsonObject): McpResult {
        args.requireTelegramOnly("query", "chat", "limit", "source", "after", "before")
        return McpResult.text(telegram.searchMessages(
            args.telegramString("query"),
            args.telegramStringOrNull("chat"),
            args.telegramInt("limit", 50, 1, 100),
            args.source(),
            args.telegramStringOrNull("after")?.let { Instant.parse(it).epochSecond },
            args.telegramStringOrNull("before")?.let { Instant.parse(it).epochSecond },
        ).json().toString())
    }

    private fun searchContacts(args: JsonObject): McpResult {
        args.requireTelegramOnly("query", "limit")
        val contacts = telegram.searchContacts(args.telegramString("query"), args.telegramInt("limit", 20, 1, 50))
        return McpResult.text(JSONObject().put("returned", contacts.size)
            .put("contacts", JSONArray(contacts.map(TelegramContact::json))).toString())
    }

    private fun downloadMedia(args: JsonObject): McpResult {
        args.requireTelegramOnly("chat", "messageId", "outputPath")
        val destination = resolve(args.telegramString("outputPath"), mustExist = false)
        require(!Files.exists(destination)) { "Download destination already exists" }
        Files.createDirectories(checkNotNull(destination.parent))
        val stage = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.download")
        return try {
            val outcome = telegram.downloadMedia(
                args.telegramString("chat"),
                args.telegramLong("messageId", 1),
                stage.toFile(),
            ) {}
            if (outcome.state == TelegramMutationState.SUCCEEDED && Files.size(stage) > 0) {
                val hash = sha256(stage)
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE)
                McpResult.text("Downloaded media to ${args.telegramString("outputPath")} (SHA-256 $hash).")
            } else mutationResult(outcome, "Telegram media download")
        } finally {
            Files.deleteIfExists(stage)
        }
    }

    private fun sendText(args: JsonObject): McpResult {
        args.requireTelegramOnly("to", "message", "parseMode", "topic", "replyTo", "silent", "disablePreview")
        return mutationResult(telegram.sendText(
            TelegramTextSend(
                callKey = UUID.randomUUID().toString(),
                to = args.telegramString("to"),
                message = args.telegramString("message"),
                parseMode = args.parseMode(),
                topic = args.telegramLongOrNull("topic"),
                replyTo = args.telegramLongOrNull("replyTo"),
                silent = args.telegramBoolean("silent", false),
                disablePreview = args.telegramBoolean("disablePreview", false),
            ),
        ) {}, "Telegram send")
    }

    private fun sendFile(args: JsonObject): McpResult {
        args.requireTelegramOnly("to", "path", "caption", "parseMode", "topic", "replyTo", "silent", "forceDocument")
        val path = args.telegramString("path")
        val file = resolve(path, mustExist = true)
        require(Files.size(file) in 1..MAX_FILE_BYTES) { "Telegram file is empty or too large" }
        return mutationResult(telegram.sendFile(
            TelegramFileSend(
                callKey = UUID.randomUUID().toString(),
                to = args.telegramString("to"),
                file = file.toFile(),
                caption = args.telegramStringOrNull("caption"),
                parseMode = args.parseMode(),
                topic = args.telegramLongOrNull("topic"),
                replyTo = args.telegramLongOrNull("replyTo"),
                silent = args.telegramBoolean("silent", false),
                forceDocument = args.telegramBoolean("forceDocument", false),
            ),
        ) {
            require(resolve(path, mustExist = true) == file) { "Telegram file path changed before dispatch" }
        }, "Telegram send")
    }

    private fun mutationResult(outcome: TelegramMutationOutcome, operation: String): McpResult = when (outcome.state) {
        TelegramMutationState.SUCCEEDED -> McpResult.text(outcome.message)
        TelegramMutationState.FAILED -> McpResult.text(outcome.message.ifBlank { "$operation failed" }, false)
        TelegramMutationState.INDETERMINATE -> McpResult.text(
            "$operation outcome is indeterminate; inspect Telegram before deciding what to do next.",
            false,
        )
    }

    private fun resolve(value: String, mustExist: Boolean): Path {
        val input = Path.of(value)
        val relative = if (input.isAbsolute) {
            val normalized = input.normalize()
            require(normalized.startsWith(hostWorkspace)) { "Path is outside the workspace" }
            hostWorkspace.relativize(normalized)
        } else input.normalize()
        require(!relative.startsWith("..")) { "Path is outside the workspace" }
        val candidate = workspace.resolve(relative).normalize()
        require(candidate.startsWith(workspace)) { "Path is outside the workspace" }
        if (mustExist) {
            val real = candidate.toRealPath()
            require(real.startsWith(workspace) && Files.isRegularFile(real)) { "Path is outside the workspace or not a file" }
            return real
        }
        val parent = existingParent(checkNotNull(candidate.parent)).toRealPath()
        require(parent.startsWith(workspace)) { "Destination is outside the workspace" }
        return candidate
    }

    private fun existingParent(path: Path): Path = when {
        Files.exists(path) -> path
        path.parent == null -> error("Destination parent is unavailable")
        else -> existingParent(path.parent)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object { const val MAX_FILE_BYTES = 100L * 1024 * 1024 }
}

internal fun telegramStateDirectory(): File = Path.of(System.getenv("CODEX_MCP_STATE") ?: "/state")
    .resolve("telegram-client").toFile()

private fun JsonObject.requireTelegramOnly(vararg allowed: String) = apply {
    require(keys.all { it in allowed }) { "Unexpected tool argument" }
}

private fun JsonObject.telegramString(name: String, default: String? = null): String =
    telegramStringOrNull(name) ?: default ?: error("Missing $name")

private fun JsonObject.telegramStringOrNull(name: String): String? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.telegramBoolean(name: String, default: Boolean): Boolean =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.telegramInt(name: String, default: Int, minimum: Int, maximum: Int): Int =
    (get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull ?: default).also {
        require(it in minimum..maximum) { "$name is out of range" }
    }

private fun JsonObject.telegramLong(name: String, minimum: Long): Long =
    (get(name)?.jsonPrimitive?.longOrNull ?: error("Missing $name")).also {
        require(it >= minimum) { "$name is out of range" }
    }

private fun JsonObject.telegramLongOrNull(name: String): Long? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull?.also {
        require(it > 0) { "$name is out of range" }
    }

private fun JsonObject.source(): TelegramSource = when (telegramString("source", "both")) {
    "archive" -> TelegramSource.ARCHIVE
    "live" -> TelegramSource.LIVE
    "both" -> TelegramSource.BOTH
    else -> error("Unsupported Telegram source")
}

private fun JsonObject.parseMode(): String = telegramString("parseMode", "none").also {
    require(it in setOf("none", "markdown", "html"))
}

private fun TelegramChat.json() = JSONObject()
    .put("id", id).put("type", type).put("title", title).put("username", username)
    .put("chatType", chatType).put("isForum", isForum).put("isGroup", isGroup)
    .put("unreadCount", unreadCount).put("unreadMentionsCount", unreadMentionsCount)

private fun TelegramMessages.json() = JSONObject()
    .put("source", source).put("returned", messages.size).put("hasMore", hasMore)
    .put("messages", JSONArray(messages.map(TelegramMessage::json)))
    .apply { nextBeforeId?.let { put("nextBeforeId", it) } }

private fun TelegramMessage.json() = JSONObject()
    .put("channelId", channelId).put("peerTitle", peerTitle).put("username", username)
    .put("messageId", messageId).put("date", date).put("fromId", fromId)
    .put("fromUsername", fromUsername).put("fromDisplayName", fromDisplayName)
    .put("fromPeerType", fromPeerType).put("fromIsBot", fromIsBot).put("text", text)
    .put("urls", JSONArray(urls)).put("media", media?.json()).put("topicId", topicId).put("source", source)

private fun TelegramMedia.json() = JSONObject()
    .put("type", type).put("fileId", fileId).put("fileName", fileName).put("mimeType", mimeType).put("size", size)

private fun TelegramContact.json() = JSONObject()
    .put("id", id).put("username", username).put("displayName", displayName)
    .put("phoneNumber", phoneNumber).put("isBot", isBot)
