package dev.ventry.raschet.presentation

import org.junit.Test

class SingleWriteTest {
    @Test(timeout = 10000)
    fun duplicateSuppressionAcknowledgmentAndRetry() {
        verifySingleWrite()
    }
}
