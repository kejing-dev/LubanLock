package com.kejing.lubanlock

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.kejing.lubanlock.ui.Containers
import com.kejing.lubanlock.ui.board.BoardScreen
import com.kejing.lubanlock.ui.control.ControlWindow
import com.kejing.lubanlock.ui.theme.LubanTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the lock volume (default container,
 * 0.68 x 0.78 x 0.80 m from the manifest) and the control window (Planar 640 x 680 dp) to its right. Every open (first
 * open, [重开控制窗]) uses Placement.right(lock) (profile D44 correction: Shared Space never promises "in front of you";
 * the user moves the window with the system handle).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            LubanTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 640.dp, height = 680.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            LubanTheme {
                Box(Modifier.windowConstraints(minWidth = 600.dp, minHeight = 640.dp, maxWidth = 900.dp, maxHeight = 760.dp)) { ControlWindow() }
            }
        }
    }
