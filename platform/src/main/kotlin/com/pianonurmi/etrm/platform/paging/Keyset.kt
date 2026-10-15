package com.pianonurmi.etrm.platform.paging

import com.pianonurmi.etrm.platform.errors.ValidationException
import java.util.Base64

/**
 * Keyset paging: a cursor holds the sort key values of a page's last row, so the next page starts after it even while
 * rows are added or removed. Cursors are URL-safe base64 of the values joined by `|`, opaque to clients; one that does
 * not decode is a validation failure (422), whichever list it was given to.
 */
object Cursor {
    private const val SEPARATOR = "|"

    fun encode(vararg values: Any?): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(values.joinToString(SEPARATOR) { it?.toString().orEmpty() }.toByteArray())

    /** The cursor's [size] values, read by [read]; a cursor that does not decode or [read] rejects is refused. */
    fun <T> decode(cursor: String, size: Int, read: (List<String>) -> T): T {
        val values = runCatching { String(Base64.getUrlDecoder().decode(cursor)).split(SEPARATOR, limit = size) }.getOrNull()
            ?.takeIf { it.size == size } ?: throw invalid()
        return try {
            read(values)
        } catch (ex: ValidationException) {
            throw ex
        } catch (ex: RuntimeException) {
            throw invalid(ex.message)
        }
    }

    private fun invalid(detail: String? = null) = ValidationException("Invalid cursor" + (detail?.let { ": $it" } ?: ""))
}

/**
 * One page from [rows] fetched with `limit + 1`: the first [limit] rows, and the cursor after the last of them when
 * there is more.
 */
fun <T> List<T>.page(limit: Int, cursorOf: (T) -> String): Pair<List<T>, String?> {
    val items = take(limit)
    return items to (if (size > limit) cursorOf(items.last()) else null)
}
