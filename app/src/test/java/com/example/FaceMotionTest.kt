package com.example

import com.example.data.AmplitudeSettings
import com.example.engine.Expression
import com.example.engine.ResolvedFace
import com.example.engine.acting.ActingContext
import com.example.engine.acting.ActingIntensity
import com.example.engine.acting.ActingLevel
import com.example.engine.acting.ActingParams
import com.example.engine.acting.FaceMotionModel
import com.example.engine.acting.FaceTimeline
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * WP2.3 step 2c (face model): pinned to the Python prototype (`tools/acting_proto/face_proto.py`), plus the brief's
 * acceptance list: steady-state geometry equals the legacy numbers for all six expressions, blend continuity and bounds,
 * gaze bounds, determinism, seek equals sequential, OFF identical to legacy.
 */
class FaceMotionTest {

    private fun near(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$what: expected $expected, got $actual (tolerance $tolerance)", abs(expected - actual) <= tolerance)
    }

    private val levels = listOf(ActingIntensity.SUBTLE, ActingIntensity.NORMAL, ActingIntensity.BOLD).map { it to ActingParams.levelFor(it) }
    private val allExpressions = listOf(Expression.NORMAL, Expression.WIDE, Expression.SQUINT, Expression.WORRIED, Expression.ANGRY, Expression.HAPPY)
    private val seed = 987654321L

    /** `face_proto.py --emit-reference`: NORMAL; WIDE at 1.0, ANGRY at 2.125, HAPPY at 2.25, SQUINT at 4.0 (all exact in float32). */
    private val refTimeline = FaceTimeline(Expression.NORMAL, floatArrayOf(1.0f, 2.125f, 2.25f, 4.0f),
        intArrayOf(Expression.WIDE, Expression.ANGRY, Expression.HAPPY, Expression.SQUINT))

