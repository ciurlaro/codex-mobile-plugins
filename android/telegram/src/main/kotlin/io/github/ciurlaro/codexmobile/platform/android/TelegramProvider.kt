package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolDefinition
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolResult
import io.github.ciurlaro.codexmobile.agent.codex.CodexMobileProvider
import io.github.ciurlaro.codexmobile.agent.codex.ProviderDescriptor
import io.github.ciurlaro.codexmobile.agent.codex.ProviderContext
import io.github.ciurlaro.codexmobile.agent.codex.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.agent.codex.ProviderSecretDefinition
import io.github.ciurlaro.codexmobile.agent.codex.ProviderSecrets
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_HASH_SECRET
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_ID_SECRET
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_PLUGIN_ID
import io.github.ciurlaro.codexmobile.providers.telegram.telegramTools
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
import org.json.JSONObject

class TelegramProvider(context: Context) : CodexMobileProvider {
    private val appContext = context.applicationContext
    private lateinit var telegram: TelegramIntegration
    private var activeCredentials: TelegramCredentials? = null
    private val workspace = WorkspaceManager(appContext)
    private val journal = BuiltInMutationJournal(appContext)

    override val descriptor = ProviderDescriptor(
        pluginId = TELEGRAM_PLUGIN_ID,
        implementationVersion = "1.0.0",
        tools = telegramTools.map { BuiltInToolDefinition(it.pluginId, it.name, it.description, it.inputSchema, it.mutation) },
        providerApi = 2,
        minHostVersionCode = 3,
        maxHostVersionCode = 3,
        displayName = "Telegram",
        settingsEntryPoint = "io.github.ciurlaro.codexmobile.providers.telegram.TelegramSettingsActivity",
        secrets = listOf(
            ProviderSecretDefinition(
                TELEGRAM_API_ID_SECRET,
                "Telegram API ID",
                "Application ID from my.telegram.org",
            ),
            ProviderSecretDefinition(
                TELEGRAM_API_HASH_SECRET,
                "Telegram API hash",
                "Application hash from my.telegram.org",
            ),
        ),
    )

    override suspend fun execute(
        call: BuiltInToolCall,
        context: ProviderContext,
    ): BuiltInToolResult = withContext(Dispatchers.IO) {
        configure(context.secrets)
        when (call.tool) {
            "telegram_list_chats" -> listChats(call)
            "telegram_list_messages" -> listMessages(call)
            "telegram_search_messages" -> searchMessages(call)
            "telegram_search_contacts" -> searchContacts(call)
            "telegram_download_media" -> downloadMedia(call, context.beforeMutationDispatch)
            "telegram_send_text" -> sendText(call, context.beforeMutationDispatch)
            "telegram_send_file" -> sendFile(call, context.beforeMutationDispatch)
            else -> error("Unknown Telegram tool")
        }
    }

    override suspend fun replay(call: BuiltInToolCall, context: ProviderContext): BuiltInToolResult? = withContext(Dispatchers.IO) {
        if (call.tool !in MUTATIONS) return@withContext null
        val existing = journal.find(call) ?: return@withContext null
        when (existing.state) {
            MutationState.PREPARED -> null
            MutationState.SUCCEEDED, MutationState.FAILED, MutationState.INDETERMINATE ->
                checkNotNull(existing.result) { "Mutation journal terminal result is missing" }
            MutationState.DISPATCHED -> execute(call, context)
        }
    }

    override suspend fun prepareUninstall(context: ProviderContext): ProviderRemovalResult = withContext(Dispatchers.IO) {
        configure(context.secrets)
        telegram.prepareRemoval()
    }

    @Synchronized
    private fun configure(secrets: ProviderSecrets) {
        val credentials = TelegramCredentials.from(secrets)
        if (credentials == activeCredentials) return
        if (::telegram.isInitialized) telegram.close()
        telegram = TelegramIntegration(appContext, credentials)
        activeCredentials = credentials
    }

    private fun listChats(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("query", "limit")
        val chats = telegram.listChats(args.stringOrNull("query"), args.int("limit", 20, 1, 50))
        return BuiltInToolResult.text(
            JSONObject().put("returned", chats.size).put("chats", JSONArray(chats.map(TelegramChat::json))).toString(),
        )
    }

    private fun listMessages(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("chat", "limit", "source", "beforeId", "afterId")
        return BuiltInToolResult.text(
            telegram.listMessages(
                args.string("chat"),
                args.int("limit", 50, 1, 100),
                args.source(),
                args.longOrNull("beforeId"),
                args.longOrNull("afterId"),
            ).json().toString(),
        )
    }

    private fun searchMessages(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("query", "chat", "limit", "source", "after", "before")
        return BuiltInToolResult.text(
            telegram.searchMessages(
                args.string("query"),
                args.stringOrNull("chat"),
                args.int("limit", 50, 1, 100),
                args.source(),
                args.stringOrNull("after")?.let { Instant.parse(it).epochSecond },
                args.stringOrNull("before")?.let { Instant.parse(it).epochSecond },
            ).json().toString(),
        )
    }

    private fun searchContacts(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("query", "limit")
        val contacts = telegram.searchContacts(args.string("query"), args.int("limit", 20, 1, 50))
        return BuiltInToolResult.text(
            JSONObject().put("returned", contacts.size)
                .put("contacts", JSONArray(contacts.map(TelegramContact::json))).toString(),
        )
    }

