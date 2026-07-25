package io.github.ciurlaro.codexmobile.providers.mcp

import io.github.ciurlaro.codexmobile.providers.documents.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonObject
import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject

internal class DocumentsMcpBackend : McpBackend {
    private val hostWorkspace = Path.of(System.getenv("CODEX_MCP_HOST_WORKSPACE") ?: "/workspace").toAbsolutePath().normalize()
    private val workspace = Path.of(System.getenv("CODEX_MCP_WORKSPACE") ?: "/workspace").toRealPath()
    private val snapshots = Path.of(System.getenv("CODEX_MCP_STATE") ?: "/state").resolve("documents/snapshots").also {
        Files.createDirectories(it)
    }.toRealPath()

    override suspend fun execute(tool: String, arguments: JsonObject): McpResult =
        when (val request = parseDocumentRequest(tool, arguments)) {
            is DocumentRequest.Read -> read(request)
            is DocumentRequest.ViewPages -> view(request)
            is DocumentRequest.Edit -> edit(request)
        }

    private fun read(request: DocumentRequest.Read): McpResult {
        val source = resolve(request.path, mustExist = true)
        require(Files.size(source) in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val snapshot = snapshot(source)
        val cacheName = sha256(
            "${snapshot.fileName}\u0000${request.mode}\u0000${request.semanticRequest}\u0000${request.selector}" +
                "\u0000${request.firstPage}\u0000${request.pageCount}",
        ) + ".txt"
        val cache = snapshots.resolve(cacheName)
        request.cursor?.let { return continueSnapshot(it, cacheName, request.maxChars) }
        if (!Files.isRegularFile(cache)) {
            val extracted = when (extension(snapshot)) {
                "pdf" -> readPdf(snapshot, request.mode, request.semanticRequest, request.firstPage, request.pageCount)
                in DOCUMENT_IMAGE_EXTENSIONS -> readImage(snapshot, request.mode, request.semanticRequest)
                in DOCUMENT_OFFICE_EXTENSIONS -> Files.newInputStream(snapshot).use {
                    OfficeDocuments.read(it, extension(snapshot), request.semanticRequest, request.selector)
                }
                else -> error("Unsupported document type")
            }
            val bytes = extracted.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_DOCUMENT_EXTRACTED_BYTES) { "Extracted document content exceeds the snapshot limit" }
            writeImmutable(cache, bytes)
        }
        return snapshotResult(cache, 0, request.maxChars)
    }

