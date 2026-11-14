package com.stardew.assistant.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelText
import kotlinx.coroutines.launch

/**
 * 压暗层的浓度。**玻璃采到的内容要用同一个值压暗**（见 drawGlassSurface 的 dimAlpha）——
 * 两处必须同源，否则面板内外亮度对不上，整块面板像一扇亮窗。
 */
private const val SCRIM_ALPHA = 0.45f

/**
 * c228：跟手下滑时玻璃采样的**反向补偿增益**。
 *
 * 面板被拉下来时我们给它 1+3% 的微缩（graphicsLayer），玻璃里的背景必须反向
 * 放大补偿（同底栏按压的 sampleScale 机制），否则玻璃里映出的内容跟着面板一起缩、
 * 与屏幕上真实内容对不上 —— 官方教程警告的同款坑（见 GlassSurface.sampleScale）。
 * 增益取微缩峰值（0.03）附近：补偿不必精确等于缩放系数，人眼对 3% 的残余错位
 * 不敏感，取整好记。
 */
private const val PULL_SCALE_GAIN = 0.03f

/**
 * c228：贴边弹层的裁切形状 —— 上面两角 28dp 圆、下面两角直角。
 * 与 shader 的 dockFlip + cornerRadii(28,28,0,0) 是同一套几何（见 GlassSheet 里
 * LaunchedEffect 的同源注释）；不写死四角半径的通用形状是因为我们只有这一种
 * 不对称需求，一个专类比通用参数化更少出错。
 */
private val ShapeCornerDocked = RoundedCornerShape(
    topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp,
)

