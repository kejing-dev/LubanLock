package io.embodit.lubanlock.domain

/** An exact rotation of the cube grid (one of the 24), as an integer 3 x 3 matrix acting on column vectors. */
data class Rot(val m: IntArray) {
    init { require(m.size == 9) }
    operator fun times(v: V3i) = V3i(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)
    operator fun times(o: Rot): Rot = Rot(IntArray(9) { i -> val r = i / 3; val c = i % 3; (0..2).sumOf { k -> m[r * 3 + k] * o.m[k * 3 + c] } })
    /** The cell (x, y, z), filling [x, x+1] x ..., after the rotation (not the corner: a negated axis shifts by one). */
    fun cell(c: V3i): V3i { val w = this * V3i(2 * c.x + 1, 2 * c.y + 1, 2 * c.z + 1); return V3i((w.x - 1) / 2, (w.y - 1) / 2, (w.z - 1) / 2) }
    fun apply(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z)
    val transpose: Rot get() = Rot(IntArray(9) { i -> m[(i % 3) * 3 + i / 3] })
    fun dir(d: Dir): Dir { val w = this * d.v; return Dir.entries.first { it.v == w } }
    override fun equals(other: Any?) = other is Rot && other.m.contentEquals(m)
    override fun hashCode() = m.contentHashCode()

    /** Unit quaternion (x, y, z, w) of this rotation. */
    fun quat(): FloatArray {
        val t = m[0] + m[4] + m[8]
        return if (t > 0) {
            val s = kotlin.math.sqrt(t + 1.0).toFloat() * 2
            floatArrayOf((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25f * s)
        } else if (m[0] >= m[4] && m[0] >= m[8]) {
            val s = kotlin.math.sqrt(1.0 + m[0] - m[4] - m[8]).toFloat() * 2
            floatArrayOf(0.25f * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
        } else if (m[4] >= m[8]) {
            val s = kotlin.math.sqrt(1.0 + m[4] - m[0] - m[8]).toFloat() * 2
            floatArrayOf((m[1] + m[3]) / s, 0.25f * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
        } else {
            val s = kotlin.math.sqrt(1.0 + m[8] - m[0] - m[4]).toFloat() * 2
            floatArrayOf((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25f * s, (m[3] - m[1]) / s)
        }
    }

    companion object {
        val I = Rot(intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1))
        /** +90° about +Y (seen from above: counter-clockwise, +x goes to -z). */
        val Y90 = Rot(intArrayOf(0, 0, 1, 0, 1, 0, -1, 0, 0))
        /** +90° about +X: the top face (+y) comes toward the viewer (+z). */
        val X90 = Rot(intArrayOf(1, 0, 0, 0, 0, -1, 0, 1, 0))

        /** The rotation taking the column vectors a -> ex, b -> ey (a, b orthogonal unit axes). */
        fun fromColumns(cx: V3i, cy: V3i, cz: V3i) = Rot(intArrayOf(cx.x, cy.x, cz.x, cx.y, cy.y, cz.y, cx.z, cy.z, cz.z))
    }
}

/**
 * How the lock is turned (design input.rotate): [yaw] quarter turns about the vertical axis (0..3; +1 = [右转], the
 * face toward you swings to the right; [左转] = -1) and [flipped] = [翻个面] (top face toward you). World = Ry^yaw · Rx^flip,
 * eight orientations in all.
 */
data class View(val yaw: Int = 0, val flipped: Boolean = false) {
    val rot: Rot get() {
        var r = Rot.I
        if (flipped) r = Rot.X90
        repeat(((yaw % 4) + 4) % 4) { r = Rot.Y90 * r }
        return r
    }
    fun turned(by: Int) = copy(yaw = ((yaw + by) % 4 + 4) % 4)
    fun flip() = copy(flipped = !flipped)
    fun code(): String = "$yaw${if (flipped) "f" else ""}"

    companion object {
        val ALL: List<View> = (0..3).flatMap { y -> listOf(View(y, false), View(y, true)) }
        fun parse(s: String): View? = runCatching { View(s.take(1).toInt(), s.endsWith("f")) }.getOrNull()
    }
}
