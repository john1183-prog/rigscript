package com.example

import com.example.data.AnimScript
import com.example.data.BuiltInSoundEffects
import com.example.engine.BakedKeyframe
import com.example.engine.PlaybackEngine
import com.example.engine.ScriptValidator
import com.example.engine.TimeResolvedOverlay
import com.example.engine.TimelineCompiler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * WP0.4: a text snapshot of what the shared engine produces for one fixture, rounded to 1e-4.
 *
 * Sections: validator warnings; a summary of the baked keyframes; caption and sound-effect cues;
 * then about 20 to 28 checkpoint times, each with joint angles, mouth, expression, eye openness,
 * camera, figure overrides, scene, caption and the resolved overlays, all read through the export
 * path (`seekToWithAmplitude`) with a synthetic speech envelope. Numbers are printed with a decimal
 * point so [GoldenCompare] can apply a tolerance to them and require everything else to match.
 */
object GoldenSnapshot {

    const val FORMAT = 1

    /** Frames replayed before each checkpoint, so amplitude smoothing is in the state an export would have. */
    private const val WARMUP_FRAMES = 15
    private const val MAX_OVERLAY_LINES = 14
    private const val CHUNK_CHARS = 20000

    private val libraryIds: Set<String> = BuiltInSoundEffects.ALL.map { it.id }.toSet() + "custom_boom"

    fun num(v: Float): String = num(v.toDouble())

    fun num(v: Float?): String = if (v == null) "-" else num(v.toDouble())

    fun num(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return d.toString()
        val s = String.format(Locale.ROOT, "%.4f", d)
        return if (s == "-0.0000") "0.0000" else s
    }

    private fun hex(v: Long?): String = if (v == null) "-" else java.lang.Long.toHexString(v)

    private fun esc(s: String?, maxChars: Int = 80): String {
        if (s == null) return "-"
        val flat = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
        return when {
            flat.isEmpty() -> "(empty)"
            flat.length > maxChars -> flat.substring(0, maxChars) + "~"
            else -> flat
        }
    }

    /** The envelope every golden uses: steady speech, as long as the export would be. */
    fun envelopeFor(totalSec: Float): FloatArray = TestEnvelopes.speechLike(ceil(totalSec))

    /** Times at which the engine is inspected: start, a few keyframes (start, mid-transition, settled) and even spacing. */
    fun checkpoints(keyframes: List<BakedKeyframe>, totalSec: Float): List<Float> {
        val raw = sortedSetOf<Float>()
        raw += 0f
        val n = keyframes.size
        val picks = if (n <= 7) (0 until n).toList() else (0 until 7).map { it * (n - 1) / 6 }.distinct()
        for (i in picks) {
            val kf = keyframes[i]
            raw += kf.timeSec
            raw += kf.timeSec + kf.duration * 0.5f
            raw += kf.timeSec + kf.duration + 0.4f
        }
        for (k in 1..6) raw += totalSec * k / 7f
        return raw.map { Math.round(it * 1000f) / 1000f }.filter { it >= 0f && it <= totalSec }.distinct()
    }

    fun build(name: String): String {
        val script = Fixtures.script(name)
        val keyframes = EngineHarness.compile(script)
        val total = EngineHarness.totalSec(keyframes, 0f)
        val envelope = envelopeFor(total)
        val sb = StringBuilder()
        sb.append("golden ").append(FORMAT).append(" fixture=").append(name).append('\n')
        sb.append("input events=").append(script.events.size)
            .append(" layers=").append(script.overlayLayers.size)
            .append(" blinks=").append(script.blinkEvents.size)
            .append(" total=").append(num(total)).append('\n')

        val warnings = ScriptValidator.validate(script, libraryIds)
        sb.append("warnings ").append(warnings.size).append('\n')
        for (w in warnings) sb.append("warn ").append(esc(w, 400)).append('\n')

        sb.append("keyframes ").append(keyframes.size).append('\n')
        keyframes.forEachIndexed { i, kf -> sb.append(keyframeLine(i, kf)).append('\n') }

        val captions = TimelineCompiler.extractCaptions(script)
        sb.append("captions ").append(captions.size).append('\n')
        for (c in captions) {
            sb.append("cap ").append(num(c.startSec)).append(' ').append(num(c.endSec)).append(' ')
                .append(esc(c.text)).append('\n')
        }
        val cues = TimelineCompiler.extractSoundEffectCues(script)
        sb.append("sfx ").append(cues.size).append('\n')
        for (c in cues) {
            sb.append("fx ").append(num(c.timeSec)).append(' ').append(c.clipId).append(' ')
                .append(num(c.volumeMultiplier)).append('\n')
        }

        val times = checkpoints(keyframes, total)
        sb.append("checkpoints ").append(times.size).append('\n')
        for (t in times) appendCheckpoint(sb, script, keyframes, envelope, t)
        return sb.toString()
    }

    private fun keyframeLine(i: Int, kf: BakedKeyframe): String {
        var sum = 0.0
        var maxDelta = 0.0
        for (k in kf.toAngles.indices) {
            sum += kf.toAngles[k]
            maxDelta = max(maxDelta, abs((kf.toAngles[k] - kf.fromAngles[k]).toDouble()))
        }
        return "kf $i t=${num(kf.timeSec)} d=${num(kf.duration)} ease=${kf.ease} " +
            "spring=${num(kf.springStiffness)}/${num(kf.springDamping)} expr=${kf.expression} " +
            "pose=${kf.fromPoseId}>${kf.toPoseId} zoom=${num(kf.fromCameraZoom)}>${num(kf.toCameraZoom)} " +
            "pan=${num(kf.fromCameraPanX)},${num(kf.fromCameraPanY)}>${num(kf.toCameraPanX)},${num(kf.toCameraPanY)} " +
            "shake=${num(kf.cameraShake)} scene=${kf.sceneShape}/${kf.sceneAtmosphere} " +
            "sum=${num(sum)} maxd=${num(maxDelta)}"
    }

