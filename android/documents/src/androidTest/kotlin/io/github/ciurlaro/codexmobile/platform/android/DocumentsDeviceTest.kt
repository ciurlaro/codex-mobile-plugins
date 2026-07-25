package io.github.ciurlaro.codexmobile.platform.android

import io.github.ciurlaro.codexmobile.providers.documents.OfficeDocuments
import io.github.ciurlaro.codexmobile.providers.documents.DocumentOperation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentsDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pdfExtractionRenderingAndBoundedOcrUseAndroidLibraries() {
        val pdf = File(context.cacheDir, "document-${System.nanoTime()}.pdf")
        val rendered = File(context.cacheDir, "document-${System.nanoTime()}.png")
        try {
            val document = PdfDocument()
            try {
                repeat(6) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
                    page.canvas.drawText("Hello PDF", 60f, 100f, Paint().apply { textSize = 36f })
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                val text = DocumentEngine(context).read(
                    source,
                    DocumentEngineRequest("pdf", mode = "native", semanticRequest = "text", firstPage = 1, pageCount = 1),
                )
                assertTrue(text.contains("Hello PDF"))
            }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                ParcelFileDescriptor.open(
                    rendered,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
                ).use { output ->
                    DocumentEngine(context).render(source, DocumentEngineRequest("pdf", page = 1, dpi = 96), output)
                }
            }
            assertTrue(BitmapFactory.decodeFile(rendered.absolutePath).run { width > 0 && height > 0 })
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                assertThrows(IllegalArgumentException::class.java) {
                    DocumentEngine(context).read(
                        source,
                        DocumentEngineRequest("pdf", mode = "ocr", semanticRequest = "text", firstPage = 1, pageCount = 6),
                    )
                }
            }
        } finally {
            pdf.delete()
            rendered.delete()
        }
    }

    @Test
    fun bundledOfflineOcrReadsAnImage() {
        val image = File(context.cacheDir, "ocr-${System.nanoTime()}.png")
        try {
            Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText("HELLO", 60f, 210f, Paint().apply { color = Color.BLACK; textSize = 160f })
                }
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                val text = DocumentEngine(context).read(
                    source,
                    DocumentEngineRequest("png", mode = "ocr", semanticRequest = "text"),
                )
                assertTrue(text.uppercase().contains("HELLO"))
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun typedProcessorUsesTheAndroidEngine() {
        val image = File(context.cacheDir, "service-ocr-${System.nanoTime()}.png")
        try {
            Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText("SERVICE", 60f, 210f, Paint().apply { color = Color.BLACK; textSize = 150f })
                }
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val text = AndroidDocumentProcessor(context).read(
                image,
                DocumentReadOptions("png", "ocr", "text", null, 1, 1),
            )
            assertTrue(text.uppercase().contains("SERVICE"))
        } finally {
            image.delete()
        }
    }

    @Test
    fun supportedOfficeEditsRoundTrip() {
        val docx = edit("docx", true, DocumentOperation("append_paragraph", path = "/body", text = "Hello"))
        val replaced = edit(
            "docx",
            false,
            DocumentOperation("replace_text", path = "/body/p[1]", oldText = "Hello", newText = "World"),
            docx,
        )
        assertTrue(OfficeDocuments.read(replaced.inputStream(), "docx", "text", null).contains("World"))

        val xlsx = edit(
            "xlsx",
            true,
            DocumentOperation(
                "cell_update",
                sheet = "Sheet1",
                cell = "A1",
                scalarType = "text",
                scalarText = "Value",
            ),
        )
        assertTrue(OfficeDocuments.read(xlsx.inputStream(), "xlsx", "text", null).contains("Value"))

        val pptx = edit("pptx", true, DocumentOperation("add_slide", title = "Title", body = "Body"))
        assertTrue(OfficeDocuments.read(pptx.inputStream(), "pptx", "text", null).contains("Title"))
    }

    @Test
    fun hostileOfficePathFailsClosed() {
        val hostile = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.xml"))
                zip.write("<x/>".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            OfficeDocuments.edit(
                hostile.inputStream(),
                ByteArrayOutputStream(),
                "docx",
                false,
                listOf(DocumentOperation("append_paragraph", path = "/body", text = "no")),
            )
        }
    }

    private fun edit(
        extension: String,
        create: Boolean,
        operation: DocumentOperation,
        input: ByteArray = ByteArray(0),
    ): ByteArray = ByteArrayOutputStream().also { output ->
        OfficeDocuments.edit(ByteArrayInputStream(input), output, extension, create, listOf(operation))
    }.toByteArray()
}
