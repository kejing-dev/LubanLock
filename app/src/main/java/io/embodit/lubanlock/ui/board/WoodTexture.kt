package io.embodit.lubanlock.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import io.embodit.lubanlock.domain.Piece
import io.embodit.lubanlock.domain.V3
import io.embodit.lubanlock.domain.V3i
import io.embodit.lubanlock.domain.Wood
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Realistic procedural wood for one stick (design assets 木条: 年轮条纹 + 噪声沿长轴、哑光、槽口内侧略深). A solid-wood
 * function: the log's pith runs parallel to the stick a few cells outside it, so the long faces show flat-sawn streaks
 * along the length and the end caps show ring arcs; fine streaks and pores follow the grain. The burnt numbers ①–⑥ go on
 * both end caps and on every long face near both ends (design entities3d.pieces). [testF] draws "F↑" instead (D10 check).
 *
 * Output: albedo (sRGB) and roughness (grey, 0.50–0.62) bitmaps in the [Atlas] layout.
 */
internal object WoodTexture {
    data class Tone(val early: Int, val late: Int, val ink: Int, val ringsPerCell: Float, val pores: Float)

    fun tone(w: Wood): Tone = when (w) {
        // beech #D9B98C: pale, fine rays, few pores
        Wood.BEECH -> Tone(Color.rgb(0xE2, 0xC4, 0x98), Color.rgb(0xC4, 0x9E, 0x6C), Color.rgb(0x4A, 0x2E, 0x18), 1.15f, 0.05f)
        // cherry #A0522D: warm red-brown, satiny
        Wood.CHERRY -> Tone(Color.rgb(0xB0, 0x62, 0x3A), Color.rgb(0x86, 0x42, 0x24), Color.rgb(0xF2, 0xDD, 0xB8), 1.0f, 0.12f)
        // walnut #5A3A22: dark, open pores, pronounced figure
        Wood.WALNUT -> Tone(Color.rgb(0x6A, 0x47, 0x2C), Color.rgb(0x46, 0x2C, 0x19), Color.rgb(0xE8, 0xD2, 0xA8), 0.85f, 0.22f)
    }

    class Maps(val albedo: Bitmap, val roughness: Bitmap)

