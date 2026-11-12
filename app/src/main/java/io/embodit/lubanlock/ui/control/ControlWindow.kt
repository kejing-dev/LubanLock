package io.embodit.lubanlock.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.lubanlock.BuildConfig
import io.embodit.lubanlock.R
import io.embodit.lubanlock.domain.ConfirmKind
import io.embodit.lubanlock.domain.Dir
import io.embodit.lubanlock.domain.Language
import io.embodit.lubanlock.domain.Locks
import io.embodit.lubanlock.domain.Phase
import io.embodit.lubanlock.domain.Piece
import io.embodit.lubanlock.domain.PlayState
import io.embodit.lubanlock.domain.Readout
import io.embodit.lubanlock.domain.Row3
import io.embodit.lubanlock.domain.Settings
import io.embodit.lubanlock.domain.Words
import io.embodit.lubanlock.platform.Graph
import io.embodit.lubanlock.platform.TAG
import io.embodit.lubanlock.ui.Page
import io.embodit.lubanlock.ui.Ui
import io.embodit.lubanlock.ui.WindowConfirm
import io.embodit.lubanlock.ui.theme.LubanColors
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the lock, 640 x 520 dp): all 2D UI, three pages switched by the
 * native SpatialUI TabBar (D8): Play / How to play / Settings. No page scrolls (D44); the volume holds no panels.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the how-to page opens first (design onboarding: 首启「怎么玩」页 + [开始])
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase) { if (state.phase == Phase.APART_DONE || state.phase == Phase.SOLVED) Ui.page = Page.PLAY }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.confirm = WindowConfirm.NONE
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.RULES -> RulesPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass
    // plain strings in (ui_gates tabbar-lang; an English cold start shows three English tabs).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

@Composable
internal fun isZh(): Boolean = stringResource(R.string.lang_code) == "zh"

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

/** Page header (52 dp): a title and [静音] (D15: mute is always one glance away). */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        MuteButton(settings, graph, "$tag/mute")
    }
}

@Composable
private fun MuteButton(settings: Settings, graph: Graph, tag: String, modifier: Modifier = Modifier) {
    ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_muted), tag, on = !settings.sound,
        onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, icon = R.drawable.ic_volume_off, modifier = modifier)
}

/** Ticks while a timed row-3 sentence is showing so it falls back to "what to do now" on time (design eachMoveWhy: 3 s). */
@Composable
private fun rememberNow(state: PlayState): Long {
    var now by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(state.lineUntil, state.line) {
        now = android.os.SystemClock.uptimeMillis()
        val wait = state.lineUntil - now
        if (wait > 0) { delay(wait + 30); now = android.os.SystemClock.uptimeMillis() }
    }
    return now
}

/** Row 3 text (design panels.play): Readout picks what to say, the templates say it in the app language. */
@Composable
internal fun row3Text(state: PlayState, now: Long): String {
    val zh = isZh()
    return when (val r = Readout.row3(state, now)) {
        is Row3.Why -> Words.say(r.reason, state.view, zh)
        is Row3.Turned -> stringResource(R.string.row3_turned, Piece.label(r.piece))
        is Row3.Inspect -> if (r.notches == 0) stringResource(R.string.row3_inspect_key, Piece.label(r.piece)) else stringResource(R.string.row3_inspect, Piece.label(r.piece), r.notches)
        is Row3.Selected -> Words.canGo(r.piece, r.dirs, state.view, zh)
        is Row3.Restored -> {
            val l = Locks.ALL[r.lockIndex]
            val name = if (zh) l.nameZh else l.nameEn
            if (r.back) stringResource(R.string.row3_restored_back, name, r.step, r.of) else stringResource(R.string.row3_restored_apart, name, r.step)
        }
        Row3.CloseFirst -> stringResource(R.string.row3_close_first)
        Row3.Exploded -> stringResource(R.string.row3_exploded)
        Row3.Start -> stringResource(R.string.row3_start)
        Row3.BackInsert -> stringResource(R.string.row3_back_insert)
        Row3.BackShift -> stringResource(R.string.row3_back_shift)
        Row3.None -> ""
    }
}

@Composable
internal fun row2Text(state: PlayState, settings: Settings): String {
    val best = settings.best[state.lock.id]
    return when (state.phase) {
        Phase.APART_DONE -> stringResource(R.string.row2_done, state.steps)
        Phase.SOLVED -> stringResource(R.string.row2_solved, state.result?.apartSteps ?: state.steps, state.plan.size)
        else -> {
            val r = Readout.row2(state, best)
            if (r.back) stringResource(R.string.row2_back, r.step, r.of)
            else if (r.best != null) stringResource(R.string.row2_apart_best, r.step, r.min, r.best)
            else stringResource(R.string.row2_apart, r.step, r.min)
        }
    }
}

