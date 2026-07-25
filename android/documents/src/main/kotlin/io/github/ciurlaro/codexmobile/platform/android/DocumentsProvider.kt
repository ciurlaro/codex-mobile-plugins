package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.provider.api.CodexMobileProvider
import io.github.ciurlaro.codexmobile.provider.api.ProviderCall as BuiltInToolCall
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent.Image
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
import io.github.ciurlaro.codexmobile.provider.api.ProviderDescriptor
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationEntry
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationJournal
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationState
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderResult as BuiltInToolResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderToolDefinition as BuiltInToolDefinition
import io.github.ciurlaro.codexmobile.providers.documents.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentsProvider(context: Context) : CodexMobileProvider {
    private val appContext = context.applicationContext
    private val documents: DocumentProcessor = AndroidDocumentProcessor(appContext)
    private val snapshots = DocumentSnapshotStore(appContext)

    private val providerTools = documentsTools.map {
        BuiltInToolDefinition(it.pluginId, it.name, it.description, it.inputSchema, it.mutation)
    }

    override val descriptor = ProviderDescriptor(
        pluginId = DOCUMENTS_PLUGIN_ID,
        implementationVersion = "1.0.0",
        tools = providerTools,
        providerApi = 2,
        minHostVersionCode = 5,
        maxHostVersionCode = 5,
        displayName = "Documents",
        schemaDigest = DOCUMENTS_SCHEMA_DIGEST,
    )

    override suspend fun execute(
        call: BuiltInToolCall,
        context: ProviderContext,
    ): BuiltInToolResult = withContext(Dispatchers.IO) {
        when (val request = parseDocumentRequest(call.tool, call.arguments)) {
            is DocumentRequest.Read -> documentsRead(request, context)
            is DocumentRequest.ViewPages -> documentsViewPages(request, context)
            is DocumentRequest.Edit -> documentEdit(call, context, request)
        }
    }

    override suspend fun replay(call: BuiltInToolCall, context: ProviderContext): BuiltInToolResult? = withContext(Dispatchers.IO) {
        if (call.tool != "documents_edit") return@withContext null
        val existing = context.mutations.find(call) ?: return@withContext null
        when (existing.state) {
            ProviderMutationState.PREPARED -> null
            ProviderMutationState.SUCCEEDED, ProviderMutationState.FAILED, ProviderMutationState.INDETERMINATE ->
                checkNotNull(existing.result) { "Mutation journal terminal result is missing" }
            ProviderMutationState.DISPATCHED -> execute(call, context)
        }
    }

    override suspend fun prepareUninstall(context: ProviderContext): ProviderRemovalResult = withContext(Dispatchers.IO) {
        check(snapshots.directory.deleteRecursively() || !snapshots.directory.exists()) {
            "Document snapshots could not be removed"
        }
        ProviderRemovalResult.ready()
    }

    private fun documentsRead(request: DocumentRequest.Read, context: ProviderContext): BuiltInToolResult {
        val source = File(context.workspace.resolve(request.path, mustExist = true))
        require(source.length() in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val snapshot = immutableSnapshot(source)
        val cacheKey = sha256(
            "${snapshot.name}\u0000${request.mode}\u0000${request.semanticRequest}\u0000${request.selector}" +
                "\u0000${request.firstPage}\u0000${request.pageCount}",
        )
        val cache = File(snapshots.directory, "$cacheKey.txt")
        request.cursor?.let { return continueTextSnapshot(it, cache.name, request.maxChars) }
        if (!cache.isFile) {
            val extracted = documents.read(
                snapshot,
                DocumentReadOptions(
                    snapshot.extension.lowercase(), request.mode, request.semanticRequest,
                    request.selector, request.firstPage, request.pageCount,
                ),
            )
            require(extracted.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_EXTRACTED_BYTES) {
                "Extracted document content exceeds the snapshot limit"
            }
            writeImmutable(cache, extracted.toByteArray(StandardCharsets.UTF_8))
        }
        return textSnapshotResult(cache, 0, request.maxChars)
    }

    private fun documentsViewPages(request: DocumentRequest.ViewPages, context: ProviderContext): BuiltInToolResult {
        val source = File(context.workspace.resolve(request.path, mustExist = true))
        require(source.length() in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val snapshot = immutableSnapshot(source)
        if (source.extension.lowercase() in DOCUMENT_IMAGE_EXTENSIONS) {
            require(request.pages == listOf(1)) { "Images only have page 1" }
            require(snapshot.length() <= MAX_DOCUMENT_INLINE_IMAGE_BYTES) { "Image is too large to return inline" }
            return BuiltInToolResult(listOf(Image(dataUrl(snapshot.extension, snapshot.readBytes()))), true)
        }
        require(source.extension.equals("pdf", true)) { "Page rendering is available for PDF and image files" }
        return BuiltInToolResult(
            request.pages.map { page ->
                val output = File(snapshots.directory, "${snapshot.nameWithoutExtension}-p$page-${request.dpi}.png")
                if (!output.isFile) {
                    val next = File(output.parentFile, ".${output.name}.next")
                    documents.render(snapshot, page, request.dpi, next)
                    require(next.length() in 1..MAX_DOCUMENT_INLINE_IMAGE_BYTES) { "Rendered page is too large" }
                    check(next.renameTo(output) || output.isFile) { "Unable to activate rendered page" }
                    next.delete()
                    output.setReadOnly()
                }
                Image(dataUrl("png", output.readBytes()))
            },
            true,
        )
    }

    private suspend fun documentEdit(
        call: BuiltInToolCall,
        context: ProviderContext,
        request: DocumentRequest.Edit,
    ): BuiltInToolResult {
        val journal = context.mutations
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        val destination = File(context.workspace.resolve(request.path, mustExist = false))
        val extension = destination.extension.lowercase()
        require(extension in DOCUMENT_OFFICE_EXTENSIONS) { "Office edits require DOCX, XLSX, or PPTX" }
        val beforeHash = destination.takeIf(File::isFile)?.sha256()
        if (existing?.state == ProviderMutationState.DISPATCHED) {
            return reconcileDocumentDispatch(call, destination, existing, beforeHash, journal)
        }
        request.validateTarget(destination.exists(), beforeHash)
        destination.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Destination folder is unavailable" } }
        val stage = stagedSibling(destination, call.callId)
        stage.delete()
        try {
            documents.edit(destination.takeIf(File::isFile), extension, request.operations, stage)
            require(stage.length() in 1..MAX_DOCUMENT_BYTES) { "Edited document is empty or too large" }
            val afterHash = stage.sha256()
            val currentHash = destination.takeIf(File::isFile)?.sha256()
            require(currentHash == beforeHash && (beforeHash != null || !destination.exists())) {
                "Document changed while the edit was prepared"
            }
            context.beforeMutationDispatch()
            journal.dispatched(call, beforeHash, afterHash)
            atomicReplace(stage, destination)
            val success = BuiltInToolResult.text(
                documentEditJson(destination.absolutePath, afterHash, request.operations.size),
            )
            journal.finish(call, ProviderMutationState.SUCCEEDED, success, beforeHash, afterHash)
            return success
        } catch (error: AtomicMoveNotSupportedException) {
            val failed = BuiltInToolResult.text("Atomic replacement is unavailable on this filesystem", false)
            journal.finish(call, ProviderMutationState.FAILED, failed, beforeHash)
            return failed
        } finally {
            stage.delete()
        }
    }

    private fun reconcileDocumentDispatch(
        call: BuiltInToolCall,
        destination: File,
        entry: ProviderMutationEntry,
        currentHash: String?,
        journal: ProviderMutationJournal,
    ): BuiltInToolResult {
        val (state, result) = when {
            entry.afterHash != null && currentHash == entry.afterHash -> ProviderMutationState.SUCCEEDED to
                BuiltInToolResult.text("Document mutation previously completed with SHA-256 ${entry.afterHash}.")
            currentHash == entry.beforeHash -> ProviderMutationState.FAILED to
                BuiltInToolResult.text("Document mutation was interrupted before replacement; the destination is unchanged.", false)
            else -> ProviderMutationState.INDETERMINATE to
                BuiltInToolResult.text("Document mutation outcome is indeterminate; inspect the destination before continuing.", false)
        }
        journal.finish(call, state, result, entry.beforeHash, entry.afterHash)
        return result
    }

    private fun replayTerminal(entry: ProviderMutationEntry?): BuiltInToolResult? = when (entry?.state) {
        ProviderMutationState.SUCCEEDED, ProviderMutationState.FAILED, ProviderMutationState.INDETERMINATE ->
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
        val page = documentTextPage(cache.readText(), offset, maxChars)
        val cursor = page.nextOffset?.let { encodeCursor(cache.name, it) }
        return BuiltInToolResult.text(page.jsonString(cursor))
    }

    private fun continueTextSnapshot(cursor: String, expectedName: String, maxChars: Int): BuiltInToolResult {
        val offset = parseDocumentCursor(cursor, expectedName) {
            String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8)
        }
        val cache = File(snapshots.directory, expectedName)
        require(cache.isFile && cache.canonicalFile.parentFile == snapshots.directory.canonicalFile) { "Snapshot cursor expired" }
        return textSnapshotResult(cache, offset, maxChars)
    }

    private fun encodeCursor(name: String, offset: Int): String = Base64.getUrlEncoder().withoutPadding()
        .let { encoder -> documentCursor(name, offset) { encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8)) } }

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
        require(bytes.size <= MAX_DOCUMENT_INLINE_IMAGE_BYTES)
        val mime = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

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
