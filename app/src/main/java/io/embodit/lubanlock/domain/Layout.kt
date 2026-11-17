package io.embodit.lubanlock.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Metres, scene frame (design spatialLayout): origin = volume centre, +X right, +Y up, +Z toward the user. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    operator fun unaryMinus() = V3(-x, -y, -z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 { val l = length; return if (l < 1e-9f) this else this * (1f / l) }
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        fun of(v: V3i, k: Float = 1f) = V3(v.x * k, v.y * k, v.z * k)
    }
}

/** A unit quaternion (x, y, z, w). */
data class Q(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Q) = Q(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )
    fun rotate(v: V3): V3 {
        val u = V3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }
    fun dot(o: Q) = x * o.x + y * o.y + z * o.z + w * o.w

    fun slerp(o: Q, t: Float): Q {
        var b = o
        var d = dot(o)
        if (d < 0) { b = Q(-o.x, -o.y, -o.z, -o.w); d = -d }
        if (d > 0.9995f) return Q(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t).norm()
        val th = acos(d.coerceIn(-1f, 1f)); val s = sin(th)
        val ka = sin((1 - t) * th) / s; val kb = sin(t * th) / s
        return Q(x * ka + b.x * kb, y * ka + b.y * kb, z * ka + b.z * kb, w * ka + b.w * kb)
    }
    fun norm(): Q { val l = sqrt(x * x + y * y + z * z + w * w); return Q(x / l, y / l, z / l, w / l) }

    companion object {
        val I = Q(0f, 0f, 0f, 1f)
        fun axisAngle(a: V3, rad: Float): Q { val n = a.normalized(); val s = sin(rad / 2); return Q(n.x * s, n.y * s, n.z * s, cos(rad / 2)) }
        fun of(r: Rot): Q { val q = r.quat(); return Q(q[0], q[1], q[2], q[3]).norm() }
    }
}

/** A stick's world pose: centre (m) and rotation. */
data class PoseW(val pos: V3, val rot: Q)

/**
 * Every length of the volume (design container.volume.math / spatialLayout / occlusionCheck). Pure, unit-tested: the
 * volume bounds over all reachable poses x 8 turns x every exit / insert path, the tray, the eyes, the window frame.
 */
object Layout {
    /**
     * Unit cell at the 4x scale (v0.1.3, profile D54 "3D 主体撑满空间"; the user picked 4x on 2026-11-16): a stick is
     * 0.36 x 0.09 x 0.09 m and the lock 0.36 m. Every length below that came from the 2x design is doubled with it
     * ([K]); the arrows keep their 2x size in metres ([AU]): they are signs, not wood.
     */
    const val U = 0.045f
    /** The 2x design's unit cell: arrow sizes and the 2x numbers the rest is scaled from. */
    const val AU = 0.0225f
    const val K = U / AU

    /**
     * Two volume sizes (D54): [SMALL] while the lock stands assembled, [BIG] while anything can leave it (a stick that can
     * come out is picked, the hint shows a removal, a stick flies, a stick lies on the tray, the lock is opened up). The
     * assembled lock's world box is 0.354 x 0.360 x 0.398 m (a cross, turned 35°): its longest edge (depth) is 70 % of
     * SMALL_D and the others 57 % / 77 % (D54Test). SMALL is set by what shows in it: sticks nudged a cell, every turn,
     * and the arrows of a picked stick that cannot come out reach 0.264 / 0.225 / 0.269 m from the centre. The width stays
     * 0.62 m so the window, placed beside the small volume, ends up at the big volume's face (WINDOW_GAP). The scene
     * origin stays the volume centre and the lock centre in both; the floor (tray) is the big volume's.
     */
    const val SMALL_W = 0.62f
    const val SMALL_H = 0.47f
    const val SMALL_D = 0.565f
    const val VOL_W = 0.68f * K
    const val VOL_H = 0.78f * K
    const val VOL_D = 0.80f * K
    const val FLOOR_Y = -VOL_H / 2
    /** Lock outer box half size (8u cube centred on the volume centre). */
    const val LOCK_HALF = 4 * U
    const val TRAY_Z = 0.22f * K
    val TRAY_X = floatArrayOf(-0.25f * K, -0.15f * K, -0.05f * K, 0.05f * K, 0.15f * K, 0.25f * K)
    /** Centre height of a stick lying in the tray. */
    const val TRAY_Y = FLOOR_Y + U
    const val TRAY_TOP = FLOOR_Y + 2 * U
    /** Fly height (centre) above the tray: the stick's underside 2 cm (x K) over the tray's top. */
    const val FLY_Y = TRAY_TOP + 0.02f * K + U
    const val EXPLODE_CELLS = 3
    const val EXPLODE_YAW_DEG = 30f
    /** Arrows are signs only (the window's direction keys move the stick, rework v1.3); 2x size kept at 4x. */
    const val ARROW_LEN = 3 * AU
    const val ARROW_THICK = 0.5f * AU
    const val ARROW_GAP = 0.6f * AU
    const val ARROW_GAP_Z = 0.4f * AU
    const val ARROW_PITCH_DEG = 30f
    /** Margin kept between a flying stick and every stick it passes (design: lock box + 2 cm; here the real cells + 2 cm). */
    const val CLEAR = 0.02f * K

