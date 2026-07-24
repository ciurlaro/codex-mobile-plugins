package io.github.ciurlaro.codexmobile.providers.shared

import io.github.ciurlaro.codexmobile.providers.documents.documentsTools
import io.github.ciurlaro.codexmobile.providers.telegram.telegramTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonPrimitive

class ProviderToolsTest {
    @Test
    fun `portable schemas expose only the stable closed tool set`() {
        val tools = documentsTools.map { it.name to it.inputSchema } +
            telegramTools.map { it.name to it.inputSchema }
        assertEquals(
            listOf(
                "documents_read", "documents_view_pages", "documents_edit",
                "telegram_list_chats", "telegram_list_messages", "telegram_search_messages",
                "telegram_search_contacts", "telegram_download_media", "telegram_send_text", "telegram_send_file",
            ),
            tools.map { it.first },
        )
        tools.forEach { assertEquals(false, it.second["additionalProperties"]?.jsonPrimitive?.content?.toBoolean()) }
        assertFalse(Regex("\"(command|subcommand|argv|rawArguments)\"").containsMatchIn(tools.toString()))
    }
}
