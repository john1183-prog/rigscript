package android.util

/** Local-only stand-in for android.util.Log (CI uses isReturnDefaultValues = true, which behaves the same). */
object Log {
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
}
