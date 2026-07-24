package io.github.ciurlaro.codexmobile.providers.mcp

import java.io.Closeable
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject

internal class TelegramConfirmedException(message: String) : IllegalStateException(message)
internal class TelegramIndeterminateException(message: String) : IllegalStateException(message)

internal class TdLibTransport : Closeable {
    private val requestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val updates = LinkedBlockingQueue<JSONObject>()
    private val authorizationStates = LinkedBlockingQueue<JSONObject>()
    private val closed = AtomicBoolean()
    private val clientId = JsonClient.createClientId()

    init {
        TdLibTransportHub.register(clientId, this)
    }

    fun request(request: JSONObject, timeoutSeconds: Long = REQUEST_TIMEOUT_SECONDS): JSONObject {
        check(!closed.get()) { "Telegram client is closed" }
        val id = requestId.getAndIncrement()
        val response = CompletableFuture<JSONObject>()
        check(pending.putIfAbsent(id, response) == null)
        request.put("@extra", id)
        try {
            JsonClient.send(clientId, request.toString())
        } catch (error: Throwable) {
            pending.remove(id)
            throw error
        }
        val result = try {
            response.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            pending.remove(id)
            throw TelegramIndeterminateException("Telegram request outcome is indeterminate")
        }
        if (result.optString("@type") == "error") {
            throw TelegramConfirmedException(result.optString("message").ifBlank { "Telegram request failed" })
        }
        return result
    }

    fun takeAuthorizationState(timeoutSeconds: Long): JSONObject =
        authorizationStates.poll(timeoutSeconds, TimeUnit.SECONDS)
            ?: throw TelegramIndeterminateException("Telegram authentication timed out")

    fun pollUpdate(timeoutSeconds: Long): JSONObject? = updates.poll(timeoutSeconds, TimeUnit.SECONDS)

    internal fun receive(value: JSONObject) {
        val extra = value.optLong("@extra", Long.MIN_VALUE)
        if (extra != Long.MIN_VALUE) {
            pending.remove(extra)?.complete(value)
            return
        }
        if (value.optString("@type") == "updateAuthorizationState") {
            authorizationStates.offer(value.getJSONObject("authorization_state"))
        } else {
            updates.offer(value)
        }
    }

    internal fun fail(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        TdLibTransportHub.unregister(clientId)
        fail(IllegalStateException("Telegram client closed"))
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}

private object TdLibTransportHub {
    private val transports = ConcurrentHashMap<Int, TdLibTransport>()
    private val started = AtomicBoolean()

    fun register(clientId: Int, transport: TdLibTransport) {
        check(transports.putIfAbsent(clientId, transport) == null)
        if (started.compareAndSet(false, true)) {
            Thread(::receiveLoop, "tdlib-receiver").apply { isDaemon = true }.start()
        }
    }

    fun unregister(clientId: Int) {
        transports.remove(clientId)
    }

