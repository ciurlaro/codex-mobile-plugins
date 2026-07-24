package io.github.ciurlaro.codexmobile.providers.mcp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

internal object OfficeDocuments {
    fun read(input: InputStream, extension: String, request: String, selector: String?): String {
        val archive = Archive.read(input)
        val elements = when (extension) {
            "docx" -> WordDocument(archive).elements()
            "xlsx" -> SpreadsheetDocument(archive).elements()
            "pptx" -> PresentationDocument(archive).elements()
            else -> error("Unsupported Office document type")
        }
        return when (request) {
            "text" -> JSONObject().put("text", elements.joinToString("\n") { it.text }.trim()).toString()
            "outline" -> JSONArray(elements.filter(DocumentElement::outline).map(DocumentElement::json)).toString()
            "stats" -> JSONObject()
                .put("elements", elements.size)
                .put("characters", elements.sumOf { it.text.length })
                .put("words", elements.sumOf { it.text.split(WHITESPACE).count(String::isNotBlank) })
                .toString()
            "issues" -> JSONObject().put("issues", JSONArray()).toString()
            "element" -> JSONArray(select(elements, checkNotNull(selector))).toString()
            else -> error("Unsupported Office request")
        }
    }

    fun edit(
        input: InputStream,
        output: OutputStream,
        extension: String,
        create: Boolean,
        operations: List<DocumentOperation>,
    ) {
        require(operations.isNotEmpty() && operations.size <= 50) { "operations must contain 1-50 entries" }
        val archive = if (create) Archive.create(extension) else Archive.read(input)
        when (extension) {
            "docx" -> WordDocument(archive).apply(operations)
            "xlsx" -> SpreadsheetDocument(archive).apply(operations)
            "pptx" -> PresentationDocument(archive).apply(operations)
            else -> error("Unsupported Office document type")
        }
        archive.validate(extension)
        val bytes = archive.encode()
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Edited Office document is too large" }
        Archive.read(ByteArrayInputStream(bytes)).validate(extension)
        output.use { it.write(bytes) }
    }

    private fun select(elements: List<DocumentElement>, selector: String): List<JSONObject> {
        if (selector.startsWith('/')) {
            return listOf(checkNotNull(elements.firstOrNull { it.path == selector }) { "Office element was not found" }.json())
        }
        require(selector.length <= 512) { "Office selector is too long" }
        val contains = CONTAINS.find(selector)?.groupValues?.get(1)
        val attribute = ATTRIBUTE.find(selector)?.let { it.groupValues[1] to it.groupValues[2] }
        val type = selector.substringBefore('[').substringBefore(':').trim().ifBlank { "*" }
        val matches = elements.filter { element ->
            (type == "*" || type == element.type || type == element.type.removeSuffix("s")) &&
                (contains == null || element.text.contains(contains, ignoreCase = true)) &&
                (attribute == null || element.attributes[attribute.first] == attribute.second)
        }
        require(matches.isNotEmpty()) { "Office selector matched no elements" }
        return matches.take(MAX_SELECTOR_RESULTS).map(DocumentElement::json)
    }

    private data class DocumentElement(
        val path: String,
        val type: String,
        val text: String,
        val attributes: Map<String, String> = emptyMap(),
        val outline: Boolean = false,
    ) {
        fun json() = JSONObject()
            .put("path", path)
            .put("type", type)
            .put("text", text)
            .put("attributes", JSONObject(attributes))
    }

    private class WordDocument(private val archive: Archive) {
        private val document = archive.xml(WORD_DOCUMENT)
        private val body = document.elements("body").singleOrNull() ?: error("DOCX body is missing")