    private val reference = """
424242,SUBTLE,0.296875,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,1.000000000,0.800000000,0.000000000,0.000000000,0.000000000
424242,SUBTLE,0.984375,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,0.884247017,0.800000000,0.000000000,0.000000000,0.000000000
424242,SUBTLE,1.015625,0.152322374,1.000000000,0.000000000,1.823223736,0.009289494,0.872413424,0.800000000,0.000000000,0.000000000,0.000000000
424242,SUBTLE,1.078125,0.185983545,1.000000000,0.000000000,2.159835448,0.143934179,1.031534818,0.800000000,0.001134432,0.006210012,0.000000000
424242,SUBTLE,1.1875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.996339007,0.800000000,0.002055394,0.011251466,0.006260373
424242,SUBTLE,1.40625,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.000176897,0.800000000,0.002055394,0.011251466,0.057094276
424242,SUBTLE,2.046875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.000000000,0.800000000,0.002055394,0.011251466,0.057094276
424242,SUBTLE,2.140625,0.197677626,0.995355253,0.002322374,2.276776264,0.165164396,0.898932676,0.800000000,0.002055394,0.011251466,0.057094276
424242,SUBTLE,2.1875,0.173438404,0.946876808,0.026561596,2.034384042,-0.198423937,0.993117395,0.800000000,0.002055394,0.011251466,0.057094276
424242,SUBTLE,2.3125,0.176561596,1.016871021,-0.008435511,1.879684787,-0.194074617,1.025558515,0.800000000,0.002055394,0.011251466,0.057094276
424242,SUBTLE,3.0,0.200000000,1.120000000,-0.060000000,1.950000000,0.120000000,1.000000000,0.800000000,-0.066899248,-0.041100786,-1.858312447
424242,SUBTLE,4.015625,0.195819728,1.114426303,-0.057213152,1.924453891,0.114426303,1.000000000,0.800000000,-0.066899248,-0.041100786,-1.858312447
424242,SUBTLE,4.203125,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.066899248,-0.041100786,-1.858312447
424242,SUBTLE,5.046875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.066899248,-0.041100786,-1.858312447
424242,SUBTLE,7.296875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,0.003511020,-0.015434375,0.097528335
424242,SUBTLE,9.71875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.011110843,0.015630028,-0.308634528
424242,NORMAL,0.296875,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,1.000000000,0.800000000,0.000000000,0.000000000,0.000000000
424242,NORMAL,0.984375,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,0.708845237,0.800000000,0.000000000,0.000000000,0.000000000
424242,NORMAL,1.015625,0.151064871,1.000000000,0.000000000,1.810648709,0.004259484,0.710660807,0.800000000,0.000000000,0.000000000,0.000000000
424242,NORMAL,1.078125,0.170080797,1.000000000,0.000000000,2.000807966,0.080323187,0.973099976,0.800000000,0.001890720,0.010350020,0.000000000
424242,NORMAL,1.1875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.066232228,0.800000000,0.003425657,0.018752443,0.006260373
424242,NORMAL,1.40625,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.995432132,0.800000000,0.003425657,0.018752443,0.057094276
424242,NORMAL,2.046875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.000000000,0.800000000,0.003425657,0.018752443,0.057094276
424242,NORMAL,2.140625,0.198935129,0.997870258,0.001064871,2.289351291,0.184026936,0.778918317,0.800000000,0.003425657,0.018752443,0.057094276
424242,NORMAL,2.1875,0.186101734,0.972203468,0.013898266,2.161017340,-0.008473990,0.908881849,0.800000000,0.003425657,0.018752443,0.057094276
424242,NORMAL,2.3125,0.171950277,0.977256393,0.011371803,1.922214912,-0.242983064,1.108842452,0.800000000,0.003425657,0.018752443,0.057094276
424242,NORMAL,3.0,0.200000000,1.120000000,-0.060000000,1.950000000,0.120000000,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,NORMAL,4.015625,0.198083232,1.117444310,-0.058722155,1.938286420,0.117444310,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,NORMAL,4.203125,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,NORMAL,5.046875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,NORMAL,7.296875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,0.005851700,-0.025723958,0.097528335
424242,NORMAL,9.71875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.018518072,0.026050047,-0.308634528
424242,BOLD,0.296875,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,1.000000000,0.800000000,0.000000000,0.000000000,0.000000000
424242,BOLD,0.984375,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,0.558774481,0.800000000,0.000000000,0.000000000,0.000000000
424242,BOLD,1.015625,0.150608188,1.000000000,0.000000000,1.806081881,0.002432753,0.572060484,0.800000000,0.000000000,0.000000000,0.000000000
424242,BOLD,1.078125,0.162445229,1.000000000,0.000000000,1.924452291,0.049780916,0.861009315,0.800000000,0.001890720,0.010350020,0.000000000
424242,BOLD,1.1875,0.193869019,1.000000000,0.000000000,2.238690186,0.175476074,1.114853613,0.800000000,0.003425657,0.018752443,0.006260373
424242,BOLD,1.40625,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.985305561,0.800000000,0.003425657,0.018752443,0.057094276
424242,BOLD,2.046875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.999432230,0.800000000,0.003425657,0.018752443,0.057094276
424242,BOLD,2.140625,0.199391812,0.998783624,0.000608188,2.293918119,0.190877178,0.678583195,0.800000000,0.003425657,0.018752443,0.057094276
424242,BOLD,2.1875,0.191593538,0.983187075,0.008406462,2.215935375,0.073903063,0.809200384,0.800000000,0.003425657,0.018752443,0.057094276
424242,BOLD,2.3125,0.177904185,0.975983881,0.012008060,2.020196617,-0.144887559,1.145501129,0.800000000,0.003425657,0.018752443,0.057094276
424242,BOLD,3.0,0.200000000,1.120000000,-0.060000000,1.950000000,0.120000000,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,BOLD,4.015625,0.198905261,1.118540348,-0.059270174,1.943309931,0.118540348,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,BOLD,4.203125,0.115721019,1.007628025,-0.003814013,1.434961783,0.007628025,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,BOLD,5.046875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.111498747,-0.068501310,-1.858312447
424242,BOLD,7.296875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,0.005851700,-0.025723958,0.097528335
424242,BOLD,9.71875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.018518072,0.026050047,-0.308634528
-3750763034362895579,SUBTLE,0.296875,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,1.000000000,0.800000000,0.018880963,0.003234945,0.207661286
-3750763034362895579,SUBTLE,0.984375,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,0.884247017,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,1.015625,0.152322374,1.000000000,0.000000000,1.823223736,0.009289494,0.872413424,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,1.078125,0.185983545,1.000000000,0.000000000,2.159835448,0.143934179,1.031534818,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,1.1875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.996339007,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,1.40625,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.000176897,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,2.046875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.000000000,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,2.140625,0.197677626,0.995355253,0.002322374,2.276776264,0.165164396,0.898932676,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,2.1875,0.173438404,0.946876808,0.026561596,2.034384042,-0.198423937,0.993117395,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,2.3125,0.176561596,1.016871021,-0.008435511,1.879684787,-0.194074617,1.025558515,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,3.0,0.200000000,1.120000000,-0.060000000,1.950000000,0.120000000,1.000000000,0.800000000,0.018880963,0.003234945,0.524471201
-3750763034362895579,SUBTLE,4.015625,0.195819728,1.114426303,-0.057213152,1.924453891,0.114426303,1.000000000,0.800000000,0.014019058,0.059531140,0.389418291
-3750763034362895579,SUBTLE,4.203125,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,0.014019058,0.059531140,0.389418291
-3750763034362895579,SUBTLE,5.046875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.087011556,0.031855529,0.389418291
-3750763034362895579,SUBTLE,7.296875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.035360803,-0.025086514,-2.416764675
-3750763034362895579,SUBTLE,9.71875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.006670544,-0.015146110,-0.185292902
-3750763034362895579,NORMAL,0.296875,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,1.000000000,0.800000000,0.031468272,0.005391576,0.207661286
-3750763034362895579,NORMAL,0.984375,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,0.708845237,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,1.015625,0.151064871,1.000000000,0.000000000,1.810648709,0.004259484,0.710660807,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,1.078125,0.170080797,1.000000000,0.000000000,2.000807966,0.080323187,0.973099976,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,1.1875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.066232228,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,1.40625,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.995432132,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,2.046875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,1.000000000,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,2.140625,0.198935129,0.997870258,0.001064871,2.289351291,0.184026936,0.778918317,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,2.1875,0.186101734,0.972203468,0.013898266,2.161017340,-0.008473990,0.908881849,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,2.3125,0.171950277,0.977256393,0.011371803,1.922214912,-0.242983064,1.108842452,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,3.0,0.200000000,1.120000000,-0.060000000,1.950000000,0.120000000,1.000000000,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,NORMAL,4.015625,0.198083232,1.117444310,-0.058722155,1.938286420,0.117444310,1.000000000,0.800000000,0.023365097,0.099218567,0.389418291
-3750763034362895579,NORMAL,4.203125,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,0.023365097,0.099218567,0.389418291
-3750763034362895579,NORMAL,5.046875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.145019261,0.053092548,0.389418291
-3750763034362895579,NORMAL,7.296875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.058934671,-0.041810857,-2.416764675
-3750763034362895579,NORMAL,9.71875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.011117574,-0.025243517,-0.185292902
-3750763034362895579,BOLD,0.296875,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,1.000000000,0.800000000,0.031468272,0.005391576,0.207661286
-3750763034362895579,BOLD,0.984375,0.150000000,1.000000000,0.000000000,1.800000000,0.000000000,0.558774481,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,1.015625,0.150608188,1.000000000,0.000000000,1.806081881,0.002432753,0.572060484,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,1.078125,0.162445229,1.000000000,0.000000000,1.924452291,0.049780916,0.861009315,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,1.1875,0.193869019,1.000000000,0.000000000,2.238690186,0.175476074,1.114853613,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,1.40625,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.985305561,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,2.046875,0.200000000,1.000000000,0.000000000,2.300000000,0.200000000,0.999432230,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,2.140625,0.199391812,0.998783624,0.000608188,2.293918119,0.190877178,0.678583195,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,2.1875,0.191593538,0.983187075,0.008406462,2.215935375,0.073903063,0.809200384,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,2.3125,0.177904185,0.975983881,0.012008060,2.020196617,-0.144887559,1.145501129,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,3.0,0.200000000,1.120000000,-0.060000000,1.950000000,0.120000000,1.000000000,0.800000000,0.031468272,0.005391576,0.524471201
-3750763034362895579,BOLD,4.015625,0.198905261,1.118540348,-0.059270174,1.943309931,0.118540348,1.000000000,0.800000000,0.023365097,0.099218567,0.389418291
-3750763034362895579,BOLD,4.203125,0.115721019,1.007628025,-0.003814013,1.434961783,0.007628025,1.000000000,0.800000000,0.023365097,0.099218567,0.389418291
-3750763034362895579,BOLD,5.046875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.145019261,0.053092548,0.389418291
-3750763034362895579,BOLD,7.296875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.058934671,-0.041810857,-2.416764675
-3750763034362895579,BOLD,9.71875,0.110000000,1.000000000,0.000000000,1.400000000,0.000000000,1.000000000,0.800000000,-0.011117574,-0.025243517,-0.185292902
""".trimIndent().lines()

