package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

internal interface DocumentProcessor {
    fun read(source: File, options: DocumentReadOptions): String
    fun render(source: File, page: Int, dpi: Int, output: File)
    fun edit(source: File?, extension: String, operations: List<DocumentEdit>, output: File)
}

internal data class DocumentReadOptions(
    val extension: String,
    val mode: String,
    val request: String,
    val selector: String?,
    val firstPage: Int,
    val pageCount: Int,
)

internal sealed interface DocumentEdit {
    data class ReplaceText(val path: String, val oldText: String, val newText: String) : DocumentEdit
    data class UpdateCell(val sheet: String, val cell: String, val value: DocumentScalar) : DocumentEdit
    data class AppendParagraph(val parentPath: String, val text: String) : DocumentEdit
    data class AddSlide(val title: String?, val body: String?) : DocumentEdit
    data class RemoveElement(val path: String) : DocumentEdit
}

internal sealed interface DocumentScalar {
    data object Null : DocumentScalar
    data class Text(val value: String) : DocumentScalar
    data class Number(val value: Double) : DocumentScalar
    data class BooleanValue(val value: Boolean) : DocumentScalar
}

internal data class DocumentEngineRequest(
    val extension: String,
    val mode: String = "",
    val semanticRequest: String = "",
    val selector: String? = null,
    val firstPage: Int = 0,
    val pageCount: Int = 0,
    val page: Int = 0,
    val dpi: Int = 0,
)

internal data class DocumentOperation(
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

internal class AndroidDocumentProcessor(context: Context) : DocumentProcessor {
    private val engine = DocumentEngine(context.applicationContext)

    override fun read(source: File, options: DocumentReadOptions): String =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
            engine.read(
                it,
                DocumentEngineRequest(
                    extension = options.extension,
                    mode = options.mode,
                    semanticRequest = options.request,
                    selector = options.selector,
                    firstPage = options.firstPage,
                    pageCount = options.pageCount,
                ),
            )
        }

    override fun render(source: File, page: Int, dpi: Int, output: File) {
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
            ).use { target ->
                engine.render(input, DocumentEngineRequest(source.extension.lowercase(), page = page, dpi = dpi), target)
            }
        }
    }

    override fun edit(source: File?, extension: String, operations: List<DocumentEdit>, output: File) {
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val input = source?.inputStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
        input.use {
            output.outputStream().use { target ->
                OfficeDocuments.edit(it, target, extension, source == null, operations.map { operation -> operation.operation() })
            }
        }
    }

    private fun DocumentEdit.operation(): DocumentOperation = when (this) {
        is DocumentEdit.ReplaceText -> DocumentOperation("replace_text", path, oldText, newText)
        is DocumentEdit.UpdateCell -> when (value) {
            DocumentScalar.Null -> DocumentOperation("cell_update", sheet = sheet, cell = cell)
            is DocumentScalar.Text -> DocumentOperation(
                "cell_update", sheet = sheet, cell = cell, scalarType = "text", scalarText = value.value,
            )
            is DocumentScalar.Number -> DocumentOperation(
                "cell_update", sheet = sheet, cell = cell, scalarType = "number", scalarNumber = value.value,
            )
            is DocumentScalar.BooleanValue -> DocumentOperation(
                "cell_update", sheet = sheet, cell = cell, scalarType = "boolean", scalarBoolean = value.value,
            )
        }
        is DocumentEdit.AppendParagraph -> DocumentOperation("append_paragraph", path = parentPath, text = text)
        is DocumentEdit.AddSlide -> DocumentOperation("add_slide", title = title, body = body)
        is DocumentEdit.RemoveElement -> DocumentOperation("remove_element", path = path)
    }
}