        fun elements(): List<DocumentElement> = buildList {
            var paragraph = 0
            var table = 0
            body.childElements().forEach { child ->
                when (child.localName) {
                    "p" -> {
                        paragraph++
                        val style = child.elements("pStyle").firstOrNull()?.attribute("val")
                        add(
                            DocumentElement(
                                path = child.attribute("paraId")?.let { "/body/p[@paraId=$it]" } ?: "/body/p[$paragraph]",
                                type = "paragraph",
                                text = child.textRuns(),
                                attributes = listOfNotNull(style?.let { "style" to it }).toMap(),
                                outline = style?.startsWith("Heading", ignoreCase = true) == true,
                            ),
                        )
                    }
                    "tbl" -> {
                        table++
                        add(DocumentElement("/body/tbl[$table]", "table", child.textRuns()))
                        child.childElements("tr").forEachIndexed { rowIndex, row ->
                            add(DocumentElement("/body/tbl[$table]/tr[${rowIndex + 1}]", "row", row.textRuns()))
                            row.childElements("tc").forEachIndexed { cellIndex, cell ->
                                val cellPath = "/body/tbl[$table]/tr[${rowIndex + 1}]/tc[${cellIndex + 1}]"
                                add(DocumentElement(cellPath, "cell", cell.textRuns()))
                                cell.childElements("p").forEachIndexed { pIndex, p ->
                                    add(DocumentElement("$cellPath/p[${pIndex + 1}]", "paragraph", p.textRuns()))
                                }
                            }
                        }
                    }
                }
            }
        }

        fun apply(operations: List<DocumentOperation>) {
            operations.forEach { operation ->
                when (operation.type) {
                    "replace_text" -> replaceText(operation)
                    "append_paragraph" -> appendParagraph(operation)
                    "remove_element" -> remove(operation.path)
                    else -> error("${operation.type} is unavailable for DOCX")
                }
            }
            archive.putXml(WORD_DOCUMENT, document)
        }

        private fun replaceText(operation: DocumentOperation) {
            val element = locate(operation.path)
            require(element.textRuns() == operation.oldText) { "Office text changed before replacement" }
            when (element.localName) {
                "p" -> element.setWordParagraphText(operation.newText)
                else -> error("Text replacement requires a DOCX paragraph path")
            }
        }

        private fun appendParagraph(operation: DocumentOperation) {
            val parent = if (operation.path == "/body") body else locate(operation.path)
            require(parent.localName in setOf("body", "tc")) { "Paragraphs can be appended to the body or a table cell" }
            val paragraph = document.wordParagraph(operation.text)
            val section = parent.childElements("sectPr").singleOrNull()
            if (section == null) parent.appendChild(paragraph) else parent.insertBefore(paragraph, section)
        }

        private fun remove(path: String) {
            val element = locate(path)
            require(element.localName != "body") { "The DOCX body cannot be removed" }
            element.parentNode.removeChild(element)
        }

        private fun locate(path: String): Element {
            elements().firstOrNull { it.path == path } ?: error("Office element was not found")
            WORD_STABLE.matchEntire(path)?.groupValues?.get(1)?.let { id ->
                return document.allElements().firstOrNull { it.localName == "p" && it.attribute("paraId") == id }
                    ?: error("Office element was not found")
            }
            var current: Element = body
            WORD_PATH.findAll(path.removePrefix("/body")).forEach { match ->
                val name = match.groupValues[1]
                val index = match.groupValues[2].toInt()
                current = current.childElements(name).getOrNull(index - 1) ?: error("Office element was not found")
            }
            return current
        }
    }

    private class SpreadsheetDocument(private val archive: Archive) {
        private val workbook = archive.xml(XL_WORKBOOK)
        private val relationships = archive.relationships(XL_WORKBOOK_RELS)
        private val sharedStrings = archive.entries[XL_SHARED_STRINGS]?.let { archive.xml(XL_SHARED_STRINGS) }
        private val sheets: List<Sheet> = workbook.elements("sheet").map { sheet ->
            val relationshipId = sheet.attribute("id") ?: error("XLSX sheet relationship is missing")
            val target = relationships[relationshipId] ?: error("XLSX sheet target is missing")
            Sheet(sheet.attribute("name") ?: error("XLSX sheet name is missing"), "xl/${target.removePrefix("/")}".normalizeZipPath())
        }

        fun elements(): List<DocumentElement> = buildList {
            sheets.forEach { sheet ->
                val document = archive.xml(sheet.path)
                val cells = document.elements("c")
                add(DocumentElement("/${sheet.name}", "sheet", cells.joinToString("\n") { cellText(it) }, outline = true))
                document.elements("row").forEach { row ->
                    val number = row.attribute("r") ?: "0"
                    add(DocumentElement("/${sheet.name}/row[$number]", "row", row.elements("c").joinToString("\t") { cellText(it) }))
                }
                cells.forEach { cell ->
                    val reference = cell.attribute("r") ?: return@forEach
                    add(DocumentElement("/${sheet.name}/$reference", "cell", cellText(cell), mapOf("cell" to reference)))
                }
            }
        }

