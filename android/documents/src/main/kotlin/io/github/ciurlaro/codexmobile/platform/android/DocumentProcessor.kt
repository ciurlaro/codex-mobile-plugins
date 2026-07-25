package io.github.ciurlaro.codexmobile.platform.android

import io.github.ciurlaro.codexmobile.providers.documents.OfficeDocuments
import io.github.ciurlaro.codexmobile.providers.documents.DocumentOperation
import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

internal interface DocumentProcessor {
    fun read(source: File, options: DocumentReadOptions): String
    fun render(source: File, page: Int, dpi: Int, output: File)
    fun edit(source: File?, extension: String, operations: List<DocumentOperation>, output: File)
}

internal data class DocumentReadOptions(
    val extension: String,
    val mode: String,
    val request: String,
    val selector: String?,
    val firstPage: Int,
    val pageCount: Int,
)

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

    override fun edit(source: File?, extension: String, operations: List<DocumentOperation>, output: File) {
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val input = source?.inputStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
        input.use {
            output.outputStream().use { target ->
                OfficeDocuments.edit(it, target, extension, source == null, operations)
            }
        }
    }
}