    /**
     * Eyes relative to the volume centre. The system's default placement was measured by QA #1 (2026-11-11): the volume
     * centre at (0, -0.061, -1.749) m from the head, so the eye sits at (0, +0.061, +1.749); plus the same distance 30° to
     * each side and a standing eye 0.45 m higher (design occlusionCheck, rework v1.3).
     */
    val EYES: List<Pair<String, V3>> = listOf(
        "default" to V3(0f, 0.061f, 1.749f), "default-L30" to V3(-0.8745f, 0.061f, 1.5147f), "default-R30" to V3(0.8745f, 0.061f, 1.5147f),
        "standing" to V3(0f, 0.511f, 1.69f),
    )
    /** The eye the app assumes at run time: Shared Space gives the app no head pose (WindowCtl: NOT_IN_FULL_SPACE). */
    val DEFAULT_EYE = EYES[0].second

    // ------------------------------------------------------------------ presentation (rework v1.3, QA q1-3)

    /**
     * The lock is shown three-quarter on: turned [PRES_YAW_DEG] about the vertical and tipped top-toward-you by
     * [PRES_PITCH_DEG] (QA #1: at the default placement, nearly level and 1.75 m away, a square-on lock reads as a flat
     * cross). The game's grid ("lock frame") stays exact; world = yaw * pitch * lock frame. The tray stays level and
     * square to the user.
     */
    const val PRES_YAW_DEG = 35f
    /** 0: a tipped lock makes a stick that slides out along a neighbour scrape it when the tip comes off (LayoutTest). */
    const val PRES_PITCH_DEG = 0f
    val YAW_Q: Q = Q.axisAngle(V3(0f, 1f, 0f), (PRES_YAW_DEG * Math.PI / 180).toFloat())
    fun pitchQ(frac: Float = 1f): Q = Q.axisAngle(V3(1f, 0f, 0f), (PRES_PITCH_DEG * frac * Math.PI / 180).toFloat())
    val PRES_Q: Q = YAW_Q * pitchQ()
    fun conj(q: Q) = Q(-q.x, -q.y, -q.z, q.w)

    /** Lock frame (view-turned grid, metres) -> world. */
    fun lockToWorld(p: V3): V3 = PRES_Q.rotate(p)
    /** World -> lock frame. */
    fun worldToLock(p: V3): V3 = conj(PRES_Q).rotate(p)
    /** World <-> the level, yawed frame the exit legs are planned in. */
    fun worldToYaw(p: V3): V3 = conj(YAW_Q).rotate(p)
    fun yawToWorld(p: V3): V3 = YAW_Q.rotate(p)

    fun trayCentre(p: Int) = V3(TRAY_X[p - 1], TRAY_Y, TRAY_Z)

    /** Cell coordinates (lock frame, unit cells) -> metres in the lock frame. */
    fun m(c: FloatArray) = V3(c[0] * U, c[1] * U, c[2] * U)

    /** The stick's centre in the lock frame (metres) at [off]. */
    fun localCentre(piece: Piece, off: V3i): V3 = m(piece.centre) + V3.of(off, U)

