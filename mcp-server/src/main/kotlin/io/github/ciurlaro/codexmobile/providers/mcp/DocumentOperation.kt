package io.github.ciurlaro.codexmobile.providers.mcp

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
