package localjvm

import java.lang.reflect.InvocationTargetException

/** Local-only: runs the @Test methods of the named classes by reflection, prints PASS/FAIL lines. */
fun main(args: Array<String>) {
    var passed = 0
    var failed = 0
    for (cn in args) {
        val cls = Class.forName(cn)
        val inst = cls.getDeclaredConstructor().newInstance()
        val methods = cls.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }
        for (m in methods) {
            val t0 = System.nanoTime()
            try {
                m.invoke(inst)
                passed++
                println("PASS ${cls.simpleName}.${m.name} (${(System.nanoTime() - t0) / 1_000_000} ms)")
            } catch (e: InvocationTargetException) {
                failed++
                println("FAIL ${cls.simpleName}.${m.name} (${(System.nanoTime() - t0) / 1_000_000} ms): ${(e.cause ?: e).toString().take(1800)}")
            }
        }
    }
    println("passed=$passed failed=$failed")
    System.exit(if (failed > 0) 1 else 0)
}
