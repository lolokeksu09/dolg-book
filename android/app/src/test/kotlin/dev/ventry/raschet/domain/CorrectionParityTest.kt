package dev.ventry.raschet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CorrectionParityTest {
    @Test fun acceptsMatchingNativeValue() {
        assertEquals(60L, CorrectionParity.verify(100, "debt", 80, 40) { kind, available, old, replacement ->
            assertEquals(1, kind)
            assertEquals(100L, available)
            assertEquals(80L, old)
            assertEquals(40L, replacement)
            60
        })
    }

    @Test fun rejectsMismatchedValueAndInternalError() {
        for (result in listOf(61L, -4L, -1L)) {
            try {
                CorrectionParity.verify(100, "debt", 80, 40) { _, _, _, _ -> result }
                fail("Unexpected native result accepted: $result")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun preservesDomainRejectionsAndRejectsMismatchedError() {
        for ((type, available, old, replacement, expected) in listOf(
            Case(null, 100, 80, 40, -1),
            Case("debt", 10, 100, 0, -2),
            Case("payment", 10, 100, 111, -3)
        )) {
            try {
                CorrectionParity.verify(available, type, old, replacement) { _, _, _, _ -> expected }
                fail("Invalid correction accepted")
            } catch (_: IllegalArgumentException) { }
            try {
                CorrectionParity.verify(available, type, old, replacement) { _, _, _, _ -> 0 }
                fail("Native success hid invalid correction")
            } catch (_: IllegalStateException) { }
        }
    }

    private data class Case(val type: String?, val available: Long, val old: Long, val replacement: Long, val expected: Long)
}
