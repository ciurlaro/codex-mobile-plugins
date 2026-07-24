package io.github.ciurlaro.codexmobile.providers.documents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DocumentTool(
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutation: Boolean = false,
)

const val DOCUMENTS_PLUGIN_ID = "documents@codex-mobile"

val documentsTools = listOf(
    DocumentTool(DOCUMENTS_PLUGIN_ID, "documents_read", "Read bounded PDF, image, or Office content semantically.", documentsReadSchema()),
    DocumentTool(DOCUMENTS_PLUGIN_ID, "documents_view_pages", "Render explicitly selected PDF pages or return a selected image.", documentsViewSchema()),
    DocumentTool(DOCUMENTS_PLUGIN_ID, "documents_edit", "Create or transactionally edit an Office document with closed operations.", documentsEditSchema(), mutation = true),
)

private fun documentsReadSchema() = objectSchema(
    linkedMapOf(
        "path" to stringSchema(maxLength = 4_096),
        "mode" to enumSchema("auto", "native", "ocr"),
        "request" to enumSchema("text", "outline", "stats", "issues", "element"),
        "selector" to stringSchema(maxLength = 512),
        "pageStart" to integerSchema(1, 100_000),
        "pageCount" to integerSchema(1, 20),
        "maxChars" to integerSchema(1, 200_000),
        "cursor" to stringSchema(maxLength = 512),
    ),
    listOf("path"),
)

private fun documentsViewSchema() = objectSchema(
    linkedMapOf(
        "path" to stringSchema(maxLength = 4_096),
        "pages" to buildJsonObject {
            put("type", "array")
            put("items", integerSchema(1, 100_000))
            put("minItems", 1)
            put("maxItems", 4)
            put("uniqueItems", true)
        },
        "dpi" to integerSchema(72, 160),
    ),
    listOf("path", "pages"),
)

private fun documentsEditSchema() = objectSchema(
    linkedMapOf(
        "path" to stringSchema(maxLength = 4_096),
        "create" to booleanSchema(),
        "overwrite" to booleanSchema(),
        "expectedSha256" to stringSchema("^[a-f0-9]{64}$", 64),
        "operations" to buildJsonObject {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 50)
            put("items", buildJsonObject {
                put("oneOf", buildJsonArray {
                    add(editOperation("replace_text", linkedMapOf(
                        "elementPath" to stringSchema(maxLength = 512),
                        "oldText" to stringSchema(maxLength = 20_000),
                        "newText" to stringSchema(maxLength = 20_000),
                    ), listOf("elementPath", "oldText", "newText")))
                    add(editOperation("cell_update", linkedMapOf(
                        "sheet" to stringSchema(maxLength = 128),
                        "cell" to stringSchema("^[A-Z]{1,3}[1-9][0-9]{0,6}$", 10),
                        "value" to scalarSchema(),
                    ), listOf("sheet", "cell", "value")))
                    add(editOperation("append_paragraph", linkedMapOf(
                        "parentPath" to stringSchema(maxLength = 512),
                        "text" to stringSchema(maxLength = 20_000),
                    ), listOf("text")))
                    add(editOperation("add_slide", linkedMapOf(
                        "title" to stringSchema(maxLength = 500),
                        "body" to stringSchema(maxLength = 20_000),
                    ), emptyList()))
                    add(editOperation("remove_element", linkedMapOf(
                        "elementPath" to stringSchema(maxLength = 512),
                    ), listOf("elementPath")))
                })
            })
        },
    ),
    listOf("path", "operations"),
)

private fun editOperation(type: String, properties: LinkedHashMap<String, JsonObject>, required: List<String>) =
    objectSchema(linkedMapOf("type" to constSchema(type)).apply { putAll(properties) }, listOf("type") + required)

private fun objectSchema(properties: LinkedHashMap<String, JsonObject>, required: List<String> = emptyList()) =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }

private fun stringSchema(pattern: String? = null, maxLength: Int) = buildJsonObject {
    put("type", "string")
    put("maxLength", maxLength)
    pattern?.let { put("pattern", it) }
}
private fun integerSchema(minimum: Long, maximum: Long) = buildJsonObject {
    put("type", "integer"); put("minimum", minimum); put("maximum", maximum)
}
private fun booleanSchema() = buildJsonObject { put("type", "boolean") }
private fun enumSchema(vararg values: String) = buildJsonObject {
    put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
}
private fun constSchema(value: String) = buildJsonObject { put("type", "string"); put("const", value) }
private fun scalarSchema() = buildJsonObject {
    put("oneOf", buildJsonArray {
        listOf("string", "number", "boolean").forEach { type -> add(buildJsonObject { put("type", type) }) }
    })
}
