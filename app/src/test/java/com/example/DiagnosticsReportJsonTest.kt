package com.example

import com.example.engine.DiagnosticsReport
import com.example.engine.DiagnosticsReport.AudioCell
import com.example.engine.DiagnosticsReport.FrameEntry
import com.example.engine.DiagnosticsReport.ParityResult
import com.example.engine.DiagnosticsReport.Report
import com.example.engine.FrameMetrics
import com.example.engine.GlesAcceptance.AudioMetrics
import com.example.engine.GlesAcceptance.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.6c: the hand-written JSON of the report is real JSON. Runs on CI only (kotlinx.serialization). */
class DiagnosticsReportJsonTest {

    @Test
    fun theReportJsonParsesAndCarriesTheVerdictAndTheCells() {
        val m = AudioMetrics(true, 7.0, 7.0, 0.3, 0.0, 0.0, 0.0, 0.0)
        val cell = AudioCell.of("GLES", setOf(Source.NARRATION), m, 7.0, 44100, 2, 12L, null, listOf("a \"quoted\" warning"))
        val nan = FrameEntry(0.6, FrameMetrics(Double.NaN, 1.0, 1.0, 1.0), null)
        val report = Report("now", 1.5, "1.0", "Phone\nModel", "UNKNOWN", listOf(cell), ParityResult(null, null, 3.0, 3.0, listOf(nan)), emptyList(), true)
        val root = Json.parseToJsonElement(DiagnosticsReport.toJson(report)).jsonObject
        assertEquals("FAIL", root["verdict"]!!.jsonObject["result"]!!.jsonPrimitive.content)
        assertTrue(root["complete"]!!.jsonPrimitive.boolean)
        assertEquals(1, root["audioMatrix"]!!.jsonArray.size)
        val frame = root["parity"]!!.jsonObject["frames"]!!.jsonArray[0].jsonObject
        assertTrue(frame["offsetPx"].toString() == "null")
        assertEquals("Phone\nModel", root["device"]!!.jsonPrimitive.content)
        assertTrue(root is JsonObject)
    }
}
