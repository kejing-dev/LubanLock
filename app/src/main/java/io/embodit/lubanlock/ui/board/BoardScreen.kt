package io.embodit.lubanlock.ui.board

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.lubanlock.domain.GameFlow
import io.embodit.lubanlock.domain.Layout
import io.embodit.lubanlock.domain.Locks
import io.embodit.lubanlock.domain.PlayState
import io.embodit.lubanlock.platform.Graph
import io.embodit.lubanlock.platform.TAG
import io.embodit.lubanlock.ui.board.input.BoardInputConfig
import io.embodit.lubanlock.ui.board.input.BoardInputListener
import io.embodit.lubanlock.ui.board.input.BoardInputLog
import io.embodit.lubanlock.ui.board.input.InputSource
import io.embodit.lubanlock.ui.board.input.TargetResolver
import io.embodit.lubanlock.ui.board.input.boardInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the volume's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    /** Debug: rebuild the sticks with "F↑" textures (D10 orientation check). */
    @Volatile var testF = false
    @Volatile var rebuild: (() -> Unit)? = null
    @Volatile var res: SceneResources? = null
}

/**
 * The lock volume (default container). Opens the control window beside it through the level gate, shows the lock, and
 * routes every pinch / trigger on a stick or an arrow to [GameFlow] through the factory board-input template (D50: hand
 * ray, controller ray and eye-hand alike). No drags in v1 (design input.rule).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var sharedIn by remember { mutableStateOf(false) }
    var gen by remember { mutableStateOf(0) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> Log.i(TAG, "LIFECYCLE foreground")
                // S18 fallback: the app icon tapped again brings the volume to the front; the window comes back
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    if (graph.window.panelState == "ON_DESTROY") graph.window.request("resume")
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> { Log.i(TAG, "VOLUME destroyed S18b"); graph.window.onVolumeLifecycle("ON_DESTROY") }
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> graph.window.onVolumeLifecycle("ON_CREATE")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        SceneHolder.rebuild = { gen++ }
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    // D54: the volume follows the state (small while the lock stands assembled, big while sticks can leave it)
    LaunchedEffect(state) { graph.window.wantVolume(Layout.volumeFor(state, graph.flow.rulesFor())) }

    // the sticks of the current lock: plain first (S00 stand-in), then the procedural wood
    LaunchedEffect(sharedIn, state.lockIndex, gen) {
        if (!sharedIn) return@LaunchedEffect
        val res = SceneHolder.res ?: return@LaunchedEffect
        val i = state.lockIndex
        val lock = Locks.ALL[i]
        val t0 = android.os.SystemClock.elapsedRealtime()
        val plain = withContext(NonCancellable + Dispatchers.IO) { res.pieces(lock, textured = false, testF = false) }
        scene.installPieces(i, lock, plain, textured = false)
        val wood = withContext(NonCancellable + Dispatchers.Default) { res.pieces(lock, textured = true, testF = SceneHolder.testF, geometry = plain) }
        scene.installPieces(i, lock, wood, textured = true)
        Log.i(TAG, "STICKS ${lock.id} plain+wood in ${android.os.SystemClock.elapsedRealtime() - t0} ms testF=${SceneHolder.testF}")
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .boardInput(remember { BoardTargets() }, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(dragStartM = 0.012f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.root)
            val res = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            SceneHolder.res = res
            scene.installShared(res)
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            sharedIn = true
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y), graph.window.volume, graph.window.volumeChangedAt)
            }
            scene.apply(state, settings)
        },
    )
}

/** Hit entity (or an ancestor) -> the holder name the game knows (sticks and arrows). */
internal class BoardTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { BoardNames.hitOf(it) != BoardNames.NoHit }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.lubanlock.ui.board.input.resolvePath(path, ::targetOf)

        /** What a target stands for in the input log (`square=`): a stick, an arrow, or "-". */
        fun squareOf(target: String?, st: PlayState): String = when (val h = BoardNames.hitOf(target)) {
            is BoardNames.PieceHit -> "${if (st.pose.isIn(h.id)) "lock" else "tray"}${h.id}"
            is BoardNames.ArrowHit -> "arrow${h.dir.code}"
            BoardNames.LockHit -> "lock"
            BoardNames.NoHit -> "-"
        }
    }
}

/** Every resolved tap goes through [routeFlow] (the same calls the panel and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value)}")
        if (BoardNames.hitOf(target) == BoardNames.LockHit) { lockTapped(graph); return }
        routeFlow(graph.flow, target)
    }

    /** No drags in v1 (design input.rule: 本版不用拖动手势): a drag on a stick counts as a tap on it. */
    // gate:allow shared-drag 拖动不移动任何东西，只当成点一下（选木条）；挪木条全靠控制窗的编号键和方向键，体积是门 B 定的 Shared Space（container.why「实现站不得改」），用户 2026-11-13 真机「其他没问题可以发布了」
    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag-as-tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value)}")
        if (BoardNames.hitOf(target) == BoardNames.LockHit) { lockTapped(graph); return true }
        routeFlow(graph.flow, target)
        return true
    }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** A pinch on the lock: brings the control window back when it was closed (S18); the sticks are picked in the window. */
internal fun lockTapped(graph: Graph) {
    if (!graph.window.panelOpen) { Log.i(TAG, "S18 pinch on the lock -> reopen the window"); graph.window.request("pinch") }
    else Log.i(TAG, "LOCK pinch (window open: pick a stick with its number in the window)")
}

/** Holder name -> the one flow call (hit-replay unit tests drive a bare GameFlow through it). */
internal fun routeFlow(flow: GameFlow, name: String?) {
    when (val h = BoardNames.hitOf(name)) {
        is BoardNames.PieceHit -> flow.tapPiece(h.id)
        is BoardNames.ArrowHit -> {
            val st = flow.state.value
            val p = st.selected ?: st.hint?.piece
            if (p != null) flow.arrowOf(p, h.dir)
        }
        BoardNames.LockHit, BoardNames.NoHit -> Unit
    }
}
