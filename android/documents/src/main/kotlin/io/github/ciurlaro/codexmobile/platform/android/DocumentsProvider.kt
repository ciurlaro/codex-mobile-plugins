package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolDefinition
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolResult
import io.github.ciurlaro.codexmobile.agent.codex.CodexMobileProvider
import io.github.ciurlaro.codexmobile.agent.codex.ProviderDescriptor
import io.github.ciurlaro.codexmobile.agent.codex.ProviderContext
import io.github.ciurlaro.codexmobile.agent.codex.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.providers.documents.DOCUMENTS_PLUGIN_ID
import io.github.ciurlaro.codexmobile.providers.documents.documentsTools
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONObject

class DocumentsProvider(context: Context) : CodexMobileProvider {
    private val appContext = context.applicationContext
    private val documents: DocumentProcessor = AndroidDocumentProcessor(appContext)
    private val snapshots = DocumentSnapshotStore(appContext)
    private val workspace = WorkspaceManager(appContext)
    private val journal = BuiltInMutationJournal(appContext)

    override val descriptor = ProviderDescriptor(
        pluginId = DOCUMENTS_PLUGIN_ID,
        implementationVersion = "1.0.0",
        tools = documentsTools.map { BuiltInToolDefinition(it.pluginId, it.name, it.description, it.inputSchema, it.mutation) },
        providerApi = 1,
        minHostVersionCode = 3,
        maxHostVersionCode = 3,
        displayName = "Documents",
    )

    override suspend fun execute(
        call: BuiltInToolCall,
        context: ProviderContext,
    ): BuiltInToolResult = withContext(Dispatchers.IO) {
        when (call.tool) {
            "documents_read" -> documentsRead(call)
            "documents_view_pages" -> documentsViewPages(call)
            "documents_edit" -> documentEdit(call, context.beforeMutationDispatch)
            else -> error("Unknown Documents tool")
        }
    }

    override suspend fun replay(call: BuiltInToolCall): BuiltInToolResult? = withContext(Dispatchers.IO) {
        if (call.tool != "documents_edit") return@withContext null
        val existing = journal.find(call) ?: return@withContext null
        when (existing.state) {
            MutationState.PREPARED -> null
            MutationState.SUCCEEDED, MutationState.FAILED, MutationState.INDETERMINATE ->
                checkNotNull(existing.result) { "Mutation journal terminal result is missing" }
            MutationState.DISPATCHED -> execute(call, ProviderContext {})
        }
    }

    override suspend fun prepareUninstall(): ProviderRemovalResult = withContext(Dispatchers.IO) {
        check(snapshots.directory.deleteRecursively() || !snapshots.directory.exists()) {
            "Document snapshots could not be removed"
        }
        ProviderRemovalResult.ready()
    }