    private fun levelOf(name: String): ActingLevel = ActingParams.levelFor(ActingIntensity.parse(name))

    @Test
    fun theKotlinModelMatchesThePythonPrototype() {
        assertTrue(reference.size == 96)
        val f = ResolvedFace()
        for (row in reference) {
            val c = row.split(",")
            val sd = c[0].toLong()
            val lv = levelOf(c[1])
            val t = c[2].toDouble()
            val tilt = FaceMotionModel.resolve(refTimeline, lv, sd, t, 0.8f, 0.15f, f)
            val tag = "seed=$sd ${c[1]} t=$t"
            near("$tag eyeRad", c[3].toDouble(), f.eyeRadiusFrac.toDouble(), 2e-6)
            near("$tag mouthW", c[4].toDouble(), f.mouthWidthMul.toDouble(), 2e-6)
            near("$tag mouthB", c[5].toDouble(), f.mouthCyBiasFrac.toDouble(), 2e-6)
            near("$tag browH", c[6].toDouble(), f.browHeight.toDouble(), 2e-6)
            near("$tag browL", c[7].toDouble(), f.browInnerLift.toDouble(), 2e-6)
            near("$tag heightMul", c[8].toDouble(), f.eyeHeightMul.toDouble(), 2e-6)
            near("$tag openness", c[9].toDouble(), f.eyeOpenness.toDouble(), 2e-6)
            near("$tag gazeX", c[10].toDouble(), f.gazeX.toDouble(), 2e-6)
            near("$tag gazeY", c[11].toDouble(), f.gazeY.toDouble(), 2e-6)
            near("$tag tilt", c[12].toDouble(), tilt, 2e-6)
        }
    }