// ------------------------------------------------------------------ Play

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.phase == Phase.LOADING) {
        PageColumn {
            Header(stringResource(R.string.app_name), "play", settings, graph)
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Txt(stringResource(R.string.loading), "play/loading", style = PicoTheme.typography.titleMedium)
            }
        }
        return
    }
    if (!graph.window.volumeOpen) { VolumeClosedPage(settings, graph); return }
    when (state.confirm) {
        ConfirmKind.RESET -> {
            ConfirmPage(stringResource(R.string.confirm_reset), stringResource(R.string.confirm_reset_line, state.steps + state.planIndex), stringResource(R.string.confirm_yes_reset),
                R.drawable.ic_replay, stringResource(R.string.tab_play), "reset", settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() })
            return
        }
        ConfirmKind.LOCK -> {
            val l = Locks.ALL[state.confirmLock]
            ConfirmPage(stringResource(R.string.confirm_lock, if (isZh()) l.nameZh else l.nameEn), stringResource(R.string.confirm_lock_line), stringResource(R.string.confirm_yes_lock),
                R.drawable.ic_replay, stringResource(R.string.tab_play), "lock", settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() })
            return
        }
        null -> Unit
    }
    val now = rememberNow(state)
    val zh = isZh()
    val idle = !state.inputLocked
    val result = state.phase == Phase.APART_DONE || state.phase == Phase.SOLVED
    PageColumn {
        // row 1: which lock (same-page option, not page switching) + mute
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // gate:allow tabs-segment 选锁：同一页里换一把锁（三把锁的数据切换），不切页面；页面只用原生 TabBar
            Segments(Locks.ALL.indices.map { it to stringResource(R.string.lock_seg, if (zh) Locks.ALL[it].nameZh else Locks.ALL[it].nameEn, "★".repeat(Locks.ALL[it].stars)) },
                state.lockIndex, "play/lock", { i -> flow.requestLock(i) }, Modifier.weight(1f), small = !zh)
            Spacer(Modifier.width(10.dp))
            MuteButton(settings, graph, "play/mute", Modifier.width(124.dp))
        }
        // row 2: phase + counters
        Txt(row2Text(state, settings), "play/steps", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        // row 3: the sentence (or the result card) + [慢放一遍]
        if (result) ResultCard(state, graph)
        else Row(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(24.dp)).background(LubanColors.TagFace).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(row3Text(state, now), "play/line", style = PicoTheme.typography.bodyLarge, color = LubanColors.Ink, maxLines = 3, modifier = Modifier.weight(1f))
            if (state.hintOn) {
                Spacer(Modifier.width(10.dp))
                SecondaryButton(stringResource(if (state.slowAt > 0) R.string.btn_slow_stop else R.string.btn_slow), "play/slow", onClick = { flow.slow() },
                    enabled = state.hint != null, modifier = Modifier.width(if (zh) 128.dp else 152.dp))
            }
        }
        // rework v1.3 (QA q1-1 / q1-4, profile D50): pick a stick by its number, then move it with a direction key
        StickHandles(state, graph)
        DirectionKeys(state, graph)
        // row 4: hint · open up · undo
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleButton(stringResource(if (state.hintOn) R.string.btn_hint_on else R.string.btn_hint_off), "play/hint", on = state.hintOn,
                onClick = { flow.setHint(!state.hintOn) }, enabled = state.phase == Phase.APART || state.phase == Phase.BACK || state.phase == Phase.APART_DONE,
                icon = R.drawable.ic_hint, modifier = Modifier.weight(1.15f))
            val flash = now - state.explodeFlashAt < 900
            ToggleButton(stringResource(if (state.explodeOn) R.string.btn_explode_on else R.string.btn_explode_off), "play/explode", on = state.explodeOn || (flash && (now / 150) % 2 == 0L),
                onClick = { flow.setExplode(!state.explodeOn) }, enabled = !state.busy && !result, icon = R.drawable.ic_explode, modifier = Modifier.weight(1.15f))
            SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = state.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(0.9f))
        }
        // row 5: turn it
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.turn_label), "play/turn", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(if (zh) 72.dp else 64.dp))
            SecondaryButton(stringResource(R.string.btn_left), "play/left", onClick = { flow.turn(-1) }, enabled = idle && state.phase != Phase.SOLVED, icon = R.drawable.ic_rotate_left, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_right), "play/right", onClick = { flow.turn(1) }, enabled = idle && state.phase != Phase.SOLVED, icon = R.drawable.ic_rotate_right, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_flip), "play/flip", onClick = { flow.flip() }, enabled = idle && state.phase != Phase.SOLVED, icon = R.drawable.ic_flip, modifier = Modifier.weight(1f))
        }
        // row 6: start over
        SecondaryButton(stringResource(R.string.btn_reset), "play/reset", onClick = { flow.requestReset() }, icon = R.drawable.ic_replay,
            enabled = !state.busy && state.confirm == null, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * ①–⑥ handles (profile D50: one controller picks a part by its number). In the lock: select it (its legal ways light up
 * in the direction row and as arrows on the lock); on the tray: look at it (S06b); putting back: put it in (the right
 * one goes, a wrong one bumps and tells why). Same GameFlow.tapPiece as every other route.
 */
@Composable
private fun StickHandles(state: PlayState, graph: Graph) {
    val usable = !state.inputLocked && state.phase != Phase.SOLVED && state.phase != Phase.LOADING
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(R.string.pick_label), "play/pick", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        for (p in 1..6) {
            val inLock = state.pose.isIn(p)
            val hintThis = state.hintOn && state.hint?.piece == p
            KeyButton(Piece.label(p), "play/pick$p", on = state.selected == p || hintThis, sub = if (inLock) null else stringResource(R.string.pick_out),
                onClick = { graph.flow.tapPiece(p) }, enabled = usable, modifier = Modifier.weight(1f), big = true)
        }
    }
}

