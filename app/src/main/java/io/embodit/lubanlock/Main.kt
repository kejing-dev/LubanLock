package io.embodit.lubanlock

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import io.embodit.lubanlock.platform.Graph
import io.embodit.lubanlock.ui.Containers
import io.embodit.lubanlock.ui.board.BoardScreen
import io.embodit.lubanlock.ui.control.ControlWindow
import io.embodit.lubanlock.ui.theme.LubanTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the lock volume (default container,
 * 0.62 x 0.47 x 0.565 m from the manifest while the lock stands assembled, grown to the big size while sticks can leave it:
 * WindowCtl.volume, D54) and the control window (Planar) to its right. Every open (first
 * open, [重开控制窗]) uses Placement.right(lock) (profile D44 correction: Shared Space never promises "in front of you";
 * the user moves the window with the system handle).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            val context = LocalContext.current
            val graph = remember(context) { Graph.from(context) }
            val v = graph.window.volume
            val conv = LocalPhysicalLengthConverter.current
            LubanTheme {
                Box(Modifier.windowConstraints(width = conv.lengthToDp(v.w, LengthUnit.Meters), height = conv.lengthToDp(v.h, LengthUnit.Meters), depth = conv.lengthToDp(v.d, LengthUnit.Meters))) {
                    BoardScreen()
                }
            }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 720.dp, height = 760.dp),
            // beside the small volume, and it stays there when the volume grows: the system leaves about 0.37 m, so its
            // left edge is where the big volume's right face is (Layout.WINDOW_GAP, D54 v0.1.3, ui-audit)
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            LubanTheme {
                Box(Modifier.windowConstraints(minWidth = 680.dp, minHeight = 720.dp, maxWidth = 960.dp, maxHeight = 840.dp)) { ControlWindow() }
            }
        }
    }
