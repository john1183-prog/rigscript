package com.example.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Anti-aliasing for the GLES renderer's plain filled polygons (WP1.4 / R3). Pure: no Android types.
 *
 * `drawSolidTriangles` and `drawSolidFan` rasterize hard-edged triangles (no MSAA is requested), so a polygon's slanted
 * edges, such as a mountain ridge, show stair steps that Canvas anti-aliases. The lines, circles and ovals have their own
 * analytic anti-aliasing; this gives the polygons the same kind of treatment without depending on the driver: for every
 * BOUNDARY edge of the polygon (an edge that belongs to exactly one triangle) a one-pixel strip is added just outside it
 * whose colour is the polygon's but whose alpha falls from the polygon's own to 0, plus a small wedge at each corner so
 * the strips join. Drawn over the polygon with ordinary alpha blending, a pixel whose centre is d pixels outside the edge
 * gets alpha 1 - d.
 *
 * What it does not do, on purpose: it does not shrink the polygon, so a shape grows by about half a pixel on average
 * (the ramp starts at the true edge instead of being centred on it); and interior seams between triangles that fail to
 * match exactly only produce a strip of the same colour over the same colour, which is invisible.
 */
object PolygonFringe {

    /** Width of the fringe in pixels. */
    const val DEFAULT_WIDTH_PX = 1f

    /** Vertices are the same vertex if they agree to a sixty-fourth of a pixel. */
    private const val KEY_SCALE = 64f

    /** Edges shorter than this (pixels) are skipped: they have no direction to push outward along. */
    private const val MIN_EDGE_PX = 1e-3f

    /** A triangle list: three vertices per triangle, an (x, y) pair and an ARGB colour for each. */
    class Fringe(val xy: FloatArray, val colorsArgb: IntArray) {
        val isEmpty: Boolean get() = colorsArgb.isEmpty()
    }

    private val EMPTY = Fringe(FloatArray(0), IntArray(0))

    private class Edge(
        val ax: Float, val ay: Float, val bx: Float, val by: Float,
        val ca: Int, val cb: Int,
        val cx: Float, val cy: Float,
        var count: Int
    )

    private fun vertexKey(x: Float, y: Float): Long =
        (Math.round(x * KEY_SCALE).toLong() shl 32) xor (Math.round(y * KEY_SCALE).toLong() and 0xFFFFFFFFL)

    private fun transparent(argb: Int): Int = argb and 0x00FFFFFF

    /** Growable primitive buffers: this runs every frame, so no boxed Float or Int per vertex. */
    private class FloatList {
        var data = FloatArray(64)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    private class IntList {
        var data = IntArray(32)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): IntArray = data.copyOf(size)
    }

