package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.provider.api.CodexMobileProvider
import io.github.ciurlaro.codexmobile.provider.api.ProviderCall as BuiltInToolCall
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
import io.github.ciurlaro.codexmobile.provider.api.ProviderDescriptor
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationEntry
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationJournal
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationState
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderResult as BuiltInToolResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecretDefinition
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecrets
import io.github.ciurlaro.codexmobile.provider.api.ProviderToolDefinition as BuiltInToolDefinition
import io.github.ciurlaro.codexmobile.providers.telegram.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TelegramProvider(context: Context) : CodexMobileProvider {
    private val appContext = context.applicationContext
    private lateinit var telegram: TelegramIntegration
    private var activeCredentials: TelegramCredentials? = null

    private val providerTools = telegramTools.map {
        BuiltInToolDefinition(it.pluginId, it.name, it.description, it.inputSchema, it.mutation)
    }

    override val descriptor = ProviderDescriptor(
        pluginId = TELEGRAM_PLUGIN_ID,
        implementationVersion = "1.0.0",
        tools = providerTools,
        providerApi = 2,
        minHostVersionCode = 5,
        maxHostVersionCode = 5,
        displayName = "Telegram",
        schemaDigest = TELEGRAM_SCHEMA_DIGEST,
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
        when (val request = parseTelegramRequest(call.tool, call.arguments, call.callKey())) {
            is TelegramRequest.ListChats -> listChats(request)
            is TelegramRequest.ListMessages -> listMessages(request)
            is TelegramRequest.SearchMessages -> searchMessages(request)
            is TelegramRequest.SearchContacts -> searchContacts(request)
            is TelegramRequest.DownloadMedia -> downloadMedia(call, context, request)
            is TelegramRequest.SendText -> sendText(call, context, request.value)
            is TelegramRequest.SendFile -> sendFile(call, context, request)
        }
    }

    override suspend fun replay(call: BuiltInToolCall, context: ProviderContext): BuiltInToolResult? = withContext(Dispatchers.IO) {
        if (call.tool !in MUTATIONS) return@withContext null
        val existing = context.mutations.find(call) ?: return@withContext null
        when (existing.state) {
            ProviderMutationState.PREPARED -> null
            ProviderMutationState.SUCCEEDED, ProviderMutationState.FAILED, ProviderMutationState.INDETERMINATE ->
                checkNotNull(existing.result) { "Mutation journal terminal result is missing" }
            ProviderMutationState.DISPATCHED -> execute(call, context)
        }
    }

    override suspend fun prepareUninstall(context: ProviderContext): ProviderRemovalResult = withContext(Dispatchers.IO) {
        configure(context.secrets)
        telegram.prepareRemoval().let {
            if (it.ready) ProviderRemovalResult.ready(it.message)
            else ProviderRemovalResult.retry(checkNotNull(it.message))
        }
    }

    @Synchronized
    private fun configure(secrets: ProviderSecrets) {
        val credentials = telegramCredentials(secrets)
        if (credentials == activeCredentials) return
        if (::telegram.isInitialized) telegram.close()
        telegram = androidTelegramIntegration(appContext, credentials)
        activeCredentials = credentials
    }

    private fun listChats(request: TelegramRequest.ListChats) = BuiltInToolResult.text(
        telegramChatsJson(telegram.listChats(request.query, request.limit)),
    )

    private fun listMessages(request: TelegramRequest.ListMessages) = BuiltInToolResult.text(
        telegram.listMessages(
            request.chat, request.limit, request.source, request.beforeId, request.afterId,
        ).jsonString(),
    )

    private fun searchMessages(request: TelegramRequest.SearchMessages) = BuiltInToolResult.text(
        telegram.searchMessages(
            request.query,
            request.chat,
            request.limit,
            request.source,
            request.after?.let { Instant.parse(it).epochSecond },
            request.before?.let { Instant.parse(it).epochSecond },
        ).jsonString(),
    )

    private fun searchContacts(request: TelegramRequest.SearchContacts) = BuiltInToolResult.text(
        telegramContactsJson(telegram.searchContacts(request.query, request.limit)),
    )

    private suspend fun downloadMedia(
        call: BuiltInToolCall,
        context: ProviderContext,
        request: TelegramRequest.DownloadMedia,
    ): BuiltInToolResult {
        val journal = context.mutations
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        val destination = File(context.workspace.resolve(request.outputPath, mustExist = false))
        val stage = File(destination.parentFile, ".${destination.name}.${safeCallId(call.callId)}.download")
        if (existing?.state == ProviderMutationState.DISPATCHED) {
            val result = if (stage.isFile && stage.length() > 0) {
                val after = stage.sha256()
                atomicReplace(stage, destination)
                BuiltInToolResult.text("Downloaded media to ${destination.absolutePath} (SHA-256 $after).")
            } else {
                BuiltInToolResult.text("Telegram media download outcome is indeterminate; it was not retried.", false)
            }
            journal.finish(
                call,
                if (result.success) ProviderMutationState.SUCCEEDED else ProviderMutationState.INDETERMINATE,
                result,
                afterHash = destination.takeIf(File::isFile)?.sha256(),
            )
            return result
        }
        require(!destination.exists()) { "Download destination already exists" }
        destination.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        stage.delete()
        val outcome = telegram.downloadMedia(request.chat, request.messageId, stage) {
            context.beforeMutationDispatch()
            journal.dispatched(call)
        }
        if (outcome.state == TelegramMutationState.SUCCEEDED && stage.isFile && stage.length() > 0) {
            val after = stage.sha256()
            journal.dispatched(call, afterHash = after)
            atomicReplace(stage, destination)
            val success = BuiltInToolResult.text("Downloaded media to ${destination.absolutePath} (SHA-256 $after).")
            journal.finish(call, ProviderMutationState.SUCCEEDED, success, afterHash = after)
            return success
        }
        stage.delete()
        return finishMutation(call, outcome, "Telegram media download", journal)
    }

    private suspend fun sendText(
        call: BuiltInToolCall,
        context: ProviderContext,
        request: TelegramTextSend,
    ): BuiltInToolResult = send(
            call = call,
            context = context,
            operation = telegram::sendText,
            request = request,
        )

    private suspend fun sendFile(
        call: BuiltInToolCall,
        context: ProviderContext,
        request: TelegramRequest.SendFile,
    ): BuiltInToolResult {
        val path = request.path
        val file = File(context.workspace.resolve(path, mustExist = true))
        require(file.length() in 1..MAX_TELEGRAM_FILE_BYTES) { "Telegram file is empty or too large" }
        return send(
            call,
            context,
            {
                require(File(context.workspace.resolve(path, mustExist = true)) == file) {
                    "Telegram file path changed before dispatch"
                }
                context.beforeMutationDispatch()
            },
            telegram::sendFile,
            TelegramFileSend(
                callKey = request.callKey,
                to = request.to,
                file = file,
                caption = request.caption,
                parseMode = request.parseMode,
                topic = request.topic,
                replyTo = request.replyTo,
                silent = request.silent,
                forceDocument = request.forceDocument,
            ),
        )
    }

    private fun <T> send(
        call: BuiltInToolCall,
        context: ProviderContext,
        beforeMutationDispatch: () -> Unit = context.beforeMutationDispatch,
        operation: (T, () -> Unit) -> TelegramMutationOutcome,
        request: T,
    ): BuiltInToolResult {
        val journal = context.mutations
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        if (existing?.state == ProviderMutationState.DISPATCHED) {
            val result = BuiltInToolResult.text("Telegram send outcome is indeterminate; it was not retried.", false)
            journal.finish(call, ProviderMutationState.INDETERMINATE, result)
            return result
        }
        return finishMutation(
            call,
            operation(request) {
                beforeMutationDispatch()
                journal.dispatched(call)
            },
            "Telegram send",
            journal,
        )
    }

    private fun finishMutation(
        call: BuiltInToolCall,
        outcome: TelegramMutationOutcome,
        operation: String,
        journal: ProviderMutationJournal,
    ): BuiltInToolResult {
        val state = when (outcome.state) {
            TelegramMutationState.SUCCEEDED -> ProviderMutationState.SUCCEEDED
            TelegramMutationState.FAILED -> ProviderMutationState.FAILED
            TelegramMutationState.INDETERMINATE -> ProviderMutationState.INDETERMINATE
        }
        val presented = outcome.result(operation)
        val result = BuiltInToolResult.text(presented.message, presented.success)
        journal.finish(call, state, result)
        return result
    }

    private fun replayTerminal(entry: ProviderMutationEntry?): BuiltInToolResult? = when (entry?.state) {
        ProviderMutationState.SUCCEEDED, ProviderMutationState.FAILED, ProviderMutationState.INDETERMINATE ->
            checkNotNull(entry.result) { "Mutation journal terminal result is missing" }
        else -> null
    }

    private fun safeCallId(value: String): String = sha256(value).take(16)

    private fun atomicReplace(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val MUTATIONS = setOf("telegram_download_media", "telegram_send_text", "telegram_send_file")
    }
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


private fun BuiltInToolCall.callKey() = "$threadId:$turnId:$callId"
