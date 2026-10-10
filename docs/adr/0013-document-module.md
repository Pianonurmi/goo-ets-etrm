# ADR 0013: A document module for rendered, immutable business documents

- Status: accepted
- Date: 2026-10-10

## Context
Increment 2 issues documents that leave the company: trade confirmations, invoices, credit notes, netting
statements. Once issued, a document must be reproducible byte for byte and provably unchanged. Several modules
(trade, settlement) need the same rendering and storage, and the PDF stack (PDFBox, fonts) is heavy.

## Decision
- A leaf module `document` owns rendering and storage. It depends on nothing but `platform`; owner modules depend on `document :: api`.
- Rendering is Mustache templates (XHTML, HTML-escaped by default) turned into PDF with openhtmltopdf. Owner modules ship their templates as classpath resources under `documents/templates/<name>.html`; template names are restricted to `[a-z0-9-]`.
- Stored documents carry kind, owner type and reference, legal entity, file name, MIME type, size and SHA-256. The table rejects updates and deletes; corrections are new documents.
- The PDF stack is kept out of `platform` so it does not reach every module and test classpath.

## Consequences
- `GET /api/v1/documents/{id}/content` serves the bytes with an `X-Content-SHA256` header; `/verify` recomputes the hash.
- Large binary content lives in PostgreSQL (`bytea`, max 20 MB per document). Moving content to object storage later only changes the repository adapter.