    @Test
    fun theHashEqualsThePythonPrototype() {
        near("a", 0.9509495449904665, FaceMotionModel.unit(424242L, 0, 1), 1e-15)
        near("b", 0.9125137712079789, FaceMotionModel.unit(424242L, 5, 2), 1e-15)
        near("c", 0.6898373186392688, FaceMotionModel.unit(424242L, 17, 7), 1e-15)
        near("d", 0.7723204119926173, FaceMotionModel.unit(424242L, 123456, 3), 1e-15)
        near("e", 0.09030102074725044, FaceMotionModel.unit(-3750763034362895579L, 0, 1), 1e-15)
        near("f", 0.17496145796783635, FaceMotionModel.unit(-3750763034362895579L, 5, 2), 1e-15)
        near("g", 0.5570307028768634, FaceMotionModel.unit(-3750763034362895579L, 17, 7), 1e-15)
        near("h", 0.18071536321731874, FaceMotionModel.unit(-3750763034362895579L, 123456, 3), 1e-15)
    }

    // ── Steady state equals the legacy numbers ─────────────────────────────

    @Test
    fun steadyStateEqualsTheLegacyFaceForAllSixExpressions() {
        // The legacy numbers come from ResolvedFace.setLegacy, which FaceGeometryTest proves bit-equal to the legacy
        // functions (derived BY READING the old code, see that test). The brows are the acting table: worried and
        // angry are the legacy 1.8 and +-0.55; the other four exist only under acting.
        for ((name, lv) in levels) for (e in allExpressions) {
            val tl = FaceTimeline(Expression.NORMAL, floatArrayOf(1.0f), intArrayOf(e))
            val face = ResolvedFace()
            FaceMotionModel.resolve(tl, lv, seed, 1.0 + ActingParams.FACE_SETTLE_SEC + 0.01, 1f, 0.15f, face)
            val legacy = ResolvedFace().setLegacy(e, 1f)
            near("$name e=$e eyeRadius", legacy.eyeRadiusFrac.toDouble(), face.eyeRadiusFrac.toDouble(), 1e-6)
            near("$name e=$e mouthW", legacy.mouthWidthMul.toDouble(), face.mouthWidthMul.toDouble(), 1e-6)
            near("$name e=$e mouthBias", legacy.mouthCyBiasFrac.toDouble(), face.mouthCyBiasFrac.toDouble(), 1e-6)
            near("$name e=$e heightMul", 1.0, face.eyeHeightMul.toDouble(), 1e-9)
            if (e == Expression.WORRIED || e == Expression.ANGRY) {
                near("$name e=$e browH", legacy.browHeight.toDouble(), face.browHeight.toDouble(), 1e-6)
                near("$name e=$e browL", legacy.browInnerLift.toDouble(), face.browInnerLift.toDouble(), 1e-6)
            }
            assertTrue("brows are drawn under acting", face.browsVisible)
        }
    }