    private fun receiveLoop() {
        while (true) {
            try {
                val raw = JsonClient.receive(1.0) ?: continue
                val value = JSONObject(raw)
                transports[value.optInt("@client_id", Int.MIN_VALUE)]?.receive(value)
            } catch (error: Throwable) {
                transports.values.forEach { it.fail(error) }
            }
        }
    }
}

internal class TdLibSession(
    directory: File,
    encryptionKey: ByteArray,
    private val apiId: Int,
    private val apiHash: String,
    private val applicationVersion: String,
) : Closeable {
    private val transport = TdLibTransport()
    private val databaseDirectory = File(directory, "database").apply {
        check(isDirectory || mkdirs()) { "Unable to prepare Telegram database" }
    }
    private val filesDirectory = File(directory, "files").apply {
        check(isDirectory || mkdirs()) { "Unable to prepare Telegram files" }
    }
    private val key = Base64.getEncoder().encodeToString(encryptionKey)
    private var state: String? = null

    fun advanceToAction(): String {
        if (state == null) {
            transport.request(JSONObject().put("@type", "getOption").put("name", "version"))
        }
        while (true) {
            val authorization = transport.takeAuthorizationState(AUTH_TIMEOUT_SECONDS)
            state = authorization.getString("@type")
            when (state) {
                "authorizationStateWaitTdlibParameters" -> transport.request(tdlibParameters())
                "authorizationStateWaitPhoneNumber",
                "authorizationStateWaitCode",
                "authorizationStateWaitPassword",
                "authorizationStateReady",
                "authorizationStateClosed" -> return checkNotNull(state)
                "authorizationStateClosing", "authorizationStateLoggingOut" -> Unit
                else -> throw TelegramConfirmedException("This Telegram authentication step is not supported")
            }
        }
    }

    fun submitPhone(phoneNumber: String) {
        require(state == "authorizationStateWaitPhoneNumber") { "Telegram is not waiting for a phone number" }
        transport.request(
            JSONObject()
                .put("@type", "setAuthenticationPhoneNumber")
                .put("phone_number", phoneNumber)
                .put(
                    "settings",
                    JSONObject()
                        .put("@type", "phoneNumberAuthenticationSettings")
                        .put("allow_flash_call", false)
                        .put("allow_missed_call", false)
                        .put("is_current_phone_number", false)
                        .put("has_unknown_phone_number", false)
                        .put("allow_sms_retriever_api", false)
                        .put("firebase_authentication_settings", JSONObject.NULL)
                        .put("authentication_tokens", org.json.JSONArray()),
                ),
        )
    }

    fun submitCode(code: String) {
        require(state == "authorizationStateWaitCode") { "Telegram is not waiting for a code" }
        transport.request(JSONObject().put("@type", "checkAuthenticationCode").put("code", code))
    }

    fun submitPassword(password: String) {
        require(state == "authorizationStateWaitPassword") { "Telegram is not waiting for a password" }
        transport.request(JSONObject().put("@type", "checkAuthenticationPassword").put("password", password))
    }

    fun request(request: JSONObject, timeoutSeconds: Long = 30): JSONObject = transport.request(request, timeoutSeconds)
    fun pollUpdate(timeoutSeconds: Long): JSONObject? = transport.pollUpdate(timeoutSeconds)

    fun account(): JSONObject = request(JSONObject().put("@type", "getMe"))

    fun logOut(): Boolean = try {
        request(JSONObject().put("@type", "logOut"), LOGOUT_TIMEOUT_SECONDS)
        while (true) {
            when (advanceToAction()) {
                "authorizationStateClosed", "authorizationStateWaitPhoneNumber" -> return true
                else -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    } catch (_: Exception) {
        false
    }

    fun closeAndFlush() {
        runCatching {
            transport.request(JSONObject().put("@type", "close"), CLOSE_TIMEOUT_SECONDS)
            while (transport.takeAuthorizationState(CLOSE_TIMEOUT_SECONDS).optString("@type") != "authorizationStateClosed") Unit
        }
        transport.close()
    }

    override fun close() = closeAndFlush()

    private fun tdlibParameters() = JSONObject()
        .put("@type", "setTdlibParameters")
        .put("use_test_dc", false)
        .put("database_directory", databaseDirectory.absolutePath)
        .put("files_directory", filesDirectory.absolutePath)
        .put("database_encryption_key", key)
        .put("use_file_database", true)
        .put("use_chat_info_database", true)
        .put("use_message_database", true)
        .put("use_secret_chats", true)
        .put("api_id", apiId)
        .put("api_hash", apiHash)
        .put("system_language_code", "en")
        .put("device_model", (System.getProperty("os.name") ?: "Linux").take(64))
        .put("system_version", (System.getProperty("os.version") ?: "unknown").take(32))
        .put("application_version", applicationVersion.take(64))

    private companion object {
        const val AUTH_TIMEOUT_SECONDS = 45L
        const val LOGOUT_TIMEOUT_SECONDS = 30L
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}
