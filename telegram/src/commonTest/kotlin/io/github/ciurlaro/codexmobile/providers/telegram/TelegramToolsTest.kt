package io.github.ciurlaro.codexmobile.providers.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class TelegramToolsTest {
    @Test
    fun `portable schemas expose only the stable closed tool set`() {
        val tools = telegramTools.map { it.name to it.inputSchema }
        assertEquals(
            listOf(
                "telegram_list_chats", "telegram_list_messages", "telegram_search_messages",
                "telegram_search_contacts", "telegram_download_media", "telegram_send_text", "telegram_send_file",
            ),
            tools.map { it.first },
        )
        tools.forEach { assertEquals(false, it.second["additionalProperties"]?.jsonPrimitive?.content?.toBoolean()) }
        assertFalse(Regex("\"(command|subcommand|argv|rawArguments)\"").containsMatchIn(tools.toString()))
    }

    @Test
    fun `portable parser and result semantics are shared by both providers`() {
        val request = parseTelegramRequest("telegram_send_text", buildJsonObject {
            put("to", "@codex")
            put("message", "hello")
            put("parseMode", "markdown")
            put("silent", true)
        }, "stable-call") as TelegramRequest.SendText

        assertEquals("stable-call", request.value.callKey)
        assertEquals("markdown", request.value.parseMode)
        assertEquals(true, request.value.silent)
        assertFailsWith<IllegalArgumentException> {
            parseTelegramRequest("telegram_list_chats", buildJsonObject { put("limit", 51) }, "call")
        }
        assertEquals(
            "Telegram send outcome is indeterminate; inspect Telegram before deciding what to do next.",
            TelegramMutationOutcome(TelegramMutationState.INDETERMINATE, "ignored").result("Telegram send").message,
        )
    }
}
