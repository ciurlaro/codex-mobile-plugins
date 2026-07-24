package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.agent.codex.ProviderSecrets
import io.github.ciurlaro.codexmobile.providers.telegram.BuildConfig
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_HASH_SECRET
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_ID_SECRET
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal class TelegramIntegration(
    context: Context,
    private val credentials: TelegramCredentials,
) : TelegramClient {
    private val store = TelegramSessionStore(context)
    private var activeAuthority: TelegramAuthority? = null
    private var activeSession: TdLibSession? = null
    private var authentication: Authentication? = null

    override val available: Boolean
        get() = credentials.valid

    @Synchronized
    override fun status(): TelegramStatus {
        if (!available) return TelegramStatus(false, false)
        return runCatching {
            val account = healthySession().account()
            TelegramStatus(true, true, username(account))
        }.getOrElse {
            closeActive()
            TelegramStatus(true, false)
        }
    }

    @Synchronized
    override fun startAuthentication(phoneNumber: String): TelegramAuthSession {
        check(available) { "Telegram application credentials are not configured" }
        require(PHONE.matches(phoneNumber.trim())) { "Use an international phone number such as +41790000000" }
        check(authentication == null) { "Telegram authentication is already active" }
        val (authority, directory) = store.createStaging()
        val controller = try {
            Authentication(authority, directory, phoneNumber.trim())
        } catch (error: Exception) {
            store.discardStaging(directory)
            throw error
        }
        authentication = controller
        return TelegramAuthSession(
            await = controller::await,
            submit = controller::submit,
            cancel = controller::cancel,
        )
    }

    @Synchronized
    override fun disconnect(): TelegramDisconnectResult {
        authentication?.cancel()
        val priorUncertainty = store.remoteRevocationUnconfirmed()
        if (store.authority() == null) {
            closeActive()
            if (priorUncertainty) store.rememberUnconfirmedRemoteAuthorization()
            return if (store.clearAll(preserveRevocationWarning = priorUncertainty) && !priorUncertainty) {
                TelegramDisconnectResult.CONFIRMED
            }
            else TelegramDisconnectResult.INDETERMINATE
        }
        val remoteConfirmed = runCatching { healthySession().logOut() }.getOrDefault(false)
        closeActive()
        val uncertain = priorUncertainty || !remoteConfirmed
        if (uncertain) store.rememberUnconfirmedRemoteAuthorization()
        val localCleared = store.clearAll(preserveRevocationWarning = uncertain)
        return if (!uncertain && localCleared) TelegramDisconnectResult.CONFIRMED
        else TelegramDisconnectResult.INDETERMINATE
    }

    @Synchronized
    fun prepareRemoval(): ProviderRemovalResult {
        authentication?.cancel()
        val authority = runCatching { store.authority() }.getOrElse {
            return ProviderRemovalResult.retry("Telegram session authority is unreadable; removal was not started")
        }
        val priorUncertainty = store.remoteRevocationUnconfirmed()
        if (authority == null) {
            return if (store.clearAll()) ProviderRemovalResult.ready(priorUncertainty.warning())
            else ProviderRemovalResult.retry("Telegram local data could not be removed")
        }
        val wasActive = activeSession != null
        val session = try {
            activeSession ?: openSession(authority, store.sessionDirectory(authority))
        } catch (_: Exception) {
            return ProviderRemovalResult.retry("Telegram remote logout could not be verified")
        }
        val revoked = try {
            when (session.advanceToAction()) {
                "authorizationStateClosed", "authorizationStateWaitPhoneNumber" -> true
                "authorizationStateReady" -> session.logOut()
                else -> false
            }
        } catch (_: Exception) {
            false
        }
        if (!revoked) {
            if (!wasActive) session.close()
            return ProviderRemovalResult.retry(
                "Telegram remote logout is indeterminate; the provider and credentials were retained for retry",
            )
        }
        closeActive()
        if (!wasActive) session.close()
        return if (store.clearAll()) ProviderRemovalResult.ready(priorUncertainty.warning())
        else ProviderRemovalResult.retry("Telegram logout succeeded, but local cleanup needs retry")
    }

    @Synchronized
    override fun listChats(query: String?, limit: Int): List<TelegramChat> {
        val session = healthySession()
        val ids = session.request(
            JSONObject()
                .put("@type", "getChats")
                .put("chat_list", JSONObject().put("@type", "chatListMain"))
                .put("limit", limit.coerceAtMost(50)),
        ).getJSONArray("chat_ids").longs()
        return ids.map { session.chat(it) }
            .map { session.toChat(it) }
            .filter { query.isNullOrBlank() || it.title.contains(query, true) || it.username?.contains(query, true) == true }
            .take(limit)
    }

    @Synchronized
    override fun listMessages(
        chat: String,
        limit: Int,
        source: TelegramSource,
        beforeId: Long?,
        afterId: Long?,
    ): TelegramMessages {
        val session = healthySession()
        val chatId = session.resolveChat(chat)
        val messages = when (source) {
            TelegramSource.ARCHIVE -> session.history(chatId, limit, beforeId, true, "archive")
            TelegramSource.LIVE -> session.history(chatId, limit, beforeId, false, "live")
            TelegramSource.BOTH -> mergeMessages(
                session.history(chatId, limit, beforeId, true, "archive"),
                session.history(chatId, limit, beforeId, false, "live"),
                limit,
            )
        }.filter { afterId == null || it.messageId > afterId }.take(limit)
        return TelegramMessages(
            source = source.name.lowercase(),
            messages = messages,
            hasMore = messages.size == limit,
            nextBeforeId = messages.lastOrNull()?.messageId,
        )
    }

    @Synchronized
    override fun searchMessages(
        query: String,
        chat: String?,
        limit: Int,
        source: TelegramSource,
        after: Long?,
        before: Long?,
    ): TelegramMessages {
        val session = healthySession()
        val archive = if (source != TelegramSource.LIVE) session.searchArchive(query, chat, limit) else emptyList()
        val live = if (source != TelegramSource.ARCHIVE) session.searchLive(query, chat, limit) else emptyList()
        val merged = when (source) {
            TelegramSource.ARCHIVE -> archive
            TelegramSource.LIVE -> live
            TelegramSource.BOTH -> mergeMessages(archive, live, limit)
        }.filter { message ->
            val time = Instant.parse(message.date).epochSecond
            (after == null || time >= after) && (before == null || time <= before)
        }.take(limit)
        return TelegramMessages(source.name.lowercase(), merged)
    }

    @Synchronized
    override fun searchContacts(query: String, limit: Int): List<TelegramContact> {
        val session = healthySession()
        val ids = session.request(
            JSONObject().put("@type", "searchContacts").put("query", query).put("limit", limit),
        ).getJSONArray("user_ids").longs()
        return ids.map { session.user(it).toContact() }
    }

    @Synchronized
    override fun downloadMedia(
        chat: String,
        messageId: Long,
        output: File,
        beforeSubmit: () -> Unit,
    ): TelegramMutationOutcome {
        val session = healthySession()
        val chatId = session.resolveChat(chat)
        val message = session.request(
            JSONObject().put("@type", "getMessage").put("chat_id", chatId).put("message_id", messageId),
        )
        val media = message.media() ?: throw TelegramConfirmedException("Telegram message has no downloadable media")
        beforeSubmit()
        return try {
            val downloaded = session.request(
                JSONObject()
                    .put("@type", "downloadFile")
                    .put("file_id", media.fileId)
                    .put("priority", 32)
                    .put("offset", 0)
                    .put("limit", 0)
                    .put("synchronous", true),
                60,
            )
            val path = downloaded.optJSONObject("local")?.optString("path").orEmpty()
            require(path.isNotBlank() && File(path).isFile) { "Telegram media download did not produce a file" }
            Files.copy(File(path).toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            TelegramMutationOutcome(TelegramMutationState.SUCCEEDED, "Telegram media downloaded")
        } catch (error: TelegramConfirmedException) {
            TelegramMutationOutcome(TelegramMutationState.FAILED, error.message ?: "Telegram media download failed")
        } catch (error: Exception) {
            TelegramMutationOutcome(TelegramMutationState.INDETERMINATE, "Telegram media download outcome is indeterminate")
        }
    }

    @Synchronized
    override fun sendText(request: TelegramTextSend, beforeSubmit: () -> Unit): TelegramMutationOutcome {
        val session = healthySession()
        return session.send(
            callKey = request.callKey,
            to = request.to,
            topic = request.topic,
            replyTo = request.replyTo,
            silent = request.silent,
            beforeSubmit = beforeSubmit,
            content = JSONObject()
                .put("@type", "inputMessageText")
                .put("text", session.formattedText(request.message, request.parseMode))
                .put("link_preview_options", JSONObject()
                    .put("@type", "linkPreviewOptions")
                    .put("is_disabled", request.disablePreview)
                    .put("url", "")
                    .put("force_small_media", false)
                    .put("force_large_media", false)
                    .put("show_above_text", false))
                .put("clear_draft", false),
        )
    }

    @Synchronized
    override fun sendFile(request: TelegramFileSend, beforeSubmit: () -> Unit): TelegramMutationOutcome {
        val session = healthySession()
        val content = session.fileContent(request)
        return session.send(request.callKey, request.to, request.topic, request.replyTo, request.silent, beforeSubmit, content)
    }

    @Synchronized
    private fun healthySession(): TdLibSession {
        check(available) { "Telegram application credentials are required" }
        val authority = store.authority() ?: error("Telegram authentication is required")
        if (activeAuthority?.sessionId != authority.sessionId || activeSession == null) {
            closeActive()
            val session = openSession(authority, store.sessionDirectory(authority))
            val state = session.advanceToAction()
            if (state != "authorizationStateReady") {
                session.close()
                error("Telegram authentication is required")
            }
            session.account()
            activeAuthority = authority
            activeSession = session
            store.cleanup(authority)
        }
        return checkNotNull(activeSession).also { it.account() }
    }

    private fun openSession(authority: TelegramAuthority, directory: File) = TdLibSession(
        directory = directory,
        encryptionKey = authority.encryptionKey,
        apiId = checkNotNull(credentials.apiId),
        apiHash = credentials.apiHash,
        applicationVersion = "Codex Mobile TDLib ${BuildConfig.TDLIB_VERSION}",
    )

    @Synchronized
    fun close() {
        authentication?.cancel()
        closeActive()
    }

    private fun closeActive() {
        activeSession?.close()
        activeSession = null
        activeAuthority = null
    }

    private inner class Authentication(
        private val authority: TelegramAuthority,
        private var directory: File,
        phoneNumber: String,
    ) {
        private var session = openSession(authority, directory)
        private var prompt: TelegramAuthPrompt? = null
        private val closed = AtomicBoolean()

        init {
            require(session.advanceToAction() == "authorizationStateWaitPhoneNumber") {
                "Telegram authentication storage is not empty"
            }
            session.submitPhone(phoneNumber)
        }

        fun await(): TelegramAuthEvent = synchronized(this@TelegramIntegration) {
            if (closed.get()) return@synchronized TelegramAuthEvent.Failed("Telegram authentication was cancelled")
            try {
                when (session.advanceToAction()) {
                    "authorizationStateWaitCode" -> TelegramAuthEvent.Prompt(TelegramAuthPrompt.CODE).also {
                        prompt = TelegramAuthPrompt.CODE
                    }
                    "authorizationStateWaitPassword" -> TelegramAuthEvent.Prompt(TelegramAuthPrompt.PASSWORD).also {
                        prompt = TelegramAuthPrompt.PASSWORD
                    }
                    "authorizationStateReady" -> activate()
                    else -> TelegramAuthEvent.Failed("Telegram authentication failed")
                }
            } catch (error: Exception) {
                TelegramAuthEvent.Failed(error.message ?: "Telegram authentication failed")
            }
        }

        fun submit(value: String) = synchronized(this@TelegramIntegration) {
            check(!closed.get()) { "Telegram authentication is no longer active" }
            when (prompt) {
                TelegramAuthPrompt.CODE -> session.submitCode(value)
                TelegramAuthPrompt.PASSWORD -> session.submitPassword(value)
                null -> error("Telegram is not waiting for an authentication answer")
            }
            prompt = null
        }

        fun cancel() = synchronized(this@TelegramIntegration) {
            if (!closed.compareAndSet(false, true)) return@synchronized
            session.close()
            store.discardStaging(directory)
            authentication = null
        }

        private fun activate(): TelegramAuthEvent {
            val account = session.account()
            session.closeAndFlush()
            val target = store.promote(authority, directory)
            directory = target
            val verified = openSession(authority, target)
            try {
                require(verified.advanceToAction() == "authorizationStateReady") { "Telegram session verification failed" }
                val verifiedAccount = verified.account()
                store.activate(authority)
                activeAuthority = authority
                activeSession = verified
                store.cleanup(authority)
                closed.set(true)
                authentication = null
                return TelegramAuthEvent.Connected(username(verifiedAccount) ?: username(account))
            } catch (error: Exception) {
                verified.close()
                throw error
            }
        }
    }

    private companion object {
        val PHONE = Regex("^\\+[1-9][0-9]{6,14}$")
    }
}

internal data class TelegramCredentials(
    val apiId: Int?,
    val apiHash: String,
) {
    val valid: Boolean
        get() = apiId?.let { it > 0 } == true && apiHash.matches(Regex("[0-9a-fA-F]{32}"))

    companion object {
        fun from(secrets: ProviderSecrets) = TelegramCredentials(
            secrets.get(TELEGRAM_API_ID_SECRET)?.toIntOrNull(),
            secrets.get(TELEGRAM_API_HASH_SECRET).orEmpty(),
        )
    }
}

private fun Boolean.warning(): String? = takeIf { it }?.let {
    "An obsolete Telegram authorization could not be identified or remotely revoked; remove it in Telegram Devices."
}

private fun TdLibSession.chat(id: Long): JSONObject = request(JSONObject().put("@type", "getChat").put("chat_id", id))
private fun TdLibSession.user(id: Long): JSONObject = request(JSONObject().put("@type", "getUser").put("user_id", id))

private fun TdLibSession.resolveChat(value: String): Long {
    value.toLongOrNull()?.let { return chat(it).getLong("id") }
    if (value.startsWith('@')) {
        return request(JSONObject().put("@type", "searchPublicChat").put("username", value.removePrefix("@"))).getLong("id")
    }
    val ids = linkedSetOf<Long>()
    listOf("searchChats", "searchChatsOnServer").forEach { type ->
        val request = JSONObject().put("@type", type).put("query", value).put("limit", 20)
        if (type == "searchChats") request.put("type_filter", JSONObject.NULL)
        runCatching { this.request(request).getJSONArray("chat_ids").longs() }.getOrNull()?.let(ids::addAll)
    }
    val matches = ids.map(::chat).filter { candidate ->
        candidate.optString("title").equals(value, true) || candidate.activeUsername()?.equals(value.removePrefix("@"), true) == true
    }
    require(matches.size == 1) { if (matches.isEmpty()) "Telegram chat was not found" else "Telegram chat name is ambiguous" }
    return matches.single().getLong("id")
}

private fun TdLibSession.toChat(chat: JSONObject): TelegramChat {
    val type = chat.getJSONObject("type")
    val typeName = type.getString("@type")
    var isForum = chat.optBoolean("is_forum")
    val chatType = when (typeName) {
        "chatTypePrivate" -> "private"
        "chatTypeSecret" -> "secret"
        "chatTypeBasicGroup" -> "group"
        "chatTypeSupergroup" -> {
            val supergroup = request(
                JSONObject().put("@type", "getSupergroup").put("supergroup_id", type.getLong("supergroup_id")),
            )
            isForum = supergroup.optBoolean("is_forum")
            if (supergroup.optBoolean("is_channel")) "channel" else "group"
        }
        else -> "unknown"
    }
    val username = chat.activeUsername() ?: when (typeName) {
        "chatTypePrivate" -> user(type.getLong("user_id")).activeUsername()
        else -> null
    }
    return TelegramChat(
        id = chat.getLong("id"),
        type = typeName.removePrefix("chatType").lowercase(),
        title = chat.optString("title"),
        username = username,
        chatType = chatType,
        isForum = isForum,
        isGroup = chatType == "group",
        unreadCount = chat.optInt("unread_count"),
        unreadMentionsCount = chat.optInt("unread_mention_count"),
    )
}

private fun TdLibSession.history(
    chatId: Long,
    limit: Int,
    beforeId: Long?,
    onlyLocal: Boolean,
    source: String,
): List<TelegramMessage> {
    val result = request(
        JSONObject()
            .put("@type", "getChatHistory")
            .put("chat_id", chatId)
            .put("from_message_id", beforeId ?: 0)
            .put("offset", 0)
            .put("limit", limit)
            .put("only_local", onlyLocal),
    )
    return result.getJSONArray("messages").objects().map { message(it, source) }
}

private fun TdLibSession.searchArchive(query: String, chatValue: String?, limit: Int): List<TelegramMessage> {
    val chatIds = if (chatValue != null) listOf(resolveChat(chatValue)) else request(
        JSONObject().put("@type", "getChats").put("chat_list", JSONObject().put("@type", "chatListMain")).put("limit", 100),
    ).getJSONArray("chat_ids").longs()
    return chatIds.asSequence()
        .flatMap { history(it, 100, null, true, "archive").asSequence() }
        .filter { it.text.contains(query, true) }
        .sortedByDescending(TelegramMessage::date)
        .take(limit)
        .toList()
}

private fun TdLibSession.searchLive(query: String, chatValue: String?, limit: Int): List<TelegramMessage> {
    val result = if (chatValue != null) {
        request(
            JSONObject()
                .put("@type", "searchChatMessages")
                .put("chat_id", resolveChat(chatValue))
                .put("topic_id", JSONObject.NULL)
                .put("query", query)
                .put("sender_id", JSONObject.NULL)
                .put("from_message_id", 0)
                .put("offset", 0)
                .put("limit", limit)
                .put("filter", JSONObject.NULL),
        )
    } else {
        request(
            JSONObject()
                .put("@type", "searchMessages")
                .put("chat_list", JSONObject().put("@type", "chatListMain"))
                .put("query", query)
                .put("offset", "")
                .put("limit", limit)
                .put("filter", JSONObject.NULL)
                .put("chat_type_filter", JSONObject.NULL)
                .put("min_date", 0)
                .put("max_date", 0),
        )
    }
    return result.getJSONArray("messages").objects().map { message(it, "live") }
}

private fun TdLibSession.message(message: JSONObject, source: String): TelegramMessage {
    val chat = chat(message.getLong("chat_id"))
    val sender = message.optJSONObject("sender_id")
    val senderInfo = when (sender?.optString("@type")) {
        "messageSenderUser" -> user(sender.getLong("user_id")).let { user ->
            Sender(
                user.getLong("id"),
                user.activeUsername(),
                listOf(user.optString("first_name"), user.optString("last_name")).filter(String::isNotBlank).joinToString(" "),
                "user",
                user.optJSONObject("type")?.optString("@type") == "userTypeBot",
            )
        }
        "messageSenderChat" -> chat(sender.getLong("chat_id")).let { senderChat ->
            Sender(senderChat.getLong("id"), senderChat.activeUsername(), senderChat.optString("title"), "chat", false)
        }
        else -> Sender(null, null, null, null, false)
    }
    val content = message.getJSONObject("content")
    val text = content.formattedText()
    return TelegramMessage(
        channelId = message.getLong("chat_id"),
        peerTitle = chat.optString("title"),
        username = chat.activeUsername(),
        messageId = message.getLong("id"),
        date = Instant.ofEpochSecond(message.getLong("date")).toString(),
        fromId = senderInfo.id,
        fromUsername = senderInfo.username,
        fromDisplayName = senderInfo.displayName,
        fromPeerType = senderInfo.type,
        fromIsBot = senderInfo.bot,
        text = text,
        urls = URL.findAll(text).map { it.value }.distinct().toList(),
        media = message.media(),
        topicId = message.optLong("message_thread_id").takeIf { it > 0 },
        source = source,
    )
}

private fun JSONObject.media(): TelegramMedia? {
    val content = optJSONObject("content") ?: return null
    val (type, value) = when (content.optString("@type")) {
        "messageDocument" -> "document" to content.optJSONObject("document")
        "messagePhoto" -> "photo" to content.optJSONObject("photo")?.optJSONArray("sizes")?.objects()?.lastOrNull()
        "messageVideo" -> "video" to content.optJSONObject("video")
        "messageAudio" -> "audio" to content.optJSONObject("audio")
        "messageAnimation" -> "animation" to content.optJSONObject("animation")
        "messageVoiceNote" -> "voice" to content.optJSONObject("voice_note")
        else -> return null
    }
    value ?: return null
    val file = value.optJSONObject(type) ?: value.optJSONObject("photo") ?: value.optJSONObject("voice") ?: return null
    return TelegramMedia(
        type = type,
        fileId = file.getInt("id"),
        fileName = value.optString("file_name").ifBlank { null },
        mimeType = value.optString("mime_type").ifBlank { null },
        size = file.optLong("size").takeIf { it > 0 },
    )
}

private fun JSONObject.formattedText(): String {
    val content = this
    return when (content.optString("@type")) {
        "messageText" -> content.optJSONObject("text")?.optString("text")
        "messageDocument" -> content.optJSONObject("caption")?.optString("text")
        "messagePhoto" -> content.optJSONObject("caption")?.optString("text")
        "messageVideo" -> content.optJSONObject("caption")?.optString("text")
        "messageAudio" -> content.optJSONObject("caption")?.optString("text")
        "messageAnimation" -> content.optJSONObject("caption")?.optString("text")
        else -> null
    }.orEmpty()
}

private fun TdLibSession.formattedText(text: String, parseMode: String): JSONObject = when (parseMode) {
    "none" -> JSONObject().put("@type", "formattedText").put("text", text).put("entities", JSONArray())
    "markdown", "html" -> request(
        JSONObject()
            .put("@type", "parseTextEntities")
            .put("text", text)
            .put("parse_mode", if (parseMode == "markdown") {
                JSONObject().put("@type", "textParseModeMarkdown").put("version", 2)
            } else JSONObject().put("@type", "textParseModeHTML")),
    )
    else -> error("Unsupported Telegram parse mode")
}

private fun TdLibSession.fileContent(request: TelegramFileSend): JSONObject {
    val formatted = formattedText(request.caption.orEmpty(), request.parseMode)
    val input = JSONObject().put("@type", "inputFileLocal").put("path", request.file.absolutePath)
    val mime = runCatching { Files.probeContentType(request.file.toPath()) }.getOrNull().orEmpty()
    return when {
        !request.forceDocument && mime.startsWith("image/") -> JSONObject()
            .put("@type", "inputMessagePhoto")
            .put("photo", JSONObject().put("@type", "inputPhoto").put("photo", input).put("thumbnail", JSONObject.NULL)
                .put("video", JSONObject.NULL).put("added_sticker_file_ids", JSONArray()).put("width", 0).put("height", 0))
            .put("caption", formatted).put("show_caption_above_media", false)
            .put("self_destruct_type", JSONObject.NULL).put("has_spoiler", false)
        !request.forceDocument && mime.startsWith("video/") -> JSONObject()
            .put("@type", "inputMessageVideo")
            .put("video", JSONObject().put("@type", "inputVideo").put("video", input).put("thumbnail", JSONObject.NULL)
                .put("cover", JSONObject.NULL).put("start_timestamp", 0).put("added_sticker_file_ids", JSONArray())
                .put("duration", 0).put("width", 0).put("height", 0).put("supports_streaming", true))
            .put("caption", formatted).put("show_caption_above_media", false)
            .put("self_destruct_type", JSONObject.NULL).put("has_spoiler", false)
        !request.forceDocument && mime.startsWith("audio/") -> JSONObject()
            .put("@type", "inputMessageAudio")
            .put("audio", JSONObject().put("@type", "inputAudio").put("audio", input)
                .put("album_cover_thumbnail", JSONObject.NULL).put("duration", 0).put("title", "").put("performer", ""))
            .put("caption", formatted)
        else -> JSONObject().put("@type", "inputMessageDocument")
            .put(
                "document",
                JSONObject().put("@type", "inputDocument").put("document", input)
                    .put("thumbnail", JSONObject.NULL).put("disable_content_type_detection", false),
            )
            .put("caption", formatted)
    }
}

private fun TdLibSession.send(
    callKey: String,
    to: String,
    topic: Long?,
    replyTo: Long?,
    silent: Boolean,
    beforeSubmit: () -> Unit,
    content: JSONObject,
): TelegramMutationOutcome {
    val chatId = try {
        resolveChat(to)
    } catch (error: TelegramConfirmedException) {
        return TelegramMutationOutcome(TelegramMutationState.FAILED, error.message ?: "Telegram chat resolution failed")
    } catch (error: Exception) {
        return TelegramMutationOutcome(TelegramMutationState.FAILED, "Telegram chat could not be resolved")
    }
    val sendingId = (callKey.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    beforeSubmit()
    return try {
        val pending = request(
            JSONObject()
                .put("@type", "sendMessage")
                .put("chat_id", chatId)
                .put("topic_id", topic?.let { JSONObject().put("@type", "messageTopicForum").put("forum_topic_id", it) } ?: JSONObject.NULL)
                .put("reply_to", replyTo?.let {
                    JSONObject().put("@type", "inputMessageReplyToMessage").put("message_id", it)
                        .put("quote", JSONObject.NULL).put("checklist_task_id", 0).put("poll_option_id", "")
                } ?: JSONObject.NULL)
                .put("options", JSONObject().put("@type", "messageSendOptions")
                    .put("suggested_post_info", JSONObject.NULL).put("disable_notification", silent)
                    .put("from_background", false).put("protect_content", false).put("allow_paid_broadcast", false)
                    .put("paid_message_star_count", 0).put("update_order_of_installed_sticker_sets", false)
                    .put("scheduling_state", JSONObject.NULL).put("effect_id", 0).put("sending_id", sendingId).put("only_preview", false))
                .put("reply_markup", JSONObject.NULL)
                .put("input_message_content", content),
            45,
        )
        val pendingId = pending.getLong("id")
        if (pending.isNull("sending_state")) return TelegramMutationOutcome(TelegramMutationState.SUCCEEDED, pending.toString())
        repeat(120) {
            val update = pollUpdate(1) ?: return@repeat
            if (update.optLong("old_message_id") != pendingId) return@repeat
            return when (update.optString("@type")) {
                "updateMessageSendSucceeded" -> TelegramMutationOutcome(
                    TelegramMutationState.SUCCEEDED,
                    update.getJSONObject("message").toString(),
                )
                "updateMessageSendFailed" -> TelegramMutationOutcome(
                    TelegramMutationState.FAILED,
                    update.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Telegram send failed" },
                )
                else -> TelegramMutationOutcome(TelegramMutationState.INDETERMINATE, "Telegram send outcome is indeterminate")
            }
        }
        TelegramMutationOutcome(TelegramMutationState.INDETERMINATE, "Telegram send outcome is indeterminate")
    } catch (error: TelegramConfirmedException) {
        TelegramMutationOutcome(TelegramMutationState.FAILED, error.message ?: "Telegram send failed")
    } catch (error: Exception) {
        TelegramMutationOutcome(TelegramMutationState.INDETERMINATE, "Telegram send outcome is indeterminate")
    }
}

private fun JSONObject.toContact() = TelegramContact(
    id = getLong("id"),
    username = activeUsername(),
    displayName = listOf(optString("first_name"), optString("last_name")).filter(String::isNotBlank).joinToString(" "),
    phoneNumber = optString("phone_number").ifBlank { null },
    isBot = optJSONObject("type")?.optString("@type") == "userTypeBot",
)

private fun JSONObject.activeUsername(): String? = optJSONObject("usernames")
    ?.optJSONArray("active_usernames")
    ?.optString(0)
    ?.ifBlank { null }

private fun username(account: JSONObject): String? = account.activeUsername()

private fun JSONArray.longs(): List<Long> = (0 until length()).map(::getLong)
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

private fun mergeMessages(
    archive: List<TelegramMessage>,
    live: List<TelegramMessage>,
    limit: Int,
): List<TelegramMessage> = (archive.associateBy { it.channelId to it.messageId } + live.associateBy { it.channelId to it.messageId })
    .values.sortedByDescending(TelegramMessage::date).take(limit)

private data class Sender(
    val id: Long?,
    val username: String?,
    val displayName: String?,
    val type: String?,
    val bot: Boolean,
)

private val URL = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
