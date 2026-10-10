package com.pianonurmi.etrm.document

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Rendered and stored business documents (confirmations, invoices, statements): immutable, hashed, retrievable. */
@PackageInfo
@ApplicationModule(displayName = "Document", allowedDependencies = [])
class DocumentModule