        fun apply(operations: List<DocumentOperation>) {
            operations.forEach { operation ->
                when (operation.type) {
                    "cell_update" -> updateCell(operation)
                    "remove_element" -> remove(operation.path)
                    else -> error("${operation.type} is unavailable for XLSX")
                }
            }
        }

        private fun updateCell(operation: DocumentOperation) {
            require(CELL.matches(operation.cell)) { "Invalid cell address" }
            val sheet = sheets.singleOrNull { it.name == operation.sheet } ?: error("Spreadsheet sheet was not found")
            val document = archive.xml(sheet.path)
            val sheetData = document.elements("sheetData").singleOrNull() ?: error("XLSX sheet data is missing")
            val rowNumber = CELL.matchEntire(operation.cell)!!.groupValues[2].toInt()
            val row = sheetData.childElements("row").firstOrNull { it.attribute("r") == rowNumber.toString() }
                ?: document.createElementNS(NS_SPREADSHEET, "row").also {
                    it.setAttribute("r", rowNumber.toString())
                    sheetData.appendChild(it)
                }
            val cell = row.childElements("c").firstOrNull { it.attribute("r") == operation.cell }
                ?: document.createElementNS(NS_SPREADSHEET, "c").also {
                    it.setAttribute("r", operation.cell)
                    row.appendChild(it)
                }
            while (cell.hasChildNodes()) cell.removeChild(cell.firstChild)
            when (operation.scalarType) {
                "null" -> row.removeChild(cell)
                "text" -> {
                    cell.setAttribute("t", "inlineStr")
                    val inline = document.createElementNS(NS_SPREADSHEET, "is")
                    inline.appendChild(document.createElementNS(NS_SPREADSHEET, "t").apply {
                        setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
                        textContent = operation.scalarText
                    })
                    cell.appendChild(inline)
                }
                "number" -> {
                    cell.removeAttribute("t")
                    cell.appendChild(document.createElementNS(NS_SPREADSHEET, "v").apply {
                        textContent = operation.scalarNumber.toString()
                    })
                }
                "boolean" -> {
                    cell.setAttribute("t", "b")
                    cell.appendChild(document.createElementNS(NS_SPREADSHEET, "v").apply {
                        textContent = if (operation.scalarBoolean) "1" else "0"
                    })
                }
                else -> error("Invalid spreadsheet value")
            }
            archive.putXml(sheet.path, document)
        }

        private fun remove(path: String) {
            val match = XLSX_PATH.matchEntire(path) ?: error("Removal requires a worksheet cell or row path")
            val sheet = sheets.singleOrNull { it.name == match.groupValues[1] } ?: error("Spreadsheet sheet was not found")
            val document = archive.xml(sheet.path)
            val rowNumber = match.groupValues[3].ifBlank {
                CELL.matchEntire(match.groupValues[2])?.groupValues?.get(2)
            }
            require(!rowNumber.isNullOrBlank()) { "Removal requires a worksheet cell or row path" }
            val row = document.elements("row").firstOrNull { it.attribute("r") == rowNumber }
                ?: error("Office element was not found")
            if (match.groupValues[2].isNotBlank()) {
                val cell = row.childElements("c").firstOrNull { it.attribute("r") == match.groupValues[2] }
                    ?: error("Office element was not found")
                row.removeChild(cell)
            } else {
                row.parentNode.removeChild(row)
            }
            archive.putXml(sheet.path, document)
        }

        private fun cellText(cell: Element): String = when (cell.attribute("t")) {
            "inlineStr" -> cell.elements("t").joinToString("") { it.textContent }
            "s" -> cell.elements("v").firstOrNull()?.textContent?.toIntOrNull()
                ?.let { sharedStrings?.elements("si")?.getOrNull(it)?.elements("t")?.joinToString("") { text -> text.textContent } }
                .orEmpty()
            "b" -> if (cell.elements("v").firstOrNull()?.textContent == "1") "true" else "false"
            else -> cell.elements("v").firstOrNull()?.textContent.orEmpty()
        }

        private data class Sheet(val name: String, val path: String)
    }

    private class PresentationDocument(private val archive: Archive) {
        private val presentation = archive.xml(PPT_PRESENTATION)
        private val relationships = archive.relationships(PPT_PRESENTATION_RELS).toMutableMap()