/**
 * v2.76.0：**玻璃弹层** —— 官方《Glass Bottom Sheet》教程在我们 App 里的落点。
 *
 * ## 为什么不用 Material3 的 ModalBottomSheet
 * ModalBottomSheet 自带 **Dialog 窗口**（独立 window）。独立窗口拿不到主窗口的
 * RenderNode 采样结果 —— 玻璃要「透出身后那一层」，跨窗口做不到。
 * 所以本组件是**主窗口内的一层覆盖层**：自己画 scrim、自己做进出场动画、
 * 自己处理返回键与下滑关闭。代价是丢掉 ModalBottomSheet 自带的那套行为，
 * 换来的是真的玻璃（能折射、能模糊、能跟主题走）。
 *
 * ## 为什么（c228 之前）是「悬浮卡片」而不是「贴边底弹层」
 * 旧 AGSL 只支持**四角统一半径**（SDF 是对称函数，四角必然同半径）。
 * 贴边底弹层要「上圆下直」，当时做不了，先落了悬浮卡片（左右 12dp、四角 28dp）。
 * c228 补上：shader 加 `docked` uniform + dockFlip（下半平面 y 镜像），
 * SDF 在镜像系里求值即可「上圆下直」，退化关系见 PixelGlass.kt 的注释。
 * 现在两种站姿都有：`docked = false` 悬浮卡片（旧观感逐像素保留）、
 * `docked = true` 贴边（跳板在用）。
 *
 * ## 参数口径（对齐官方《Glass Bottom Sheet》教程）
 *   官方：vibrancy() → blur(4dp) → lens(24dp, 48dp, depthEffect=true)，白纱 50%
 *   本项目：去掉 vibrancy（用户反馈「发绿」，见 PixelGlass.kt）；白纱改随主题走
 *           （GlassLadder.veilFor：白天白纱、夜间暗纱），玻璃面板本身是像素风内容，
 *           内容底色本来就是不透明的（parchment 行 / slot 输入框），可读性不受影响。
 *           模糊 4dp → 12dp、玻璃内容随 scrim 同步压暗 —— 见下面两处注释。
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    /** 标题栏右侧的自定义动作（与 DetailDrawer 同款语义） */
    right: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * c228：**贴边模式**。true = 面板贴住屏幕左右与底部（零侧边距），上面两角
     * 28dp 圆、下面两角直角（shader dockFlip 上圆下直）——原生 BottomSheet 的站姿。
     * false = 悬浮卡片（c227 及更早的观感：左右 12dp、四角统一 28dp、底部 12dp）。
     * 两种模式下玻璃采样、拖拽、scrim 的机制完全共用，只差外框几何。
     */
    docked: Boolean = false,
    /**
     * v2.98.0：内容区。参数是**面板拖拽接口** —— 调用方据此在「列表已到顶、
     * 手指还在往下拉」时把面板跟手拉下来（判断依据在调用方手里：它才知道
     * 列表滚到哪了）。传 null = 面板内容区不可拖（只有头部那一条窄条能下滑关闭）。
     */
    content: @Composable (SheetDrag?) -> Unit,
) {
    val c = LocalPixel.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val glassState = LocalGlassState.current

    // 进度：0 = 完全收起，1 = 完全展开
    val progress = remember { Animatable(0f) }
    // mounted 与 visible 分开：visible 变 false 后还要留着跑完退场动画
    var mounted by remember { mutableStateOf(false) }
    // 下滑手势的实时位移（px）
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // ── v2.98.0：面板跟手下滑（与内容列表协调）────────────────────────
    // 病根：以前「下滑关闭」只挂在**头部那一条窄条**上，内容区一按就只会滚列表，
    //   面板本身完全不动 —— 群友反馈「跳到页面滑不动、滑动不跟手」。
    //   而没有玻璃时走的 Material 抽屉（DetailDrawer）是能整体跟手的：
    //   同一个功能两种表现 = 「偶发」。
    // 修法：**列表能滚就列表先滚；列表到顶还继续往下拉，面板才跟手下滑**。
    //
    // ⚠⚠⚠ 修法换过两次才定位准（模拟器实测 + Compose 1.7.6 字节码对账）：
    //   ① `Modifier.nestedScroll` 吃「列表没吃掉的余量」——**在 Compose 1.7 走不通**：
    //      `Modifier.scrollable` 的拖拽走的是「子节点吃掉了多少就派发多少」的
    //      nestedScroll（`ScrollingLogic.performScroll` 把 `available` 设成
    //      **子节点将要消费的量**），**列表在顶时派发的就是 0** ⇒ 父节点永远拿不到
    //      「手指还在往下拉」这个信息。实测日志：列表在顶时 available 全是 0.0；
    //      列表有余量时 consumed 全被吃掉、available 仍是 0.0 ⇒ available 恒为 0。
    //   ② `Modifier.draggable`（Material3 抽屉同款的指针竞争）——**也不行**：
    //      指针事件的 Main pass 是**子节点先拿到**，列表的 `ScrollableNode` 会先
    //      吃掉 slop，父级的 draggable 直接被取消（实测：onDelta 一次都没触发）。
    //   ③ 现在改成：**内容区自己读指针的绝对坐标**（`change.position`，不受
    //      「位移已被消费」影响），自己判断「列表已到顶 + 手指还在往下拉」，
    //      满足就把面板跟手拉下来。**不参与手势竞争**，所以既不抢列表的滚动，
    //      又能在列表到顶时接管。
    //
    // ⚠ 用 rememberUpdatedState 兜住会被重组换掉的值（onDismiss / 阈值），
    //   免得回调对象里留着一份第一帧的旧引用。
    val onDismissNow = rememberUpdatedState(onDismiss)
    val dismissThreshold = rememberUpdatedState(with(density) { 96.dp.toPx() })

    // 结算：拖够了就关，没拖够弹回原位
    fun settleDrag() {
        val target = dragOffset
        if (target <= 0f) return
        if (target > dismissThreshold.value) {
            onDismissNow.value()
        } else {
            scope.launch {
                val back = Animatable(target)
                back.animateTo(0f, spring(0.9f, 320f, 0.01f)) { dragOffset = value }
                dragOffset = 0f
            }
        }
    }

    // 交给内容区的「面板拖拽」接口（内容区据此把面板跟手拉下来）
    val sheetDrag = remember {
        object : SheetDrag {
            override val offset: Float get() = dragOffset
            override fun onDelta(dy: Float) {
                dragOffset = (dragOffset + dy).coerceAtLeast(0f)
            }
            override fun onRelease() { settleDrag() }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            dragOffset = 0f
            progress.snapTo(0f)
            progress.animateTo(1f, spring(0.9f, 320f, 0.01f))
        } else if (mounted) {
            progress.animateTo(0f, tween(180))
            mounted = false
        }
    }

    if (!mounted) return

    // 系统返回手势优先收起弹层，而不是退 App
    BackHandler(onBack = onDismiss)

    // 面板在窗口里的位置（不含动画位移 —— 位移是 draw 期的变换，不改布局坐标）
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    // 玻璃表面：与底栏胶囊各一块，参数取官方玻璃弹层配方
    val surface = remember(glassState) {
        glassState?.surface(
            "sheet",
            // ⚠ 模糊半径比官方的 4dp 大：官方那套是压在**照片壁纸**上的，4dp 就够；
            //   我们压在**像素画页面**上 —— 卡片边框只有 2~3px 宽，4dp（≈9px）糊不化，
            //   透出来的鬼影是**有形状的**（一道斜边、一个大圆弧），看着像渲染坏了
            //   而不是磨砂玻璃。c186 由 12dp 提到 16dp（≈36px）：装机反馈弹层背后
            //   今日页内容仍透出来，12dp 对高频像素边框还不够糊成一片。
            blurDp = 16f,
            refractionHeightDp = 24f,
            refractionAmountDp = 48f,
            depthEffect = true,
        )
    }
    // 面板圆角 = 28dp，必须与 shader 的 cornerRadii 一致，否则玻璃边缘与裁切圆角对不上。
    // c228 贴边模式：shader 侧 dockFlip 上圆下直 + cornerRadii 下两角 0 —— 与
    // 下面 clip 的 ShapeCornerDocked 同一套几何（上 28 / 下 0），两处必须同源。
    LaunchedEffect(surface, docked) {
        surface?.radiusDp = 28f
        surface?.docked = docked
    }

    // 进出场位移：收起时整块往下挪 48dp
    val slideDistance = with(density) { 48.dp.toPx() }
    // 白纱比底栏胶囊浓一档：弹层是**大片内容面**，背后是整页像素画。
    // 装机实测基准浓度下背后卡片的文字会透过来和面板自己的字打架。
    // c186：只把**夜间暗纱**单独加浓（0.35×2.4=0.84）—— 透显主诉在夜间；
    //   白天白纱 0.42×1.7=0.714 保持不动，再提会被 coerceAtMost(1f) 封顶成死白，
    //   玻璃效果直接消失。判据复用 isLightSurface（与 veilFor 同一套单源）。
    val veil = if (GlassLadder.isLightSurface(c.screen)) GlassLadder.veilFor(c.screen, alphaScale = 1.7f)
               else GlassLadder.veilFor(c.screen, alphaScale = 2.4f)

    Box(modifier.fillMaxSize()) {
        // ── 压暗层：点它 = 关闭 ──
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA * progress.value))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // ── 玻璃面板 ──
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // c228 贴边模式：零侧边距、贴底（只留导航条高度）；悬浮模式保留
                //   12dp 边距的旧观感（c227 前逐像素一致）。
                .then(
                    if (docked) Modifier
                    else Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                )
                .navigationBarsPadding()
                .fillMaxHeight(0.86f)
                .graphicsLayer {
                    // 退场 + 下滑拖拽合并成一个位移
                    translationY = (1f - progress.value) * slideDistance + dragOffset
                    alpha = progress.value
                    // c228 动效：跟手下滑时面板微缩（最大 3%）+ 底部锚点——
                    //   「拉下来」的手感来自位移+缩放的组合，只位移像「平移贴图」，
                    //   加一点缩放才有「把一张玻璃片从屏幕上揭下来」的实体感。
                    //   幅度刻意小：>5% 会让人以为要点到别的东西；缩放中心在底部
                    //   （transformOrigin），面板顶边下移、底边贴地不动。
                    //   归一化用 dismissThreshold 的参考量程：96dp 拉满时缩到最小。
                    val pull = dragOffset / dismissThreshold.value
                    val s = 1f - 0.03f * pull.coerceIn(0f, 1f)
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                }
                .clip(if (docked) ShapeCornerDocked else RoundedCornerShape(28.dp))
                .trackPosition { panelOrigin = it }
                .drawBehind {
                    // 玻璃表面要跟着面板一起动：把动画位移补进采样坐标，
                    // 否则滑动过程中玻璃里透出的背景会「停在原地」。
                    // c228：跟手缩放也要反向补偿（同底栏按压的 sampleScale 机制，
                    //   否则玻璃里的背景跟着面板一起缩、与屏幕对不上）。
                    val shift = (1f - progress.value) * slideDistance + dragOffset
                    surface?.origin = panelOrigin + Offset(0f, shift)
                    val pull = if (slideDistance > 0f) dragOffset / slideDistance else 0f
                    surface?.sampleScale = 1f + PULL_SCALE_GAIN * pull.coerceIn(0f, 1f)
                    // 玻璃采到的内容要和**外面那层 scrim 同步压暗**（同一个 0.45）：
                    // 否则面板里透出的是没压暗的页面，比周围更亮 → 整块面板像一扇亮窗，
                    // 页面卡片的大边框还会以有形状的鬼影显出来（用户真机截图圈出来的）。
                    surface?.dimAlpha = SCRIM_ALPHA * progress.value
                    glassState?.drawGlassSurface(this, surface!!, veil)
                },
        ) {
            // ── 面板头部（标题 + 右侧动作 + ✕）──
            Row(
                Modifier
                    .fillMaxWidth()
                    // 头部自己也能拖（老路径，实测可用）。
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { settleDrag() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                            },
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⚠ 玻璃面板底色随主题走（白天白纱 / 夜间暗纱），所以标题字必须用
                //   **主题自适应**的 ink：白天 ink 是深色压白纱，夜间 ink 是浅色压暗纱。
                //   用 cream 会在白天「浅字压浅底」——与 v2.72.1 修过的底栏同一个病根。
                PixelText(title, color = c.ink)
                Spacer(Modifier.weight(1f))
                right()
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.slot)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { PixelText("✕", color = c.ink) }
            }

            // ── 内容区 ──
            // v2.98.0：把「面板拖拽」接口交给内容方，由它判断何时该接管。
            Box(Modifier.fillMaxWidth().weight(1f)) { content(sheetDrag) }
        }
    }
}