    /** In-lock pose of [piece] at [off] turned by [view] in the lock frame (not exploded, no presentation). */
    fun inLockFrame(piece: Piece, off: V3i, view: View): PoseW {
        val c = localCentre(piece, off)
        val w = view.rot.apply(c.x, c.y, c.z)
        return PoseW(V3(w[0], w[1], w[2]), Q.of(view.rot))
    }

    /** In-lock world pose (presentation applied). */
    fun inLock(piece: Piece, off: V3i, view: View): PoseW {
        val l = inLockFrame(piece, off, view)
        return PoseW(lockToWorld(l.pos), PRES_Q * l.rot)
    }

    /**
     * Lying in the tray (design spatialLayout.tray, rework v1.3 QA q1-6): long axis toward the user and the burnt number
     * on the end facing the user upright (profile D10): the end cap's "up" (PieceMesh Atlas: +y for lying sticks, -z for
     * standing ones) maps to +Y. The rotation maps the long axis to +Z and that vector to +Y.
     */
    fun trayRot(piece: Piece): Rot {
        val long = V3i.axis(piece.axis, 1)
        val up = if (piece.axis == 1) V3i(0, 0, -1) else V3i(0, 1, 0)
        val x = cross(up, long)
        // columns of R^T are (x, up, long) in local; R maps local -> world: local x -> ex, up -> ey, long -> ez
        return Rot.fromColumns(x, up, long).transpose
    }