/**
 * Direction keys (profile D50 眼手方向键 UI): the six ways as seen from where you sit (the words follow the turn of the
 * lock). Only the ways the selected stick can go are enabled; with the hint on and nothing selected, the hint's way.
 */
@Composable
private fun DirectionKeys(state: PlayState, graph: Graph) {
    val zh = isZh()
    val hint = state.hint?.takeIf { state.hintOn && state.phase == Phase.APART && state.pose.isIn(it.piece) }
    val piece = state.selected ?: hint?.piece
    val legal: List<Dir> = when {
        state.selected != null -> state.arrows
        hint != null -> listOf(hint.dir)
        else -> emptyList()
    }
    val usable = !state.inputLocked && state.phase == Phase.APART && !state.explodeOn
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(R.string.way_label), "play/way", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        for (w in listOf(Dir.NX, Dir.PX, Dir.PY, Dir.NY, Dir.PZ, Dir.NZ)) {
            val d = state.view.rot.transpose.dir(w)
            val ok = usable && piece != null && d in legal
            val word = if (zh) Words.zh(w) else Words.shortEn(w)
            KeyButton(word, "play/way${w.code}", on = ok && hint != null && hint.dir == d && state.selected == null,
                onClick = { if (piece != null) graph.flow.arrowOf(piece, d) }, enabled = ok, modifier = Modifier.weight(1f))
        }
    }
}

/** S10 / S13 result card in row 3 (design: 结果卡 + 主按钮 [开始装] / [下一把]). */
@Composable
private fun ResultCard(state: PlayState, graph: Graph) {
    val r = state.result
    val last = state.lockIndex == Locks.ALL.lastIndex
    Row(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(24.dp)).background(LubanColors.TagFace).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val text = if (state.phase == Phase.APART_DONE) stringResource(R.string.result_apart, state.steps, state.lock.minMoves)
            else if (last) stringResource(R.string.result_solved_last, r?.apartSteps ?: 0, r?.backSteps ?: 0)
            else stringResource(R.string.result_solved, r?.apartSteps ?: 0, r?.backSteps ?: 0)
            Txt(text, "result/text", style = PicoTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = LubanColors.Ink, maxLines = 3)
            if (state.phase == Phase.APART_DONE && r?.newRecord == true) {
                Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(LubanColors.Primary).padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Txt(stringResource(R.string.result_record), "result/record", style = PicoTheme.typography.labelMedium, color = LubanColors.OnPrimary)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (state.phase == Phase.APART_DONE) PrimaryButton(stringResource(R.string.btn_start_back), "result/back", onClick = { graph.flow.startBack() }, enabled = !state.busy)
        else PrimaryButton(stringResource(if (last) R.string.btn_again else R.string.btn_next), "result/next", onClick = { graph.flow.next() })
    }
}