        fun elements(): List<DocumentElement> = buildList {
            slides().forEachIndexed { slideIndex, path ->
                val document = archive.xml(path)
                val shapes = document.elements("sp")
                add(DocumentElement("/slide[${slideIndex + 1}]", "slide", shapes.joinToString("\n") { it.textRuns() }, outline = true))
                shapes.forEachIndexed { shapeIndex, shape ->
                    val properties = shape.elements("cNvPr").firstOrNull()
                    val id = properties?.attribute("id")
                    val name = properties?.attribute("name")
                    add(
                        DocumentElement(
                            path = id?.let { "/slide[${slideIndex + 1}]/shape[@id=$it]" }
                                ?: "/slide[${slideIndex + 1}]/shape[${shapeIndex + 1}]",
                            type = "shape",
                            text = shape.textRuns(),
                            attributes = listOfNotNull(id?.let { "id" to it }, name?.let { "name" to it }).toMap(),
                        ),
                    )
                }
            }
        }

        fun apply(operations: List<DocumentOperation>) {
            operations.forEach { operation ->
                when (operation.type) {
                    "replace_text" -> replaceText(operation)
                    "add_slide" -> addSlide(operation.title, operation.body)
                    "remove_element" -> remove(operation.path)
                    else -> error("${operation.type} is unavailable for PPTX")
                }
            }
        }

        private fun replaceText(operation: DocumentOperation) {
            val (path, shape) = locateShape(operation.path)
            require(shape.textRuns() == operation.oldText) { "Office text changed before replacement" }
            shape.setPresentationText(operation.newText)
            archive.putXml(path, shape.ownerDocument)
        }

        private fun remove(path: String) {
            val slideMatch = PPT_SLIDE_PATH.matchEntire(path)
            if (slideMatch != null) {
                removeSlide(slideMatch.groupValues[1].toInt())
                return
            }
            val (slidePath, shape) = locateShape(path)
            shape.parentNode.removeChild(shape)
            archive.putXml(slidePath, shape.ownerDocument)
        }

