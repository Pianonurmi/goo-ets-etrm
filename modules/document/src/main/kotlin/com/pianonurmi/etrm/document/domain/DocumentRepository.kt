package com.pianonurmi.etrm.document.domain

import com.pianonurmi.etrm.document.api.DocumentView
import java.util.UUID

interface DocumentRepository {
    fun insert(document: DocumentView, bytes: ByteArray)

    fun find(id: UUID): DocumentView?

    fun bytes(id: UUID): ByteArray?

    fun forOwner(ownerType: String, ownerRef: UUID): List<DocumentView>
}

/** Turns XHTML into PDF bytes. */
fun interface PdfRenderer {
    fun render(xhtml: String, title: String): ByteArray
}