/** S16: a whole-page confirmation card; the volume is locked while it is open (PlayState.inputLocked). */
@Composable
private fun ConfirmPage(q: String, line: String, yes: String, icon: Int, title: String, tag: String, settings: Settings, graph: Graph, onYes: () -> Unit, onNo: () -> Unit) {
    PageColumn {
        Header(title, "confirm", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).clip(RoundedCornerShape(28.dp)).background(LubanColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(52.dp), tint = LubanColors.Ink)
            Txt(q, "confirm/$tag", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = LubanColors.Ink, maxLines = 2)
            Txt(line, "confirm/line", style = PicoTheme.typography.bodyLarge, color = LubanColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(64.dp))
                SecondaryButton(yes, "confirm/yes", danger = true, onClick = onYes, modifier = Modifier.weight(1f).height(64.dp))
            }
        }
        Txt(stringResource(R.string.confirm_lock_note), "confirm/note", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** S18b: the system [×] closed the lock volume; the play buttons wait (D41: controls follow their window). */
@Composable
private fun VolumeClosedPage(settings: Settings, graph: Graph) {
    PageColumn {
        Header(stringResource(R.string.app_name), "volume", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(LubanColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Txt(stringResource(R.string.volume_closed), "volume/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = LubanColors.Ink)
            Txt(stringResource(R.string.volume_closed_line), "volume/line", style = PicoTheme.typography.bodyLarge, color = LubanColors.InkSecondary, maxLines = 2)
            PrimaryButton(stringResource(R.string.btn_bring_lock), "volume/bring", onClick = { graph.window.reopenVolume() }, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "volume/undo", onClick = {}, enabled = false, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_hint_off), "volume/hint", onClick = {}, enabled = false, icon = R.drawable.ic_hint, modifier = Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ How to play

@Composable
private fun RulesPage(state: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 150.dp, height = 112.dp).clip(RoundedCornerShape(16.dp)).background(LubanColors.TagFace), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(width = 140.dp, height = 104.dp)) { drawLockFigure(this) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(LubanColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = LubanColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line$i", style = PicoTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Txt(stringResource(R.string.why_title), "rules/why", style = PicoTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.why_key to 0, R.string.why_notch to 1, R.string.why_gap to 2).forEach { (id, kind) ->
                Row(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(14.dp)).background(LubanColors.TagFace).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(34.dp)) { drawWhyFigure(this, kind) }
                    Spacer(Modifier.width(6.dp))
                    Txt(stringResource(id), "rules/why$kind", style = PicoTheme.typography.bodySmall, color = LubanColors.Ink, maxLines = 4, modifier = Modifier.weight(1f))
                }
            }
        }
        Txt(stringResource(R.string.mistakes_title), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { k, id ->
                    Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(12.dp)).background(LubanColors.TagFace).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(stringResource(id), "rules/mistake${row * 2 + k}", style = PicoTheme.typography.bodySmall, color = LubanColors.Ink, maxLines = 3)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            PrimaryButton(stringResource(if (state.welcome) R.string.btn_start else R.string.btn_back_play), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY })
        }
    }
}

/** An isometric bar (cells x 1 x 1) from [o] along [axis] 0 = x (right-down), 1 = y (up), 2 = z (left-down). */
private fun DrawScope.isoBar(o: Offset, len: Float, axis: Int, cell: Float, face: Color, side: Color, top: Color, line: Color) {
    val ex = Offset(cell * 0.87f, cell * 0.5f); val ey = Offset(0f, -cell); val ez = Offset(-cell * 0.87f, cell * 0.5f)
    val a = when (axis) { 0 -> ex * len; 1 -> ey * len; else -> ez * len }
    val b = if (axis == 0) ez else ex
    val c = if (axis == 1) ez else ey
    fun quad(p: Offset, u: Offset, v: Offset, col: Color) {
        val path = Path().apply { moveTo(p.x, p.y); lineTo(p.x + u.x, p.y + u.y); lineTo(p.x + u.x + v.x, p.y + u.y + v.y); lineTo(p.x + v.x, p.y + v.y); close() }
        drawPath(path, col); drawPath(path, line, style = Stroke(width = 1.2f))
    }
    quad(o + c, a, b, top)
    quad(o, a, c, face)
    quad(o + a, b, c, side)
}

/** The rules picture (design rulesPage: 锁 + 抽出来的钥匙 + 一根上的槽). */
private fun drawLockFigure(d: DrawScope) = with(d) {
    val cell = size.height / 9f
    val o = Offset(size.width * 0.42f, size.height * 0.58f)
    val wood = LubanColors.Beech; val dark = Color(0xFFB8956A); val light = Color(0xFFEBD5B0); val line = Color(0xFF4A3222)
    isoBar(o + Offset(-cell * 2.6f, -cell * 0.3f), 5f, 0, cell, wood, dark, light, line)
    isoBar(o + Offset(-cell * 0.4f, cell * 1.3f), 4.5f, 1, cell, wood, dark, light, line)
    isoBar(o + Offset(cell * 1.6f, -cell * 0.6f), 4f, 2, cell, wood, dark, light, line)
    // the key half out toward the viewer (lower left)
    isoBar(o + Offset(-cell * 0.2f, cell * 0.9f), 5f, 2, cell, LubanColors.Turquoise, Color(0xFF2E8C85), Color(0xFF7FD3CC), line)
    // one notch shown red on the top bar
    drawCircle(LubanColors.Vermilion, radius = cell * 0.35f, center = o + Offset(cell * 0.9f, -cell * 1.0f))
}

