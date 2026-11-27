package com.example

import com.example.data.AnimScript
import java.io.File
import localjvm.LocalJson

/**
 * Local-only twin of app/src/test/java/com/example/Fixtures.kt (which parses with kotlinx.serialization):
 * same API, reads the test resources from disk and decodes scripts with [LocalJson].
 */
object Fixtures {
    private val ROOT = File(System.getProperty("repo.root") ?: ".", "app/src/test/resources")

    val SCRIPT_NAMES: List<String> = (File(ROOT, "fixtures").listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".json") }
        .map { it.name.removeSuffix(".json") }
        .sorted()

    fun textOrNull(resourcePath: String): String? {
        val f = File(ROOT, resourcePath)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    fun text(resourcePath: String): String = textOrNull(resourcePath) ?: error("Missing test resource: $resourcePath")

    fun scriptText(name: String): String = text("fixtures/$name.json")

    fun script(name: String): AnimScript = LocalJson.decode(AnimScript::class, LocalJson.parse(scriptText(name)))

    fun scriptFromText(text: String): AnimScript = LocalJson.decode(AnimScript::class, LocalJson.parse(text))

    fun docSample(name: String): String {
        val roots = listOfNotNull(System.getProperty("repo.root")?.let { File(it) }, File("."), File(".."), File("../.."))
        val root = roots.firstOrNull { File(it, "docs/samples").isDirectory } ?: error("docs/samples not found")
        return File(root, "docs/samples/$name.json").readText(Charsets.UTF_8)
    }

    fun legacyJson(name: String): String = text("fixtures/legacy_json/$name.json")
}
