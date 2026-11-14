package com.stardew.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * c202：居中确认框（不是底部抽屉）。
 *
 * ## 为什么不用 DetailDrawer
 * 底部抽屉占 78% 屏高，是「看一堆内容」的容器；而「确定删除吗」只有两行字加两个按钮，
 * 弹一个满屏抽屉**太重**（用户 2026-11-01 原话：「这个直接从底部弹出一个底部抽屉，
 * 我感觉太占用空间了」）。所以另起一个居中小对话框：屏幕上只占中间一小块。
 *
 * ## 为什么不做背景推后
 * 详情抽屉打开时背景会加深+模糊+缩小（c193 的推后感），那是「进入一层内容」的信号。
 * 确认框只是一次问答，背景推后反而更重 ⇒ **只压一层半透明遮罩**，背景保持清晰。
 *
 * ## 申报 / 渲染分离
 * 与 [DetailDrawer] 同一套范式：本组件只**申报**（写 [LocalConfirmHost]），
 * 渲染交给 MainActivity 顶层的 [ConfirmOverlayHost]。调用点不必关心层级与遮罩。
 */
data class ConfirmSpec(
    val visible: Boolean,
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * c202：确认框宿主状态（[PixelConfirmDialog] 申报、[ConfirmOverlayHost] 消费）。
 *
 * ⚠ 与 [LocalDrawerHost] 一样**不设 provider**：靠 staticCompositionLocalOf 的
 *   默认值工厂缓存出一个全局单例，任何层级的调用点与顶层 Host 都能看到同一份状态。
 */
val LocalConfirmHost = staticCompositionLocalOf { mutableStateOf<ConfirmSpec?>(null) }

/**
 * c202：居中确认框（声明侧）。
 *
 * @param message 说明文字，一两句即可（居中卡片不大，长文请改用底部抽屉）。
 */
@Composable
fun PixelConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    cancelText: String = "取消",
) {
    val host = LocalConfirmHost.current
    // 申报守卫（照 c197 DetailDrawer 的同一条纪律）：visible=false 的常驻调用点
    // 不许覆盖别人的活框——只有 host 空、或 host 里已经是自己时才写。
    SideEffect {
        val cur = host.value
        if (visible || cur == null || (cur.title == title && cur.onDismiss === onDismiss)) {
            host.value = ConfirmSpec(
                visible = visible,
                title = title,
                message = message,
                confirmText = confirmText,
                cancelText = cancelText,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }
    }
    DisposableEffect(visible) {
        onDispose {
            if (host.value?.title == title && host.value?.onDismiss === onDismiss) {
                host.value = null
            }
        }
    }
}

/**
 * c202：确认框覆盖层渲染宿主（MainActivity 顶层调用）。
 *
 * 层级：全屏遮罩 + **屏幕正中**的小卡片（宽度上限 320dp）。
 * 进入：淡入 + 由 0.92 放大到 1（约 180ms）；退出：淡出（约 140ms）。
 *
 * ⚠ 动画用 Animatable 手动驱动（c197 教训）：animateFloatAsState 首次组合时
 *   初值就等于 target ⇒ 动画根本不播（框会直接「出现」）。这里 Host 在 spec 出现
 *   后才挂载，所以初值必须从 0 起、由 LaunchedEffect 推到 1。
 * ⚠ everShown 的 remember 与 visible 记忆必须留在早退**之前**（c198 教训）：
 *   否则退出动画期间 visible=false 会重新走 never-shown 分支而闪退。
 */
@Composable
fun ConfirmOverlayHost() {
    val host = LocalConfirmHost.current
    val spec = host.value ?: return

    var everShown by remember { mutableStateOf(false) }
    if (spec.visible) everShown = true
    // 纯申报（visible=false 且从未显示过）→ 直接早退，不装任何副作用
    if (!everShown) return

    BackHandler(onBack = spec.onDismiss)

    val progress = remember { Animatable(0f) }
    LaunchedEffect(spec.visible) {
        val target = if (spec.visible) 1f else 0f
        val dur = if (spec.visible) 180 else 140
        progress.animateTo(target, tween(dur, easing = FastOutSlowInEasing))
        if (!spec.visible && progress.value == 0f) {
            everShown = false
            host.value = null
        }
    }

    val c = LocalPixel.current
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(60f)
            // 点遮罩 = 取消（不是「确定」）——误触遮罩不该造成不可撤销的操作
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = spec.onDismiss,
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * progress.value)),
        )
        // 卡片本体：屏幕正中，宽度上限 320dp（宽屏上不至于拉成一条横幅）
        Box(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 320.dp)
                .graphicsLayer {
                    val p = progress.value
                    alpha = p
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                }
                // 卡片不吃遮罩的点击（否则点卡片等于点遮罩 = 取消）
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .border(3.dp, c.woodDark)
                .background(c.wood)
                .padding(5.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, c.woodDark)
                    .background(c.parchment)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                PixelText(spec.title, color = c.ink)
                Spacer(Modifier.height(6.dp))
                PixelText(spec.message, color = c.inkDim, style = Px.caption)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelButton(spec.cancelText, onClick = spec.onDismiss)
                    Spacer(Modifier.width(8.dp))
                    PixelButton(spec.confirmText, onClick = {
                        spec.onDismiss()
                        spec.onConfirm()
                    })
                }
            }
        }
    }
}
