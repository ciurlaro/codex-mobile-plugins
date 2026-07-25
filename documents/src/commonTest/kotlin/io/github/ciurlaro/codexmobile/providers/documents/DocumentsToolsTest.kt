package io.github.ciurlaro.codexmobile.providers.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DocumentsToolsTest {
    @Test
    fun `portable schemas expose only the stable closed tool set`() {
        val tools = documentsTools.map { it.name to it.inputSchema }
        assertEquals(
            listOf(
                "documents_read", "documents_view_pages", "documents_edit",
            ),
            tools.map { it.first },
        )
        tools.forEach { assertEquals(false, it.second["additionalProperties"]?.jsonPrimitive?.content?.toBoolean()) }
        assertFalse(Regex("\"(command|subcommand|argv|rawArguments)\"").containsMatchIn(tools.toString()))
    }

    @Test
    fun `edit operations use one portable parser`() {
        val operations = parseDocumentOperations(buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("cell_update"))
                put("sheet", JsonPrimitive("Revenue.2026"))
                put("cell", JsonPrimitive("B12"))
                put("value", JsonPrimitive(42))
            })
            add(buildJsonObject {
                put("type", JsonPrimitive("append_paragraph"))
                put("text", JsonPrimitive("Hello"))
            })
        })

        assertEquals("number", operations[0].scalarType)
        assertEquals("/body", operations[1].path)
        assertFailsWith<IllegalArgumentException> {
            parseDocumentOperations(buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("cell_update"))
                    put("sheet", JsonPrimitive("invalid/name"))
                    put("cell", JsonPrimitive("A1"))
                    put("value", JsonPrimitive(true))
                })
            })
        }

        val operationSchema = documentsTools.single { it.name == "documents_edit" }.inputSchema
            .getValue("properties").jsonObject
            .getValue("operations").jsonObject
        assertEquals("50", operationSchema["maxItems"]?.jsonPrimitive?.content)
    }

    @Test
    fun `portable request pagination and cursor rules are shared`() {
        val request = parseDocumentRequest("documents_read", buildJsonObject {
            put("path", "report.pdf")
            put("mode", "ocr")
            put("pageCount", 5)
            put("maxChars", 3)
        }) as DocumentRequest.Read
        assertEquals(5, request.pageCount)
        assertEquals("ocr", request.mode)

        val page = documentTextPage("abcdef", 0, request.maxChars)
        assertEquals("abc", page.text)
        assertEquals(3, page.nextOffset)
        val cursor = documentCursor("${"a".repeat(64)}.txt", 3) { it.reversed() }
        assertEquals(3, parseDocumentCursor(cursor, "${"a".repeat(64)}.txt") { it.reversed() })

        assertFailsWith<IllegalArgumentException> {
            parseDocumentRequest("documents_view_pages", buildJsonObject {
                put("path", "report.pdf")
                put("pages", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1)) })
            })
        }
    }
}