/**
 * v2.98.0：弹层内容的**面板拖拽**接口。
 *
 * ## 为什么要有这个接口
 * 面板要「跟手下滑」，得知道两件事：
 *   ① 手指此刻在哪、往哪走；② **内容里的列表是不是已经到顶了**。
 * 第 ② 件事只有内容方知道（列表状态在它手里），第 ① 件事在 Compose 里
 * 又有坑（见 GlassSheet 里那段注释：`nestedScroll` 拿不到余量、`draggable`
 * 抢不到 slop）⇒ 拆开：弹层管「面板挪多少」，内容方管「什么时候该挪」。
 *
 * ## 怎么用
 * 内容方在自己的滚动容器上读指针的**绝对坐标**（`change.position`，不受
 * 「位移已被消费」影响），自行判断「列表已到顶 && 手指还在往下拉」，
 * 满足就调 [onDelta]；手指抬起时调 [onRelease] 结算。
 */
interface SheetDrag {
    /** 面板当前被拉下多少像素（0 = 在原位） */
    val offset: Float

    /** 手指位移（正 = 往下）。面板只往下跟、不往上超原位。 */
    fun onDelta(dy: Float)

    /** 手指抬起：结算（拖够阈值就关闭面板，否则弹回原位）。 */
    fun onRelease()
}

/**
 * v2.76.0：当前窗口的玻璃状态。由 AppRoot provide；null = 没开玻璃（低版本 /
 * 用户关了开关）→ 弹层退化成普通覆盖层（仍然可用，只是不玻璃）。
 */
val LocalGlassState = androidx.compose.runtime.compositionLocalOf<GlassBackdropState?> { null }
