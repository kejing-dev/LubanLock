package com.kejing.lubanlock.domain

import kotlin.math.max
import kotlin.math.min

/**
 * How much of a stick the user sees (design input.autoRotate / occlusionCheck.hiddenTarget): its outer faces that face
 * the eye, sampled 2 x 2 per cell face, each sample tested with a ray from the eye against every cell of the lock. The
 * visible area = the share of unblocked samples x the facing outer area. Below 6 cm² (2x scale) the lock turns 90° about
 * the vertical axis to the quarter that shows it best.
 */
object Visibility {
    const val MIN_AREA_M2 = 6e-4f

    /** [eyeW] is in the volume frame; the cells are in the lock frame, so the eye is taken into it (presentation undone). */
    fun area(rules: Rules, pose: Pose, view: View, p: Int, eyeW: V3 = Layout.DEFAULT_EYE): Float {
        val eye = Layout.worldToLock(eyeW)
        val off = pose.at(p) ?: return Float.MAX_VALUE
        val target = rules.lock.piece(p).cells.map { view.rot.cell(it + off) }
        val tset = target.toHashSet()
        val others = Layout.worldCells(rules, pose, view, skip = p)
        val all = HashSet<V3i>(others).apply { addAll(tset) }
        val u = Layout.U
        var faces = 0
        var samples = 0
        var seen = 0
        for (c in target) for (d in Dir.entries) {
            val nb = c + d.v
            if (nb in all) continue
            val n = V3.of(d.v)
            val fc = V3((c.x + 0.5f + d.v.x * 0.5f) * u, (c.y + 0.5f + d.v.y * 0.5f) * u, (c.z + 0.5f + d.v.z * 0.5f) * u)
            if ((eye - fc).dot(n) <= 0f) continue
            faces++
            // two in-face axes
            val a1 = if (d.axis == 0) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
            val a2 = if (d.axis == 2) V3(0f, 1f, 0f) else V3(0f, 0f, 1f)
            for (i in 0..1) for (j in 0..1) {
                val pt = fc + a1 * ((i - 0.5f) * 0.5f * u) + a2 * ((j - 0.5f) * 0.5f * u) + n * 1e-4f
                samples++
                if (!blocked(eye, pt, all, c)) seen++
            }
        }
        if (samples == 0) return 0f
        return seen.toFloat() / samples * faces * u * u
    }

    /** Segment eye -> pt hits any cell (unit cubes in world cells, metres) other than [own]. */
    private fun blocked(eye: V3, pt: V3, cells: Set<V3i>, own: V3i): Boolean {
        val u = Layout.U
        val d = pt - eye
        for (c in cells) {
            if (c == own) continue
            val lo = V3(c.x * u, c.y * u, c.z * u)
            if (segBox(eye, d, lo, V3(lo.x + u, lo.y + u, lo.z + u))) return true
        }
        return false
    }

    /** Slab test for the segment o + t d, t in [0, 1). */
    fun segBox(o: V3, d: V3, lo: V3, hi: V3): Boolean {
        var t0 = 0f; var t1 = 0.999f
        val os = floatArrayOf(o.x, o.y, o.z); val ds = floatArrayOf(d.x, d.y, d.z)
        val ls = floatArrayOf(lo.x, lo.y, lo.z); val hs = floatArrayOf(hi.x, hi.y, hi.z)
        for (a in 0..2) {
            if (kotlin.math.abs(ds[a]) < 1e-9f) { if (os[a] < ls[a] || os[a] > hs[a]) return false; continue }
            var ta = (ls[a] - os[a]) / ds[a]; var tb = (hs[a] - os[a]) / ds[a]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = max(t0, ta); t1 = min(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    /**
     * The quarter turn (keeping the flip) that shows [p] best, or null when it is already visible enough
     * (design: 被点名的木条 < 6 cm² -> 绕竖直轴转 90°，取露得最多的那一档).
     */
    fun bestTurn(rules: Rules, pose: Pose, view: View, p: Int, eye: V3 = Layout.DEFAULT_EYE): View? {
        if (area(rules, pose, view, p, eye) >= MIN_AREA_M2) return null
        return (1..3).map { view.turned(it) }.maxBy { area(rules, pose, it, p, eye) }
    }
}
