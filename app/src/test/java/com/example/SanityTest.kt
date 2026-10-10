package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WP0.2: proves that JUnit 4 is wired into the Gradle build and into CI.
 * Real tests (fixtures, goldens, engine properties) arrive in WP0.3 onward.
 */
class SanityTest {

    @Test
    fun junitIsWired() {
        assertEquals(4, 2 + 2)
    }

    /**
     * `testOptions.unitTests.isReturnDefaultValues = true` makes the stubbed
     * android.jar return defaults (0 for Log.d) instead of throwing
     * "Method d in android.util.Log not mocked". Pure engine code that logs
     * can therefore be tested on the JVM.
     */
    @Test
    fun androidLogCallsReturnDefaultsOnTheJvm() {
        assertEquals(0, android.util.Log.d("RigScriptTest", "hello"))
    }
}
