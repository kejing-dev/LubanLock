package io.embodit.lubanlock.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.lubanlock.domain.BumpAnim
import io.embodit.lubanlock.domain.Dir
import io.embodit.lubanlock.domain.GameFlow
import io.embodit.lubanlock.domain.InspectAnim
import io.embodit.lubanlock.domain.Layout
import io.embodit.lubanlock.domain.Lock
import io.embodit.lubanlock.domain.NopeAnim
import io.embodit.lubanlock.domain.PathAnim
import io.embodit.lubanlock.domain.Phase
import io.embodit.lubanlock.domain.Piece
import io.embodit.lubanlock.domain.PlayState
import io.embodit.lubanlock.domain.PoseW
import io.embodit.lubanlock.domain.Q
import io.embodit.lubanlock.domain.Rules
import io.embodit.lubanlock.domain.Settings
import io.embodit.lubanlock.domain.SlideAnim
import io.embodit.lubanlock.domain.V3
import io.embodit.lubanlock.domain.V3i
import io.embodit.lubanlock.domain.View
import io.embodit.lubanlock.platform.TAG
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Every entity of the volume (design spatialLayout.hierarchy). Compose hands over [PlayState] + [Settings] through
 * [apply]; everything that moves is placed in [onFrame], driven by BoardFrameSystem (ECS, not Compose).
 *
 * ```
 * root (volume centre, scaled to the volume's real size)
 * ├ key light, lock shadow, 6 tray shadows, 4 hole marks (slow replay)
 * ├ piece:<1..6>  holder = collider (cells merged into boxes) + Interactable + system HoverEffect
 * │   ├ wood (outer faces)  ├ cut (notch walls: darker wood / vermilion)  └ hull (gold / select / coach / hover outline)
 * ├ ghost:<1..6>  the hint ghost (35 %, no collider)
 * └ arrow:<+x..-z>  amber arrows with a bigger hit box (lock-frame direction in the name)
 * ```
 * The lock root is virtual: each stick's world pose = turn (animated) x explode x its cells (lock frame).
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val content = Entity().apply { setName("content") }

    private var res: SceneResources? = null
    private var pieces: List<PieceRes> = emptyList()
    private var builtLock: Int = -1
    private var texturedLock: Int = -1

    private val holders = ArrayList<Entity>()
    private val woods = ArrayList<ModelEntity>()
    private val cuts = ArrayList<ModelEntity>()
    private val hulls = ArrayList<ModelEntity>()
    private val ghosts = ArrayList<Entity>()
    private val arrows = HashMap<Dir, ModelEntity>()
    private val trayShadows = ArrayList<ModelEntity>()
    private var lockShadow: ModelEntity? = null
    private var lockTarget: Entity? = null
    private val holes = ArrayList<ModelEntity>()
    private val fading = BooleanArray(6)

    private var state = PlayState()
    private var settings = Settings()
    private var rules: Rules? = null
    private var regionScale = 1f
    @Volatile private var hover: String? = null
    @Volatile private var hoverAt = 0L
    /** Where each arrow stands now (debug evidence / hit replay). */
    val arrowSpots = HashMap<Dir, V3>()
    var builtInfo = ""; private set

    init { root.addChild(content) }

    val ready: Boolean get() = res != null && holders.isNotEmpty()

    /** D50 hover: the holder under a resting pointer (null = none). A hint only, never a state change. */
    fun setHover(name: String?) {
        hoverAt = SystemClock.uptimeMillis()
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    /** The volume size the scene is laid out for now (D54): the lock shadow sits on its floor. */
    private var vol = Layout.Vol.SMALL

    /**
     * The volume's real half height (m): content scales with a uniform resize by the user (design container: 等比缩放).
     * [target] is the size the app asked for (WindowCtl.volume); while a resize of ours is under way the measured size
     * is still the old one, so the scale is left alone ([Layout.regionScale]).
     */
    fun setRegion(halfY: Float, target: Layout.Vol, changedAt: Long) {
        if (target != vol) { vol = target; lockShadow?.transform()?.setPosition(Vector3(0f, -vol.h / 2 + 0.001f, 0f)) }
        val k = Layout.regionScale(halfY, target, SystemClock.uptimeMillis() - changedAt) ?: return
        if (kotlin.math.abs(k - regionScale) < 1e-3f) return
        regionScale = k
        content.transform().setScaleVector(Vector3(k, k, k))
        Log.i(TAG, "SCENE region halfY=$halfY target=$target scale=$k")
    }

    fun installShared(r: SceneResources) {
        if (res != null) { r.close(); return }
        res = r
        // design lighting: a warm directional light from the upper left front (45°), moderate (B24: no blown whites)
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0.93f, 0.84f, 1f), 5500f, false))
            val q = Quat(Vector3(0f, 1f, 0f), (-45 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-45 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            content.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
        // soft contact shadows on the volume floor (design entities3d.shadow: 影子贴片)
        lockShadow = ModelEntity(r.shadowMesh, r.shadowMat).apply {
            setName("lockShadow"); content.addChild(this)
            transform().setPosition(Vector3(0f, -vol.h / 2 + 0.001f, 0f)); transform().setScaleVector(Vector3(0.30f * Layout.K, 1f, 0.30f * Layout.K))
        }
        for (p in 1..6) {
            val c = Layout.trayCentre(p)
            trayShadows += ModelEntity(r.shadowMesh, r.shadowMat).apply {
                setName("trayShadow:$p"); enabled = false; content.addChild(this)
                transform().setPosition(Vector3(c.x, Layout.FLOOR_Y + 0.0012f, c.z)); transform().setScaleVector(Vector3(0.075f * Layout.K, 1f, 0.23f * Layout.K))
            }
        }
        repeat(4) { i -> holes += ModelEntity(r.holeMesh, r.holeMat).apply { setName("hole:$i"); enabled = false; content.addChild(this); transform().setScaleVector(Vector3(Layout.U, Layout.U, Layout.U)) } }
        // arrows are signs only (rework v1.3, QA q1-1 / q1-2): the window's direction keys move the stick
        for (d in Dir.entries) {
            val a = ModelEntity(r.arrowMesh, r.arrowMat).apply { setName(BoardNames.arrow(d)); enabled = false }
            content.addChild(a); arrows[d] = a
        }
        // the one 3D target: the whole lock (46 cm at 4x, about 15 degrees at the default 1.75 m); a pinch on it brings the
        // control window back when it was closed (S18), otherwise it is only hover feedback
        lockTarget = Entity().apply { setName(BoardNames.LOCK) }
        lockTarget!!.components.set(CollisionComponent(collisionShape = listOf(r.lockShape), physicsMaterial = r.physics))
        lockTarget!!.components.set(InteractableComponent())
        lockTarget!!.components.set(HoverEffectComponent())
        content.addChild(lockTarget!!)
        Log.i(TAG, "SCENE shared resources in")
    }

    /**
     * The six sticks of lock [index]: geometry and colliders from the data. [textured] = the procedural wood is in
     * (S00 shows the same sticks plain first, design lifecycle 首帧兜底).
     */
    fun installPieces(index: Int, lock: Lock, list: List<PieceRes>, textured: Boolean) {
        val r = res ?: return
        if (index == builtLock && holders.isNotEmpty()) {
            // same lock, the textured wood arrives: swap the materials only
            for (i in list.indices) {
                woods[i].components[ModelComponent::class.java]?.materials?.set(0, list[i].wood)
                cuts[i].components[ModelComponent::class.java]?.materials?.set(0, list[i].cutWood)
            }
            val old = pieces; pieces = list; r.release(old, keepGeometry = true)
            texturedLock = if (textured) index else -1
            Log.i(TAG, "SCENE wood textures in for ${lock.id}")
            return
        }
        clearPieces()
        rules = Rules(lock)
        pieces = list
        builtLock = index
        texturedLock = if (textured) index else -1
        val dims = { p: Piece -> V3(if (p.axis == 0) 8f else 2f, if (p.axis == 1) 8f else 2f, if (p.axis == 2) 8f else 2f) * Layout.U }
        for ((i, pr) in list.withIndex()) {
            val piece = lock.pieces[i]
            val h = Entity().apply { setName(BoardNames.piece(piece.id)) }
            val wood = ModelEntity(pr.outer, pr.wood).apply { setName("wood:${piece.id}") }
            val cut = ModelEntity(pr.cut, pr.cutWood).apply { setName("cut:${piece.id}") }
            val hull = ModelEntity(pr.outer, r.glowMat).apply { setName("hull:${piece.id}"); enabled = false }
            val d = dims(piece)
            val grow = 0.012f * Layout.K
            hull.transform().setScaleVector(Vector3((d.x + grow) / d.x, (d.y + grow) / d.y, (d.z + grow) / d.z))
            h.addChild(wood); h.addChild(cut); h.addChild(hull)
            // no collider on a stick (rework v1.3): 4.5 cm sticks touching each other cannot meet D17 (>= 2 degrees, 1.5 cm
            // gaps) at the default 1.75 m; sticks are picked with the window's numbered handles (D50)
            content.addChild(h)
            holders += h; woods += wood; cuts += cut; hulls += hull
            val g = Entity().apply { setName("ghost:${piece.id}"); enabled = false }
            g.addChild(ModelEntity(pr.outer, r.ghostMat)); g.addChild(ModelEntity(pr.cut, r.ghostMat))
            content.addChild(g); ghosts += g
        }
        builtInfo = list.mapIndexed { i, p -> "${Piece.label(i + 1)} ${p.triangles} tris ${p.boxes} boxes" }.joinToString(", ")
        Log.i(TAG, "SCENE sticks of ${lock.id} built ($builtInfo) textured=$textured")
        onFrame(0f)
    }

    private fun clearPieces() {
        holders.forEach { it.removeFromParent() }; ghosts.forEach { it.removeFromParent() }
        holders.clear(); woods.clear(); cuts.clear(); hulls.clear(); ghosts.clear()
        res?.release(pieces)
        pieces = emptyList()
        builtLock = -1
    }

    fun builtLockIndex(): Int = builtLock
    fun texturedLockIndex(): Int = texturedLock

    fun apply(next: PlayState, s: Settings) {
        state = next
        settings = s
        onFrame(0f)
    }

    // ---------------------------------------------------------------- per frame

    private fun ease(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3 - 2 * u) }

    private fun set(e: Entity, p: PoseW) {
        e.transform().setPosition(Vector3(p.pos.x, p.pos.y, p.pos.z))
        e.transform().setQuaternion(Quat(p.rot.x, p.rot.y, p.rot.z, p.rot.w))
    }

    /** Lock rotation now (turn animation, explode's extra 30°, the S13 slow spin). */
    /** Presentation (yaw x pitch, rework v1.3) x explode / solved spin (about the vertical) x the turn of the lock. */
    private fun lockRot(st: PlayState, now: Long, elapsed: (Long) -> Long): Q {
        var q = Q.of(st.view.rot)
        val from = st.turnFrom
        if (from != null && !settings.reducedMotion) q = Q.of(from.rot).slerp(q, ease(elapsed(st.turnAt).toFloat() / GameFlow.TURN_MS))
        var spin = Q.I
        val e = explodeAmount(st, elapsed)
        if (e > 0f) spin = Q.axisAngle(V3(0f, 1f, 0f), (Layout.EXPLODE_YAW_DEG * PI / 180).toFloat() * e)
        if (st.phase == Phase.SOLVED && st.solvedAt > 0 && !settings.reducedMotion) {
            val t = (elapsed(st.solvedAt) / 6000f).coerceIn(0f, 1f)
            spin = Q.axisAngle(V3(0f, 1f, 0f), (2 * PI).toFloat() * ease(t)) * spin
        }
        return Layout.YAW_Q * spin * Layout.pitchQ() * q
    }


    private fun explodeAmount(st: PlayState, elapsed: (Long) -> Long): Float {
        if (st.explodeAt == 0L) return if (st.explodeOn) 1f else 0f
        val t = if (settings.reducedMotion) 1f else ease(elapsed(st.explodeAt).toFloat() / GameFlow.EXPLODE_MS)
        return if (st.explodeOn) t else 1f - t
    }

    private fun inLockPose(piece: Piece, off: V3i, q: Q, explode: Float): PoseW {
        var c = Layout.localCentre(piece, off)
        if (explode > 0f) c = c + Layout.explodeOffset(piece.id) * explode
        return PoseW(q.rotate(c), q)
    }

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        val r = res ?: return
        val rl = rules ?: return
        if (holders.isEmpty()) return
        val st = state
        if (st.lockIndex != builtLock) return
        val lock = rl.lock
        val now = SystemClock.uptimeMillis()
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.let { f -> min(e, f) } ?: e }
        val q = lockRot(st, now, ::elapsed)
        val explode = explodeAmount(st, ::elapsed)
        val reduced = settings.reducedMotion
        val anim = st.anim
        val hoverPiece = hover?.takeIf { now - hoverAt < 1500 }?.let { BoardNames.pieceOf(it) }

        for (i in holders.indices) {
            val piece = lock.pieces[i]
            val p = piece.id
            val off = st.pose.at(p)
            var pose = if (off != null) inLockPose(piece, off, q, explode) else Layout.inTray(piece)
            var opacity = 1f
            var red = explode > 0.5f
            if (anim != null && anim.piece == p) {
                val t = (elapsed(anim.startedAt).toFloat() / anim.ms).coerceIn(0f, 1f)
                when (anim) {
                    is SlideAnim -> {
                        if (reduced) {
                            pose = inLockPose(piece, if (t < 0.5f) anim.from else anim.to, q, 0f)
                            opacity = if (t < 0.5f) 1f - t * 2 else (t - 0.5f) * 2
                        } else {
                            val e = ease(t)
                            val a = Layout.localCentre(piece, anim.from); val b = Layout.localCentre(piece, anim.to)
                            pose = PoseW(q.rotate(a.lerp(b, e)), q)
                        }
                    }
                    is PathAnim -> {
                        if (reduced) {
                            pose = if (t < 0.5f) anim.path.points.first() else anim.path.points.last()
                            opacity = if (t < 0.5f) 1f - t * 2 else (t - 0.5f) * 2
                        } else pose = anim.path.at(ease(t))
                    }
                    is BumpAnim -> if (!reduced) {
                        val axis = q.rotate(V3.of(V3i.axis(piece.axis, 1)))
                        pose = PoseW(pose.pos + axis * (0.002f * Layout.K * sin(t * 4 * PI.toFloat())), pose.rot)
                    }
                    is NopeAnim -> pose = PoseW(pose.pos + V3(0f, 0.01f * Layout.K * sin(t * PI.toFloat()), 0f), pose.rot)
                    is InspectAnim -> {
                        val lift = 0.03f * Layout.K * sin(t * PI.toFloat()).let { if (t < 0.15f || t > 0.85f) it else 1f.coerceAtMost(it * 1.6f) }
                        val spin = Q.axisAngle(V3(0f, 0f, 1f), (2 * PI).toFloat() * ease((t - 0.1f) / 0.8f))
                        pose = PoseW(pose.pos + V3(0f, lift, 0f), if (reduced) pose.rot else spin * pose.rot)
                        red = true
                    }
                }
            }
            set(holders[i], pose)
            // D24: an opacity component of 1.0 still renders blended; remove it when not fading
            if (opacity < 0.999f) { holders[i].components.set(OpacityControllerComponent(opacity.coerceIn(0.02f, 1f))); fading[i] = true }
            else if (fading[i]) { holders[i].components.remove(OpacityControllerComponent::class.java); fading[i] = false }
            cuts[i].components[ModelComponent::class.java]?.materials?.set(0, if (red) r.vermilion else pieces[i].cutWood)
            // QA q1-5: the selected stick glows warm, the ones named "this one" (blockers, the right stick) blink hot
            // a debug hold (freeze) keeps the glow lit for the screenshot
            val glowing = p in st.glow && (now < st.glowUntil || st.freezeMs != null)
            val hot = glowing && ((now / 200) % 2 == 0L || st.freezeMs != null)
            val woodMat = when { hot -> pieces[i].woodHot; st.selected == p -> pieces[i].woodSel; else -> pieces[i].wood }
            woods[i].components[ModelComponent::class.java]?.materials?.set(0, woodMat)
            trayShadows[i].enabled = off == null && (anim == null || anim.piece != p)

            // outline: gold (blocked by / right one / slow helper) > select > coach > hover
            val hullMat: Material? = when {
                glowing -> r.glowMat
                st.selected == p -> r.selectMat
                st.coachGlow == p && now < st.coachGlowUntil && ((now / 300) % 2 == 0L) -> r.coachMat
                hoverPiece == p && anim == null -> r.hoverMat
                else -> null
            }
            hulls[i].enabled = hullMat != null
            if (hullMat != null) hulls[i].components[ModelComponent::class.java]?.materials?.set(0, hullMat)
        }
        lockShadow?.enabled = st.pose.inLock.isNotEmpty()

        // ---------------- hint ghost (M1) and slow replay (M2)
        ghosts.forEach { it.enabled = false }
        holes.forEach { it.enabled = false }
        val h = st.hint
        if (st.hintOn && h != null && !st.busy && explode == 0f && (st.phase == Phase.APART || st.phase == Phase.BACK)) {
            val piece = lock.piece(h.piece)
            val target: V3i? = h.ghost ?: st.pose.at(h.piece)?.let { off -> ghostOut(piece, off, h.dir) }
            val start = st.pose.at(h.piece)
            if (target != null) {
                var pose = inLockPose(piece, target, q, 0f)
                if (st.slowAt > 0 && start != null) {
                    val run = (elapsed(st.slowAt) % GameFlow.SLOW_RUN_MS).toFloat() / GameFlow.SLOW_RUN_MS
                    val a = Layout.localCentre(piece, start); val b = Layout.localCentre(piece, target)
                    pose = PoseW(q.rotate(a.lerp(b, ease(run))), q)
                    // design M2: the empty inner cells it moves into show as see-through red
                    var k = 0
                    if (h.reason.kind == io.embodit.lubanlock.domain.Kind.INTO_GAP) {
                        val occ = rl.occupancy(st.pose)
                        for (cell in lock.holes) if (cell !in occ && k < holes.size) {
                            val c = q.rotate(V3((cell.x + 0.5f) * Layout.U, (cell.y + 0.5f) * Layout.U, (cell.z + 0.5f) * Layout.U))
                            holes[k].transform().setPosition(Vector3(c.x, c.y, c.z)); holes[k].transform().setQuaternion(Quat(q.x, q.y, q.z, q.w)); holes[k].enabled = true; k++
                        }
                    }
                } else if (st.phase == Phase.BACK && start == null) {
                    pose = inLockPose(piece, target, q, 0f)
                }
                set(ghosts[h.piece - 1], pose)
                ghosts[h.piece - 1].enabled = true
            }
        }

        // ---------------- arrows: the selected stick's legal directions, or the hint's direction
        arrowSpots.clear()
        val arrowPiece = st.selected ?: h?.takeIf { st.hintOn && st.phase == Phase.APART && st.pose.isIn(it.piece) }?.piece
        val dirs = when {
            st.busy || explode > 0f || st.phase != Phase.APART -> emptyList()
            st.selected != null -> st.arrows
            arrowPiece != null && h != null -> listOf(h.dir)
            else -> emptyList()
        }
        for (d in Dir.entries) {
            val a = arrows[d] ?: continue
            if (arrowPiece == null || d !in dirs) { a.enabled = false; continue }
            val (spotL, w) = Layout.arrowSpot(rl, st.pose, arrowPiece, d, st.view)
            val pres = Layout.PRES_Q
            val spot = pres.rotate(spotL)
            arrowSpots[d] = spot
            a.transform().setPosition(Vector3(spot.x, spot.y, spot.z))
            val qa = pres * arrowRot(w)
            a.transform().setQuaternion(Quat(qa.x, qa.y, qa.z, qa.w))
            a.enabled = true
        }
    }

    /** The ghost of a removal stops 1u outside the lock box (design spatialLayout.ghost). */
    private fun ghostOut(piece: Piece, off: V3i, d: Dir): V3i {
        var k = 1
        while (k < 20) {
            val o = off + d.v * k
            if (piece.cells.all { c -> (c + o)[d.axis] * d.sign >= 5 }) break
            k++
        }
        return off + d.v * k
    }

    /** Arrow rotation: its +Z to [w]; flat face toward the user for left / right / up / down; tipped up 30° for toward / away. */
    private fun arrowRot(w: Dir): Q = when (w) {
        Dir.PX -> Q.axisAngle(V3(1f, 0f, 0f), (PI / 2).toFloat()) * Q.axisAngle(V3(0f, 1f, 0f), (PI / 2).toFloat())
        Dir.NX -> Q.axisAngle(V3(1f, 0f, 0f), (PI / 2).toFloat()) * Q.axisAngle(V3(0f, 1f, 0f), (-PI / 2).toFloat())
        Dir.PY -> Q.axisAngle(V3(1f, 0f, 0f), (-PI / 2).toFloat())
        Dir.NY -> Q.axisAngle(V3(1f, 0f, 0f), (PI / 2).toFloat()) * Q.axisAngle(V3(0f, 0f, 1f), PI.toFloat())
        Dir.PZ -> Q.axisAngle(V3(1f, 0f, 0f), (-Layout.ARROW_PITCH_DEG * PI / 180).toFloat())
        Dir.NZ -> Q.axisAngle(V3(1f, 0f, 0f), (Layout.ARROW_PITCH_DEG * PI / 180).toFloat()) * Q.axisAngle(V3(0f, 1f, 0f), PI.toFloat())
    }

    /** Debug hit replay (board-input template 必带单测 4): the holder and its deepest child. */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    fun evidence(): String {
        val st = state
        val pos = holders.mapIndexed { i, h -> "${Piece.label(i + 1)}=${h.transform().position}" }.joinToString(" ")
        return "lock=$builtLock textured=$texturedLock scale=$regionScale view=${st.view.code()} $pos arrows=${arrowSpots.map { "${it.key.code}@${it.value}" }} sticks: $builtInfo"
    }

    fun dispose() {
        res?.release(pieces)
        res?.close()
        res = null
    }
}

/** Every holder name (one place). */
object BoardNames {
    /** The whole lock (the only 3D target since rework v1.3). */
    const val LOCK = "lock"
    fun piece(id: Int) = "piece:$id"
    fun arrow(d: Dir) = "arrow:${d.code}"

    sealed interface Hit
    data class PieceHit(val id: Int) : Hit
    data class ArrowHit(val dir: Dir) : Hit
    data object LockHit : Hit
    data object NoHit : Hit

    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        if (name == LOCK) return LockHit
        val f = name.split(":")
        if (f.size != 2) return NoHit
        return when (f[0]) {
            "piece" -> f[1].toIntOrNull()?.takeIf { it in 1..6 }?.let { PieceHit(it) } ?: NoHit
            "arrow" -> Dir.parse(f[1])?.let { ArrowHit(it) } ?: NoHit
            else -> NoHit
        }
    }

    fun pieceOf(name: String): Int? = (hitOf(name) as? PieceHit)?.id
}