    @Test
    fun withoutAnyChangeTheInitialExpressionIsSteadyFromTheStart() {
        // The legacy engine shows the first keyframe's expression even before its time: no change means no blend.
        for (e in allExpressions) {
            val tl = FaceTimeline(e, FloatArray(0), IntArray(0))
            val f = ResolvedFace()
            FaceMotionModel.resolve(tl, ActingParams.NORMAL_LEVEL, seed, 0.0, 1f, 0.15f, f)
            near("e=$e", ResolvedFace().setLegacy(e, 1f).eyeRadiusFrac.toDouble(), f.eyeRadiusFrac.toDouble(), 1e-6)
            near("mul", 1.0, f.eyeHeightMul.toDouble(), 1e-12)
        }
    }

    // ── The timeline of changes ────────────────────────────────────────────

    @Test
    fun theChangesComeFromTheKeyframesAndFollowTheLegacyRule() {
        val kfs = EngineHarness.compile(com.example.data.AnimScript(events = listOf(
            com.example.data.ScriptEvent(timeSec = 0f, pose = "stand_straight", duration = 0.4f, expression = "worried"),
            com.example.data.ScriptEvent(timeSec = 1f, pose = "think", duration = 0.4f),
            com.example.data.ScriptEvent(timeSec = 2f, pose = "wave", duration = 0.4f, expression = "happy"),
            com.example.data.ScriptEvent(timeSec = 3f, pose = "stand_straight", duration = 0.4f, expression = "happy"),
            com.example.data.ScriptEvent(timeSec = 4f, pose = "think", duration = 0.4f, expression = "normal")
        )))
        val tl = FaceTimeline.fromKeyframes(kfs)
        // The first keyframe's expression holds from the start; the keyframe that merely carries it forward is no change.
        assertTrue(tl.expr0 == Expression.WORRIED)
        assertTrue("changes=${tl.times.toList()}/${tl.exprs.toList()}", tl.times.toList() == listOf(2f, 4f) && tl.exprs.toList() == listOf(Expression.HAPPY, Expression.NORMAL))
        assertTrue(FaceTimeline.fromKeyframes(emptyList()).size == 0)
    }

    // ── Continuity and bounds ──────────────────────────────────────────────

