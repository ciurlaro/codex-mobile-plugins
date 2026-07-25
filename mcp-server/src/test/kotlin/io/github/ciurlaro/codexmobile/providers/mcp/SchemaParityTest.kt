package io.github.ciurlaro.codexmobile.providers.mcp

import java.io.File
import io.github.ciurlaro.codexmobile.providers.documents.DOCUMENTS_SCHEMA_DIGEST
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_SCHEMA_DIGEST
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SchemaParityTest {
    @Test
    fun `mobile add-ons and MCP use the canonical schema digests`() {
        val root = File(checkNotNull(System.getProperty("provider.root")))
        val providerDigests = mapOf(
            "documents" to DOCUMENTS_SCHEMA_DIGEST,
            "telegram" to TELEGRAM_SCHEMA_DIGEST,
        )
        schemaDigests().forEach { (plugin, digest) ->
            val manifest = File(root, ".agents/plugins/plugins/$plugin/codex-mobile-addon.json")
            val declared = Json.parseToJsonElement(manifest.readText()).jsonObject
                .getValue("schemaDigest").jsonPrimitive.content
            assertEquals(digest, providerDigests.getValue(plugin))
            assertEquals(digest, declared)
        }
    }
}
