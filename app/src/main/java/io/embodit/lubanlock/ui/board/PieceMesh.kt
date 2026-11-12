package io.embodit.lubanlock.ui.board

import io.embodit.lubanlock.domain.Dir
import io.embodit.lubanlock.domain.Layout
import io.embodit.lubanlock.domain.Piece
import io.embodit.lubanlock.domain.V3
import io.embodit.lubanlock.domain.V3i

/**
 * The geometry of one stick, straight from the lock data (design entities3d.pieces: "完整 2 x 2 x 8 格去掉 notches 后剩下的格，
 * 合并成一个网格（去掉内部面）"): every face of a solid cell whose neighbour is empty. Faces on the 2 x 2 x 8 envelope go to
 * the [outer] part (wood + burnt numbers); faces inside the envelope are the notch walls ([cut]: the same wood 10 % darker,
 * vermilion in the exploded view). Positions are metres relative to the envelope centre; UVs address the stick's own
 * 512 x 512 texture ([Atlas]). The collider is the cells merged into <= 12 boxes (D17: collider = visible body).
 */
class PieceGeometry(val piece: Piece) {
    class Part {
        val pos = ArrayList<V3>()
        val nrm = ArrayList<V3>()
        val uv = ArrayList<FloatArray>()
        val idx = ArrayList<Int>()
        val triangles: Int get() = idx.size / 3
    }

    val outer = Part()
    val cut = Part()
    val boxes: List<Pair<V3, V3>> // centre, size (m)

    private val c = piece.centre
    private val u = Layout.U
    private val la = piece.axis

    init {
        val solid = piece.cellSet
        for (cell in piece.cells) for (d in Dir.entries) {
            if ((cell + d.v) in solid) continue
            val onEnvelope = if (d.sign > 0) cell[d.axis] + 1 == piece.max[d.axis] else cell[d.axis] == piece.min[d.axis]
            face(if (onEnvelope) outer else cut, cell, d, onEnvelope)
        }
        boxes = mergeBoxes(piece)
    }

    private fun m(x: Float, y: Float, z: Float) = V3((x - c[0]) * u, (y - c[1]) * u, (z - c[2]) * u)

    private fun face(part: Part, cell: V3i, d: Dir, envelope: Boolean) {
        val a = d.axis
        val b = (a + 1) % 3
        val cc = (a + 2) % 3
        val plane = cell[a] + if (d.sign > 0) 1 else 0
        fun corner(sb: Int, sc: Int): V3 {
            val p = FloatArray(3)
            p[a] = plane.toFloat(); p[b] = (cell[b] + sb).toFloat(); p[cc] = (cell[cc] + sc).toFloat()
            return m(p[0], p[1], p[2])
        }
        val q = listOf(corner(0, 0), corner(1, 0), corner(1, 1), corner(0, 1))
        val n = V3.of(d.v)
        val base = part.pos.size
        // wind counter-clockwise seen from outside: (b x c) == +a, so flip for the negative side
        val order = if (d.sign > 0) listOf(0, 1, 2, 3) else listOf(0, 3, 2, 1)
        for (i in order) {
            part.pos += q[i]; part.nrm += n
            part.uv += Atlas.uv(piece, d, q[i] * (1f / u) + V3(c[0], c[1], c[2]), envelope)
        }
        part.idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
    }

