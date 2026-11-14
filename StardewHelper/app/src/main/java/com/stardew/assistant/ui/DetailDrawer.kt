package com.stardew.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * c193 背景推后：任何 DetailDrawer 打开时置 true。MainActivity 据此给主内容层做
 * 加深+模糊+轻微缩小。c196 起由 DrawerOverlayHost 置位/复位（host 挂载=有抽屉在显示）。
 */
val LocalDrawerOpen = staticCompositionLocalOf { mutableStateOf(false) }

/** c196 抽屉申报五元组（DetailDrawer 调用点内容原样搬运，渲染交给顶层 Host）。 */
data class DrawerSpec(
    val visible: Boolean,
    val title: String,
    val onDismiss: () -> Unit,
    val right: @Composable () -> Unit,
    val content: @Composable () -> Unit,
    // c221：稳定身份。c219 让图鉴抽屉的 title 在抽屉开着时**中途会变**（进包内模式），
    //   而 title 曾被当「这条 spec 是不是我的」的身份判据 —— DisposableEffect(visible)
    //   只在 visible 变化时重建闭包，捕获的是首次组合的旧 title；销毁时旧≠新 ⇒ host
    //   永不清空 ⇒ 僵尸抽屉关不掉（两轮审查独立确认的阻断）。传了 id 的调用点改用
    //   id 核对；历史调用点不传（null），保持 title+onDismiss 核对，行为一字不变。
    val id: Any? = null,
)

/**
 * c196 抽屉宿主状态：DetailDrawer 调用点申报 DrawerSpec，MainActivity 顶层的
 * DrawerOverlayHost 消费并渲染。渲染层在背景推后层与 dim 罩**之后**（z 序最高）——
 * 修 c195「抽屉也被 blur/scale/dim 打中」「空白判定线与视觉错位」两个问题。
 */
val LocalDrawerHost = staticCompositionLocalOf { mutableStateOf<DrawerSpec?>(null) }

/**
 * 通用详情底部抽屉：木色外壳 + 自绘「标题 / ✕」头部，与 WheelDrawer 视觉一致。
 * 内容区固定占抽屉剩余高度（weight），由调用方的 body 自带滚动（LazyColumn / verticalScroll）。
 *
 * c196：本函数只做**申报**（把五元组写进 LocalDrawerHost），渲染交给
 * MainActivity 顶层的 DrawerOverlayHost——22 个调用点签名与行为完全不变。
 * identity 防串扰：组合销毁时只有 host 里还是自己的 spec 才清（后申报者覆盖先申报者）。
 */
@Composable
fun DetailDrawer(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    // v2.26：可选的标题栏右侧动作（分享按钮挂在 ✕ 左边）。默认空 = 行为与以前一致。
    right: @Composable () -> Unit = {},
    // c221：稳定身份（见 DrawerSpec.id）。不传 = 保持 title+onDismiss 核对，历史调用点行为不变。
    id: Any? = null,
    content: @Composable () -> Unit,
) {
    val host = LocalDrawerHost.current
    // c221：身份核对单源。传 id 的调用点只看 id（title 可以中途变）；不传的
    //   保持 title+onDismiss 双核对 —— 与 c196/c197 的既有行为完全一致。
    fun mine(spec: DrawerSpec?): Boolean =
        spec != null &&
            if (id != null) spec.id == id
            else (spec.title == title && spec.onDismiss === onDismiss)
    // c197 申报守卫：visible=true 必写；visible=false 只在「host 空或 host 里是自己的
    // spec」时写——修图鉴页偶发不弹出：同页两个 DetailDrawer（条目详情 + 常驻的全部分类
    // 面板 visible=false），组合序里面板后申报把详情刚写入的 spec 覆盖成 visible=false，
    // Host 直接不渲染。守卫后活抽屉不被任何其他常驻调用点踩掉。
    SideEffect {
        val cur = host.value
        if (visible || cur == null || mine(cur)) {
            host.value = DrawerSpec(visible, title, onDismiss, right, content, id)
        }
    }
    // 组合销毁（页面离开/抽屉状态删除）时清场——只有 host 里还是自己的才清
    DisposableEffect(visible) {
        onDispose {
            if (mine(host.value)) {
                host.value = null
            }
        }
    }
}

/**
 * c196 抽屉覆盖层渲染宿主：MainActivity 顶层调用（dim 罩之后），消费 LocalDrawerHost
 * 渲染 scrim + 抽屉本体。挂载即置 LocalDrawerOpen=true（c193 背景推后），卸载复位。
 * 进入/退出动画自控：550ms 进 / 260ms 出；退出动画跑完才清 spec（不闪跳）。
 */
