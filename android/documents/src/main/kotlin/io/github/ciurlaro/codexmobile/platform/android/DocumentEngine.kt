// GPL-3.0-or-later; the ML Kit linking permission in LICENSES/MLKIT-EXCEPTION.txt applies.
package io.github.ciurlaro.codexmobile.platform.android

import io.github.ciurlaro.codexmobile.providers.documents.OfficeDocuments
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.core.unlocked.PdfDocumentU
import io.legere.pdfiumandroid.core.unlocked.PdfiumCoreU
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal class DocumentEngine(private val context: Context) {
    fun read(source: ParcelFileDescriptor, request: DocumentEngineRequest): String = when (request.extension) {
        "pdf" -> readPdf(source, request)
        in IMAGE_EXTENSIONS -> readImage(source, request)
        in OFFICE_EXTENSIONS -> ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            OfficeDocuments.read(input, request.extension, request.semanticRequest, request.selector)
        }
        else -> error("Unsupported document type")
    }

    fun render(source: ParcelFileDescriptor, request: DocumentEngineRequest, output: ParcelFileDescriptor) {
        require(request.extension == "pdf") { "Page rendering is available for PDF files" }
        openPdf(source).use { document ->
            require(request.page in 1..document.getPageCount()) { "PDF page is out of range" }
            val bitmap = renderPage(document, request.page - 1, request.dpi)
            bitmap.useBitmap {
                val encoded = ByteArrayOutputStream()
                check(it.compress(Bitmap.CompressFormat.PNG, 100, encoded)) { "Unable to encode PDF page" }
                require(encoded.size() <= MAX_RENDER_BYTES) { "Rendered page is too large" }
                output.use { target ->
                    ParcelFileDescriptor.AutoCloseOutputStream(target).use { stream -> encoded.writeTo(stream) }
                }
            }
        }
    }

    private fun readPdf(source: ParcelFileDescriptor, request: DocumentEngineRequest): String = openPdf(source).use { document ->
        require(request.firstPage >= 1 && request.pageCount >= 1)
        require(request.firstPage <= document.getPageCount()) { "PDF page is out of range" }
        val last = minOf(document.getPageCount(), request.firstPage + request.pageCount - 1)
        when (request.semanticRequest) {
            "element" -> error("PDF element selection is unavailable; use bounded page text")
            "outline" -> bookmarks(document.getTableOfContents()).toString()
            "stats" -> pdfStats(document).toString()
            "issues" -> JSONObject()
                .put("pageCount", document.getPageCount())
                .put("issues", JSONArray())
                .toString()
            "text" -> {
                if (request.mode == "ocr") return@use ocrPdf(document, request.firstPage - 1, last - 1)
                val native = buildString {
                    for (pageIndex in request.firstPage - 1 until last) {
                        checkNotNull(document.openPage(pageIndex)) { "Unable to open PDF page" }.use { page ->
                            page.openTextPage().use { textPage ->
                                append(textPage.textPageGetText(0, textPage.textPageCountChars()))
                            }
                        }
                        if (pageIndex + 1 < last) append('\n')
                    }
                }
                if (native.isNotBlank() || request.mode == "native") native
                else ocrPdf(document, request.firstPage - 1, last - 1)
            }
            else -> error("Unsupported PDF request")
        }
    }

    private fun readImage(source: ParcelFileDescriptor, request: DocumentEngineRequest): String {
        require(request.semanticRequest in setOf("text", "outline")) { "Images support text extraction only" }
        require(request.mode != "native") { "Images do not contain native document text" }
        val descriptor = source.fileDescriptor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(descriptor, null, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to decode image" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_BITMAP_PIXELS) { "Image dimensions are too large" }
        Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_SET)
        val bitmap = checkNotNull(BitmapFactory.decodeFileDescriptor(descriptor)) { "Unable to decode image" }
        return bitmap.useBitmap(::recognize)
    }

    private fun ocrPdf(document: PdfDocumentU, firstPage: Int, lastPage: Int): String {
        require(lastPage - firstPage + 1 <= MAX_OCR_PAGES) { "OCR is limited to $MAX_OCR_PAGES pages" }
        return buildString {
            for (pageIndex in firstPage..lastPage) {
                if (lastPage > firstPage) append("\n--- Page ${pageIndex + 1} ---\n")
                renderPage(document, pageIndex, OCR_DPI).useBitmap { append(recognize(it)) }
            }
        }
    }

    private fun recognize(bitmap: Bitmap): String {
        MlKitContext.initializeIfNeeded(context)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS).text
        } finally {
            recognizer.close()
        }
    }

    private fun openPdf(source: ParcelFileDescriptor): PdfDocumentU =
        PdfiumCoreU(context).newDocument(ParcelFileDescriptor.dup(source.fileDescriptor))

    private fun renderPage(document: PdfDocumentU, pageIndex: Int, dpi: Int): Bitmap =
        checkNotNull(document.openPage(pageIndex)) { "Unable to open PDF page" }.use { page ->
            val width = page.getPageWidth(dpi)
            val height = page.getPageHeight(dpi)
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_BITMAP_PIXELS) {
                "Rendered page dimensions are too large"
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.renderPageBitmap(bitmap, 0, 0, width, height, true, true, 0, 0)
            }
        }

    private fun pdfStats(document: PdfDocumentU): JSONObject {
        val meta = document.getDocumentMeta()
        return JSONObject()
            .put("pageCount", document.getPageCount())
            .put("title", meta.title)
            .put("author", meta.author)
            .put("subject", meta.subject)
            .put("keywords", meta.keywords)
            .put("creator", meta.creator)
            .put("producer", meta.producer)
    }

    private fun bookmarks(items: List<Bookmark>): JSONArray = JSONArray().also { array ->
        items.forEach { bookmark ->
            array.put(
                JSONObject()
                    .put("title", bookmark.title)
                    .put("page", bookmark.pageIdx + 1)
                    .put("children", bookmarks(bookmark.children)),
            )
        }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }

    private companion object {
        const val OCR_DPI = 144
        const val MAX_OCR_PAGES = 5
        const val MAX_BITMAP_PIXELS = 20_000_000L
        const val MAX_RENDER_BYTES = 2 * 1024 * 1024
        const val OCR_TIMEOUT_SECONDS = 120L
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        val OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx")
    }
}
