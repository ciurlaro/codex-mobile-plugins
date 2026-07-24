package io.github.ciurlaro.codexmobile.providers.mcp

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

    override suspend fun execute(tool: String, arguments: JsonObject): McpResult = when (tool) {
        "documents_read" -> read(arguments)
        "documents_view_pages" -> view(arguments)
        "documents_edit" -> edit(arguments)
        else -> error("Unknown Documents tool")
    }

    private fun read(args: JsonObject): McpResult {
        args.requireOnly("path", "mode", "request", "selector", "pageStart", "pageCount", "maxChars", "cursor")
        val source = resolve(args.string("path"), mustExist = true)
        require(Files.size(source) in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val snapshot = snapshot(source)
        val mode = args.string("mode", "auto").also { require(it in MODES) { "Invalid read mode" } }
        val request = args.string("request", "text").also { require(it in REQUESTS) { "Invalid semantic request" } }
        val selector = args.stringOrNull("selector")
        if (request == "element") require(!selector.isNullOrBlank()) { "selector is required for element reads" }
        val firstPage = args.int("pageStart", 1, 1, 100_000)
        val pageCount = args.int("pageCount", 10, 1, MAX_READ_PAGES)
        val maxChars = args.int("maxChars", 48_000, 1, MAX_TEXT_CHARS)
        val cacheName = sha256("${snapshot.fileName}\u0000$mode\u0000$request\u0000$selector\u0000$firstPage\u0000$pageCount") + ".txt"
        val cache = snapshots.resolve(cacheName)
        args.stringOrNull("cursor")?.let { return continueSnapshot(it, cacheName, maxChars) }
        if (!Files.isRegularFile(cache)) {
            val extracted = when (extension(snapshot)) {
                "pdf" -> readPdf(snapshot, mode, request, firstPage, pageCount)
                in IMAGE_EXTENSIONS -> readImage(snapshot, mode, request)
                in OFFICE_EXTENSIONS -> Files.newInputStream(snapshot).use {
                    OfficeDocuments.read(it, extension(snapshot), request, selector)
                }
                else -> error("Unsupported document type")
            }
            val bytes = extracted.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_EXTRACTED_BYTES) { "Extracted document content exceeds the snapshot limit" }
            writeImmutable(cache, bytes)
        }
        return snapshotResult(cache, 0, maxChars)
    }

    private fun view(args: JsonObject): McpResult {
        args.requireOnly("path", "pages", "dpi")
        val source = resolve(args.string("path"), mustExist = true)
        require(Files.size(source) in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val pages = args.array("pages").map { it.jsonPrimitive.intOrNull ?: error("Page must be an integer") }
        require(pages.isNotEmpty() && pages.size <= MAX_VIEW_PAGES && pages.all { it > 0 } && pages.distinct() == pages) {
            "pages must contain 1-$MAX_VIEW_PAGES unique positive integers"
        }
        val dpi = args.int("dpi", 120, 72, 160)
        val snapshot = snapshot(source)
        val extension = extension(snapshot)
        if (extension in IMAGE_EXTENSIONS) {
            require(pages == listOf(1)) { "Images only have page 1" }
            val bytes = Files.readAllBytes(snapshot)
            require(bytes.size <= MAX_INLINE_IMAGE_BYTES) { "Image is too large to return inline" }
            return McpResult(listOf(McpContent.Image(bytes, imageMimeType(extension))))
        }
        require(extension == "pdf") { "Page rendering is available for PDF and image files" }
        return Loader.loadPDF(snapshot.toFile()).use { document ->
            val renderer = PDFRenderer(document)
            McpResult(pages.map { page ->
                require(page in 1..document.numberOfPages) { "PDF page is out of range" }
                val image = render(renderer, document.getPage(page - 1), page - 1, dpi)
                val bytes = ByteArrayOutputStream().use { output ->
                    check(ImageIO.write(image, "png", output)) { "Unable to encode PDF page" }
                    output.toByteArray()
                }
                require(bytes.size <= MAX_INLINE_IMAGE_BYTES) { "Rendered page is too large" }
                McpContent.Image(bytes, "image/png")
            })
        }
    }

    private fun edit(args: JsonObject): McpResult {
        args.requireOnly("path", "create", "overwrite", "expectedSha256", "operations")
        val destination = resolve(args.string("path"), mustExist = false)
        val extension = extension(destination)
        require(extension in OFFICE_EXTENSIONS) { "Office edits require DOCX, XLSX, or PPTX" }
        val exists = Files.isRegularFile(destination)
        val beforeHash = destination.takeIf { exists }?.let(::sha256)
        if (exists) {
            require(args.boolean("overwrite", false)) { "overwrite=true is required for an existing document" }
            require(args.stringOrNull("expectedSha256") == beforeHash) { "Document changed since it was read" }
        } else {
            require(args.boolean("create", false)) { "create=true is required for a new document" }
            require(args["expectedSha256"] == null || args["expectedSha256"] is JsonNull) {
                "expectedSha256 is only valid for overwrite"
            }
        }
        val operations = parseOperations(args.array("operations"))
        Files.createDirectories(checkNotNull(destination.parent))
        val stage = destination.resolveSibling(".${destination.fileName}.${System.nanoTime()}.stage")
        try {
            val input = if (exists) Files.newInputStream(destination) else ByteArrayInputStream(ByteArray(0))
            input.use { source ->
                Files.newOutputStream(stage).use { target ->
                    OfficeDocuments.edit(source, target, extension, !exists, operations)
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
            return McpResult.text(JSONObject()
                .put("path", args.string("path"))
                .put("sha256", sha256(destination))
                .put("operations", operations.size)
                .toString())
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

    private fun parseOperations(raw: JsonArray): List<DocumentOperation> {
        require(raw.isNotEmpty() && raw.size <= 50) { "operations must contain 1-50 entries" }
        return raw.map { element ->
            val operation = element.jsonObject
            when (operation.string("type")) {
                "replace_text" -> {
                    operation.requireOnly("type", "elementPath", "oldText", "newText")
                    DocumentOperation(
                        type = "replace_text",
                        path = elementPath(operation.string("elementPath")),
                        oldText = operation.string("oldText").also { require(it.isNotEmpty()) },
                        newText = operation.string("newText"),
                    )
                }
                "cell_update" -> {
                    operation.requireOnly("type", "sheet", "cell", "value")
                    val value = operation.getValue("value")
                    DocumentOperation(
                        type = "cell_update",
                        sheet = operation.string("sheet").also { require(SHEET_NAME.matches(it)) { "Invalid sheet name" } },
                        cell = operation.string("cell").also { require(CELL.matches(it)) { "Invalid cell address" } },
                        scalarType = when {
                            value is JsonNull -> "null"
                            value.jsonPrimitive.booleanOrNull != null -> "boolean"
                            value.jsonPrimitive.doubleOrNull != null && !value.jsonPrimitive.isString -> "number"
                            else -> "text"
                        },
                        scalarText = value.jsonPrimitive.contentOrNull.orEmpty(),
                        scalarNumber = value.jsonPrimitive.doubleOrNull ?: 0.0,
                        scalarBoolean = value.jsonPrimitive.booleanOrNull ?: false,
                    )
                }
                "append_paragraph" -> {
                    operation.requireOnly("type", "parentPath", "text")
                    DocumentOperation(
                        type = "append_paragraph",
                        path = elementPath(operation.string("parentPath", "/body")),
                        text = operation.string("text"),
                    )
                }
                "add_slide" -> {
                    operation.requireOnly("type", "title", "body")
                    DocumentOperation("add_slide", title = operation.stringOrNull("title"), body = operation.stringOrNull("body"))
                }
                "remove_element" -> {
                    operation.requireOnly("type", "elementPath")
                    DocumentOperation("remove_element", path = elementPath(operation.string("elementPath")))
                }
                else -> error("Unsupported document edit operation")
            }
        }
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
        val value = Files.readString(cache)
        require(offset in 0..value.length) { "Snapshot cursor offset is invalid" }
        val end = minOf(value.length, offset + maxChars)
        val next = if (end < value.length) Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${cache.fileName}:$end".toByteArray(StandardCharsets.UTF_8)) else null
        return McpResult.text(JSONObject().put("text", value.substring(offset, end)).put("totalChars", value.length).apply {
            next?.let { put("cursor", it) }
        }.toString())
    }

    private fun continueSnapshot(cursor: String, expectedName: String, maxChars: Int): McpResult {
        require(cursor.length <= 512) { "Snapshot cursor is too long" }
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8) }
            .getOrElse { error("Snapshot cursor is invalid") }
        val name = decoded.substringBefore(':')
        val offset = decoded.substringAfter(':', "").toIntOrNull() ?: error("Snapshot cursor is invalid")
        require(name == expectedName && SNAPSHOT_NAME.matches(name)) { "Snapshot cursor is invalid" }
        val cache = snapshots.resolve(name)
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

    private fun elementPath(value: String) = value.also {
        require(it.startsWith('/') && it.length <= 512) { "Invalid Office element path" }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 2 * 1024 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_TEXT_CHARS = 200_000
        const val MAX_READ_PAGES = 20
        const val MAX_OCR_PAGES = 5
        const val MAX_VIEW_PAGES = 4
        const val MAX_BITMAP_PIXELS = 20_000_000L
        const val OCR_DPI = 144
        val MODES = setOf("auto", "native", "ocr")
        val REQUESTS = setOf("text", "outline", "stats", "issues", "element")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
        val OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx")
        val SHEET_NAME = Regex("[^\\[\\]:*?/\\\\]{1,31}")
        val CELL = Regex("[A-Z]{1,3}[1-9][0-9]{0,6}")
        val SNAPSHOT_NAME = Regex("[a-f0-9]{64}\\.txt")
    }
}

private fun JsonObject.requireOnly(vararg names: String): JsonObject = apply {
    require(keys.all(names.toSet()::contains)) { "Unknown argument: ${keys.first { it !in names }}" }
}

private fun JsonObject.string(name: String, default: String? = null): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: default ?: error("$name is required")

private fun JsonObject.stringOrNull(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String, default: Int, minimum: Int, maximum: Int): Int =
    (this[name]?.jsonPrimitive?.intOrNull ?: default).also { require(it in minimum..maximum) { "$name is out of range" } }

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: error("$name is required")
