---
name: documents
description: Use typed document tools to read, render, OCR, create, and edit files in the selected workspace.
---

# Documents

Use `documents_read` for bounded semantic extraction from PDF, image, DOCX,
XLSX, and PPTX files. Choose `native` for embedded text, `ocr` for scanned
content, or `auto` to prefer native extraction and fall back to English OCR.

Use `documents_view_pages` only when specific pages must be inspected. Use
`documents_edit` for its closed transaction operations. Existing files require
`overwrite=true` and their current SHA-256.

