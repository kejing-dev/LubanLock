package io.embodit.lubanlock.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.lubanlock.domain.Layout
import io.embodit.lubanlock.domain.Lock
import io.embodit.lubanlock.domain.V3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

private fun c4(argb: Long, a: Float = 1f) = Color4(((argb shr 16) and 255) / 255f, ((argb shr 8) and 255) / 255f, (argb and 255) / 255f, a)

/** Builds a MeshResource from triangles (MeshModel), bounds from the positions. */
internal fun meshOf(name: String, pos: List<V3>, nrm: List<V3>, uv: List<FloatArray>?, idx: List<Int>): MeshResource {
    var lo = V3(1e9f, 1e9f, 1e9f); var hi = V3(-1e9f, -1e9f, -1e9f)
    for (p in pos) { lo = V3(minOf(lo.x, p.x), minOf(lo.y, p.y), minOf(lo.z, p.z)); hi = V3(maxOf(hi.x, p.x), maxOf(hi.y, p.y), maxOf(hi.z, p.z)) }
    val c = (lo + hi) * 0.5f
    val h = (hi - lo) * 0.5f
    return MeshResource.createWithMeshModel(
        model = MeshModel(
            positions = pos.map { Vector3(it.x, it.y, it.z) }, triangleIndices = idx, normals = nrm.map { Vector3(it.x, it.y, it.z) },
            uv0 = uv?.map { Vector2(it[0], it[1]) },
        ),
        bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(h.x, 1e-3f), maxOf(h.y, 1e-3f), maxOf(h.z, 1e-3f))),
        name = name,
    )
}

/** One stick's render + hit resources (rebuilt when the lock changes). */
internal class PieceRes(
    val outer: MeshResource,
    val cut: MeshResource,
    val wood: Material,
    val cutWood: Material,
    /** Selected (warm glow) and "this one" (blocker / the right stick, hot glow): the whole stick lights up (QA q1-5). */
    val woodSel: Material,
    val woodHot: Material,
    val shape: List<ShapeResource>,
    val textures: List<TextureResource>,
    val triangles: Int,
    val boxes: Int,
)

/**
 * Everything the volume draws. Shared marks (arrow, ghost / glow / vermilion materials, shadows, hole cube) are made
 * once; the six sticks of a lock (meshes from the lock data, procedural wood textures, colliders) per lock. Creating a
 * resource waits on the renderer, so it all happens on Dispatchers.IO (memory note: dynamic mesh ANR).
 */
/** Warm glow of the selected stick; hot glow of the blockers / the right stick (emissive, adds to any wood, QA q1-5). */
internal val SEL_GLOW = Color4(0.55f, 0.36f, 0.04f, 1f)
internal val HOT_GLOW = Color4(0.70f, 0.10f, 0.02f, 1f)
/** Edge of the lock's single collider (m): the 8u lock plus a margin, about 7.5° at the default 1.75 m. */
internal const val LOCK_TARGET_M = 0.23f

internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var arrowMesh: MeshResource; private set
    lateinit var arrowMat: Material; private set
    lateinit var arrowShape: ShapeResource; private set
    lateinit var ghostMat: Material; private set
    lateinit var glowMat: Material; private set
    lateinit var selectMat: Material; private set
    lateinit var coachMat: Material; private set
    lateinit var hoverMat: Material; private set
    lateinit var vermilion: Material; private set
    lateinit var holeMesh: MeshResource; private set
    lateinit var holeMat: Material; private set
    lateinit var shadowMesh: MeshResource; private set
    lateinit var shadowMat: Material; private set
    lateinit var physics: PhysicsMaterialResource; private set
    /** The whole lock as one big target (rework v1.3: sticks / arrows are picked in the window, D50 + D17 2°). */
    lateinit var lockShape: ShapeResource; private set

    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }

    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    companion object {
        private fun unlit(argb: Long, opacity: Float = 1f, tex: TextureResource? = null, cull: MaterialCullingMode = MaterialCullingMode.BACK): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f || tex != null) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(c4(argb))
                if (opacity < 1f) setOpacity(opacity)
                tex?.let { setBaseColorTexture(it) }
                setCullingMode(cull)
                setApplyToneMapping(false)
            }

        /** A flat arrow pointing +Z (shaft + head), thickness along Y (design: 长 2u、粗 0.36u). */
        fun arrowGeometry(): MeshResource {
            val len = Layout.ARROW_LEN; val th = Layout.ARROW_THICK
            val shaftW = 0.42f * Layout.U; val headW = 1.15f * Layout.U; val headL = 0.85f * Layout.U
            val z0 = -len / 2; val z1 = len / 2 - headL; val z2 = len / 2
            val outline = listOf(V3(-shaftW / 2, 0f, z0), V3(shaftW / 2, 0f, z0), V3(shaftW / 2, 0f, z1), V3(headW / 2, 0f, z1), V3(0f, 0f, z2), V3(-headW / 2, 0f, z1), V3(-shaftW / 2, 0f, z1))
            val pos = ArrayList<V3>(); val nrm = ArrayList<V3>(); val idx = ArrayList<Int>()
            fun tri(a: V3, b: V3, c: V3, n: V3) { val base = pos.size; pos += a; pos += b; pos += c; repeat(3) { nrm += n }; idx += listOf(base, base + 1, base + 2) }
            val top = V3(0f, th / 2, 0f); val bot = V3(0f, -th / 2, 0f)
            // caps: shaft quad + head triangle, both sides
            val capTris = listOf(Triple(0, 1, 2), Triple(0, 2, 6), Triple(3, 4, 5))
            for ((a, b, c) in capTris) {
                tri(outline[a] + top, outline[c] + top, outline[b] + top, V3(0f, 1f, 0f))
                tri(outline[a] + bot, outline[b] + bot, outline[c] + bot, V3(0f, -1f, 0f))
            }
            for (i in outline.indices) {
                val a = outline[i]; val b = outline[(i + 1) % outline.size]
                val e = b - a; val n = V3(e.z, 0f, -e.x).normalized()
                tri(a + bot, b + bot, b + top, n); tri(a + bot, b + top, a + top, n)
            }
            return meshOf("arrow", pos, nrm, null, idx)
        }

        /** A unit quad on XZ facing +Y, with UVs (shadow decal). */
        fun quadGeometry(): MeshResource = meshOf(
            "quad", listOf(V3(-0.5f, 0f, -0.5f), V3(-0.5f, 0f, 0.5f), V3(0.5f, 0f, 0.5f), V3(0.5f, 0f, -0.5f)),
            List(4) { V3(0f, 1f, 0f) }, listOf(floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f)), listOf(0, 1, 2, 0, 2, 3),
        )

        /** A unit cube centred on the origin (hole marks in the slow replay; memory: createBox renders 2x on the emulator). */
        fun cubeGeometry(): MeshResource {
            val pos = ArrayList<V3>(); val nrm = ArrayList<V3>(); val idx = ArrayList<Int>()
            for (a in 0..2) for (s in listOf(1, -1)) {
                val b = (a + 1) % 3; val c = (a + 2) % 3
                fun p(sb: Float, sc: Float): V3 { val f = FloatArray(3); f[a] = 0.5f * s; f[b] = sb; f[c] = sc; return V3(f[0], f[1], f[2]) }
                val n = FloatArray(3).also { it[a] = s.toFloat() }.let { V3(it[0], it[1], it[2]) }
                val q = listOf(p(-0.5f, -0.5f), p(0.5f, -0.5f), p(0.5f, 0.5f), p(-0.5f, 0.5f)).let { if (s > 0) it else it.reversed() }
                val base = pos.size
                q.forEach { pos += it; nrm += n }
                idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
            }
            return meshOf("cube", pos, nrm, null, idx)
        }

        fun shadowBitmap(): Bitmap {
            val n = 128
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(0x70000000, 0x30000000, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) }
            Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
            return b
        }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T = r.keep(this)
            val io = Dispatchers.IO
            val shadowTex = async(io) { TextureResource.create(shadowBitmap()).keep() }
            listOf(
                async(io) { r.arrowMesh = arrowGeometry().keep() },
                async(io) { r.holeMesh = cubeGeometry().keep() },
                async(io) { r.shadowMesh = quadGeometry().keep() },
                // the arrow's hit box is a size bigger than the arrow (design input.rule)
                async(io) { r.arrowShape = ShapeResource.createBox(Vector3(1.6f * Layout.U, 1.2f * Layout.U, Layout.ARROW_LEN + 0.8f * Layout.U)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                async(io) { r.lockShape = ShapeResource.createBox(Vector3(LOCK_TARGET_M, LOCK_TARGET_M, LOCK_TARGET_M)).keep() },
            ).awaitAll()
            // design assets: arrows amber #E8A33D unlit, ghost 35 % of the stick, outline gold #F3D27A, cuts vermilion #C0392B
            r.arrowMat = unlit(0xFFE8A33D, cull = MaterialCullingMode.NONE).keep()
            r.ghostMat = PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(c4(0xFFF4E6CF)); setOpacity(0.35f); setRoughness(0.6f); setMetallic(0f) }.keep()
            // outlines are inverted hulls: the stick's own mesh, a little larger, front faces culled
            r.glowMat = unlit(0xFFFF6A2A, cull = MaterialCullingMode.FRONT).keep()
            r.selectMat = unlit(0xFFFFC23D, cull = MaterialCullingMode.FRONT).keep()
            r.coachMat = unlit(0xFFE8A33D, cull = MaterialCullingMode.FRONT).keep()
            r.hoverMat = unlit(0xFFFFFFFF, opacity = 0.45f, cull = MaterialCullingMode.FRONT).keep()
            r.vermilion = PhysicallyBasedMaterial.create().apply { setBaseColor(c4(0xFFC0392B)); setRoughness(0.6f); setMetallic(0f) }.keep()
            r.holeMat = unlit(0xFFC0392B, opacity = 0.35f, cull = MaterialCullingMode.NONE).keep()
            r.shadowMat = unlit(0xFFFFFFFF, tex = shadowTex.await(), cull = MaterialCullingMode.NONE).keep()
            r
        }
    }

    /**
     * The six sticks of [lock] (meshes + colliders from the data). [textured] = the procedural wood (a few hundred ms);
     * without it the sticks are plain wood-coloured (S00 stand-in, design lifecycle 首帧兜底). [testF] = "F↑" textures (D10).
     */
    suspend fun pieces(lock: Lock, textured: Boolean, testF: Boolean, geometry: List<PieceRes>? = null): List<PieceRes> = coroutineScope {
        fun <T : Resource> T.keep(): T = this@SceneResources.keep(this)
        val io = Dispatchers.IO
        lock.pieces.mapIndexed { i, piece ->
            async(io) {
                val g = PieceGeometry(piece)
                val textures = ArrayList<TextureResource>()
                val wood: Material
                val cutWood: Material
                val woodSel: Material
                val woodHot: Material
                if (textured) {
                    val maps = WoodTexture.build(piece, lock.wood, seed = lock.id.hashCode(), testF = testF)
                    val albedo = TextureResource.create(maps.albedo).keep()
                    val rough = TextureResource.create(maps.roughness).keep()
                    textures += albedo; textures += rough
                    maps.albedo.recycle(); maps.roughness.recycle()
                    wood = PhysicallyBasedMaterial.create().apply {
                        setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(albedo); setRoughness(1f); setRoughnessTexture(rough); setMetallic(0f)
                    }.keep()
                    // notch walls 10 % darker (design: 槽口内侧略深 10 %)
                    cutWood = PhysicallyBasedMaterial.create().apply {
                        setBaseColor(Color4(0.86f, 0.84f, 0.82f, 1f)); setBaseColorTexture(albedo); setRoughness(1f); setRoughnessTexture(rough); setMetallic(0f)
                    }.keep()
                    woodSel = PhysicallyBasedMaterial.create().apply {
                        setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(albedo); setRoughness(1f); setRoughnessTexture(rough); setMetallic(0f); setEmissiveColor(SEL_GLOW)
                    }.keep()
                    woodHot = PhysicallyBasedMaterial.create().apply {
                        setBaseColor(Color4(1f, 0.85f, 0.8f, 1f)); setBaseColorTexture(albedo); setRoughness(1f); setRoughnessTexture(rough); setMetallic(0f); setEmissiveColor(HOT_GLOW)
                    }.keep()
                } else {
                    val t = WoodTexture.tone(lock.wood).early
                    val cr = android.graphics.Color.red(t) / 255f; val cg = android.graphics.Color.green(t) / 255f; val cb = android.graphics.Color.blue(t) / 255f
                    wood = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(cr, cg, cb, 1f)); setRoughness(0.6f); setMetallic(0f) }.keep()
                    cutWood = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(cr * 0.86f, cg * 0.84f, cb * 0.82f, 1f)); setRoughness(0.6f); setMetallic(0f) }.keep()
                    woodSel = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(cr, cg, cb, 1f)); setRoughness(0.6f); setMetallic(0f); setEmissiveColor(SEL_GLOW) }.keep()
                    woodHot = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(cr, cg * 0.85f, cb * 0.8f, 1f)); setRoughness(0.6f); setMetallic(0f); setEmissiveColor(HOT_GLOW) }.keep()
                }
                val reuse = geometry?.getOrNull(i)
                if (reuse != null) return@async PieceRes(reuse.outer, reuse.cut, wood, cutWood, woodSel, woodHot, reuse.shape, textures, reuse.triangles, reuse.boxes)
                val outer = meshOf("stick${piece.id}", g.outer.pos, g.outer.nrm, g.outer.uv, g.outer.idx).keep()
                val cut = meshOf("stick${piece.id}cut", g.cut.pos.ifEmpty { listOf(V3.ZERO, V3.ZERO, V3.ZERO) }, g.cut.nrm.ifEmpty { List(3) { V3(0f, 1f, 0f) } },
                    g.cut.uv.ifEmpty { List(3) { floatArrayOf(0f, 0f) } }, g.cut.idx.ifEmpty { listOf(0, 1, 2) }).keep()
                val shapes = g.boxes.map { (c, sz) -> ShapeResource.createBox(Vector3(sz.x, sz.y, sz.z)).offsetByTranslation(Vector3(c.x, c.y, c.z)).keep() }
                PieceRes(outer, cut, wood, cutWood, woodSel, woodHot, shapes, textures, g.outer.triangles + g.cut.triangles, g.boxes.size)
            }
        }.awaitAll()
    }

    /** Releases the materials / textures of [list]; meshes and colliders too unless [keepGeometry]. */
    fun release(list: List<PieceRes>, keepGeometry: Boolean = false) {
        val gone = list.flatMap { (if (keepGeometry) emptyList() else listOf<Resource>(it.outer, it.cut) + it.shape) + listOf<Resource>(it.wood, it.cutWood, it.woodSel, it.woodHot) + it.textures }
        synchronized(all) { all.removeAll(gone.toSet()) }
        gone.forEach { runCatching { it.close() } }
    }
}
