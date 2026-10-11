package org.junit

/** Local-only stand-ins for JUnit 4 (the real jar is on Maven Central, unreachable here). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

object Assert {
    private fun bad(message: String?): Nothing = throw AssertionError(message ?: "")
    @JvmStatic fun fail(message: String?) { bad(message) }
    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) bad(null) }
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) { if (!condition) bad(message) }
    @JvmStatic fun assertFalse(condition: Boolean) { if (condition) bad(null) }
    @JvmStatic fun assertFalse(message: String?, condition: Boolean) { if (condition) bad(message) }
    @JvmStatic fun assertNull(o: Any?) { if (o != null) bad("expected null but was <$o>") }
    @JvmStatic fun assertNotNull(o: Any?) { if (o == null) bad("expected not null") }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) bad("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) { if (expected != actual) bad("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) {
        if (!(Math.abs(expected - actual) <= delta)) bad("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
        if (!(Math.abs(expected - actual) <= delta)) bad("$message expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertArrayEquals(expecteds: FloatArray, actuals: FloatArray, delta: Float) = assertArrayEquals(null, expecteds, actuals, delta)
    @JvmStatic fun assertArrayEquals(message: String?, expecteds: FloatArray, actuals: FloatArray, delta: Float) {
        if (expecteds.size != actuals.size) bad("$message array lengths differ: ${expecteds.size} vs ${actuals.size}")
        for (i in expecteds.indices) if (!(Math.abs(expecteds[i] - actuals[i]) <= delta)) bad("$message arrays differ at $i: ${expecteds[i]} vs ${actuals[i]}")
    }
}