    @Test
    fun theFaceIsContinuousAndBoundedEvenWithChangesClosetogether() {
        val tl = FaceTimeline(Expression.NORMAL, floatArrayOf(1.0f, 1.2f, 1.25f, 2.0f, 2.03125f, 2.0625f),
            intArrayOf(Expression.WIDE, Expression.ANGRY, Expression.HAPPY, Expression.WORRIED, Expression.SQUINT, Expression.WIDE))
        for ((name, lv) in levels) {
            val a = ResolvedFace(); val b = ResolvedFace()
            var maxHeightStep = 0.0; var maxRadiusStep = 0.0; var maxBrowStep = 0.0
            val steps = 240 * 5
            for (i in 0 until steps) {
                FaceMotionModel.resolve(tl, lv, seed, i / 240.0, 1f, 0.15f, a)
                FaceMotionModel.resolve(tl, lv, seed, (i + 1) / 240.0, 1f, 0.15f, b)
                maxHeightStep = max(maxHeightStep, abs((b.eyeHeightMul - a.eyeHeightMul).toDouble()))
                maxRadiusStep = max(maxRadiusStep, abs((b.eyeRadiusFrac - a.eyeRadiusFrac).toDouble()))
                maxBrowStep = max(maxBrowStep, abs((b.browHeight - a.browHeight).toDouble()))
                for ((what, v) in listOf("mul" to a.eyeHeightMul, "radius" to a.eyeRadiusFrac, "browH" to a.browHeight, "browL" to a.browInnerLift, "w" to a.mouthWidthMul,
                    "gx" to a.gazeX, "gy" to a.gazeY, "open" to a.eyeOpenness)) {
                    assertTrue("$name $what is finite at step $i", v.isFinite())
                }
                assertTrue("$name height multiplier in range at step $i: ${a.eyeHeightMul}", a.eyeHeightMul in 0.2f..1.25f)
                assertTrue("$name radius in range", a.eyeRadiusFrac in 0.10f..0.21f)
                assertTrue("$name openness in range", a.eyeOpenness in 0f..1f)
            }
            assertTrue("$name height step $maxHeightStep", maxHeightStep < 0.08)
            assertTrue("$name radius step $maxRadiusStep", maxRadiusStep < 0.01)
            assertTrue("$name brow step $maxBrowStep", maxBrowStep < 0.12)
        }
    }

    @Test
    fun theAnticipationSquintEndsAtTheChangeAndTheTakeOvershoots() {
        val tl = FaceTimeline(Expression.NORMAL, floatArrayOf(1.0f), intArrayOf(Expression.WIDE))
        for ((name, lv) in levels) {
            val f = ResolvedFace()
            FaceMotionModel.resolve(tl, lv, seed, 1.0 - lv.faceAnticipationSec - 0.001, 1f, 0.15f, f)
            near("$name before the squint", 1.0, f.eyeHeightMul.toDouble(), 1e-9)
            var minM = 9.0; var maxM = 0.0
            var t = 1.0 - lv.faceAnticipationSec
            while (t < 1.7) {
                FaceMotionModel.resolve(tl, lv, seed, t, 1f, 0.15f, f)
                minM = minOf(minM, f.eyeHeightMul.toDouble()); maxM = maxOf(maxM, f.eyeHeightMul.toDouble())
                t += 0.0005
            }
            val depth = ActingParams.FACE_ANT_DEPTH[Expression.WIDE] * lv.faceTakeScale
            near("$name squint depth", 1.0 - depth, minM, 0.01)
            assertTrue("$name the take overshoots 1: $maxM", maxM > 1.02)
            FaceMotionModel.resolve(tl, lv, seed, 1.0, 1f, 0.15f, f)
            near("$name value at the change", 1.0 - depth, f.eyeHeightMul.toDouble(), 1e-6)
        }
        // NORMAL peaks near the brief's 10 percent (the squint and the kick do not peak together; see V2_DECISIONS).
        val f = ResolvedFace(); var mx = 0.0; var t = 1.0
        while (t < 1.7) { FaceMotionModel.resolve(tl, ActingParams.NORMAL_LEVEL, seed, t, 1f, 0.15f, f); mx = max(mx, f.eyeHeightMul.toDouble()); t += 0.0005 }
        assertTrue("NORMAL take peak $mx", mx in 1.05..1.12)
    }

    // ── Gaze ───────────────────────────────────────────────────────────────

    @Test
    fun saccadeIntervalsAreBetweenOneAndThreePointTwoSeconds() {
        for (sd in listOf(seed, 424242L, -3750763034362895579L, 0L)) {
            var minGap = 99.0; var maxGap = 0.0
            for (k in 0L until 3000L) {
                val g = FaceMotionModel.saccadeTime(sd, k + 1) - FaceMotionModel.saccadeTime(sd, k)
                minGap = minOf(minGap, g); maxGap = max(maxGap, g)
            }
            assertTrue("seed $sd min gap $minGap", minGap >= 1.0 - 1e-9)
            assertTrue("seed $sd max gap $maxGap", maxGap <= 3.2 + 1e-9)
        }
    }

