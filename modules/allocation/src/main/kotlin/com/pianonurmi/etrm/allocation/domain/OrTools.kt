package com.pianonurmi.etrm.allocation.domain

import com.google.ortools.Loader
import org.slf4j.LoggerFactory

/** The OR-Tools native library, loaded once and shared by the exact solver and the pricer. */
internal object OrTools {
    private val log = LoggerFactory.getLogger(OrTools::class.java)

    @Volatile
    private var usable = true

    private val loaded: Boolean by lazy {
        try {
            Loader.loadNativeLibraries()
            true
        } catch (e: LinkageError) {
            log.warn("OR-Tools native library could not be loaded; exact allocation uses greedy and plans have no shadow prices", e)
            false
        }
    }

    val available: Boolean get() = loaded && usable

    /** The library loaded but cannot be linked, e.g. extracted to a temp directory mounted noexec: stop using it. */
    fun unusable(e: LinkageError) {
        log.error("OR-Tools native library is not usable; exact allocation uses greedy until restart", e)
        usable = false
    }
}
