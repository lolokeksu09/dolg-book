package dev.ventry.raschet.bridge

import dev.ventry.raschet.BuildConfig
import dev.ventry.raschet.domain.CorrectionParity
import dev.ventry.raschet.domain.LedgerArithmetic

object CorrectionEngine {
    private val libraryLoaded by lazy {
        NativeCorrection.loadAndroidLibrary()
        true
    }

    @JvmStatic
    fun correctedBalance(available: Long, type: String?, oldAmount: Long, replacement: Long): Long {
        if (!BuildConfig.RUST_PREVIEW) {
            return LedgerArithmetic.correctedBalance(available, type, oldAmount, replacement)
        }
        return try {
            check(libraryLoaded)
            CorrectionParity.verify(available, type, oldAmount, replacement, NativeCorrection::correctedBalance)
        } catch (error: LinkageError) {
            throw IllegalStateException("Нативный финансовый модуль недоступен.", error)
        }
    }
}