@Composable
fun DrawerOverlayHost() {
    val host = LocalDrawerHost.current
    val spec = host.value ?: return

    // 退出动画期间保留渲染（visible=false 仍显示跑完 260ms 收场）
    var everShown by remember { mutableStateOf(false) }
    if (spec.visible) everShown = true

    // c198：纯申报（visible=false 且从未显示过）**在任何副作用之前**直接早退——
    // 不置 LocalDrawerOpen、不装 BackHandler、不建动画。否则冷启动时各页常驻的
    // DetailDrawer(visible=false) 申报也会让本 Host 挂载：drawerOpen 冲成 true，
    // MainActivity 的背景推后（scale+blur+dim）跑起一点、再被清场复位 ⇒ 用户看到
    // 切页时整屏闪一次模糊（只在冷启动稳定复现，因为 host 是空的，c197 守卫放行首个申报）。
    // everShown 的 remember 必须留在早退之前：退出动画期间 spec.visible=false，
    // 若那时才声明就会重新走 never-shown 分支而闪退。
    if (!everShown) return

    // LocalDrawerOpen：host 有活抽屉（含退出动画期间）= 背景 一直推后
    val drawerOpen = LocalDrawerOpen.current
    DisposableEffect(Unit) {
        drawerOpen.value = true
        onDispose { drawerOpen.value = false }
    }

    BackHandler(onBack = spec.onDismiss)

    // c197：改 Animatable 手动驱动。animateFloatAsState 首次组合时初值=target——
    // Host 在 spec 出现后才挂载，target 已是 1f，progress 出生即 1 → 弹片直接出现
    // 没有滑入动画（退出方向 1→0 有动画，与真机观察吻合）。Animatable(0f) 首帧
    // 进入从 0 滑到 1，真正从屏幕底部弹出；LocalDrawerOpen 在同一挂载帧置位，
    // 背景模糊与弹窗同帧起跑（修「弹片先出、背景后糊」）。
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(spec.visible) {
        val target = if (spec.visible) 1f else 0f
        val dur = if (spec.visible) 550 else 260
        progress.animateTo(target, tween(dur, easing = FastOutSlowInEasing))
        // 退出动画跑完才清 spec（不闪跳）；进入完成不动 host
        if (!spec.visible && progress.value == 0f) {
            everShown = false
            host.value = null
        }
    }

    val c = LocalPixel.current
    var overlayH by remember { mutableStateOf(0) }
    var drawerH by remember { mutableStateOf(0) }

    // ── 全屏覆盖层：scrim + 底部抽屉，同帧绘制（无 window 切换 = 无纯色闪底）──
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(50f)
            .onSizeChanged { overlayH = it.height }
            // 点空白关闭：落点在抽屉顶边之上（真正的空白区）抬手才关；
            // 抽屉本体（含木色边框空隙）内部点击不进这个判定，绝不误关。
            // c197：判定线在点击瞬间现算（覆盖层高减抽屉高）——onSizeChanged 先量子级
            // 后量父级，抽屉测量时 overlayH 还是 0，提前算会把 sheetTop 写成负数，
            // inBlank 恒 false → 点空白关不掉（c196 真机实测）。点击时两值均已就位。
            .pointerInput(spec.onDismiss) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val sheetTop = overlayH - drawerH
                    val inBlank = down.position.y < sheetTop
                    val up = waitForUpOrCancellation()
                    if (inBlank && up != null) spec.onDismiss()
                }
            },
    ) {
        // scrim：随进度淡入淡出（黑 45% 满进度）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * progress.value)),
        )
        // 抽屉本体：从屏幕底部滑入/滑出（translationY 跟进度），高 78%
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .graphicsLayer { translationY = (1f - progress.value) * size.height }
                .onSizeChanged { sz -> drawerH = sz.height }
                .background(c.wood)
                .woodGrain(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                // ── 抽屉头部 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelText(spec.title, color = c.cream, shadow = true, maxLines = 1, style = Px.title)
                    Spacer(Modifier.weight(1f))
                    // 调用方自带尾部间距，这里不额外插 spacer——避免影响所有既有抽屉的 ✕ 贴右边缘
                    spec.right()
                    Box(
                        Modifier
                            .background(c.slot)
                            .slotBevel(inset = 2.dp, width = 1.dp)
                            .border(2.dp, c.slotEdge)
                            .clickable(onClick = spec.onDismiss)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { PixelText("✕", color = c.ink) }
                }
                Spacer(Modifier.height(12.dp))

                // ── 内容区（有界高度，body 自行滚动） ──
                Box(Modifier.fillMaxWidth().weight(1f)) { spec.content() }
            }
        }
    }
}
