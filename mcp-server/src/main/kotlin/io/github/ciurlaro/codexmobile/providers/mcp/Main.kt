package io.github.ciurlaro.codexmobile.providers.mcp

import io.github.ciurlaro.codexmobile.providers.documents.documentsTools
import io.github.ciurlaro.codexmobile.providers.telegram.telegramTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class McpTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
)

internal interface McpBackend : AutoCloseable {
    suspend fun execute(tool: String, arguments: JsonObject): McpResult
    override fun close() = Unit
}

internal data class McpResult(
    val content: List<McpContent>,
    val success: Boolean = true,
) {
    companion object {
        fun text(value: String, success: Boolean = true) = McpResult(listOf(McpContent.Text(value)), success)
    }
}

internal sealed interface McpContent {
    data class Text(val value: String) : McpContent
    data class Image(val bytes: ByteArray, val mimeType: String) : McpContent
}

fun main(args: Array<String>) {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    runBlocking { runProvider(args) }
}

private suspend fun runProvider(args: Array<String>) {
    val provider = args.singleOrNull() ?: System.getenv("CODEX_PROVIDER")
        ?: error("Provider must be documents or telegram")
    when (provider) {
        "schema-digests" -> return printSchemaDigests()
        "telegram-auth" -> return telegramAuthenticate()
        "telegram-status" -> return telegramStatus()
        "telegram-disconnect" -> return telegramDisconnect()
    }
    val (tools, backend) = when (provider) {
        "documents" -> documentsTools.map { McpTool(it.name, it.description, it.inputSchema) } to DocumentsMcpBackend()
        "telegram" -> telegramTools.map { McpTool(it.name, it.description, it.inputSchema) } to TelegramMcpBackend()
        else -> error("Unknown provider: $provider")
    }
    backend.use {
        val server = Server(
            serverInfo = Implementation("codex-mobile-$provider", "1.0.0"),
            options = ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        tools.forEach { tool ->
            val required = tool.schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = ToolSchema(
                    properties = tool.schema.getValue("properties").jsonObject,
                    required = required,
                ),
            ) { request ->
                val result = runCatching {
                    withContext(Dispatchers.IO) { backend.execute(tool.name, request.arguments ?: JsonObject(emptyMap())) }
                }.getOrElse { error -> McpResult.text(error.message ?: "Provider operation failed", success = false) }
                CallToolResult(
                    content = result.content.map(McpContent::protocolContent),
                    isError = !result.success,
                )
            }
        }

        val closed = CompletableDeferred<Unit>()
        val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered()) {}
        transport.onClose { closed.complete(Unit) }
        val session = server.createSession(transport)
        session.onClose { closed.complete(Unit) }
        closed.await()
        server.close()
    }
}

private fun printSchemaDigests() {
    schemaDigests().forEach { (provider, digest) -> println("$provider=$digest") }
}

internal fun schemaDigests() = linkedMapOf(
    "documents" to schemaDigest(documentsTools.map { DigestTool(it.pluginId, it.name, it.description, it.inputSchema, it.mutation) }),
    "telegram" to schemaDigest(telegramTools.map { DigestTool(it.pluginId, it.name, it.description, it.inputSchema, it.mutation) }),
)

private data class DigestTool(
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutation: Boolean,
)

private fun schemaDigest(tools: List<DigestTool>): String {
    val value = tools.sortedBy(DigestTool::name).joinToString("\n") { tool ->
        canonicalJson(buildJsonObject {
            put("pluginId", JsonPrimitive(tool.pluginId))
            put("name", JsonPrimitive(tool.name))
            put("description", JsonPrimitive(tool.description))
            put("inputSchema", tool.inputSchema)
            put("mutation", JsonPrimitive(tool.mutation))
        })
    }
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${JsonPrimitive(key)}:${canonicalJson(item)}"
        }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
    else -> value.toString()
}

private fun telegramAuthenticate() {
    val telegram = TelegramIntegration(telegramStateDirectory())
    try {
        check(telegram.available) { "Set TELEGRAM_API_ID and TELEGRAM_API_HASH" }
        if (telegram.status().connected) {
            println("Telegram is already connected.")
            return
        }
        print("Phone number (international format): ")
        System.out.flush()
        val phone = readlnOrNull()?.trim().orEmpty()
        telegram.startAuthentication(phone).use { session ->
            while (true) {
                when (val event = session.awaitEvent()) {
                    is TelegramAuthEvent.Prompt -> {
                        print(if (event.prompt == TelegramAuthPrompt.CODE) "Telegram code: " else "2FA password: ")
                        System.out.flush()
                        session.submitAnswer(readlnOrNull().orEmpty())
                    }
                    is TelegramAuthEvent.Connected -> {
                        println("Connected${event.username?.let { " as @$it" }.orEmpty()}.")
                        return
                    }
                    is TelegramAuthEvent.Failed -> error(event.message)
                }
            }
        }
    } finally {
        telegram.close()
    }
}

private fun telegramStatus() {
    val telegram = TelegramIntegration(telegramStateDirectory())
    try {
        val status = telegram.status()
        println(when {
            !status.available -> "Telegram API credentials are not configured."
            status.connected -> "Connected${status.username?.let { " as @$it" }.orEmpty()}."
            else -> "Authentication is required."
        })
    } finally {
        telegram.close()
    }
}

private fun telegramDisconnect() {
    val telegram = TelegramIntegration(telegramStateDirectory())
    try {
        when (telegram.disconnect()) {
            TelegramDisconnectResult.CONFIRMED -> println("Remote logout and local cleanup completed.")
            TelegramDisconnectResult.INDETERMINATE -> error(
                "Local authority was removed, but remote logout or cleanup could not be confirmed.",
            )
        }
    } finally {
        telegram.close()
    }
}

private fun McpContent.protocolContent(): ContentBlock = when (this) {
    is McpContent.Text -> TextContent(value)
    is McpContent.Image -> ImageContent(java.util.Base64.getEncoder().encodeToString(bytes), mimeType)
}