    private suspend fun downloadMedia(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit,
    ): BuiltInToolResult {
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        val args = call.arguments.requireOnly("chat", "messageId", "outputPath")
        val destination = workspace.resolveFile(call.workspace, args.string("outputPath"), mustExist = false)
        val stage = File(destination.parentFile, ".${destination.name}.${safeCallId(call.callId)}.download")
        if (existing?.state == MutationState.DISPATCHED) {
            val result = if (stage.isFile && stage.length() > 0) {
                val after = stage.sha256()
                atomicReplace(stage, destination)
                BuiltInToolResult.text("Downloaded media to ${destination.absolutePath} (SHA-256 $after).")
            } else {
                BuiltInToolResult.text("Telegram media download outcome is indeterminate; it was not retried.", false)
            }
            journal.finish(
                call,
                if (result.success) MutationState.SUCCEEDED else MutationState.INDETERMINATE,
                result,
                afterHash = destination.takeIf(File::isFile)?.sha256(),
            )
            return result
        }
        require(!destination.exists()) { "Download destination already exists" }
        destination.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        stage.delete()
        val outcome = telegram.downloadMedia(args.string("chat"), args.long("messageId", 1), stage) {
            beforeMutationDispatch()
            journal.dispatched(call)
        }
        if (outcome.state == TelegramMutationState.SUCCEEDED && stage.isFile && stage.length() > 0) {
            val after = stage.sha256()
            journal.dispatched(call, afterHash = after)
            atomicReplace(stage, destination)
            val success = BuiltInToolResult.text("Downloaded media to ${destination.absolutePath} (SHA-256 $after).")
            journal.finish(call, MutationState.SUCCEEDED, success, afterHash = after)
            return success
        }
        stage.delete()
        return finishMutation(call, outcome, "Telegram media download")
    }

    private suspend fun sendText(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit,
    ): BuiltInToolResult {
        val args = call.arguments.requireOnly(
            "to", "message", "parseMode", "topic", "replyTo", "silent", "disablePreview",
        )
        return send(
            call,
            beforeMutationDispatch,
            telegram::sendText,
            TelegramTextSend(
                callKey = "${call.threadId}:${call.turnId}:${call.callId}",
                to = args.string("to"),
                message = args.string("message"),
                parseMode = args.parseMode(),
                topic = args.longOrNull("topic"),
                replyTo = args.longOrNull("replyTo"),
                silent = args.boolean("silent", false),
                disablePreview = args.boolean("disablePreview", false),
            ),
        )
    }

    private suspend fun sendFile(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit,
    ): BuiltInToolResult {
        val args = call.arguments.requireOnly(
            "to", "path", "caption", "parseMode", "topic", "replyTo", "silent", "forceDocument",
        )
        val path = args.string("path")
        val file = workspace.resolveFile(call.workspace, path, mustExist = true)
        require(file.length() in 1..MAX_FILE_BYTES) { "Telegram file is empty or too large" }
        return send(
            call,
            {
                require(workspace.resolveFile(call.workspace, path, mustExist = true) == file) {
                    "Telegram file path changed before dispatch"
                }
                beforeMutationDispatch()
            },
            telegram::sendFile,
            TelegramFileSend(
                callKey = "${call.threadId}:${call.turnId}:${call.callId}",
                to = args.string("to"),
                file = file,
                caption = args.stringOrNull("caption"),
                parseMode = args.parseMode(),
                topic = args.longOrNull("topic"),
                replyTo = args.longOrNull("replyTo"),
                silent = args.boolean("silent", false),
                forceDocument = args.boolean("forceDocument", false),
            ),
        )
    }

    private fun <T> send(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit,
        operation: (T, () -> Unit) -> TelegramMutationOutcome,
        request: T,
    ): BuiltInToolResult {
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        if (existing?.state == MutationState.DISPATCHED) {
            val result = BuiltInToolResult.text("Telegram send outcome is indeterminate; it was not retried.", false)
            journal.finish(call, MutationState.INDETERMINATE, result)
            return result
        }
        return finishMutation(
            call,
            operation(request) {
                beforeMutationDispatch()
                journal.dispatched(call)
            },
            "Telegram send",
        )
    }

    private fun finishMutation(
        call: BuiltInToolCall,
        outcome: TelegramMutationOutcome,
        operation: String,
    ): BuiltInToolResult {
        val state = when (outcome.state) {
            TelegramMutationState.SUCCEEDED -> MutationState.SUCCEEDED
            TelegramMutationState.FAILED -> MutationState.FAILED
            TelegramMutationState.INDETERMINATE -> MutationState.INDETERMINATE
        }
        val result = when (outcome.state) {
            TelegramMutationState.SUCCEEDED -> BuiltInToolResult.text(outcome.message)
            TelegramMutationState.FAILED -> BuiltInToolResult.text(outcome.message.ifBlank { "$operation failed" }, false)
            TelegramMutationState.INDETERMINATE -> BuiltInToolResult.text(
                "$operation outcome is indeterminate; inspect Telegram before deciding what to do next.",
                false,
            )
        }
        journal.finish(call, state, result)
        return result
    }

    private fun replayTerminal(entry: JournalEntry?): BuiltInToolResult? = when (entry?.state) {
        MutationState.SUCCEEDED, MutationState.FAILED, MutationState.INDETERMINATE ->
            checkNotNull(entry.result) { "Mutation journal terminal result is missing" }
        else -> null
    }

    private fun safeCallId(value: String): String = sha256(value).take(16)

    private fun atomicReplace(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        val MUTATIONS = setOf("telegram_download_media", "telegram_send_text", "telegram_send_file")
    }
}

private fun JsonObject.requireOnly(vararg allowed: String): JsonObject = apply {
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
    require(it in setOf("none", "markdown", "html"))
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
