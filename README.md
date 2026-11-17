<p align="center"><img src="docs/icon.png" width="128" alt="Luban Lock icon"></p>

# Luban Lock | 鲁班锁

**模拟器 QA 通过，真机已验证（2026-11-12 头显试玩）· Emulator QA passed, verified on a real headset (2026-11-12)**

## 安装 / Install

从 [Releases](https://github.com/kejing-dev/LubanLock/releases/tag/v0.1.2) 下载 `LubanLock-v0.1.2-debug.apk`（可直接 adb 安装），然后：
Download `LubanLock-v0.1.2-debug.apk` (installs directly with adb) from [Releases](https://github.com/kejing-dev/LubanLock/releases/tag/v0.1.2), then:

```bash
adb install -r LubanLock-v0.1.2-debug.apk
pico-cli app launch io.embodit.lubanlock
```

Release 里的 `LubanLock-v0.1.2.apk` 是未签名的 release 包，用于应用商店上架，不能直接 adb 安装。
`LubanLock-v0.1.2.apk` in the release is the unsigned release build for store submission; it cannot be installed with adb as is.

PICO OS 6 · PICO Spatial SDK 6.1 · Kotlin / SpatialUI · `io.embodit.lubanlock` · v0.1.2 · 离线、中英双语、无需账号

![鲁班锁：选中 ⑤ 号木条，方向键只亮能走的方向](docs/S03-zh.jpg)

> 演示截图来自原生 PICO 模拟器，由调试输入驱动；**非真实手势录像**。
> Screenshots come from the native PICO emulator, driven by debug input; **not a recording of real hand gestures.**

---

## 中文

### 这是什么

桌上浮着一只六根木头卡在一起的鲁班锁（孔明锁，放大 4 倍）。在旁边的控制窗按编号 ①–⑥ 选一根，它能往哪走，方向键就亮；点方向键，它就顺着槽滑出来、落到桌上。六根全拆下来，再一根根装回去。每一步都告诉你这根为什么现在能动、被卡住时是哪一根卡着它。三把锁由易到难：钥匙在哪 / 先让一步 / 里面是空的。

### 怎么玩

- **选木条**：控制窗「选木条」一行的 ①–⑥ 编号把手。选中的木条亮暖光、描金边。
- **滑动**：「往哪走」一行的 6 个方向键（左 / 右 / 上 / 下 / 朝你 / 往里），只有能走的方向亮。
- **被卡住**：卡着它的那根木条亮红光，说明框写出是谁挡着。
- **给个提示 / 拆开看 / 悔一步 / 左转 / 右转 / 翻个面 / 重来**：都在控制窗里。
- 3D 里只有整把锁一个可点目标：控制窗关了时点锁就能重新打开。

### 容器

Shared Space 体积窗口（`IN_VOLUME`）放锁和桌上一排木条：锁装好时 0.62 × 0.47 × 0.565 m，锁几乎撑满；能拆出来时长到 1.36 × 1.56 × 1.60 m（v0.1.3）。旁边一块 720 × 760 dp 的 SpatialUI 控制窗（玩 / 怎么玩 / 怎么操作 / 设置）。系统负责摆放、拖动和缩放，不需要平面识别和锚点。锁默认绕竖直轴转 35°，六根都看得到。

### 截图

| | |
|---|---|
| ![顶住：卡着它的木条亮红光](docs/S05-zh.jpg) | ![抽出来了：木条顺着槽滑出](docs/S06-zh.jpg) |
| 顶住 — 说明框写出谁卡着它 | 抽出来了 — 木条顺着槽滑出锁外 |
| ![给个提示：钥匙那根先动](docs/M1-key-zh.jpg) | ![拆开了：六根木条摆在桌上](docs/S10-zh.jpg) |
| 给个提示 — 告诉你钥匙是哪根、为什么 | 拆开了 — 六根按编号摆在桌上，开始装 |
| ![装好了](docs/S13-zh.jpg) | ![English UI](docs/S03-en.jpg) |
| 装好了 — 拆 8 步、装 8 步 | 英文界面 |

截图来自 v0.1.1 的 QA 第 2 轮；v0.1.2 界面和玩法与 v0.1.1 相同。

### 验收状态

- 模拟器 QA：第 2 轮通过（只用 ①–⑥ 把手和方向键真实点按，拆 6 根、装 6 根到装好；中英 46 张状态截图；全程无崩溃 / ANR / 文字溢出）。
- 模拟器冒烟（v0.1.2）：中英冷启动无 ANR / 崩溃，退出后无残留进程。
- **真机已验证**：2026-11-12 在 PICO 头显上试玩通过。

### 已知问题

- **K1 控制窗按钮偏小**：①–⑥ 把手和方向键高 52 dp，窗口在约 1.34 m 处时约 1.7°、按钮间隙 8 dp。符合窗口控件 ≥ 48 dp 的规矩，但低于 3D 目标 2° 的标准；真机上若觉得难点，下一版加大到 60–64 dp。
- 默认视角看不到锁的顶面（不挡玩，需要时用「翻个面」）。
- 选中 6 s 不操作会自动取消选中。
- 木纹贴图在启动后约 6–7 s 才加载上，之前是浅色木块。
- 控制窗右沿贴着默认视野边缘。

---

## English

### What it is

A six-piece Luban lock (Kongming lock, six-piece burr, shown at 4x) floats over your desk. Pick a stick with the numbered ①–⑥ handles in the control window; the direction keys it can move along light up. Tap one and the stick slides along its slot and drops onto the desk. Take all six apart, then put them back one by one. Every step explains why this stick can move now, and when it is blocked, which stick is in the way. Three locks, easy to hard: Where's the key / Make room first / Hollow inside.

### How to play

- **Pick a stick**: the ①–⑥ handles in the control window. The selected stick glows warm with a gold outline.
- **Slide**: six direction keys (left / right / up / down / toward you / away); only the possible ones light up.
- **Blocked**: the stick in the way glows red and the caption names it.
- **Hint / Open up / Undo / Turn left / Turn right / Flip / Start over** are all in the control window.
- The whole lock is the only tap target in 3D: tap it to reopen the control window after closing it.

### Container

A Shared Space volume (`IN_VOLUME`) holds the lock and the row of sticks on the desk: 0.62 × 0.47 × 0.565 m while the lock stands assembled, so it nearly fills it, growing to 1.36 × 1.56 × 1.60 m while sticks can come out (v0.1.3). Next to it is a 720 × 760 dp SpatialUI control window (Play / How to play / Controls / Settings). The system places, moves and scales it; no plane detection or anchors needed. The lock is turned 35° about the vertical axis so all six sticks are visible.

### Status

- Emulator QA round 2 passed: full take-apart and reassembly with only the handles and direction keys, 46 state screenshots in Chinese and English, no crash / ANR / text overflow.
- v0.1.2 smoke-tested on the emulator (cold start in both languages, clean exit).
- **Verified on a real headset**: played on a PICO headset on 2026-11-12.

### Known issues

- **K1 small control-window buttons**: handles and direction keys are 52 dp, about 1.7° at roughly 1.34 m with 8 dp gaps. They meet the ≥ 48 dp window-control rule but not the 2° target for 3D objects; if they are hard to hit on a real headset, the next version goes to 60–64 dp.
- The default view does not show the top face of the lock (use Flip if needed).
- A selection is cleared after 6 s without input.
- The wood texture appears about 6–7 s after launch; before that the sticks are plain light wood.
- The control window's right edge touches the edge of the default field of view.

## Licences

Code: all rights reserved by the author. Fonts: Noto Sans (SIL OFL 1.1, `third_party/noto-sans`). Icons: Material Icons (Apache-2.0). Sounds: CC0 recordings (freesound cmorris035 #254343, Kenney impact-sounds / interface-sounds).
