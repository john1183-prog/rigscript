package com.example.engine

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Binary on-disk storage for amplitude/mouth-shape envelopes.
 *
 * V1 stored these as `List<Float>` / `List<Int>` directly inside the
 * [com.example.data.ProjectDef] JSON blob. For a 7.5-min project at 30fps
 * that's ~225,000 floats + ints encoded as JSON text — on the order of 150-200KB
 * living inside a single SQLite column, decoded in full every time the project
 * loads. This was flagged as the #1 architectural debt item in the V1→V2 handoff.
 *
 * V2 instead writes them as raw binary files under `filesDir/envelopes/` and
 * stores only the file PATH in [com.example.data.ProjectDef]. Two format notes:
 *
 *  - Amplitude is written as 4-byte floats via [DataOutputStream] (Java's
 *    big-endian convention — fine here since the same app always reads what it
 *    wrote; there's no cross-platform exchange requirement).
 *  - Mouth shape only ever takes values 0-3 ([MouthShape]), so it's written as
 *    ONE byte per frame instead of a 4-byte Int — a further 4x reduction on top
 *    of just leaving JSON.
 *
 * Reading/deleting operate on already-resolved absolute path strings and don't
 * need a [Context]. Writing a NEW envelope needs [Context] to resolve `filesDir`.
 */
object EnvelopeStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "envelopes").also { it.mkdirs() }

    fun ampPath(context: Context, projectId: String): String =
        File(dir(context), "${projectId}_amp.bin").absolutePath

    fun mouthPath(context: Context, projectId: String): String =
        File(dir(context), "${projectId}_mouth.bin").absolutePath

    // ── Write ──────────────────────────────────────────────────────────────────

    fun writeAmplitude(context: Context, projectId: String, data: FloatArray): String {
        val path = ampPath(context, projectId)
        DataOutputStream(BufferedOutputStream(File(path).outputStream())).use { out ->
            for (v in data) out.writeFloat(v)
        }
        return path
    }

    /** Mouth shapes are 0-3 — stored as one byte each. */
    fun writeMouthShapes(context: Context, projectId: String, data: IntArray): String {
        val path = mouthPath(context, projectId)
        BufferedOutputStream(File(path).outputStream()).use { out ->
            for (v in data) out.write(v and 0xFF)
        }
        return path
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    fun readAmplitude(path: String?): FloatArray {
        if (path.isNullOrBlank()) return FloatArray(0)
        val f = File(path)
        if (!f.exists()) return FloatArray(0)
        return runCatching {
            val count = (f.length() / 4).toInt()
            val result = FloatArray(count)
            DataInputStream(BufferedInputStream(f.inputStream())).use { input ->
                for (i in 0 until count) result[i] = input.readFloat()
            }
            result
        }.getOrElse { FloatArray(0) }
    }

    fun readMouthShapes(path: String?): IntArray {
        if (path.isNullOrBlank()) return IntArray(0)
        val f = File(path)
        if (!f.exists()) return IntArray(0)
        return runCatching {
            val bytes = f.readBytes()
            IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        }.getOrElse { IntArray(0) }
    }

    /**
     * Reads from disk first, falling back to the deprecated inline
     * [com.example.data.ProjectDef] fields for a project saved before the V2
     * migration to disk-backed envelopes. Centralises this fallback so
     * callers ([com.example.engine.VideoExporter], the editor's audio-preview
     * loader, ...) don't each reimplement it slightly differently.
     */
    @Suppress("DEPRECATION")
    fun readAmplitudeWithFallback(path: String?, deprecatedInline: List<Float>): FloatArray =
        readAmplitude(path).let { if (it.isNotEmpty()) it else deprecatedInline.toFloatArray() }

    @Suppress("DEPRECATION")
    fun readMouthShapesWithFallback(path: String?, deprecatedInline: List<Int>): IntArray =
        readMouthShapes(path).let { if (it.isNotEmpty()) it else deprecatedInline.toIntArray() }

    // ── Delete ─────────────────────────────────────────────────────────────────

    fun deletePaths(ampPath: String?, mouthPath: String?) {
        ampPath?.let { runCatching { File(it).delete() } }
        mouthPath?.let { runCatching { File(it).delete() } }
    }
}
