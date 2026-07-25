package io.github.ciurlaro.codexmobile.providers.mcp

import io.github.ciurlaro.codexmobile.providers.telegram.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject

internal class TelegramMcpBackend : McpBackend {
    private val hostWorkspace = Path.of(System.getenv("CODEX_MCP_HOST_WORKSPACE") ?: "/workspace").toAbsolutePath().normalize()
    private val workspace = Path.of(System.getenv("CODEX_MCP_WORKSPACE") ?: "/workspace").toRealPath()
    private val telegram = mcpTelegramIntegration()

    override suspend fun execute(tool: String, arguments: JsonObject): McpResult =
        when (val request = parseTelegramRequest(tool, arguments, UUID.randomUUID().toString())) {
            is TelegramRequest.ListChats -> listChats(request)
            is TelegramRequest.ListMessages -> listMessages(request)
            is TelegramRequest.SearchMessages -> searchMessages(request)
            is TelegramRequest.SearchContacts -> searchContacts(request)
            is TelegramRequest.DownloadMedia -> downloadMedia(request)
            is TelegramRequest.SendText -> sendText(request.value)
            is TelegramRequest.SendFile -> sendFile(request)
        }

    override fun close() = telegram.close()

    private fun listChats(request: TelegramRequest.ListChats) = McpResult.text(
        telegramChatsJson(telegram.listChats(request.query, request.limit)),
    )

    private fun listMessages(request: TelegramRequest.ListMessages) = McpResult.text(
        telegram.listMessages(
            request.chat, request.limit, request.source, request.beforeId, request.afterId,
        ).jsonString(),
    )

    private fun searchMessages(request: TelegramRequest.SearchMessages) = McpResult.text(
        telegram.searchMessages(
            request.query,
            request.chat,
            request.limit,
            request.source,
            request.after?.let { Instant.parse(it).epochSecond },
            request.before?.let { Instant.parse(it).epochSecond },
        ).jsonString(),
    )

    private fun searchContacts(request: TelegramRequest.SearchContacts) = McpResult.text(
        telegramContactsJson(telegram.searchContacts(request.query, request.limit)),
    )

    private fun downloadMedia(request: TelegramRequest.DownloadMedia): McpResult {
        val destination = resolve(request.outputPath, mustExist = false)
        require(!Files.exists(destination)) { "Download destination already exists" }
        Files.createDirectories(checkNotNull(destination.parent))
        val stage = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.download")
        return try {
            val outcome = telegram.downloadMedia(request.chat, request.messageId, stage.toFile()) {}
            if (outcome.state == TelegramMutationState.SUCCEEDED && Files.size(stage) > 0) {
                val hash = sha256(stage)
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE)
                McpResult.text("Downloaded media to ${request.outputPath} (SHA-256 $hash).")
            } else mutationResult(outcome, "Telegram media download")
        } finally {
            Files.deleteIfExists(stage)
        }
    }

    private fun sendText(request: TelegramTextSend) =
        mutationResult(telegram.sendText(request) {}, "Telegram send")

    private fun sendFile(request: TelegramRequest.SendFile): McpResult {
        val path = request.path
        val file = resolve(path, mustExist = true)
        require(Files.size(file) in 1..MAX_TELEGRAM_FILE_BYTES) { "Telegram file is empty or too large" }
        return mutationResult(telegram.sendFile(
            TelegramFileSend(
                callKey = request.callKey,
                to = request.to,
                file = file.toFile(),
                caption = request.caption,
                parseMode = request.parseMode,
                topic = request.topic,
                replyTo = request.replyTo,
                silent = request.silent,
                forceDocument = request.forceDocument,
            ),
        ) {
            require(resolve(path, mustExist = true) == file) { "Telegram file path changed before dispatch" }
        }, "Telegram send")
    }

    private fun mutationResult(outcome: TelegramMutationOutcome, operation: String): McpResult =
        outcome.result(operation).let { McpResult.text(it.message, it.success) }

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

}

internal fun telegramStateDirectory(): File = Path.of(System.getenv("CODEX_MCP_STATE") ?: "/state")
    .resolve("telegram-client").toFile()

internal fun mcpTelegramIntegration() = TelegramIntegration(
    JvmTelegramSessionStorage(telegramStateDirectory()),
    TelegramCredentials(
        System.getenv("TELEGRAM_API_ID")?.toIntOrNull(),
        System.getenv("TELEGRAM_API_HASH").orEmpty(),
    ),
    TelegramClientInfo(
        System.getProperty("os.name") ?: "Linux",
        System.getProperty("os.version") ?: "unknown",
        "Codex plugin TDLib 1.8.66",
    ),
)