    @Test
    fun gazeAndHeadTiltStayInsideTheirCaps() {
        for ((name, lv) in levels) for (sd in listOf(seed, 424242L, -3750763034362895579L)) {
            val f = ResolvedFace()
            var maxGaze = 0.0; var maxTilt = 0.0; var blinks = 0
            val tl = FaceTimeline(Expression.NORMAL, FloatArray(0), IntArray(0))
            for (i in 0 until 60 * 400) {
                val tilt = FaceMotionModel.resolve(tl, lv, sd, i / 60.0, 1f, 0.15f, f)
                maxGaze = max(maxGaze, hypot(f.gazeX.toDouble(), f.gazeY.toDouble()))
                maxTilt = max(maxTilt, abs(tilt))
                if (f.eyeOpenness < 0.99f) blinks++
            }
            assertTrue("$name seed $sd gaze $maxGaze", maxGaze <= ActingParams.GAZE_CAP + 1e-6)
            assertTrue("$name seed $sd tilt $maxTilt", maxTilt <= ActingParams.HEAD_TILT_MAX_DEG + 1e-9)
            assertTrue("$name seed $sd saw gaze move", maxGaze > 0.02)
            assertTrue("$name seed $sd saw a coupled blink", blinks > 0)
        }
    }

    @Test
    fun aBlinkCoupledToAShiftOnlyHappensForLargeShiftsAndTakesTheSmallerOpenness() {
        val tl = FaceTimeline(Expression.NORMAL, FloatArray(0), IntArray(0))
        val f = ResolvedFace()
        val lv = ActingParams.NORMAL_LEVEL
        // The legacy blink wins when it is more closed than the coupled one.
        FaceMotionModel.resolve(tl, lv, seed, 5.0, 0.2f, 0.15f, f)
        assertTrue(f.eyeOpenness <= 0.2f)
        // A coupled blink never opens the eye further than the legacy openness.
        for (i in 0 until 60 * 100) {
            FaceMotionModel.resolve(tl, lv, seed, i / 60.0, 0.5f, 0.15f, f)
            assertTrue(f.eyeOpenness <= 0.5f)
        }
        // The probability among large shifts is about 0.35 (seeded, so a fixed number with a wide margin).
        var big = 0; var withBlink = 0
        val a = DoubleArray(2); val b = DoubleArray(2)
        for (k in 1L until 4000L) {
            FaceMotionModel.gazeTarget(seed, k - 1, 1.0, a); FaceMotionModel.gazeTarget(seed, k, 1.0, b)
            if (hypot(b[0] - a[0], b[1] - a[1]) > ActingParams.GAZE_BLINK_SHIFT) {
                big++
                if (FaceMotionModel.unit(seed, k, 7) < ActingParams.GAZE_P_BLINK) withBlink++
            }
        }
        val rate = withBlink.toDouble() / big
        assertTrue("blink rate $rate of $big", rate in 0.28..0.42)
    }

    @Test
    fun aLevelWithoutGazeHasNoGazeAndNoTilt() {
        val l = ActingLevel(0.025f, 0.06f, 0.06f, 6f, 0.05f, 5f, 0.18f, 0.10f, 0.25f, 0.060f, 1.0f, 0f)
        val f = ResolvedFace()
        val tl = FaceTimeline(Expression.NORMAL, FloatArray(0), IntArray(0))
        for (i in 0 until 600) {
            val tilt = FaceMotionModel.resolve(tl, l, seed, i / 30.0, 1f, 0.15f, f)
            assertTrue(f.gazeX == 0f && f.gazeY == 0f && tilt == 0.0 && f.eyeOpenness == 1f)
        }
    }

    // ── Determinism, seek equals sequential, OFF ───────────────────────────

