package io.github.ciurlaro.codexmobile.providers.documents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
const val MAX_DOCUMENT_EXTRACTED_BYTES = 2 * 1024 * 1024
const val MAX_DOCUMENT_INLINE_IMAGE_BYTES = 2L * 1024 * 1024
const val MAX_DOCUMENT_TEXT_CHARS = 200_000
const val MAX_DOCUMENT_READ_PAGES = 20
const val MAX_DOCUMENT_VIEW_PAGES = 4
const val MAX_DOCUMENT_CURSOR_LENGTH = 512

val DOCUMENT_MODES = setOf("auto", "native", "ocr")
val DOCUMENT_REQUESTS = setOf("text", "outline", "stats", "issues", "element")
val DOCUMENT_OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx")
val DOCUMENT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
val DOCUMENT_SNAPSHOT_NAME = Regex("^[a-f0-9]{64}\\.txt$")

sealed interface DocumentRequest {
    data class Read(
        val path: String,
        val mode: String,
        val semanticRequest: String,
        val selector: String?,
        val firstPage: Int,
        val pageCount: Int,
        val maxChars: Int,
        val cursor: String?,
    ) : DocumentRequest

    data class ViewPages(val path: String, val pages: List<Int>, val dpi: Int) : DocumentRequest

    data class Edit(
        val path: String,
        val create: Boolean,
        val overwrite: Boolean,
        val expectedSha256: String?,
        val operations: List<DocumentOperation>,
    ) : DocumentRequest
}

fun parseDocumentRequest(tool: String, args: JsonObject): DocumentRequest = when (tool) {
    "documents_read" -> {
        args.requireOnly("path", "mode", "request", "selector", "pageStart", "pageCount", "maxChars", "cursor")
        val mode = args.string("mode", "auto").also { require(it in DOCUMENT_MODES) { "Invalid read mode" } }
        val request = args.string("request", "text").also {
            require(it in DOCUMENT_REQUESTS) { "Invalid semantic request" }
        }
        val selector = args.stringOrNull("selector")
        if (request == "element") require(!selector.isNullOrBlank()) { "selector is required for element reads" }
        DocumentRequest.Read(
            args.string("path"),
            mode,
            request,
            selector,
            args.int("pageStart", 1, 1, 100_000),
            args.int("pageCount", 10, 1, MAX_DOCUMENT_READ_PAGES),
            args.int("maxChars", 48_000, 1, MAX_DOCUMENT_TEXT_CHARS),
            args.stringOrNull("cursor"),
        )
    }
    "documents_view_pages" -> {
        args.requireOnly("path", "pages", "dpi")
        val pages = args.array("pages").map { it.jsonPrimitive.intOrNull ?: error("Page must be an integer") }
        require(
            pages.isNotEmpty() && pages.size <= MAX_DOCUMENT_VIEW_PAGES &&
                pages.all { it > 0 } && pages.distinct() == pages,
        ) { "pages must contain 1-$MAX_DOCUMENT_VIEW_PAGES unique positive integers" }
        DocumentRequest.ViewPages(args.string("path"), pages, args.int("dpi", 120, 72, 160))
    }
    "documents_edit" -> {
        args.requireOnly("path", "create", "overwrite", "expectedSha256", "operations")
        DocumentRequest.Edit(
            args.string("path"),
            args.boolean("create", false),
            args.boolean("overwrite", false),
            args.stringOrNull("expectedSha256"),
            parseDocumentOperations(args.array("operations")),
        )
    }
    else -> error("Unknown Documents tool")
}

fun DocumentRequest.Edit.validateTarget(exists: Boolean, beforeHash: String?) {
    if (exists) {
        require(overwrite) { "overwrite=true is required for an existing document" }
        require(expectedSha256 == beforeHash) { "Document changed since it was read" }
    } else {
        require(create) { "create=true is required for a new document" }
        require(expectedSha256 == null) { "expectedSha256 is only valid for overwrite" }
    }
}

data class DocumentTextPage(val text: String, val totalChars: Int, val nextOffset: Int?)

fun documentTextPage(value: String, offset: Int, maxChars: Int): DocumentTextPage {
    require(offset in 0..value.length) { "Snapshot cursor offset is invalid" }
    val end = minOf(value.length, offset + maxChars)
    return DocumentTextPage(value.substring(offset, end), value.length, end.takeIf { it < value.length })
}

fun DocumentTextPage.jsonString(cursor: String?): String = buildJsonObject {
    put("text", text)
    put("totalChars", totalChars)
    cursor?.let { put("cursor", it) }
}.toString()

fun documentEditJson(path: String, sha256: String, operationCount: Int): String = buildJsonObject {
    put("path", path)
    put("sha256", sha256)
    put("operations", operationCount)
}.toString()

fun parseDocumentCursor(cursor: String, expectedName: String, decode: (String) -> String): Int {
    require(cursor.length <= MAX_DOCUMENT_CURSOR_LENGTH) { "Snapshot cursor is too long" }
    val decoded = runCatching { decode(cursor) }.getOrElse { error("Snapshot cursor is invalid") }
    val name = decoded.substringBefore(':')
    val offset = decoded.substringAfter(':', "").toIntOrNull() ?: error("Snapshot cursor is invalid")
    require(name == expectedName && DOCUMENT_SNAPSHOT_NAME.matches(name)) { "Snapshot cursor is invalid" }
    return offset
}

fun documentCursor(name: String, offset: Int, encode: (String) -> String): String = encode("$name:$offset")

private fun JsonObject.requireOnly(vararg names: String) {
    require(keys.all { it in names }) { "Unexpected tool argument" }
}

private fun JsonObject.string(name: String, default: String? = null): String =
    stringOrNull(name) ?: default ?: error("Missing $name")

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String, default: Int, minimum: Int, maximum: Int): Int =
    (get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull ?: default).also {
        require(it in minimum..maximum) { "$name is out of range" }
    }

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.array(name: String): JsonArray = get(name)?.jsonArray ?: error("Missing $name")