        private fun addSlide(title: String?, body: String?) {
            val number = (archive.entries.keys.mapNotNull { PPT_SLIDE_FILE.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
            val path = "ppt/slides/slide$number.xml"
            val relationshipPath = "ppt/slides/_rels/slide$number.xml.rels"
            archive.entries[path] = slideXml(title, body).toByteArray()
            archive.entries[relationshipPath] = SLIDE_RELS.toByteArray()

            val relDocument = archive.xml(PPT_PRESENTATION_RELS)
            val relationshipId = nextRelationshipId(relDocument)
            relDocument.documentElement.appendChild(relDocument.createElementNS(NS_PACKAGE_RELS, "Relationship").apply {
                setAttribute("Id", relationshipId)
                setAttribute("Type", "$NS_OFFICE_RELS/slide")
                setAttribute("Target", "slides/slide$number.xml")
            })
            archive.putXml(PPT_PRESENTATION_RELS, relDocument)
            relationships[relationshipId] = "slides/slide$number.xml"

            val slideIds = presentation.elements("sldIdLst").singleOrNull() ?: presentation.createElementNS(NS_PRESENTATION, "p:sldIdLst").also {
                presentation.documentElement.appendChild(it)
            }
            val numericId = presentation.elements("sldId").mapNotNull { it.attribute("id")?.toLongOrNull() }.maxOrNull()?.plus(1) ?: 256
            slideIds.appendChild(presentation.createElementNS(NS_PRESENTATION, "p:sldId").apply {
                setAttribute("id", numericId.toString())
                setAttributeNS(NS_RELATIONSHIPS, "r:id", relationshipId)
            })
            archive.putXml(PPT_PRESENTATION, presentation)
            archive.addContentType("/$path", "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")
        }

        private fun removeSlide(index: Int) {
            val ids = presentation.elements("sldId")
            val id = ids.getOrNull(index - 1) ?: error("Office element was not found")
            val relationshipId = id.attribute("id", NS_RELATIONSHIPS) ?: error("PPTX slide relationship is missing")
            val target = relationships[relationshipId] ?: error("PPTX slide target is missing")
            val path = "ppt/${target.removePrefix("/")}".normalizeZipPath()
            id.parentNode.removeChild(id)
            archive.putXml(PPT_PRESENTATION, presentation)
            archive.removeRelationship(PPT_PRESENTATION_RELS, relationshipId)
            archive.removeContentType("/$path")
            archive.entries.remove(path)
            archive.entries.remove(path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels")
        }

        private fun locateShape(requestedPath: String): Pair<String, Element> {
            val match = PPT_SHAPE_PATH.matchEntire(requestedPath) ?: error("Invalid presentation element path")
            val slidePath = slides().getOrNull(match.groupValues[1].toInt() - 1) ?: error("Office element was not found")
            val document = archive.xml(slidePath)
            val shapes = document.elements("sp")
            val shape = when {
                match.groupValues[2].isNotBlank() -> shapes.firstOrNull {
                    it.elements("cNvPr").firstOrNull()?.attribute("id") == match.groupValues[2]
                }
                match.groupValues[3].isNotBlank() -> shapes.getOrNull(match.groupValues[3].toInt() - 1)
                else -> null
            } ?: error("Office element was not found")
            return slidePath to shape
        }

        private fun slides(): List<String> = presentation.elements("sldId").map { id ->
            val relationshipId = id.attribute("id", NS_RELATIONSHIPS) ?: error("PPTX slide relationship is missing")
            "ppt/${relationships[relationshipId]?.removePrefix("/") ?: error("PPTX slide target is missing")}".normalizeZipPath()
        }
    }

    private class Archive(val entries: LinkedHashMap<String, ByteArray>) {
        fun xml(path: String): Document = parseXml(entries[path] ?: error("Office package part is missing: $path"))

        fun putXml(path: String, document: Document) {
            entries[path] = encodeXml(document)
        }

        fun relationships(path: String): Map<String, String> = xml(path).elements("Relationship")
            .filter { it.getAttribute("TargetMode") != "External" }
            .associate { relationship ->
                (relationship.getAttribute("Id") to relationship.getAttribute("Target")).also {
                    require(it.first.isNotBlank() && it.second.isNotBlank()) { "Invalid Office relationship" }
                }
            }

        fun removeRelationship(path: String, id: String) {
            val document = xml(path)
            val relationship = document.elements("Relationship").singleOrNull { it.getAttribute("Id") == id }
                ?: error("Office relationship was not found")
            relationship.parentNode.removeChild(relationship)
            putXml(path, document)
        }

        fun addContentType(part: String, contentType: String) {
            val document = xml(CONTENT_TYPES)
            require(document.elements("Override").none { it.getAttribute("PartName") == part }) { "Duplicate Office part" }
            document.documentElement.appendChild(document.createElementNS(NS_CONTENT_TYPES, "Override").apply {
                setAttribute("PartName", part)
                setAttribute("ContentType", contentType)
            })
            putXml(CONTENT_TYPES, document)
        }

        fun removeContentType(part: String) {
            val document = xml(CONTENT_TYPES)
            document.elements("Override").firstOrNull { it.getAttribute("PartName") == part }?.let {
                it.parentNode.removeChild(it)
                putXml(CONTENT_TYPES, document)
            }
        }

        fun validate(extension: String) {
            require(entries.keys.all(::safeZipPath)) { "Office package contains an invalid path" }
            require(CONTENT_TYPES in entries && ROOT_RELS in entries) { "Office package metadata is missing" }
            when (extension) {
                "docx" -> require(WORD_DOCUMENT in entries) { "DOCX document part is missing" }
                "xlsx" -> require(XL_WORKBOOK in entries && XL_WORKBOOK_RELS in entries) { "XLSX workbook parts are missing" }
                "pptx" -> require(PPT_PRESENTATION in entries && PPT_PRESENTATION_RELS in entries) { "PPTX presentation parts are missing" }
            }
            entries.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (path, bytes) ->
                runCatching { parseXml(bytes) }.getOrElse { throw IllegalArgumentException("Invalid Office XML part: $path", it) }
            }
        }

        fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        companion object {
            fun read(input: InputStream): Archive {
                val entries = LinkedHashMap<String, ByteArray>()
                var expanded = 0L
                try {
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            require(safeZipPath(entry.name)) { "Invalid Office package entry" }
                            if (entry.isDirectory) {
                                zip.closeEntry()
                                continue
                            }
                            require(entries.size < MAX_ZIP_ENTRIES) { "Office package contains too many entries" }
                            require(entry.name !in entries) { "Office package contains duplicate entries" }
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                require(bytes.size() + count <= MAX_PART_BYTES) { "Office package part is too large" }
                                bytes.write(buffer, 0, count)
                            }
                            val content = bytes.toByteArray()
                            expanded += content.size
                            require(expanded <= MAX_EXPANDED_BYTES) { "Office package expands beyond its limit" }
                            if (entry.compressedSize > 0) {
                                require(content.size.toLong() <= entry.compressedSize * MAX_COMPRESSION_RATIO) {
                                    "Office package compression ratio is unsafe"
                                }
                            }
                            entries[entry.name] = content
                            zip.closeEntry()
                        }
                    }
                } catch (error: ZipException) {
                    throw IllegalArgumentException("Invalid Office package", error)
                }
                require(entries.isNotEmpty()) { "Office package is empty" }
                return Archive(entries)
            }

