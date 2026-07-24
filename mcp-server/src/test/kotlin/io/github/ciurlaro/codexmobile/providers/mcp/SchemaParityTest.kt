package io.github.ciurlaro.codexmobile.providers.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SchemaParityTest {
    @Test
    fun `mobile add-ons and MCP use the canonical schema digests`() {
        val root = File(checkNotNull(System.getProperty("provider.root")))
        schemaDigests().forEach { (plugin, digest) ->
            val manifest = File(root, ".agents/plugins/plugins/$plugin/codex-mobile-addon.json")
            val declared = Json.parseToJsonElement(manifest.readText()).jsonObject
                .getValue("schemaDigest").jsonPrimitive.content
            assertEquals(digest, declared)
        }
    }
}
