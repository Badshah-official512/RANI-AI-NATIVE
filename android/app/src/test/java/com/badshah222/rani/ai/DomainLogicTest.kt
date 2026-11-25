package com.badshah222.rani.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class DomainLogicTest {
    @Test fun allDomainAssertionsPass() {
        DomainLogic.runAll()
        assertTrue(DomainLogic.failures.joinToString("\n"), DomainLogic.failures.isEmpty())
    }
}