    fun build(piece: Piece, wood: Wood, seed: Int, testF: Boolean = false): Maps {
        val n = Atlas.SIZE
        val alb = IntArray(n * n) { Color.rgb(0x80, 0x60, 0x40) }
        val rough = IntArray(n * n) { Color.rgb(140, 140, 140) }
        val t = tone(wood)
        val rnd = java.util.Random(seed * 7919L + piece.id * 104729L)
        val pithB = -5.5f - rnd.nextFloat() * 3f
        val pithC = -7.0f - rnd.nextFloat() * 4f
        val phase = rnd.nextFloat() * 6.28f
        val l = V3i.axis(piece.axis, 1)
        val b = V3i.axis((piece.axis + 1) % 3, 1)
        val c = V3i.axis((piece.axis + 2) % 3, 1)

        // QA q1-5: neighbouring sticks must not melt into one block: each stick its own shade (+-6 %), every face edge darker
        val stickTone = 0.94f + 0.12f * (((piece.id * 5 + seed) % 6 + 6) % 6) / 5f

        fun shade(rel: V3, idx: Int, edge: Float = 0f) {
            val a = dot(l, rel); val pb = dot(b, rel); val pc = dot(c, rel)
            val warp = 0.45f * fbm(a * 0.07f + phase, pb * 0.25f, pc * 0.25f) + 0.18f * sin(a * 0.3f + phase)
            val r = sqrt((pb - pithB) * (pb - pithB) + (pc - pithC) * (pc - pithC)) + warp
            val ring = frac(r * t.ringsPerCell)
            val late = smooth(0.55f, 0.88f, ring) * (1f - smooth(0.94f, 1f, ring))
            val streak = fbm(a * 0.45f, pb * 9f + phase, pc * 9f)
            val pore = if (noise(a * 2.6f, pb * 38f, pc * 38f) > 1f - t.pores) 0.82f else 1f
            val k = (0.93f + 0.14f * streak) * pore * stickTone * (1f - 0.30f * edge)
            val col = mix(t.early, t.late, late * 0.9f)
            alb[idx] = Color.rgb(clamp(Color.red(col) * k), clamp(Color.green(col) * k), clamp(Color.blue(col) * k))
            val ro = 0.55f - 0.05f * late + (if (pore < 1f) 0.07f else 0f)
            val g = clamp(ro * 255f)
            rough[idx] = Color.rgb(g, g, g)
        }

        // four side strips
        val sides = Atlas.sides(piece)
        for ((k, nrm) in sides.withIndex()) {
            val (right, up) = Atlas.frame(nrm, l)
            for (py in 0 until Atlas.STRIP_H) for (px in 0 until Atlas.STRIP_W) {
                val r = (px + 0.5f) / Atlas.PX - 4f
                val tt = 1f - (py + 0.5f) / Atlas.PX
                val rel = vec(right) * r + vec(up) * tt + vec(nrm) * 1f
                shade(rel, (k * Atlas.STRIP_H + py) * n + px, smooth(0.80f, 1f, kotlin.math.abs(tt)))
            }
        }
        // end caps (+L, -L) and the plain end-grain tile for notch walls across the axis
        for ((row, end) in listOf(0 to l, 1 to -l, 2 to l)) {
            val (right, up) = Atlas.frame(end, l)
            val depth = if (row == 2) 0.5f else 4f
            for (py in 0 until Atlas.STRIP_H) for (px in 0 until Atlas.STRIP_H) {
                val r = (px + 0.5f) / Atlas.PX - 1f
                val tt = 1f - (py + 0.5f) / Atlas.PX
                val rel = vec(right) * r + vec(up) * tt + vec(end) * depth
                val e = if (row == 2) 0f else smooth(0.80f, 1f, maxOf(kotlin.math.abs(r), kotlin.math.abs(tt)))
                shade(rel, (row * Atlas.STRIP_H + py) * n + Atlas.END_X + px, e)
            }
        }
        val bmp = Bitmap.createBitmap(alb, n, n, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        burn(bmp, piece, t, testF)
        return Maps(bmp, Bitmap.createBitmap(rough, n, n, Bitmap.Config.ARGB_8888))
    }

    /** The burnt numbers: a scorched halo, then a ring and the digit (drawn, so no font needs the circled glyphs). */
    private fun burn(bmp: Bitmap, piece: Piece, t: Tone, testF: Boolean) {
        val cv = Canvas(bmp)
        val spots = ArrayList<Triple<Float, Float, Float>>()
        val h = Atlas.STRIP_H.toFloat()
        spots += Triple(Atlas.END_X + h / 2, h / 2, 34f)
        spots += Triple(Atlas.END_X + h / 2, h + h / 2, 34f)
        for (k in 0..3) for (px in listOf(Atlas.PX * 1f, Atlas.STRIP_W - Atlas.PX * 1f)) spots += Triple(px, k * h + h / 2, 21f)
        val ink = t.ink
        for ((x, y, r) in spots) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(x, y, r * 1.35f, Color.argb(70, Color.red(ink), Color.green(ink), Color.blue(ink)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            cv.drawCircle(x, y, r * 1.35f, halo)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, Color.red(ink), Color.green(ink), Color.blue(ink)); style = Paint.Style.STROKE; strokeWidth = r * 0.14f }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pen.color; textAlign = Paint.Align.CENTER; textSize = r * 1.25f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            if (testF) {
                text.textSize = r * 1.1f
                cv.drawText("F↑", x, y + text.textSize * 0.36f, text)
            } else {
                cv.drawCircle(x, y, r * 0.82f, pen)
                cv.drawText(piece.id.toString(), x, y + text.textSize * 0.36f, text)
            }
        }
    }

    private fun dot(a: V3i, p: V3) = a.x * p.x + a.y * p.y + a.z * p.z
    private fun vec(a: V3i) = V3(a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
    private fun frac(x: Float) = x - floor(x)
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val k = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }
    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)
    private fun mix(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())

    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0x7fffffff) / 2147483647f
    }

    /** Value noise 0..1. */
    fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val xf = x - xi; val yf = y - yi; val zf = z - zi
        val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf); val w = zf * zf * (3 - 2 * zf)
        fun l(a: Float, b: Float, t: Float) = a + (b - a) * t
        return l(
            l(l(hash(xi, yi, zi), hash(xi + 1, yi, zi), u), l(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), u), v),
            l(l(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), u), l(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), u), v),
            w,
        )
    }

    /** Two-octave noise, -0.5..0.5. */
    fun fbm(x: Float, y: Float, z: Float): Float = (noise(x, y, z) * 0.67f + noise(x * 2.1f, y * 2.1f, z * 2.1f) * 0.33f) - 0.5f
}