    /** The fringe of a triangle list ([xy] and [colorsArgb] as `drawSolidTriangles` takes them). */
    fun forTriangles(xy: FloatArray, colorsArgb: IntArray, widthPx: Float = DEFAULT_WIDTH_PX): Fringe {
        val vertexCount = minOf(xy.size / 2, colorsArgb.size)
        val triangles = vertexCount / 3
        if (triangles == 0 || !(widthPx > 0f) || !widthPx.isFinite()) return EMPTY

        // Count how many triangles use each edge (an edge is the same edge whichever way round it is walked).
        val edges = LinkedHashMap<Pair<Long, Long>, Edge>()
        for (t in 0 until triangles) {
            val i0 = t * 3
            val x0 = xy[i0 * 2]; val y0 = xy[i0 * 2 + 1]
            val x1 = xy[i0 * 2 + 2]; val y1 = xy[i0 * 2 + 3]
            val x2 = xy[i0 * 2 + 4]; val y2 = xy[i0 * 2 + 5]
            if (!(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite())) continue
            val area2 = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (abs(area2) < 1e-6f) continue                       // a sliver has no inside to point away from

            fun add(ax: Float, ay: Float, ca: Int, bx: Float, by: Float, cb: Int, cx: Float, cy: Float) {
                val ka = vertexKey(ax, ay)
                val kb = vertexKey(bx, by)
                val key = if (ka <= kb) Pair(ka, kb) else Pair(kb, ka)
                val existing = edges[key]
                if (existing != null) existing.count++
                else edges[key] = Edge(ax, ay, bx, by, ca, cb, cx, cy, 1)
            }
            add(x0, y0, colorsArgb[i0], x1, y1, colorsArgb[i0 + 1], x2, y2)
            add(x1, y1, colorsArgb[i0 + 1], x2, y2, colorsArgb[i0 + 2], x0, y0)
            add(x2, y2, colorsArgb[i0 + 2], x0, y0, colorsArgb[i0], x1, y1)
        }

        val outXy = FloatList()
        val outColors = IntList()
        fun vertex(x: Float, y: Float, argb: Int) { outXy.add(x); outXy.add(y); outColors.add(argb) }

        // Boundary vertices with the outward normal and colour of each boundary edge that touches them (for the corners).
        class Touch(val nx: Float, val ny: Float, val x: Float, val y: Float, val argb: Int)
        val touches = LinkedHashMap<Long, MutableList<Touch>>()

        for (e in edges.values) {
            if (e.count != 1) continue
            val dx = e.bx - e.ax
            val dy = e.by - e.ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < MIN_EDGE_PX) continue
            var nx = dy / len
            var ny = -dx / len
            // Point away from the triangle's third vertex.
            if (nx * (e.cx - e.ax) + ny * (e.cy - e.ay) > 0f) { nx = -nx; ny = -ny }

            val ox = nx * widthPx
            val oy = ny * widthPx
            val a0 = transparent(e.ca)
            val b0 = transparent(e.cb)
            // The strip: two triangles from the edge out to the edge pushed outward by the width.
            vertex(e.ax, e.ay, e.ca); vertex(e.bx, e.by, e.cb); vertex(e.bx + ox, e.by + oy, b0)
            vertex(e.ax, e.ay, e.ca); vertex(e.bx + ox, e.by + oy, b0); vertex(e.ax + ox, e.ay + oy, a0)

            touches.getOrPut(vertexKey(e.ax, e.ay)) { ArrayList() }.add(Touch(nx, ny, e.ax, e.ay, e.ca))
            touches.getOrPut(vertexKey(e.bx, e.by)) { ArrayList() }.add(Touch(nx, ny, e.bx, e.by, e.cb))
        }

        // A wedge at every corner where exactly two boundary edges meet, so the strips join instead of leaving a notch.
        for (list in touches.values) {
            if (list.size != 2) continue
            val p = list[0]
            val q = list[1]
            if (p.nx * q.nx + p.ny * q.ny > 0.9999f) continue       // a straight continuation has no corner to fill
            val c0 = transparent(p.argb)
            vertex(p.x, p.y, p.argb)
            vertex(p.x + p.nx * widthPx, p.y + p.ny * widthPx, c0)
            vertex(p.x + q.nx * widthPx, p.y + q.ny * widthPx, c0)
        }

        if (outColors.size == 0) return EMPTY
        return Fringe(outXy.toArray(), outColors.toArray())
    }

    /**
     * The fringe of a polygon drawn as a triangle fan from its first vertex, as `drawSolidFan` (and `GL_TRIANGLE_FAN`)
     * does: the triangles are (0, i, i + 1) for i from 1 to n - 2.
     */
    fun forFan(xy: FloatArray, colorsArgb: IntArray, widthPx: Float = DEFAULT_WIDTH_PX): Fringe {
        val n = minOf(xy.size / 2, colorsArgb.size)
        if (n < 3) return EMPTY
        val triXy = FloatArray((n - 2) * 6)
        val triColors = IntArray((n - 2) * 3)
        var o = 0
        var c = 0
        for (i in 1 until n - 1) {
            triXy[o++] = xy[0]; triXy[o++] = xy[1]; triColors[c++] = colorsArgb[0]
            triXy[o++] = xy[i * 2]; triXy[o++] = xy[i * 2 + 1]; triColors[c++] = colorsArgb[i]
            triXy[o++] = xy[i * 2 + 2]; triXy[o++] = xy[i * 2 + 3]; triColors[c++] = colorsArgb[i + 1]
        }
        return forTriangles(triXy, triColors, widthPx)
    }
}