    @Test
    fun theResultIsAPureFunctionOfItsInputs() {
        val a = ResolvedFace(); val b = ResolvedFace()
        val times = (0 until 400).map { it * 0.0173 }
        val forward = times.map { t -> FaceMotionModel.resolve(refTimeline, ActingParams.NORMAL_LEVEL, seed, t, 1f, 0.15f, a); listOf(a.eyeRadiusFrac, a.eyeHeightMul, a.gazeX, a.gazeY, a.eyeOpenness, a.browHeight) }
        val shuffled = times.indices.shuffled(java.util.Random(5)).map { i ->
            FaceMotionModel.resolve(refTimeline, ActingParams.NORMAL_LEVEL, seed, times[i], 1f, 0.15f, b); i to listOf(b.eyeRadiusFrac, b.eyeHeightMul, b.gazeX, b.gazeY, b.eyeOpenness, b.browHeight)
        }
        for ((i, v) in shuffled) assertTrue("t=${times[i]}", v == forward[i])
    }

    private val noAmplitude = AmplitudeSettings(enabled = false)

    @Test
    fun seekEqualsSequentialForTheRenderedFaceOnEveryFixture() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            val kfs = EngineHarness.compile(script)
            val total = EngineHarness.totalSec(kfs, 0f)
            val envelope = GoldenSnapshot.envelopeFor(total)
            val walker = EngineHarness.create(script, envelope, noAmplitude, kfs)
            walker.actingContext = ActingContext.create("NORMAL", name)
            var i = 0
            for (t in GoldenSnapshot.checkpoints(kfs, total)) {
                val target = (t * EngineHarness.ENV_FPS).toInt()
                while (i <= target) { EngineHarness.seekFrame(walker, envelope, i.toFloat() / EngineHarness.ENV_FPS); i++ }
                val fresh = EngineHarness.create(script, envelope, noAmplitude, kfs)
                fresh.actingContext = ActingContext.create("NORMAL", name)
                fresh.seekTo(target.toFloat() / EngineHarness.ENV_FPS)
                val a = walker.renderFace!!; val b = fresh.renderFace!!
                val tag = "$name t=$t"
                near("$tag radius", a.eyeRadiusFrac.toDouble(), b.eyeRadiusFrac.toDouble(), 1e-4)
                near("$tag mul", a.eyeHeightMul.toDouble(), b.eyeHeightMul.toDouble(), 1e-4)
                near("$tag gx", a.gazeX.toDouble(), b.gazeX.toDouble(), 1e-4); near("$tag gy", a.gazeY.toDouble(), b.gazeY.toDouble(), 1e-4)
                near("$tag browH", a.browHeight.toDouble(), b.browHeight.toDouble(), 1e-4)
                near("$tag head", walker.renderAngles[1].toDouble(), fresh.renderAngles[1].toDouble(), 1e-4)
            }
        }
    }

    @Test
    fun withActingOffThereIsNoActedFaceAndTheLegacyStateIsUntouched() {
        val script = Fixtures.script("02_poses_expressions")
        val envelope = TestEnvelopes.silence(20f)
        for (ctx in listOf<ActingContext?>(null, ActingContext.create("OFF", "p"))) {
            val e = EngineHarness.create(script, envelope, noAmplitude)
            e.actingContext = ctx
            for (t in listOf(0f, 1.5f, 3f, 6f)) {
                e.seekTo(t)
                assertTrue("renderFace must be null when OFF", e.renderFace == null)
            }
        }
        // And with acting on, the engine's own legacy expression and blink are still the discrete ones.
        val on = EngineHarness.create(script, envelope, noAmplitude); on.actingContext = ActingContext.create("NORMAL", "p")
        val off = EngineHarness.create(script, envelope, noAmplitude)
        for (t in listOf(0f, 1.5f, 3f, 6f)) {
            on.seekTo(t); off.seekTo(t)
            assertTrue(on.currentExpression == off.currentExpression)
            assertTrue(on.currentEyeOpenness == off.currentEyeOpenness)
            assertTrue(on.renderFace != null)
        }
    }

    @Test
    fun theFaceNeverReadsTheEnvelopeSoTheMouthStaysWithTheLegacyShapeForNow() {
        // WP2.4 will give the mouth continuous parameters; until then the acted face carries the expression-driven mouth
        // width and bias only, and the renderers still take the mouth SHAPE and openness from the engine.
        val f = ResolvedFace()
        FaceMotionModel.resolve(refTimeline, ActingParams.NORMAL_LEVEL, seed, 10.0, 1f, 0.15f, f)
        near("steady squint mouth width", 1.0, f.mouthWidthMul.toDouble(), 1e-9)
    }
}
