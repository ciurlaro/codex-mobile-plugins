package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.os.Build
import io.github.ciurlaro.codexmobile.providers.telegram.BuildConfig
import android.util.AtomicFile
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecrets
import io.github.ciurlaro.codexmobile.providers.telegram.TelegramAuthority
import io.github.ciurlaro.codexmobile.providers.telegram.TelegramClientInfo
import io.github.ciurlaro.codexmobile.providers.telegram.TelegramCredentials
import io.github.ciurlaro.codexmobile.providers.telegram.TelegramIntegration
import io.github.ciurlaro.codexmobile.providers.telegram.TelegramSessionStorage
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_HASH_SECRET
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_ID_SECRET
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

internal class AndroidTelegramSessionStorage(context: Context) : TelegramSessionStorage {
    private val root = File(context.noBackupFilesDir, "telegram-client")
    private val sessions = File(root, "sessions")
    private val staging = File(root, "staging")
    private val authorityFile = AtomicFile(File(root, "authority.json"))
    private val revocationWarningFile = AtomicFile(File(root, "remote-revocation-unconfirmed"))
    private val unmanagedSessionDirectory = File(context.noBackupFilesDir, "telegram")

    init {
        check(root.isDirectory || root.mkdirs()) { "Unable to prepare Telegram storage" }
        check(sessions.isDirectory || sessions.mkdirs()) { "Unable to prepare Telegram sessions" }
        check(staging.isDirectory || staging.mkdirs()) { "Unable to prepare Telegram authentication" }
    }

    @Synchronized
    override fun authority(): TelegramAuthority? {
        if (!authorityFile.baseFile.isFile) return null
        val json = try {
            authorityFile.openRead().bufferedReader().use { JSONObject(it.readText()) }
        } catch (error: Exception) {
            throw IllegalStateException("Telegram session authority is unreadable", error)
        }
        require(json.length() == 4 && json.getInt("schemaVersion") == SCHEMA_VERSION) {
            "Telegram session authority is invalid"
        }
        require(json.getString("tdlibCommit") == BuildConfig.TDLIB_COMMIT) {
            "Telegram session authority uses an unsupported client version"
        }
        val sessionId = json.getString("sessionId")
        require(UUID.fromString(sessionId).toString() == sessionId) { "Telegram session authority is invalid" }
        val key = runCatching { Base64.getDecoder().decode(json.getString("encryptionKey")) }
            .getOrElse { throw IllegalStateException("Telegram session authority is invalid", it) }
        require(key.size == KEY_BYTES) { "Telegram session authority is invalid" }
        return TelegramAuthority(sessionId, key)
    }

    override fun sessionDirectory(authority: TelegramAuthority): File = File(sessions, authority.sessionId).also {
        require(it.isDirectory && it.canonicalFile.parentFile == sessions.canonicalFile) {
            "Authoritative Telegram session is missing"
        }
    }

    @Synchronized
    override fun createStaging(): Pair<TelegramAuthority, File> {
        val authority = TelegramAuthority(
            UUID.randomUUID().toString(),
            ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes),
        )
        val directory = File(staging, authority.sessionId)
        check(directory.mkdir()) { "Unable to prepare Telegram authentication" }
        return authority to directory
    }

    @Synchronized
    override fun promote(authority: TelegramAuthority, staged: File): File {
        require(staged.isDirectory && staged.canonicalFile.parentFile == staging.canonicalFile)
        val target = File(sessions, authority.sessionId)
        require(!target.exists()) { "Telegram session already exists" }
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    @Synchronized
    override fun activate(authority: TelegramAuthority) {
        require(File(sessions, authority.sessionId).isDirectory) { "Telegram session was not verified" }
        writeAuthority(authority)
    }

    @Synchronized
    override fun cleanup(authority: TelegramAuthority) {
        sessions.listFiles().orEmpty().filter { it.name != authority.sessionId }.forEach(File::deleteRecursively)
        staging.listFiles().orEmpty().forEach(File::deleteRecursively)
        if (unmanagedSessionDirectory.exists()) rememberUnconfirmedRemoteAuthorization()
        unmanagedSessionDirectory.deleteRecursively()
    }

    @Synchronized
    override fun clearAll(preserveRevocationWarning: Boolean): Boolean {
        authorityFile.delete()
        val sessionsRemoved = sessions.deleteRecursively()
        val stagingRemoved = staging.deleteRecursively()
        val unmanagedRemoved = unmanagedSessionDirectory.deleteRecursively()
        if (!preserveRevocationWarning) revocationWarningFile.delete()
        sessions.mkdirs()
        staging.mkdirs()
        return sessionsRemoved && stagingRemoved && unmanagedRemoved
    }

    override fun remoteRevocationUnconfirmed(): Boolean =
        revocationWarningFile.baseFile.isFile || unmanagedSessionDirectory.exists()

    @Synchronized
    override fun rememberUnconfirmedRemoteAuthorization() {
        val output = revocationWarningFile.startWrite()
        try {
            output.write(byteArrayOf(1))
            output.fd.sync()
            revocationWarningFile.finishWrite(output)
        } catch (error: Exception) {
            revocationWarningFile.failWrite(output)
            throw error
        }
    }

    override fun discardStaging(directory: File) {
        if (directory.canonicalFile.parentFile == staging.canonicalFile) directory.deleteRecursively()
    }

    private fun writeAuthority(authority: TelegramAuthority) {
        val bytes = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("sessionId", authority.sessionId)
            .put("encryptionKey", Base64.getEncoder().encodeToString(authority.encryptionKey))
            .put("tdlibCommit", BuildConfig.TDLIB_COMMIT)
            .toString()
            .toByteArray()
        val output = authorityFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            authorityFile.finishWrite(output)
        } catch (error: Exception) {
            authorityFile.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_BYTES = 32
    }
}

internal fun androidTelegramIntegration(context: Context, credentials: TelegramCredentials) = TelegramIntegration(
    AndroidTelegramSessionStorage(context),
    credentials,
    TelegramClientInfo(
        Build.MODEL,
        Build.VERSION.RELEASE,
        "Codex Mobile TDLib ${BuildConfig.TDLIB_VERSION}",
    ),
)

internal fun telegramCredentials(secrets: ProviderSecrets) = TelegramCredentials(
    secrets.get(TELEGRAM_API_ID_SECRET)?.toIntOrNull(),
    secrets.get(TELEGRAM_API_HASH_SECRET).orEmpty(),
)