    private fun cross(a: V3i, b: V3i) = V3i(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    /** Notches open toward side [s]: notch cells in the outer layer on that side. */
    fun notchesFacing(piece: Piece, s: V3i): Int {
        val a = if (s.x != 0) 0 else if (s.y != 0) 1 else 2
        val sign = s[a]
        val layer = if (sign > 0) piece.max[a] - 1 else piece.min[a]
        return piece.notches.count { it[a] == layer }
    }

    fun inTray(piece: Piece): PoseW = PoseW(trayCentre(piece.id), Q.of(trayRot(piece)))

    // ------------------------------------------------------------------ exit path (design spatialLayout.exit)

    /** World cells of the sticks still in the lock (except [skip]) turned by [view]. */
    fun worldCells(rules: Rules, pose: Pose, view: View, skip: Int? = null): List<V3i> = buildList {
        for (p in pose.inLock) if (p != skip) { val off = pose.at(p)!!; for (c in rules.lock.piece(p).cells) add(view.rot.cell(c + off)) }
    }

    /** Axis-aligned bounds (metres, world) of a set of world cells. */
    fun bounds(cells: Collection<V3i>): Pair<V3, V3> {
        var lo = V3(1e9f, 1e9f, 1e9f); var hi = V3(-1e9f, -1e9f, -1e9f)
        for (c in cells) {
            lo = V3(min(lo.x, c.x * U), min(lo.y, c.y * U), min(lo.z, c.z * U))
            hi = V3(max(hi.x, (c.x + 1) * U), max(hi.y, (c.y + 1) * U), max(hi.z, (c.z + 1) * U))
        }
        return lo to hi
    }

    /** The waypoints of one stick leaving the lock (scene frame), with a rotation at each; first = in the lock. */
    data class Path(val points: List<PoseW>, val slideCells: Int) {
        /** Cumulative lengths for timing (rotation counts as 0.1 m per radian). */
        val lengths: FloatArray = FloatArray(points.size).also { l ->
            for (i in 1 until points.size) {
                val a = points[i - 1]; val b = points[i]
                val ang = 2 * acos(abs(a.rot.dot(b.rot)).coerceIn(0f, 1f))
                l[i] = l[i - 1] + (b.pos - a.pos).length + 0.1f * ang
            }
        }
        val total: Float get() = lengths.last()

        /** Pose at fraction [t] of the length. */
        fun at(t: Float): PoseW {
            val s = t.coerceIn(0f, 1f) * total
            for (i in 1 until points.size) {
                if (s <= lengths[i] || i == points.lastIndex) {
                    val seg = (lengths[i] - lengths[i - 1]).coerceAtLeast(1e-6f)
                    val u = ((s - lengths[i - 1]) / seg).coerceIn(0f, 1f)
                    val a = points[i - 1]; val b = points[i]
                    return PoseW(a.pos.lerp(b.pos, u), a.rot.slerp(b.rot, u))
                }
            }
            return points.last()
        }

        fun reversed() = Path(points.reversed(), slideCells)
    }

    /**
     * The L-shaped way out (design spatialLayout.exit): ① slide along the exit direction until it could drop straight to
     * the table without touching a stick (out over the top: until it could move toward the user), then one cell more;
     * ② over the top -> toward the user past the lock's front + 3 cm, behind -> sideways past the lock's side + 3 cm;
     * ③ a flat stick first drops below the lock, then drops to 2 cm over the tray while it turns to lie flat; ④ across at
     * that height over its own slot; ⑤ down into the slot.
     */
    fun exitPath(rules: Rules, pose: Pose, p: Int, d: Dir, view: View): Path {
        val y = exitPathYaw(rules, pose, p, d, view)
        // to world: the in-lock part carries the pitch; it comes off on a short rigid arc about the lock centre right after
        // the slide (the stick is already clear of the lock there); the level legs are only yawed
        val pts = ArrayList<PoseW>()
        fun lockPt(a: PoseW, frac: Float) = PoseW(YAW_Q.rotate(pitchQ(frac).rotate(a.pos)), YAW_Q * pitchQ(frac) * a.rot)
        pts += lockPt(y.points[0], 1f)
        pts += lockPt(y.points[1], 1f)
        for (i in 1..ARC_STEPS) pts += lockPt(y.points[1], 1f - i.toFloat() / ARC_STEPS)
        for (i in 2 until y.points.size) pts += PoseW(yawToWorld(y.points[i].pos), YAW_Q * y.points[i].rot)
        return Path(pts, y.slideCells)
    }

    const val ARC_STEPS = 6
    /** The yawed-frame side (+1 / -1) a stick leaving out the back steps to: the one that stays nearer the world middle. */
    val BACK_SIDE: Int by lazy { if (kotlin.math.abs(yawToWorld(V3(0.2f, 0f, -0.2f)).x) <= kotlin.math.abs(yawToWorld(V3(-0.2f, 0f, -0.2f)).x)) 1 else -1 }
    /** Extra cells a stick slides out before the pitch comes off (the arc swings it a little). */
    const val PITCH_EXTRA_CELLS = 3

    /** The exit planned in the level, yawed frame with the lock unpitched; the tray converted into that frame. */
    private fun exitPathYaw(rules: Rules, pose: Pose, p: Int, d: Dir, view: View): Path {
        val piece = rules.lock.piece(p)
        val off = pose.at(p)!!
        val others = worldCells(rules, pose, view, skip = p)
        val occupied = others.toHashSet()
        val dw = view.rot.dir(d)
        fun cellsAt(k: Int) = piece.cells.map { view.rot.cell(it + off + d.v * k) }
        // ① ends when the next leg is free with one cell to spare (a stick sliding right against another counts as
        // touching): out over the top -> nothing in front of it (it goes toward the user next); out the back -> nothing
        // toward its tray side (it goes sideways next); otherwise nothing at or below it (it drops next)
        // out the back it steps sideways in the yawed frame; take the side the 35° yaw swings back toward the middle
        // (the other side reaches 0.36 m sideways in the world and needs a volume too wide for the window to fit, v1.3)
        val side = if (dw == Dir.NZ) BACK_SIDE else if (TRAY_X[p - 1] >= 0) 1 else -1
        // a standing stick leaving sideways lies down by swinging its top toward the user or away: its whole slab must be free
        val standing = halfExtents(piece, view.rot).y > 2 * U
        fun clear(k: Int): Boolean {
            val cs = cellsAt(k)
            if (cs.any { it in occupied }) return false
            if (standing && dw.axis == 0) return cs.none { c -> occupied.any { o -> abs(o.x - c.x) <= 1 } }
            // a standing stick leaving downward lies down under the lock (its top swings 4 cells sideways): its top must
            // be a cell below the lowest stick of the lock
            if (standing && dw == Dir.NY) { val bottom = occupied.minOfOrNull { it.y } ?: return true; return cs.all { it.y + 1 < bottom } }
            return when (dw) {
                Dir.PY -> cs.none { c -> occupied.any { o -> abs(o.x - c.x) <= 1 && abs(o.y - c.y) <= 1 && o.z >= c.z } }
                Dir.NZ -> cs.none { c -> occupied.any { o -> abs(o.y - c.y) <= 1 && abs(o.z - c.z) <= 1 && (o.x - c.x) * side >= 0 } }
                else -> cs.none { c -> occupied.any { o -> abs(o.x - c.x) <= 1 && abs(o.z - c.z) <= 1 && o.y <= c.y } }
            }
        }
        // the first k where the next leg is free, plus the one cell more of the design, which must be free as well (the
        // trailing end of a notched stick can come closer to another stick as it slides on)
        var k = 1
        while (k < 40 && !(clear(k) && clear(k + 1))) k++
        // going down it is already a cell below the whole lock; extra cells would take it through the floor
        k += 1 + if (dw == Dir.NY || PRES_PITCH_DEG == 0f) 0 else PITCH_EXTRA_CELLS
        val start = inLockFrame(piece, off, view)
        val r0 = start.rot
        fun centreAt(kk: Int): V3 = start.pos + V3.of(dw.v, kk * U)
        val pts = arrayListOf(start, PoseW(centreAt(k), r0))
        var cur = centreAt(k)
        // the lock as it really stands in this frame: pitched, so the bounds of its tipped cell corners
        val (lo, hi) = if (others.isEmpty()) (V3(-LOCK_HALF, -LOCK_HALF, -LOCK_HALF) to V3(LOCK_HALF, LOCK_HALF, LOCK_HALF)) else pitchedBounds(others)
        val ext = halfExtents(piece, view.rot)
        if (dw == Dir.PY) {
            val z = max(cur.z, hi.z + 0.03f * K + ext.z)
            cur = V3(cur.x, cur.y, z); pts += PoseW(cur, r0)
        } else if (dw == Dir.NZ) {
            val x = if (side > 0) max(cur.x, hi.x + 0.03f * K + ext.x) else min(cur.x, lo.x - 0.03f * K - ext.x)
            cur = V3(x, cur.y, cur.z); pts += PoseW(cur, r0)
        }
        val trayQ = conj(YAW_Q) * Q.of(trayRot(piece))
        val vertical = ext.y > 2 * U
        if (vertical && (dw == Dir.PZ || dw == Dir.PY)) {
            // a standing stick in front of the lock lies down along z: first step far enough out that its upper end,
            // swinging back as it turns, still clears the lock's front by 2 cm
            val z = max(cur.z, hi.z + 4 * U + CLEAR)
            if (z > cur.z + 1e-4f) { cur = V3(cur.x, cur.y, z); pts += PoseW(cur, r0) }
        }
        if (!vertical) {
            // a flat stick drops below the lock before it turns (its ends sweep wide when it turns)
            val belowY = min(cur.y, lo.y - CLEAR - ext.y)
            if (belowY < cur.y - 1e-4f) { cur = V3(cur.x, max(belowY, FLY_Y), cur.z); pts += PoseW(cur, r0) }
        }
        // lie down first (long axis -> +z by one quarter turn about a horizontal / vertical axis, sweeping only through
        // space already cleared), then roll about the long axis so the most-notched face comes up, at fly height
        val w = view.rot * V3i.axis(piece.axis, 1)
        val lieQ = when {
            w.z > 0 -> r0
            w.z < 0 -> Q.axisAngle(V3(0f, 1f, 0f), Math.PI.toFloat()) * r0 // end for end about the vertical axis, never through the tray
            else -> Q.axisAngle(V3(w.y.toFloat(), -w.x.toFloat(), 0f), (Math.PI / 2).toFloat()) * r0
        }
        val midY = (cur.y + FLY_Y) / 2
        cur = V3(cur.x, midY, cur.z); pts += PoseW(cur, lieQ)
        cur = V3(cur.x, FLY_Y, cur.z); pts += PoseW(cur, trayQ)
        val slot = worldToYaw(trayCentre(p))
        cur = V3(slot.x, FLY_Y, slot.z); pts += PoseW(cur, trayQ)
        pts += PoseW(slot, trayQ)
        return Path(pts, k)
    }

    /** Bounds (yaw frame) of lock cells after the presentation pitch. */
    fun pitchedBounds(cells: Collection<V3i>): Pair<V3, V3> {
        val pq = pitchQ()
        var lo = V3(1e9f, 1e9f, 1e9f); var hi = V3(-1e9f, -1e9f, -1e9f)
        for (c in cells) for (i in 0..1) for (j in 0..1) for (k in 0..1) {
            val q = pq.rotate(V3((c.x + i) * U, (c.y + j) * U, (c.z + k) * U))
            lo = V3(min(lo.x, q.x), min(lo.y, q.y), min(lo.z, q.z)); hi = V3(max(hi.x, q.x), max(hi.y, q.y), max(hi.z, q.z))
        }
        return lo to hi
    }

    /** Half extents (m) of a stick's 2 x 2 x 8 envelope turned by [r]. */
    fun halfExtents(piece: Piece, r: Rot): V3 {
        val e = V3i((piece.max.x - piece.min.x), (piece.max.y - piece.min.y), (piece.max.z - piece.min.z))
        val w = r * e
        return V3(abs(w.x) * U / 2, abs(w.y) * U / 2, abs(w.z) * U / 2)
    }

    // ------------------------------------------------------------------ arrows (design spatialLayout.arrows)

    /**
     * Where the arrow for [p] going [d] stands, and which way it points (world). Along the long axis: 0.6u past the
     * end; sideways: 0.6u off the side; toward / away from the user: on the stick's outer side, 0.4u outside the lock
     * box, tipped up 30°.
     */
    fun arrowSpot(rules: Rules, pose: Pose, p: Int, d: Dir, view: View): Pair<V3, Dir> {
        val piece = rules.lock.piece(p)
        val off = pose.at(p)!!
        val dw = view.rot.dir(d)
        val cells = piece.cells.map { view.rot.cell(it + off) }
        val (lo, hi) = bounds(cells)
        val mid = (lo + hi) * 0.5f
        if (dw.axis == 2) {
            // the stick's outer side: the world axis (x or y) in which its centre is farthest from the lock centre
            val lockB = bounds(worldCells(rules, pose, view))
            val ax = if (abs(mid.x) >= abs(mid.y)) 0 else 1
            val sign = if ((if (ax == 0) mid.x else mid.y) >= 0) 1f else -1f
            val face = if (ax == 0) (if (sign > 0) lockB.second.x else lockB.first.x) else (if (sign > 0) lockB.second.y else lockB.first.y)
            val out = face + sign * (ARROW_GAP_Z + ARROW_THICK * 2)
            val z = if (dw.sign > 0) hi.z - ARROW_LEN / 2 else lo.z + ARROW_LEN / 2
            // toward / away of one stick never share a line of sight (QA q1-2): toward below, away above
            val lift = if (dw.sign > 0) -1.4f * AU else 1.4f * AU
            val at = if (ax == 0) V3(out, mid.y + lift, z) else V3(mid.x + lift, out, z)
            return at to dw
        }
        val far = when (dw) {
            Dir.PX -> V3(hi.x + ARROW_GAP + ARROW_LEN / 2, mid.y, mid.z)
            Dir.NX -> V3(lo.x - ARROW_GAP - ARROW_LEN / 2, mid.y, mid.z)
            Dir.PY -> V3(mid.x, hi.y + ARROW_GAP + ARROW_LEN / 2, mid.z)
            else -> V3(mid.x, lo.y - ARROW_GAP - ARROW_LEN / 2, mid.z)
        }
        return far to dw
    }

    // ------------------------------------------------------------------ explode (design spatialLayout.explode)

    fun explodeOffset(p: Int): V3 = V3.of(Piece.EXPLODE[p - 1].v, EXPLODE_CELLS * U)

    // ------------------------------------------------------------------ control window (D41)

    /** dp -> m for a Planar window at its default distance (1 dp = 1 mm; an assumption, see ui-audit). */
    const val DP_M = 0.001f
    const val WINDOW_W_DP = 640f
    const val WINDOW_H_DP = 680f
    /**
     * Gap the system leaves between the volume's right face and the window frame, in this model's z = 0 plane. Measured
     * on the emulator's default view (0.0537 deg/px, QA #1): v0.1.0 / v0.1.1 21.3 / 21.1 deg and v0.1.3 (0.62 m small
     * volume, 2026-11-16) 21.4 deg from the lock centre to the window's left edge, i.e. 0.686 m out at 1.749 m: 0.37 m
     * past the small volume's face. The window stays there when the volume grows; the big volume's face is at 0.68 m.
     */
    const val WINDOW_GAP = 0.37f
    const val TABBAR_DP = 88f
    const val CONTROLS_BELOW_DP = 60f

    /** The window frame's left edge (x) and its z plane, scene frame, for Placement.right(volume of size [vol]). */
    fun windowFrameLeft(vol: Vol = Vol.SMALL): V3 = V3(vol.w / 2 + WINDOW_GAP, 0f, 0f)

    /** Horizontal angle (deg) between the lock box's rightmost silhouette and the window frame's left edge, seen from [eye]. */
    fun windowMarginDeg(eye: V3, lockHalf: Float = LOCK_HALF, vol: Vol = Vol.SMALL): Float {
        fun yaw(p: V3): Double = Math.toDegrees(kotlin.math.atan2((p.x - eye.x).toDouble(), (eye.z - p.z).toDouble()))
        val corners = listOf(-lockHalf, lockHalf).flatMap { x -> listOf(-lockHalf, lockHalf).flatMap { yy -> listOf(-lockHalf, lockHalf).map { z -> lockToWorld(V3(x, yy, z)) } } }
        val lockMax = corners.maxOf { yaw(it) }
        return (yaw(windowFrameLeft(vol)) - lockMax).toFloat()
    }

    // ------------------------------------------------------------------ volume size (D54, v0.1.3)

    enum class Vol(val w: Float, val h: Float, val d: Float) { SMALL(SMALL_W, SMALL_H, SMALL_D), BIG(VOL_W, VOL_H, VOL_D) }

    /** How long a resize of ours may take before a size that is still off counts as the user's own resize. */
    const val RESIZE_SETTLE_MS = 5000L

    /**
     * The content scale for a measured volume half height (design container: 等比缩放 by the user), or null to leave the
     * scale alone. While a resize of ours to [target] is under way (asked [sinceChangeMs] ago) the measured size is still
     * the old one for about 2 s on the emulator: keep the scale until it reaches the target (QA #3 q3-1 v0.1.4: a fixed
     * 1.5 s window scaled the lock to 0.30 for a frame). After [RESIZE_SETTLE_MS] any size counts.
     */
    fun regionScale(halfY: Float, target: Vol, sinceChangeMs: Long): Float? {
        val k = halfY / (target.h / 2)
        if (sinceChangeMs < RESIZE_SETTLE_MS && kotlin.math.abs(k - 1f) > 0.05f) return null
        return k.takeIf { it in 0.2f..5f } ?: 1f
    }

    /**
     * The volume this state needs: [Vol.BIG] as soon as something can leave the lock box (a picked stick that can come
     * out, the hint showing a removal, a flight, a stick on the tray, the lock opened up), else [Vol.SMALL].
     */
    fun volumeFor(st: PlayState, rules: Rules): Vol {
        val pose = st.pose
        val big = st.explodeOn || pose.inLock.size < 6 || st.anim is PathAnim ||
            st.selected?.let { p -> pose.isIn(p) && st.arrows.any { d -> rules.arrow(pose, p, d)?.out == true } } == true ||
            (st.hintOn && st.hint?.out == true)
        return if (big) Vol.BIG else Vol.SMALL
    }

    /** The assembled lock's world bounds (home pose, [view], presentation applied): what D54 measures. */
    fun lockBounds(rules: Rules, view: View = View()): Pair<V3, V3> {
        var lo = V3(1e9f, 1e9f, 1e9f); var hi = V3(-1e9f, -1e9f, -1e9f)
        for (c in worldCells(rules, Pose.HOME, view)) for (i in 0..1) for (j in 0..1) for (k in 0..1) {
            val q = lockToWorld(V3((c.x + i) * U, (c.y + j) * U, (c.z + k) * U))
            lo = V3(min(lo.x, q.x), min(lo.y, q.y), min(lo.z, q.z)); hi = V3(max(hi.x, q.x), max(hi.y, q.y), max(hi.z, q.z))
        }
        return lo to hi
    }
}
