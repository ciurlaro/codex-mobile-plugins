package io.github.ciurlaro.codexmobile.platform.android

import java.io.Closeable
import java.io.File

internal interface TelegramClient {
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

internal data class TelegramStatus(val available: Boolean, val connected: Boolean, val username: String? = null)
internal enum class TelegramAuthPrompt { CODE, PASSWORD }
internal enum class TelegramDisconnectResult { CONFIRMED, INDETERMINATE }
internal enum class TelegramSource { ARCHIVE, LIVE, BOTH }
internal enum class TelegramMutationState { SUCCEEDED, FAILED, INDETERMINATE }

internal sealed interface TelegramAuthEvent {
    data class Prompt(val prompt: TelegramAuthPrompt) : TelegramAuthEvent
    data class Connected(val username: String?) : TelegramAuthEvent
    data class Failed(val message: String) : TelegramAuthEvent
}

internal class TelegramAuthSession(
    private val await: () -> TelegramAuthEvent,
    private val submit: (String) -> Unit,
    private val cancel: () -> Unit,
) : Closeable {
    fun awaitEvent(): TelegramAuthEvent = await()
    fun submitAnswer(value: String) = submit(value)
    override fun close() = cancel()
}

internal data class TelegramChat(
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

internal data class TelegramMessage(
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

internal data class TelegramMedia(
    val type: String,
    val fileId: Int,
    val fileName: String?,
    val mimeType: String?,
    val size: Long?,
)

internal data class TelegramMessages(
    val source: String,
    val messages: List<TelegramMessage>,
    val hasMore: Boolean = false,
    val nextBeforeId: Long? = null,
)

internal data class TelegramContact(
    val id: Long,
    val username: String?,
    val displayName: String,
    val phoneNumber: String?,
    val isBot: Boolean,
)

internal data class TelegramMutationOutcome(val state: TelegramMutationState, val message: String)

internal data class TelegramTextSend(
    val callKey: String,
    val to: String,
    val message: String,
    val parseMode: String,
    val topic: Long?,
    val replyTo: Long?,
    val silent: Boolean,
    val disablePreview: Boolean,
)

internal data class TelegramFileSend(
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