    private fun documentsRead(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly(
            "path", "mode", "request", "selector", "pageStart", "pageCount", "maxChars", "cursor",
        )
        val maxChars = args.int("maxChars", 48_000, 1, MAX_TEXT_RESULT_CHARS)
        val source = workspace.resolveFile(call.workspace, args.string("path"), mustExist = true)
        require(source.length() in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val snapshot = immutableSnapshot(source)
        val mode = args.string("mode", "auto").also { require(it in MODES) }
        val request = args.string("request", "text").also { require(it in REQUESTS) }
        val selector = args.stringOrNull("selector")
        if (request == "element") require(!selector.isNullOrBlank()) { "selector is required for element reads" }
        val firstPage = args.int("pageStart", 1, 1, 100_000)
        val pageCount = args.int("pageCount", 10, 1, MAX_READ_PAGES)
        val cacheKey = sha256("${snapshot.name}\u0000$mode\u0000$request\u0000$selector\u0000$firstPage\u0000$pageCount")
        val cache = File(snapshots.directory, "$cacheKey.txt")
        args.stringOrNull("cursor")?.let { return continueTextSnapshot(it, cache.name, maxChars) }
        if (!cache.isFile) {
            val extracted = documents.read(
                snapshot,
                DocumentReadOptions(snapshot.extension.lowercase(), mode, request, selector, firstPage, pageCount),
            )
            require(extracted.toByteArray(StandardCharsets.UTF_8).size <= MAX_EXTRACTED_BYTES) {
                "Extracted document content exceeds the snapshot limit"
            }
            writeImmutable(cache, extracted.toByteArray(StandardCharsets.UTF_8))
        }
        return textSnapshotResult(cache, 0, maxChars)
    }

    private fun documentsViewPages(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("path", "pages", "dpi")
        val source = workspace.resolveFile(call.workspace, args.string("path"), mustExist = true)
        require(source.length() in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val pages = args.array("pages").map { it.jsonPrimitive.intOrNull ?: error("Page must be an integer") }
        require(pages.isNotEmpty() && pages.size <= MAX_VIEW_PAGES && pages.all { it > 0 } && pages.distinct() == pages) {
            "pages must contain 1-$MAX_VIEW_PAGES unique positive integers"
        }
        val dpi = args.int("dpi", 120, 72, 160)
        val snapshot = immutableSnapshot(source)
        if (source.extension.lowercase() in IMAGE_EXTENSIONS) {
            require(pages == listOf(1)) { "Images only have page 1" }
            require(snapshot.length() <= MAX_INLINE_IMAGE_BYTES) { "Image is too large to return inline" }
            return BuiltInToolResult(listOf(BuiltInToolContent.Image(dataUrl(snapshot.extension, snapshot.readBytes()))), true)
        }
        require(source.extension.equals("pdf", true)) { "Page rendering is available for PDF and image files" }
        return BuiltInToolResult(
            pages.map { page ->
                val output = File(snapshots.directory, "${snapshot.nameWithoutExtension}-p$page-$dpi.png")
                if (!output.isFile) {
                    val next = File(output.parentFile, ".${output.name}.next")
                    documents.render(snapshot, page, dpi, next)
                    require(next.length() in 1..MAX_INLINE_IMAGE_BYTES) { "Rendered page is too large" }
                    check(next.renameTo(output) || output.isFile) { "Unable to activate rendered page" }
                    next.delete()
                    output.setReadOnly()
                }
                BuiltInToolContent.Image(dataUrl("png", output.readBytes()))
            },
            true,
        )
    }

    private suspend fun documentEdit(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit,
    ): BuiltInToolResult {
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        val args = call.arguments.requireOnly("path", "create", "overwrite", "expectedSha256", "operations")
        val destination = workspace.resolveFile(call.workspace, args.string("path"), mustExist = false)
        val extension = destination.extension.lowercase()
        require(extension in OFFICE_EXTENSIONS) { "Office edits require DOCX, XLSX, or PPTX" }
        val beforeHash = destination.takeIf(File::isFile)?.sha256()
        if (existing?.state == MutationState.DISPATCHED) {
            return reconcileDocumentDispatch(call, destination, existing, beforeHash)
        }
        if (destination.exists()) {
            require(args.boolean("overwrite", false)) { "overwrite=true is required for an existing document" }
            require(args.stringOrNull("expectedSha256") == beforeHash) { "Document changed since it was read" }
        } else {
            require(args.boolean("create", false)) { "create=true is required for a new document" }
            require(args["expectedSha256"] == null || args["expectedSha256"] is JsonNull) {
                "expectedSha256 is only valid for overwrite"
            }
        }
        destination.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Destination folder is unavailable" } }
        val stage = stagedSibling(destination, call.callId)
        stage.delete()
        try {
            val operations = parseDocumentEdits(args.array("operations"))
            documents.edit(destination.takeIf(File::isFile), extension, operations, stage)
            require(stage.length() in 1..MAX_DOCUMENT_BYTES) { "Edited document is empty or too large" }
            val afterHash = stage.sha256()
            val currentHash = destination.takeIf(File::isFile)?.sha256()
            require(currentHash == beforeHash && (beforeHash != null || !destination.exists())) {
                "Document changed while the edit was prepared"
            }
            beforeMutationDispatch()
            journal.dispatched(call, beforeHash, afterHash)
            atomicReplace(stage, destination)
            val success = BuiltInToolResult.text(
                JSONObject()
                    .put("path", destination.absolutePath)
                    .put("sha256", afterHash)
                    .put("operations", operations.size)
                    .toString(),
            )
            journal.finish(call, MutationState.SUCCEEDED, success, beforeHash, afterHash)
            return success
        } catch (error: AtomicMoveNotSupportedException) {
            val failed = BuiltInToolResult.text("Atomic replacement is unavailable on this filesystem", false)
            journal.finish(call, MutationState.FAILED, failed, beforeHash)
            return failed
        } finally {
            stage.delete()
        }
    }

    private fun parseDocumentEdits(raw: JsonArray): List<DocumentEdit> {
        require(raw.isNotEmpty() && raw.size <= 50) { "operations must contain 1-50 entries" }
        return raw.map { item ->
            val operation = item.jsonObject
            when (operation.string("type")) {
                "replace_text" -> {
                    operation.requireOnly("type", "elementPath", "oldText", "newText")
                    require(operation.string("oldText").isNotEmpty()) { "oldText must not be empty" }
                    DocumentEdit.ReplaceText(
                        validElementPath(operation.string("elementPath")),
                        operation.string("oldText"),
                        operation.string("newText"),
                    )
                }
                "cell_update" -> {
                    operation.requireOnly("type", "sheet", "cell", "value")
                    val sheet = operation.string("sheet").also { require(SHEET_NAME.matches(it)) { "Invalid sheet name" } }
                    val cell = operation.string("cell").also { require(CELL.matches(it)) { "Invalid cell address" } }
                    DocumentEdit.UpdateCell(sheet, cell, operation["value"].toScalar())
                }
                "append_paragraph" -> {
                    operation.requireOnly("type", "parentPath", "text")
                    DocumentEdit.AppendParagraph(validElementPath(operation.string("parentPath", "/body")), operation.string("text"))
                }
                "add_slide" -> {
                    operation.requireOnly("type", "title", "body")
                    DocumentEdit.AddSlide(operation.stringOrNull("title"), operation.stringOrNull("body"))
                }
                "remove_element" -> {
                    operation.requireOnly("type", "elementPath")
                    DocumentEdit.RemoveElement(validElementPath(operation.string("elementPath")))
                }
                else -> error("Unsupported document edit operation")
            }
        }
    }

    private fun validElementPath(path: String): String = path.also {
        require(it.startsWith('/') && it.length <= 512) { "Invalid Office element path" }
    }

    private fun reconcileDocumentDispatch(
        call: BuiltInToolCall,
        destination: File,
        entry: JournalEntry,
        currentHash: String?,
    ): BuiltInToolResult {
        val (state, result) = when {
            entry.afterHash != null && currentHash == entry.afterHash -> MutationState.SUCCEEDED to
                BuiltInToolResult.text("Document mutation previously completed with SHA-256 ${entry.afterHash}.")
            currentHash == entry.beforeHash -> MutationState.FAILED to
                BuiltInToolResult.text("Document mutation was interrupted before replacement; the destination is unchanged.", false)
            else -> MutationState.INDETERMINATE to
                BuiltInToolResult.text("Document mutation outcome is indeterminate; inspect the destination before continuing.", false)
        }
        journal.finish(call, state, result, entry.beforeHash, entry.afterHash)
        return result
    }

    private fun replayTerminal(entry: JournalEntry?): BuiltInToolResult? = when (entry?.state) {
        MutationState.SUCCEEDED, MutationState.FAILED, MutationState.INDETERMINATE ->
            checkNotNull(entry.result) { "Mutation journal terminal result is missing" }
        else -> null
    }

    private fun immutableSnapshot(source: File): File {
        val hash = source.sha256()
        val snapshot = File(snapshots.directory, "$hash.${source.extension.lowercase()}")
        if (!snapshot.isFile) {
            val next = File(snapshot.parentFile, ".${snapshot.name}.next")
            Files.copy(source.toPath(), next.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(next.sha256() == hash) { "Document changed while taking a snapshot" }
            check(next.renameTo(snapshot) || snapshot.isFile) { "Unable to activate document snapshot" }
            next.delete()
            snapshot.setReadOnly()
        }
        return snapshot
    }

    private fun textSnapshotResult(cache: File, offset: Int, maxChars: Int): BuiltInToolResult {
        val value = cache.readText()
        require(offset in 0..value.length) { "Snapshot cursor offset is invalid" }
        val end = minOf(value.length, offset + maxChars)
        val next = if (end < value.length) encodeCursor(cache.name, end) else null
        return BuiltInToolResult.text(
            JSONObject().put("text", value.substring(offset, end)).put("totalChars", value.length).apply {
                next?.let { put("cursor", it) }
            }.toString(),
        )
    }

    private fun continueTextSnapshot(cursor: String, expectedName: String, maxChars: Int): BuiltInToolResult {
        require(cursor.length <= 512) { "Snapshot cursor is too long" }
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val name = decoded.substringBefore(':')
        val offset = decoded.substringAfter(':', "").toIntOrNull() ?: error("Snapshot cursor is invalid")
        require(SNAPSHOT_NAME.matches(name) && name == expectedName) { "Snapshot cursor is invalid" }
        val cache = File(snapshots.directory, name)
        require(cache.isFile && cache.canonicalFile.parentFile == snapshots.directory.canonicalFile) { "Snapshot cursor expired" }
        return textSnapshotResult(cache, offset, maxChars)
    }

    private fun encodeCursor(name: String, offset: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$name:$offset".toByteArray(StandardCharsets.UTF_8))

    private fun writeImmutable(file: File, bytes: ByteArray) {
        val next = File(file.parentFile, ".${file.name}.next")
        next.writeBytes(bytes)
        check(next.renameTo(file) || file.isFile) { "Unable to activate document snapshot" }
        next.delete()
        file.setReadOnly()
    }

    private fun stagedSibling(destination: File, callId: String): File {
        val suffix = destination.extension.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
        return File(destination.parentFile, ".${destination.nameWithoutExtension}.${safeCallId(callId)}.stage$suffix")
    }

    private fun safeCallId(value: String): String = sha256(value).take(16)

    private fun atomicReplace(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun dataUrl(extension: String, bytes: ByteArray): String {
        require(bytes.size <= MAX_INLINE_IMAGE_BYTES)
        val mime = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 2L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 2 * 1024 * 1024
        const val MAX_TEXT_RESULT_CHARS = 200_000
        const val MAX_READ_PAGES = 20
        const val MAX_VIEW_PAGES = 4
        val MODES = setOf("auto", "native", "ocr")
        val REQUESTS = setOf("text", "outline", "stats", "issues", "element")
        val OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        val CELL = Regex("^[A-Z]{1,3}[1-9][0-9]{0,6}$")
        val SHEET_NAME = Regex("^[A-Za-z0-9 _-]{1,128}$")
        val SNAPSHOT_NAME = Regex("^[a-f0-9]{64}\\.txt$")
    }
}

private fun JsonObject.requireOnly(vararg allowed: String): JsonObject = apply {
    require(keys.all { it in allowed }) { "Unexpected tool argument" }
}
private fun JsonObject.string(name: String, default: String? = null): String = stringOrNull(name) ?: default ?: error("Missing $name")
private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: default
private fun JsonObject.int(name: String, default: Int, minimum: Int, maximum: Int): Int =
    (get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull ?: default).also {
        require(it in minimum..maximum) { "$name is out of range" }
    }
private fun JsonObject.long(name: String, minimum: Long): Long = (get(name)?.jsonPrimitive?.longOrNull ?: error("Missing $name")).also {
    require(it >= minimum) { "$name is out of range" }
}
private fun JsonObject.longOrNull(name: String): Long? = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull?.also {
    require(it > 0) { "$name is out of range" }
}
private fun JsonObject.array(name: String): JsonArray = get(name)?.jsonArray ?: error("Missing $name")

private fun JsonElement?.toScalar(): DocumentScalar = when (this) {
    null, JsonNull -> DocumentScalar.Null
    is JsonPrimitive -> when {
        isString -> DocumentScalar.Text(content)
        booleanOrNull != null -> DocumentScalar.BooleanValue(checkNotNull(booleanOrNull))
        doubleOrNull != null -> DocumentScalar.Number(checkNotNull(doubleOrNull))
        else -> error("Spreadsheet values must be scalar")
    }
    else -> error("Spreadsheet values must be scalar")
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