    private fun appendCheckpoint(
        sb: StringBuilder,
        script: AnimScript,
        keyframes: List<BakedKeyframe>,
        envelope: FloatArray,
        t: Float
    ) {
        val e: PlaybackEngine = EngineHarness.create(script, envelope, keyframes = keyframes)
        for (k in WARMUP_FRAMES downTo 1) {
            val earlier = t - k.toFloat() / EngineHarness.ENV_FPS
            if (earlier >= 0f) EngineHarness.seekFrame(e, envelope, earlier)
        }
        EngineHarness.seekFrame(e, envelope, t)

        sb.append("cp t=").append(num(t)).append('\n')
        sb.append("ang")
        for (a in e.currentAngles) sb.append(' ').append(num(a))
        sb.append('\n')
        sb.append("st mouth=").append(e.currentMouthShape)
            .append(" expr=").append(e.currentExpression)
            .append(" eye=").append(num(e.currentEyeOpenness))
            .append(" hip=").append(num(e.currentHipBobOffset))
            .append(" amp=").append(num(e.currentAmplitude)).append('\n')
        sb.append("cam zoom=").append(num(e.currentCameraZoom))
            .append(" panx=").append(num(e.currentCameraPanX))
            .append(" pany=").append(num(e.currentCameraPanY))
            .append(" shake=").append(num(e.currentShakeIntensity)).append('\n')
        val f = e.currentFigureOverrides
        sb.append("fig x=").append(num(f.x)).append(" y=").append(num(f.y))
            .append(" scale=").append(num(f.scale)).append(" head=").append(num(f.headScale))
            .append(" op=").append(num(f.opacity)).append('\n')
        sb.append("col bone=").append(hex(f.boneColor)).append(" head=").append(hex(f.headColor))
            .append(" joint=").append(hex(f.jointColor)).append(" bg=").append(hex(f.bgColor))
            .append(" grad=").append(hex(f.backgroundGradientColor))
            .append(" style=").append(f.backgroundStyle ?: "-")
            .append(" line=").append(hex(f.groundLineColor))
            .append(" showline=").append(f.showGroundLine?.toString() ?: "-")
            .append(" liney=").append(num(f.groundLineYFraction))
            .append(" mouth=").append(hex(f.mouthColor)).append(" eye=").append(hex(f.eyeColor))
            .append(" brow=").append(hex(f.eyebrowColor)).append('\n')
        sb.append("scene shape=").append(e.currentSceneShape).append(" atmos=").append(e.currentSceneAtmosphere)
            .append(" sky=").append(hex(e.currentSkyColor)).append(" ground=").append(hex(e.currentGroundColor))
            .append(" horizon=").append(num(e.currentHorizonY)).append('\n')
        sb.append("caption ").append(esc(e.currentCaption)).append('\n')

        val overlays = e.currentOverlays
        var sumX = 0.0
        var sumY = 0.0
        for (o in overlays) {
            sumX += o.localX
            sumY += o.localY
        }
        sb.append("ov n=").append(overlays.size).append(" sumx=").append(num(sumX))
            .append(" sumy=").append(num(sumY)).append('\n')
        for (o in overlays.take(MAX_OVERLAY_LINES)) sb.append(overlayLine(o)).append('\n')
    }

    private fun overlayLine(o: TimeResolvedOverlay): String =
        "o ${o.id.ifEmpty { "-" }} ${o.type}/${o.shape} pb=${o.parentBone ?: "-"} pl=${o.parentLayer ?: "-"} " +
            "x=${num(o.localX)} y=${num(o.localY)} rot=${num(o.localRotationDeg)} sc=${num(o.localScale)} " +
            "op=${num(o.opacity)} col=${hex(o.color)} grad=${hex(o.gradientColor)} " +
            "w=${num(o.width)} h=${num(o.height)} r=${num(o.radius)} fs=${num(o.fontSize)} " +
            "glow=${o.glow} vel=${num(o.velocityAngleDeg)} trail=${o.trailPointsLocal.size} " +
            "pose=${o.figurePoseAngles?.size ?: 0} fe=${o.figureExpression} " +
            "front=${o.inFrontOfFigure} ss=${o.screenSpace} txt=${esc(o.text, 40)}"

    /**
     * Transport for the CI report: gzip, base64, chunks of [CHUNK_CHARS] characters, each on a line
     * `CAPTURE|golden:<name>:<i>/<n>|<chunk>` (see `ci_report.py`). The sandbox decodes with a Python twin of [decodeCapture].
     */
    fun captureLines(name: String, text: String): List<String> {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val chunks = Base64.getEncoder().encodeToString(bos.toByteArray()).chunked(CHUNK_CHARS)
        return chunks.mapIndexed { i, c -> "CAPTURE|golden:$name:${i + 1}/${chunks.size}|$c" }
    }

    fun decodeCapture(lines: List<String>): String {
        val payload = lines.joinToString("") { it.substringAfterLast('|') }
        val bytes = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(payload))).use { it.readBytes() }
        return String(bytes, Charsets.UTF_8)
    }
}