            fun create(extension: String): Archive = when (extension) {
                "docx" -> Archive(linkedMapOf(
                    CONTENT_TYPES to DOCX_CONTENT_TYPES.toByteArray(),
                    ROOT_RELS to DOCX_ROOT_RELS.toByteArray(),
                    WORD_DOCUMENT to DOCX_DOCUMENT.toByteArray(),
                ))
                "xlsx" -> Archive(linkedMapOf(
                    CONTENT_TYPES to XLSX_CONTENT_TYPES.toByteArray(),
                    ROOT_RELS to XLSX_ROOT_RELS.toByteArray(),
                    XL_WORKBOOK to XLSX_WORKBOOK.toByteArray(),
                    XL_WORKBOOK_RELS to XLSX_WORKBOOK_RELS.toByteArray(),
                    "xl/worksheets/sheet1.xml" to XLSX_SHEET.toByteArray(),
                ))
                "pptx" -> Archive(pptxTemplate())
                else -> error("Unsupported Office document type")
            }
        }
    }

    private fun parseXml(bytes: ByteArray): Document {
        require(bytes.size <= MAX_PART_BYTES)
        require(!containsUnsafeXmlMarkup(bytes)) { "Office XML declarations are not allowed" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        }
        return builder.parse(ByteArrayInputStream(bytes)).also { document ->
            require(documentDepth(document.documentElement) <= MAX_XML_DEPTH) { "Office XML is too deeply nested" }
        }
    }

    private fun encodeXml(document: Document): ByteArray = ByteArrayOutputStream().also { output ->
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(output))
    }.toByteArray()

    private fun containsUnsafeXmlMarkup(bytes: ByteArray): Boolean =
        listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).any { charset ->
            listOf("<!DOCTYPE", "<!ENTITY").any { bytes.containsSequence(it.toByteArray(charset)) }
        }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
        indices.any { start ->
            start + sequence.size <= size && sequence.indices.all { this[start + it] == sequence[it] }
        }

    private fun documentDepth(node: Node, depth: Int = 1): Int {
        var maximum = depth
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) maximum = maxOf(maximum, documentDepth(child, depth + 1))
            child = child.nextSibling
        }
        return maximum
    }

    private fun Document.allElements(): List<Element> = documentElement.descendantsAndSelf()
    private fun Document.elements(localName: String): List<Element> = allElements().filter { it.localName == localName }
    private fun Element.elements(localName: String): List<Element> = descendantsAndSelf().filter { it.localName == localName }
    private fun Element.descendantsAndSelf(): List<Element> = buildList {
        add(this@descendantsAndSelf)
        childElements().forEach { addAll(it.descendantsAndSelf()) }
    }
    private fun Element.childElements(localName: String? = null): List<Element> = buildList {
        var child = firstChild
        while (child != null) {
            if (child is Element && (localName == null || child.localName == localName)) add(child)
            child = child.nextSibling
        }
    }
    private fun Element.attribute(localName: String, namespace: String? = null): String? {
        val value = if (namespace == null) {
            attributes?.let { attrs -> (0 until attrs.length).map { attrs.item(it) }.firstOrNull { it.localName == localName }?.nodeValue }
        } else getAttributeNS(namespace, localName)
        return value?.takeIf(String::isNotBlank)
    }
    private fun Element.textRuns(): String = elements("t").joinToString("") { it.textContent }

    private fun Element.setWordParagraphText(value: String) {
        childElements().filter { it.localName != "pPr" }.forEach(::removeChild)
        appendChild(ownerDocument.wordRun(value))
    }
    private fun Document.wordRun(value: String): Element = createElementNS(NS_WORD, "w:r").apply {
        appendChild(createElementNS(NS_WORD, "w:t").apply {
            setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
            textContent = value
        })
    }
    private fun Document.wordParagraph(value: String): Element = createElementNS(NS_WORD, "w:p").apply {
        appendChild(wordRun(value))
    }
    private fun Element.setPresentationText(value: String) {
        val runs = elements("t")
        if (runs.isNotEmpty()) {
            runs.first().textContent = value
            runs.drop(1).forEach { it.textContent = "" }
            return
        }
        appendChild(ownerDocument.createElementNS(NS_PRESENTATION, "p:txBody").apply {
            appendChild(ownerDocument.createElementNS(NS_DRAWING, "a:bodyPr"))
            appendChild(ownerDocument.createElementNS(NS_DRAWING, "a:lstStyle"))
            appendChild(ownerDocument.createElementNS(NS_DRAWING, "a:p").apply {
                appendChild(ownerDocument.createElementNS(NS_DRAWING, "a:r").apply {
                    appendChild(ownerDocument.createElementNS(NS_DRAWING, "a:t").apply { textContent = value })
                })
            })
        })
    }

    private fun safeZipPath(path: String): Boolean = path.isNotBlank() && path.length <= 512 &&
        !path.startsWith('/') && !path.startsWith('\\') && '\\' !in path && '\u0000' !in path &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun String.normalizeZipPath(): String {
        val result = ArrayDeque<String>()
        split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> require(result.isNotEmpty()).also { result.removeLast() }
                else -> result.addLast(part)
            }
        }
        return result.joinToString("/")
    }

    private fun nextRelationshipId(document: Document): String {
        val used = document.elements("Relationship").mapNotNull { it.getAttribute("Id").removePrefix("rId").toIntOrNull() }
        return "rId${(used.maxOrNull() ?: 0) + 1}"
    }

    private fun slideXml(title: String?, body: String?): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sld xmlns:a="$NS_DRAWING" xmlns:r="$NS_RELATIONSHIPS" xmlns:p="$NS_PRESENTATION"><p:cSld><p:spTree>
        <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
        ${presentationShape(2, "Title", title.orEmpty(), 457200, 274638, 8229600, 1143000)}
        ${presentationShape(3, "Body", body.orEmpty(), 457200, 1600200, 8229600, 4572000)}
        </p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>
    """.trimIndent().trimStart()

    private fun presentationShape(id: Int, name: String, text: String, x: Int, y: Int, cx: Int, cy: Int) = """
        <p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>
        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US"/><a:t>${xmlEscape(text)}</a:t></a:r><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>
    """.trimIndent()

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun pptxTemplate(): LinkedHashMap<String, ByteArray> = linkedMapOf(
        CONTENT_TYPES to PPTX_CONTENT_TYPES.toByteArray(),
        ROOT_RELS to PPTX_ROOT_RELS.toByteArray(),
        PPT_PRESENTATION to PPTX_PRESENTATION.toByteArray(),
        PPT_PRESENTATION_RELS to PPTX_PRESENTATION_RELS.toByteArray(),
        "ppt/slideMasters/slideMaster1.xml" to PPTX_MASTER.toByteArray(),
        "ppt/slideMasters/_rels/slideMaster1.xml.rels" to PPTX_MASTER_RELS.toByteArray(),
        "ppt/slideLayouts/slideLayout1.xml" to PPTX_LAYOUT.toByteArray(),
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to PPTX_LAYOUT_RELS.toByteArray(),
        "ppt/theme/theme1.xml" to PPTX_THEME.toByteArray(),
    )

    private const val NS_WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_SPREADSHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_PRESENTATION = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PACKAGE_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val CONTENT_TYPES = "[Content_Types].xml"
    private const val ROOT_RELS = "_rels/.rels"
    private const val WORD_DOCUMENT = "word/document.xml"
    private const val XL_WORKBOOK = "xl/workbook.xml"
    private const val XL_WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"
    private const val XL_SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val PPT_PRESENTATION = "ppt/presentation.xml"
    private const val PPT_PRESENTATION_RELS = "ppt/_rels/presentation.xml.rels"
    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_PART_BYTES = 16 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100L
    private const val MAX_XML_DEPTH = 256
    private const val MAX_SELECTOR_RESULTS = 100
    private val WHITESPACE = Regex("\\s+")
    private val CONTAINS = Regex(":contains\\(\"([^\"]{0,256})\"\\)")
    private val ATTRIBUTE = Regex("\\[([A-Za-z][A-Za-z0-9_-]*)=([^]\\s]{1,256})]")
    private val WORD_PATH = Regex("/(p|tbl|tr|tc)\\[([1-9][0-9]*)]")
    private val WORD_STABLE = Regex("/body/p\\[@paraId=([A-Za-z0-9]+)]")
    private val CELL = Regex("^([A-Z]{1,3})([1-9][0-9]{0,6})$")
    private val XLSX_PATH = Regex("^/([^/]{1,128})/(?:(\\p{Lu}{1,3}[1-9][0-9]{0,6})|row\\[([1-9][0-9]*)])$")
    private val PPT_SLIDE_PATH = Regex("^/slide\\[([1-9][0-9]*)]$")
    private val PPT_SHAPE_PATH = Regex("^/slide\\[([1-9][0-9]*)]/shape(?:\\[@id=([1-9][0-9]*)]|\\[([1-9][0-9]*)])$")
    private val PPT_SLIDE_FILE = Regex("ppt/slides/slide([1-9][0-9]*)\\.xml")

    private const val DOCX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="$NS_CONTENT_TYPES"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
    private const val DOCX_ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/officeDocument" Target="word/document.xml"/></Relationships>"""
    private const val DOCX_DOCUMENT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$NS_WORD"><w:body><w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""

    private const val XLSX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="$NS_CONTENT_TYPES"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
    private const val XLSX_ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private const val XLSX_WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$NS_SPREADSHEET" xmlns:r="$NS_RELATIONSHIPS"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    private const val XLSX_WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
    private const val XLSX_SHEET = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="$NS_SPREADSHEET"><sheetData/></worksheet>"""

    private const val PPTX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="$NS_CONTENT_TYPES"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>"""
    private const val PPTX_ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""
    private const val PPTX_PRESENTATION = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:a="$NS_DRAWING" xmlns:r="$NS_RELATIONSHIPS" xmlns:p="$NS_PRESENTATION"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst/><p:sldSz cx="9144000" cy="6858000" type="screen4x3"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""
    private const val PPTX_PRESENTATION_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/slideMaster" Target="slideMasters/slideMaster1.xml"/></Relationships>"""
    private const val PPTX_MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldMaster xmlns:a="$NS_DRAWING" xmlns:r="$NS_RELATIONSHIPS" xmlns:p="$NS_PRESENTATION"><p:cSld name="Default"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" folHlink="folHlink" hlink="hlink" tx1="dk1" tx2="dk2"/><p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"""
    private const val PPTX_MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$NS_OFFICE_RELS/theme" Target="../theme/theme1.xml"/></Relationships>"""
    private const val PPTX_LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldLayout xmlns:a="$NS_DRAWING" xmlns:r="$NS_RELATIONSHIPS" xmlns:p="$NS_PRESENTATION" type="blank"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
    private const val PPTX_LAYOUT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
    private const val SLIDE_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$NS_PACKAGE_RELS"><Relationship Id="rId1" Type="$NS_OFFICE_RELS/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
    private const val PPTX_THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:theme xmlns:a="$NS_DRAWING" name="Default"><a:themeElements><a:clrScheme name="Default"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F497D"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2><a:accent1><a:srgbClr val="4F81BD"/></a:accent1><a:accent2><a:srgbClr val="C0504D"/></a:accent2><a:accent3><a:srgbClr val="9BBB59"/></a:accent3><a:accent4><a:srgbClr val="8064A2"/></a:accent4><a:accent5><a:srgbClr val="4BACC6"/></a:accent5><a:accent6><a:srgbClr val="F79646"/></a:accent6><a:hlink><a:srgbClr val="0000FF"/></a:hlink><a:folHlink><a:srgbClr val="800080"/></a:folHlink></a:clrScheme><a:fontScheme name="Default"><a:majorFont><a:latin typeface="Arial"/></a:majorFont><a:minorFont><a:latin typeface="Arial"/></a:minorFont></a:fontScheme><a:fmtScheme name="Default"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
}