    companion object {
        /** Greedy merge of the solid cells into axis-aligned boxes (along the long axis first). */
        fun mergeBoxes(piece: Piece): List<Pair<V3, V3>> {
            val left = piece.cellSet.toMutableSet()
            val out = ArrayList<Pair<V3, V3>>()
            val la = piece.axis
            val ord = listOf(la, (la + 1) % 3, (la + 2) % 3)
            while (left.isNotEmpty()) {
                val start = left.minWith(compareBy<V3i>({ it[ord[2]] }, { it[ord[1]] }, { it[ord[0]] }))
                val lo = intArrayOf(start.x, start.y, start.z)
                val hi = intArrayOf(start.x + 1, start.y + 1, start.z + 1)
                fun all(l: IntArray, h: IntArray): Boolean {
                    for (x in l[0] until h[0]) for (y in l[1] until h[1]) for (z in l[2] until h[2]) if (V3i(x, y, z) !in left) return false
                    return true
                }
                for (ax in ord) {
                    while (true) {
                        val l2 = lo.copyOf(); val h2 = hi.copyOf(); l2[ax] = hi[ax]; h2[ax] = hi[ax] + 1
                        if (all(l2, h2)) hi[ax]++ else break
                    }
                }
                for (x in lo[0] until hi[0]) for (y in lo[1] until hi[1]) for (z in lo[2] until hi[2]) left.remove(V3i(x, y, z))
                val cc = piece.centre
                val u = Layout.U
                val centre = V3(((lo[0] + hi[0]) / 2f - cc[0]) * u, ((lo[1] + hi[1]) / 2f - cc[1]) * u, ((lo[2] + hi[2]) / 2f - cc[2]) * u)
                out += centre to V3((hi[0] - lo[0]) * u, (hi[1] - lo[1]) * u, (hi[2] - lo[2]) * u)
            }
            return out
        }
    }
}

/**
 * The 512 x 512 texture of one stick (pixels, y down; UV v = 1 - y / 512 because PBR samples v from the bottom on PICO,
 * memory note "bianzhong"): four 384 x 96 side strips (the long axis runs along u), two 96 x 96 end caps with the burnt
 * number, one plain end-grain tile for the notch walls across the axis. Each face picks the u direction (+/- long axis)
 * that keeps the burnt number upright and never mirrored: right x up = outward normal (profile D10).
 */
object Atlas {
    const val SIZE = 512
    const val PX = 48 // per cell
    const val STRIP_W = 8 * PX
    const val STRIP_H = 2 * PX
    const val END_X = STRIP_W
    const val GRAIN_Y = 2 * STRIP_H

    /** The four side normals of a stick in a fixed order (strip index 0..3). */
    fun sides(piece: Piece): List<V3i> {
        val a = piece.axis
        val b = (a + 1) % 3; val c = (a + 2) % 3
        return listOf(V3i.axis(b, 1), V3i.axis(b, -1), V3i.axis(c, 1), V3i.axis(c, -1))
    }

    private fun cross(a: V3i, b: V3i) = V3i(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    private fun dot(a: V3i, p: V3) = a.x * p.x + a.y * p.y + a.z * p.z

    /** Prefer text up = +y, then away from the user (-z). */
    private fun upness(t: V3i) = t.y * 2 - t.z

    /** For a face with outward normal [n] on a stick with long axis [l]: (right, up) unit axes. */
    fun frame(n: V3i, l: V3i): Pair<V3i, V3i> {
        if (n == l || n == -l) {
            val up = if (l.y != 0) V3i(0, 0, -1) else V3i(0, 1, 0)
            return cross(up, n) to up
        }
        val t1 = cross(n, l)
        return if (upness(t1) >= upness(-t1)) l to t1 else -l to -t1
    }

    /** UV of surface point [p] (cell units, lock frame) on a face of [piece] facing [d]. */
    fun uv(piece: Piece, d: Dir, p: V3, envelope: Boolean): FloatArray {
        val l = V3i.axis(piece.axis, 1)
        val n = d.v
        val (right, up) = frame(n, l)
        val cc = piece.centre
        val rel = V3(p.x - cc[0], p.y - cc[1], p.z - cc[2])
        val r = dot(right, rel)
        val t = dot(up, rel)
        val px: Float
        val py: Float
        if (d.axis == piece.axis) {
            // end cap (on the envelope) or a notch wall across the axis (plain end grain)
            val ox = END_X
            val oy = if (!envelope) GRAIN_Y else if (d.sign > 0) 0 else STRIP_H
            px = ox + (r + 1f) * PX
            py = oy + (1f - t) * PX
        } else {
            val k = sides(piece).indexOf(n).coerceAtLeast(0)
            px = (r + 4f) * PX
            py = k * STRIP_H + (1f - t) * PX
        }
        return floatArrayOf(px / SIZE, 1f - py / SIZE)
    }
}
