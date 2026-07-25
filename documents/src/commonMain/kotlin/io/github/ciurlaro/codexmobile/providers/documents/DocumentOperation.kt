package io.github.ciurlaro.codexmobile.providers.documents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DocumentOperation(
    val type: String,
    val path: String = "",
    val oldText: String = "",
    val newText: String = "",
    val sheet: String = "",
    val cell: String = "",
    val text: String = "",
    val title: String? = null,
    val body: String? = null,
    val scalarType: String = "null",
    val scalarText: String = "",
    val scalarNumber: Double = 0.0,
    val scalarBoolean: Boolean = false,
)

fun parseDocumentOperations(raw: JsonArray): List<DocumentOperation> {
    require(raw.isNotEmpty() && raw.size <= MAX_DOCUMENT_OPERATIONS) {
        "operations must contain 1-$MAX_DOCUMENT_OPERATIONS entries"
    }
    return raw.map { element ->
        val operation = element.jsonObject
        when (operation.string("type")) {
            "replace_text" -> {
                operation.requireOnly("type", "elementPath", "oldText", "newText")
                DocumentOperation(
                    type = "replace_text",
                    path = documentElementPath(operation.string("elementPath")),
                    oldText = operation.string("oldText").also { require(it.isNotEmpty()) { "oldText must not be empty" } },
                    newText = operation.string("newText"),
                )
            }
            "cell_update" -> {
                operation.requireOnly("type", "sheet", "cell", "value")
                val value = operation.getValue("value")
                require(value is JsonPrimitive) { "Spreadsheet values must be scalar" }
                val sheet = operation.string("sheet").also {
                    require(DOCUMENT_SHEET_NAME.matches(it)) { "Invalid sheet name" }
                }
                val cell = operation.string("cell").also {
                    require(DOCUMENT_CELL.matches(it)) { "Invalid cell address" }
                }
                DocumentOperation(
                    type = "cell_update",
                    sheet = sheet,
                    cell = cell,
                    scalarType = when {
                        value.isString -> "text"
                        value.booleanOrNull != null -> "boolean"
                        value.doubleOrNull != null -> "number"
                        else -> error("Spreadsheet values must be scalar")
                    },
                    scalarText = value.contentOrNull.orEmpty(),
                    scalarNumber = value.doubleOrNull ?: 0.0,
                    scalarBoolean = value.booleanOrNull ?: false,
                )
            }
            "append_paragraph" -> {
                operation.requireOnly("type", "parentPath", "text")
                DocumentOperation(
                    type = "append_paragraph",
                    path = documentElementPath(operation.string("parentPath", "/body")),
                    text = operation.string("text"),
                )
            }
            "add_slide" -> {
                operation.requireOnly("type", "title", "body")
                DocumentOperation(
                    type = "add_slide",
                    title = operation.stringOrNull("title"),
                    body = operation.stringOrNull("body"),
                )
            }
            "remove_element" -> {
                operation.requireOnly("type", "elementPath")
                DocumentOperation(
                    type = "remove_element",
                    path = documentElementPath(operation.string("elementPath")),
                )
            }
            else -> error("Unsupported document edit operation")
        }
    }
}

const val MAX_DOCUMENT_OPERATIONS = 50
const val MAX_DOCUMENT_SHEET_NAME_LENGTH = 31
const val DOCUMENT_SHEET_NAME_PATTERN = "[^\\[\\]:*?/\\\\]{1,31}"
val DOCUMENT_CELL = Regex("^[A-Z]{1,3}[1-9][0-9]{0,6}$")
val DOCUMENT_SHEET_NAME = Regex("^$DOCUMENT_SHEET_NAME_PATTERN$")

private fun documentElementPath(value: String) = value.also {
    require(it.startsWith('/') && it.length <= 512) { "Invalid Office element path" }
}

private fun JsonObject.requireOnly(vararg allowed: String) = apply {
    require(keys.all { it in allowed }) { "Unexpected tool argument" }
}

private fun JsonObject.string(name: String, default: String? = null): String =
    stringOrNull(name) ?: default ?: error("Missing $name")

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
