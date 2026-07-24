package io.github.ciurlaro.codexmobile.providers.mcp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

internal data class TelegramAuthority(
    val sessionId: String,
    val encryptionKey: ByteArray,
)

internal class TelegramSessionStore(private val root: File) {
    private val sessions = File(root, "sessions")
    private val staging = File(root, "staging")
    private val authorityFile = File(root, "authority.json")

    init {
        check(root.isDirectory || root.mkdirs()) { "Unable to prepare Telegram storage" }
        check(sessions.isDirectory || sessions.mkdirs()) { "Unable to prepare Telegram sessions" }
        check(staging.isDirectory || staging.mkdirs()) { "Unable to prepare Telegram authentication" }
    }

    @Synchronized
    fun authority(): TelegramAuthority? {
        if (!authorityFile.isFile) return null
        val json = try {
            JSONObject(authorityFile.readText())
        } catch (error: Exception) {
            throw IllegalStateException("Telegram session authority is unreadable", error)
        }
        require(json.length() == 4 && json.getInt("schemaVersion") == SCHEMA_VERSION) {
            "Telegram session authority is invalid"
        }
        require(json.getString("tdlibCommit") == TDLIB_COMMIT) {
            "Telegram session authority uses an unsupported client version"
        }
        val sessionId = json.getString("sessionId")
        require(UUID.fromString(sessionId).toString() == sessionId) { "Telegram session authority is invalid" }
        val key = runCatching { Base64.getDecoder().decode(json.getString("encryptionKey")) }
            .getOrElse { throw IllegalStateException("Telegram session authority is invalid", it) }
        require(key.size == KEY_BYTES) { "Telegram session authority is invalid" }
        return TelegramAuthority(sessionId, key)
    }

    fun sessionDirectory(authority: TelegramAuthority): File = File(sessions, authority.sessionId).also {
        require(it.isDirectory && it.canonicalFile.parentFile == sessions.canonicalFile) {
            "Authoritative Telegram session is missing"
        }
    }

    @Synchronized
    fun createStaging(): Pair<TelegramAuthority, File> {
        val authority = TelegramAuthority(UUID.randomUUID().toString(), ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes))
        val directory = File(staging, authority.sessionId)
        check(directory.mkdir()) { "Unable to prepare Telegram authentication" }
        return authority to directory
    }

    @Synchronized
    fun promote(authority: TelegramAuthority, staged: File): File {
        require(staged.isDirectory && staged.canonicalFile.parentFile == staging.canonicalFile)
        val target = File(sessions, authority.sessionId)
        require(!target.exists()) { "Telegram session already exists" }
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    @Synchronized
    fun activate(authority: TelegramAuthority) {
        require(File(sessions, authority.sessionId).isDirectory) { "Telegram session was not verified" }
        val bytes = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("sessionId", authority.sessionId)
            .put("encryptionKey", Base64.getEncoder().encodeToString(authority.encryptionKey))
            .put("tdlibCommit", TDLIB_COMMIT)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val next = File(root, ".authority.${UUID.randomUUID()}.next")
        next.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(next.toPath(), authorityFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    fun cleanup(authority: TelegramAuthority) {
        sessions.listFiles().orEmpty().filter { it.name != authority.sessionId }.forEach(File::deleteRecursively)
        staging.listFiles().orEmpty().forEach(File::deleteRecursively)
    }

    @Synchronized
    fun clearAll(): Boolean {
        val authorityRemoved = !authorityFile.exists() || authorityFile.delete()
        val sessionsRemoved = sessions.deleteRecursively()
        val stagingRemoved = staging.deleteRecursively()
        sessions.mkdirs()
        staging.mkdirs()
        return authorityRemoved && sessionsRemoved && stagingRemoved
    }

    fun discardStaging(directory: File) {
        if (directory.canonicalFile.parentFile == staging.canonicalFile) directory.deleteRecursively()
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_BYTES = 32
        const val TDLIB_COMMIT = "022d60202e446ad1287b9fb68e687c8a0760788b"
    }
}