/** Small pictures for 钥匙 / 槽口 / 空格. */
private fun drawWhyFigure(d: DrawScope, kind: Int) = with(d) {
    val w = size.width; val h = size.height
    val wood = LubanColors.Beech; val line = Color(0xFF4A3222)
    when (kind) {
        0 -> { // a plain bar with an arrow out
            drawRect(wood, Offset(w * 0.1f, h * 0.38f), androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.24f)); drawRect(line, Offset(w * 0.1f, h * 0.38f), androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.24f), style = Stroke(1.5f))
            val p = Path().apply { moveTo(w * 0.76f, h * 0.3f); lineTo(w * 0.96f, h * 0.5f); lineTo(w * 0.76f, h * 0.7f); close() }; drawPath(p, LubanColors.Amber)
        }
        1 -> { // a bar with a cut and a crossing bar in it
            val p = Path().apply { moveTo(w * 0.05f, h * 0.35f); lineTo(w * 0.38f, h * 0.35f); lineTo(w * 0.38f, h * 0.5f); lineTo(w * 0.62f, h * 0.5f); lineTo(w * 0.62f, h * 0.35f); lineTo(w * 0.95f, h * 0.35f); lineTo(w * 0.95f, h * 0.7f); lineTo(w * 0.05f, h * 0.7f); close() }
            drawPath(p, wood); drawPath(p, line, style = Stroke(1.5f))
            drawRect(LubanColors.Vermilion, Offset(w * 0.40f, h * 0.05f), androidx.compose.ui.geometry.Size(w * 0.2f, h * 0.43f))
        }
        else -> { // a grid with one empty cell
            val c = w / 3f
            for (i in 0..2) for (j in 0..2) {
                val tl = Offset(i * c, j * c)
                if (i == 1 && j == 1) drawRect(LubanColors.Vermilion.copy(alpha = 0.35f), tl, androidx.compose.ui.geometry.Size(c, c))
                else drawRect(wood, tl, androidx.compose.ui.geometry.Size(c, c))
                drawRect(line, tl, androidx.compose.ui.geometry.Size(c, c), style = Stroke(1.2f))
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    when (Ui.confirm) {
        WindowConfirm.EXIT -> {
            ConfirmPage(stringResource(R.string.confirm_exit), stringResource(R.string.confirm_exit_line), stringResource(R.string.confirm_yes_exit),
                R.drawable.ic_exit, stringResource(R.string.settings_title), "exit", settings, graph, onYes = { exitApp(context, graph) }, onNo = { Ui.confirm = WindowConfirm.NONE })
            return
        }
        WindowConfirm.CLEAR_BEST -> {
            ConfirmPage(stringResource(R.string.confirm_best), stringResource(R.string.confirm_best_line), stringResource(R.string.confirm_yes_best),
                R.drawable.ic_replay, stringResource(R.string.settings_title), "best", settings, graph, onYes = { graph.flow.clearBest(); Ui.confirm = WindowConfirm.NONE }, onNo = { Ui.confirm = WindowConfirm.NONE })
            return
        }
        WindowConfirm.NONE -> Unit
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    val labelW = 112.dp
    PageColumn {
        Header(stringResource(R.string.settings_title), "settings", settings, graph)
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = labelW) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = labelW) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.width(150.dp))
            Spacer(Modifier.width(12.dp))
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = labelW) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.settings_reopen_panel), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.width(220.dp))
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.settings_clear_best), "settings/best", onClick = { Ui.confirm = WindowConfirm.CLEAR_BEST; Log.i(TAG, "CONFIRM BEST S16") },
            icon = R.drawable.ic_replay, modifier = Modifier.width(220.dp))
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(labelW))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; the confirmation is a whole-page card
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.confirm = WindowConfirm.EXIT; Log.i(TAG, "CONFIRM EXIT S16") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                // the system capsule never draws supportContent (pico-cli-feedback: TabBar 只画图标), so the word sits under
                // the icon in mainContent and takes the item's content colour (white when selected, puzzle-box QA q2-1)
                mainContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
                        Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelSmall, color = LocalContentColor.current)
                    }
                },
            )
        }
    }
}
