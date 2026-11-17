package io.embodit.lubanlock.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The lock volume is the default container (manifest). */
object Containers {
    const val BOARD = "LubanLock"
    const val CONTROL = "LubanControl"
    const val CONTROL_TAG = "control"
}

/** The three pages of the control window (design panels.control: 玩 / 怎么玩 / 设置). */
enum class Page(val stateId: String) { PLAY("S02"), RULES("S15"), HOWTO("S15b"), SETTINGS("S14") }

/** Window-page confirmations that are not game state (design S16: 清空最佳 / 退出应用). */
enum class WindowConfirm { NONE, CLEAR_BEST, EXIT }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var confirm by mutableStateOf(WindowConfirm.NONE)
}