    private fun view(request: DocumentRequest.ViewPages): McpResult {
        val source = resolve(request.path, mustExist = true)
        require(Files.size(source) in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val snapshot = snapshot(source)
        val extension = extension(snapshot)
        if (extension in DOCUMENT_IMAGE_EXTENSIONS) {
            require(request.pages == listOf(1)) { "Images only have page 1" }
            val bytes = Files.readAllBytes(snapshot)
            require(bytes.size <= MAX_DOCUMENT_INLINE_IMAGE_BYTES) { "Image is too large to return inline" }
            return McpResult(listOf(McpContent.Image(bytes, imageMimeType(extension))))
        }
        require(extension == "pdf") { "Page rendering is available for PDF and image files" }
        return Loader.loadPDF(snapshot.toFile()).use { document ->
            val renderer = PDFRenderer(document)
            McpResult(request.pages.map { page ->
                require(page in 1..document.numberOfPages) { "PDF page is out of range" }
                val image = render(renderer, document.getPage(page - 1), page - 1, request.dpi)
                val bytes = ByteArrayOutputStream().use { output ->
                    check(ImageIO.write(image, "png", output)) { "Unable to encode PDF page" }
                    output.toByteArray()
                }
                require(bytes.size <= MAX_DOCUMENT_INLINE_IMAGE_BYTES) { "Rendered page is too large" }
                McpContent.Image(bytes, "image/png")
            })
        }
    }

    private fun edit(request: DocumentRequest.Edit): McpResult {
        val destination = resolve(request.path, mustExist = false)
        val extension = extension(destination)
        require(extension in DOCUMENT_OFFICE_EXTENSIONS) { "Office edits require DOCX, XLSX, or PPTX" }
        val exists = Files.isRegularFile(destination)
        val beforeHash = destination.takeIf { exists }?.let(::sha256)
        request.validateTarget(exists, beforeHash)
        Files.createDirectories(checkNotNull(destination.parent))
        val stage = destination.resolveSibling(".${destination.fileName}.${System.nanoTime()}.stage")
        try {
            val input = if (exists) Files.newInputStream(destination) else ByteArrayInputStream(ByteArray(0))
            input.use { source ->
                Files.newOutputStream(stage).use { target ->
                    OfficeDocuments.edit(source, target, extension, !exists, request.operations)
                }
            }
            require(Files.size(stage) in 1..MAX_DOCUMENT_BYTES) { "Edited document is empty or too large" }
            require((destination.takeIf(Files::isRegularFile)?.let(::sha256)) == beforeHash) {
                "Document changed while the edit was prepared"
            }
            try {
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                error("Atomic replacement is unavailable on this filesystem")
            }
            return McpResult.text(documentEditJson(request.path, sha256(destination), request.operations.size))
        } finally {
            Files.deleteIfExists(stage)
        }
    }

    private fun readPdf(path: Path, mode: String, request: String, firstPage: Int, pageCount: Int): String =
        Loader.loadPDF(path.toFile()).use { document ->
            require(firstPage in 1..document.numberOfPages) { "PDF page is out of range" }
            val lastPage = minOf(document.numberOfPages, firstPage + pageCount - 1)
            when (request) {
                "element" -> error("PDF element selection is unavailable; use bounded page text")
                "outline" -> pdfOutline(document)
                "stats" -> JSONObject().put("pageCount", document.numberOfPages).toString()
                "issues" -> JSONObject().put("pageCount", document.numberOfPages).put("issues", JSONArray()).toString()
                "text" -> {
                    if (mode == "ocr") return@use ocrPdf(document, firstPage, lastPage)
                    val native = PDFTextStripper().apply {
                        startPage = firstPage
                        endPage = lastPage
                    }.getText(document).trim()
                    if (native.isNotBlank() || mode == "native") native else ocrPdf(document, firstPage, lastPage)
                }
                else -> error("Unsupported PDF request")
            }
        }

    private fun readImage(path: Path, mode: String, request: String): String {
        require(request in setOf("text", "outline")) { "Images support text extraction only" }
        require(mode != "native") { "Images do not contain native document text" }
        val image = checkNotNull(ImageIO.read(path.toFile())) { "Unable to decode image" }
        require(image.width.toLong() * image.height <= MAX_BITMAP_PIXELS) { "Image dimensions are too large" }
        return ocr(image)
    }

    private fun ocrPdf(document: PDDocument, firstPage: Int, lastPage: Int): String {
        require(lastPage - firstPage + 1 <= MAX_OCR_PAGES) { "OCR is limited to $MAX_OCR_PAGES pages" }
        val renderer = PDFRenderer(document)
        return (firstPage..lastPage).joinToString("\n") { page ->
            ocr(render(renderer, document.getPage(page - 1), page - 1, OCR_DPI))
        }
    }

    private fun render(renderer: PDFRenderer, page: PDPage, index: Int, dpi: Int): BufferedImage {
        val pixels = (page.cropBox.width * dpi / 72f).toLong() * (page.cropBox.height * dpi / 72f).toLong()
        require(pixels in 1..MAX_BITMAP_PIXELS) { "Rendered page dimensions are too large" }
        return renderer.renderImageWithDPI(index, dpi.toFloat(), ImageType.RGB)
    }

    private fun ocr(image: BufferedImage): String = Tesseract().apply {
        setLanguage("eng")
        System.getenv("TESSDATA_PREFIX")?.takeIf(String::isNotBlank)?.let(::setDatapath)
    }.doOCR(image).trim()

    private fun pdfOutline(document: PDDocument): String {
        val result = JSONArray()
        fun add(item: PDOutlineItem?, depth: Int) {
            var current = item
            while (current != null) {
                result.put(JSONObject().put("title", current.title).put("depth", depth))
                add(current.firstChild, depth + 1)
                current = current.nextSibling
            }
        }
        add(document.documentCatalog.documentOutline?.firstChild, 0)
        return result.toString()
    }

    private fun resolve(value: String, mustExist: Boolean): Path {
        val input = Path.of(value)
        val relative = if (input.isAbsolute) {
            val normalized = input.normalize()
            require(normalized.startsWith(hostWorkspace)) { "Path is outside the workspace" }
            hostWorkspace.relativize(normalized)
        } else input.normalize()
        require(!relative.startsWith("..")) { "Path is outside the workspace" }
        val candidate = workspace.resolve(relative).normalize()
        require(candidate.startsWith(workspace)) { "Path is outside the workspace" }
        if (mustExist) {
            val real = candidate.toRealPath()
            require(real.startsWith(workspace) && Files.isRegularFile(real)) { "Path is outside the workspace or not a file" }
            return real
        }
        val parent = checkNotNull(candidate.parent).let { existingParent(it) }.toRealPath()
        require(parent.startsWith(workspace)) { "Destination is outside the workspace" }
        return candidate
    }

    private fun existingParent(path: Path): Path = when {
        Files.exists(path) -> path
        path.parent == null -> error("Destination parent is unavailable")
        else -> existingParent(path.parent)
    }

    private fun snapshot(source: Path): Path {
        val hash = sha256(source)
        val target = snapshots.resolve("$hash.${extension(source)}")
        if (!Files.isRegularFile(target)) {
            val next = snapshots.resolve(".${target.fileName}.next")
            Files.copy(source, next, StandardCopyOption.REPLACE_EXISTING)
            require(sha256(next) == hash) { "Document changed while taking a snapshot" }
            try {
                Files.move(next, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                Files.deleteIfExists(next)
            }
        }
        return target
    }

    private fun snapshotResult(cache: Path, offset: Int, maxChars: Int): McpResult {
        val page = documentTextPage(Files.readString(cache), offset, maxChars)
        val cursor = page.nextOffset?.let { next ->
            val encoder = Base64.getUrlEncoder().withoutPadding()
            documentCursor(cache.fileName.toString(), next) {
                encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8))
            }
        }
        return McpResult.text(page.jsonString(cursor))
    }

    private fun continueSnapshot(cursor: String, expectedName: String, maxChars: Int): McpResult {
        val offset = parseDocumentCursor(cursor, expectedName) {
            String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8)
        }
        val cache = snapshots.resolve(expectedName)
        require(Files.isRegularFile(cache)) { "Snapshot cursor expired" }
        return snapshotResult(cache, offset, maxChars)
    }

    private fun writeImmutable(file: Path, bytes: ByteArray) {
        val next = file.resolveSibling(".${file.fileName}.next")
        Files.write(next, bytes)
        try {
            Files.move(next, file, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(next)
        }
    }

    private fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().hex()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun extension(path: Path) = path.fileName.toString().substringAfterLast('.', "").lowercase()
    private fun imageMimeType(extension: String) = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    private companion object {
        const val MAX_OCR_PAGES = 5
        const val MAX_BITMAP_PIXELS = 20_000_000L
        const val OCR_DPI = 144
    }
}
