package io.github.ciurlaro.codexmobile.providers.telegram

import java.io.Closeable
import java.io.File

interface TelegramClient {
    val available: Boolean
    fun status(): TelegramStatus
    fun startAuthentication(phoneNumber: String): TelegramAuthSession
    fun disconnect(): TelegramDisconnectResult
    fun listChats(query: String?, limit: Int): List<TelegramChat>
    fun listMessages(chat: String, limit: Int, source: TelegramSource, beforeId: Long?, afterId: Long?): TelegramMessages
    fun searchMessages(
        query: String,
        chat: String?,
        limit: Int,
        source: TelegramSource,
        after: Long?,
        before: Long?,
    ): TelegramMessages
    fun searchContacts(query: String, limit: Int): List<TelegramContact>
    fun downloadMedia(chat: String, messageId: Long, output: File, beforeSubmit: () -> Unit): TelegramMutationOutcome
    fun sendText(request: TelegramTextSend, beforeSubmit: () -> Unit): TelegramMutationOutcome
    fun sendFile(request: TelegramFileSend, beforeSubmit: () -> Unit): TelegramMutationOutcome
}

data class TelegramCredentials(val apiId: Int?, val apiHash: String) {
    val valid: Boolean
        get() = apiId?.let { it > 0 } == true && apiHash.matches(Regex("[0-9a-fA-F]{32}"))
}

data class TelegramClientInfo(
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
)

data class TelegramAuthority(val sessionId: String, val encryptionKey: ByteArray)

interface TelegramSessionStorage {
    fun authority(): TelegramAuthority?
    fun sessionDirectory(authority: TelegramAuthority): File
    fun createStaging(): Pair<TelegramAuthority, File>
    fun promote(authority: TelegramAuthority, staged: File): File
    fun activate(authority: TelegramAuthority)
    fun cleanup(authority: TelegramAuthority)
    fun clearAll(preserveRevocationWarning: Boolean = false): Boolean
    fun remoteRevocationUnconfirmed(): Boolean
    fun rememberUnconfirmedRemoteAuthorization()
    fun discardStaging(directory: File)
}

data class TelegramRemovalOutcome(val ready: Boolean, val message: String? = null)

class TelegramAuthSession(
    private val await: () -> TelegramAuthEvent,
    private val submit: (String) -> Unit,
    private val cancel: () -> Unit,
) : Closeable {
    fun awaitEvent(): TelegramAuthEvent = await()
    fun submitAnswer(value: String) = submit(value)
    override fun close() = cancel()
}

data class TelegramFileSend(
    val callKey: String,
    val to: String,
    val file: File,
    val caption: String?,
    val parseMode: String,
    val topic: Long?,
    val replyTo: Long?,
    val silent: Boolean,
    val forceDocument: Boolean,
)
