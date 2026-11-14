package com.stardew.assistant.ui.glass

import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ↓↓↓ 本文件的玻璃算法整体移植自 Kyant0/AndroidLiquidGlass
 *   （https://github.com/Kyant0/AndroidLiquidGlass，Apache-2.0），
 *   按其官方文档《Glass Bottom Bar》教程的参数与效果链逐项对齐：
 *     effects = { vibrancy(); blur(4.dp); lens(16.dp, 32.dp) }
 *     surface = 白色 50% 半透明；形状 = 胶囊（CircleShape）；底栏悬浮于内容之上。
 *   AGSL shader（internal/Shaders.kt 的 RoundedRectRefractionShaderString）逐字保留，
 *   含其 offset 语义与**负值** refractionAmount（边缘向内采样）。
 *   与库的差异只在宿主层：库用 Compose 1.8 的 GraphicsLayer，本项目停在 1.7.6，
 *   改用 android.graphics.RenderNode 托管（同为「录内容 + 挂 RenderEffect」，API 29+）。
 *   完整 Apache-2.0 许可证副本见 assets/licenses/Apache-2.0-AndroidLiquidGlass.txt。
 *
 * ⚠ v2.76.0 修正一条**过时且错误的注释**（原注释写「rememberGraphicsLayer() 要 Compose 1.8+，
 *   本项目 1.7.6 里不存在（javap 核过字节）」）——**那是错的**。用 javap 复核本机 Gradle
 *   缓存里的 androidx.compose.ui:ui:1.7.6 与 ui-graphics-android:1.7.6 实测：
 *     androidx.compose.ui.graphics.GraphicsLayerScopeKt.rememberGraphicsLayer(Composer, int) 存在
 *     androidx.compose.ui.graphics.layer.GraphicsLayer 存在（含 record / setRenderEffect）
 *     androidx.compose.ui.graphics.GraphicsLayerScope.renderEffect 存在
 *   仍然**不切换**宿主层，理由是本文件这套 RenderNode 实现已经装机验证过，
 *   而上游那套 drawBackdrop 门面要改 4 处 record 重载 + 1 处 Kotlin 2.2 上下文参数
 *   + 多引一个 com.kyant0:shapes 依赖 —— 收益不抵改写成本。
 *   但**别再拿「1.7.6 没有」当理由**，那是错的。
 */

// ── 教程参数（单位 dp；px 换算在绘制时按 density 做）────────────────────
// ⚠ 门槛是 33 而不是 31：RenderNode.setRenderEffect 是 **API 33** 才有的方法
//   （31/32 调用直接 NoSuchMethodError 崩），而完整效果本来就要 33 的 AGSL。
//   31–32 设备 GlassLadder.enabled = false → 走原木纹底栏。
private const val GLASS_HEIGHT_DP = 64f   // 胶囊高度（教程 64dp，胶囊半径 = 高/2）
// 教程 demo 是 4dp；用户参考图（悬浮胶囊 App）是强磨砂「透过白纱的文字应该是模糊的」。
// 12dp 实测"不够透明"（用户反馈）→ 8dp：文字仍糊，但背后内容轮廓透得出来。
private const val BLUR_RADIUS_DP = 8f     // 模糊半径
private const val REFRACTION_HEIGHT_DP = 16f // 折射带高度（教程 lens 第一参）
private const val REFRACTION_AMOUNT_DP = 32f // 折射量（教程 lens 第二参；shader 里取负 = 向内采样）
// 白纱浓度调参史：0.5 教程值压不住木纹底色（发绿）→ 0.62 中性但"不够透明"
// （用户反馈）→ 0.42 靠去 vibrancy 后的中性底色维持不绿，同时透出背景。
private const val SURFACE_ALPHA = 0.42f   // 表面白纱（白天）
// v2.76.0：夜间改用**暗纱**（黑 35%）—— 原来日夜共用一张白纱，夜里玻璃发白，
// 弹层标题「浅字压浅底」看不见。白天/夜间两档都 ≥ 0.3（低于 0.3 玻璃约等于隐形）。
private const val SURFACE_ALPHA_DARK = 0.35f

object GlassLadder {
    /**
     * v2.70.0 玻璃能力分级：**33+ 才有玻璃**（RenderNode.setRenderEffect 与 AGSL 同门槛）。
     * 低于 33 的设备 GlassLadder.enabled = false，走原木纹底栏。
     */
    val enabled: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** 胶囊高度（dp→px 由调用方换算） */
    val heightDp: Float get() = GLASS_HEIGHT_DP

    /** c228：底栏基准模糊半径（dp）——档位倍率与护栏减半在调用方乘。 */
    val defaultBlurDp: Float get() = BLUR_RADIUS_DP

    /** 药丸高（dp）= 胶囊高 − 2×内缩。 */
    val itemHeightDp: Float get() = heightDp - 2f * BAR_ITEM_INSET_DP

    /** 药丸圆角（dp）= 药丸高/2 —— 正胶囊，与外层同一套圆角语言。 */
    val itemRadiusDp: Float get() = itemHeightDp / 2f

    /**
     * 胶囊列的上下留白（dp）。**列 padding 与 LocalGlassInset 共用这一个数**——
     * 两处各写一份必然漂移（v2.27 老坑同族），改留白只许改这里。
     */
    const val BAR_V_PAD_DP = 10f

    /**
     * v2.70.2：选中药丸相对胶囊的**内缩**（dp）——水平与垂直用同一个数。
     *
     * ## 为什么是「同心嵌套」而不是随手给个圆角
     * v2.70.1 的药丸是 52dp 高、圆角 20dp（不是正胶囊），行内只留 4dp 边距，
     * 于是药丸右缘**顶到胶囊的大圆弧上**：外层 32dp 圆弧在药丸顶/底那一行
     * 只退到 ~28dp 处，药丸自己的 20dp 右圆角被整段裁掉（用户截图实测：
     * 药丸右缘 1034px，胶囊右弧在药丸顶行只到 1007px）。
     * 结果是外层一个大圆弧、内层一个被切平的小圆角，两套圆角挨在一起互不对齐。
     *
     * ## 本次的口径
     * 药丸高度 = 胶囊高 − 2×内缩；药丸半径 = 药丸高/2（**正胶囊**，与外层同族）。
     * 水平内缩 = 垂直内缩 = [BAR_ITEM_INSET_DP] 时，两个圆弧的**圆心重合**：
     *   外层右端圆心 = (胶囊右 − R, 胶囊顶 + R)，R = 胶囊高/2
     *   药丸右端圆心 = (胶囊右 − 内缩 − r, 胶囊顶 + 内缩 + r)，r = 药丸高/2 = R − 内缩
     *   → 两者同为 (胶囊右 − R, 胶囊顶 + R) ✅
     * 于是药丸四周留白恒等于内缩，圆角看上去是「同心的两层」，而不是两个圆硬碰。
     *
     * 内缩取 6dp 的取舍：药丸高 52dp（与 v2.70.1 一致，图标与文字**位置不动**），
     * 文字档位放到「特大」（1.3×）时内容约 47dp，仍在 52dp 内装得下、不会被裁。
     */
    const val BAR_ITEM_INSET_DP = 6f

    /**
     * v2.76.0：玻璃**表面**的白纱颜色改成**随主题走**。
     *
     * ## 为什么必须改
     * 原来白纱是写死的 `Color.White.copy(alpha = SURFACE_ALPHA)`，日夜两套主题**同一张脸**：
     * 夜间模式里白纱压在深色背景上 = 一块偏亮的灰玻璃，而压在浅色背景上 = 一块亮玻璃。
     * 玻璃弹层（v2.76.0 新增）的标题/关闭按钮直接坐在白纱上，夜间就会「浅字压浅底」
     * —— 与 v2.72.1 修过的底栏同一个病根（`NightColors.ink` 与 `NightColors.cream`
     * 同为 #F0E2C0，浅色底 + 浅色字 = 看不见）。
     *
     * ## 口径
     * 白纱还是白纱，但**夜间换成暗纱**（黑 SURFACE_ALPHA_DARK），让玻璃在夜里是
     * 「暗玻璃」而不是「发白的玻璃」；亮/暗由当前主题的屏幕底色亮度判定（我们本来
     * 就知道自己的主题，不必像上游 AdaptiveLuminance 那样去读背景像素 ——
     * 那是给随机壁纸用的办法）。
     *
     * ## alphaScale：底栏胶囊与弹层面板不该用同一档浓度
     * 底栏胶囊是**窄条**，用户主要看图标，玻璃要「透」才有意思 → 用基准浓度。
     * 弹层面板是**大片内容面**，背后是整页像素画 —— 实测（装机截图）基准浓度下
     * 背后卡片的文字直接透过来和面板自己的字打架（「融化、重型熔炉」那行），
     * 所以弹层用更高的浓度（GlassSheet 里传 alphaScale）压住背景字。
     *
     * ## ⚠ 判「白天/夜间」必须用 sRGB 感知亮度，**不能**用 Compose 的 Color.luminance()
     * `Color.luminance()` 是**线性**亮度（sRGB → linear 之后才加权），而春主题的
     * 橄榄绿底 (#A9C58F 附近) 算出来是 **0.497 —— 刚好卡在 0.5 下面** → 白天被
     * 误判成夜间，弹层套了**暗纱**，整个面板发灰发暗（装机截图才发现）。
     * 这里的 sRGB 感知亮度（0.299/0.587/0.114）算出来是 0.71 → 正确判为白天。
     */
    fun veilFor(screenColor: Color, alphaScale: Float = 1f): Color =
        if (isLightSurface(screenColor)) {
            Color.White.copy(alpha = (SURFACE_ALPHA * alphaScale).coerceAtMost(1f))
        } else {
            Color.Black.copy(alpha = (SURFACE_ALPHA_DARK * alphaScale).coerceAtMost(1f))
        }

    /**
     * 当前主题的玻璃面是**浅色**还是**深色**。
     *
     * 判据与 [veilFor] 共用同一套 sRGB 感知亮度（见那里的说明：不能用
     * Compose 的 `Color.luminance()`，它是线性亮度，会把春主题橄榄绿误判成夜间）。
     *
     * 用途：按压反馈的形态 —— **浅色面不能用白色柔光**（会把像素图标洗白，
     * 用户真机录屏实测过），浅色面改用深色压暗。
     */
    fun isLightSurface(screenColor: Color): Boolean {
        val lum = screenColor.red * 0.299f + screenColor.green * 0.587f + screenColor.blue * 0.114f
        return lum >= 0.5f
    }

    // ── c228 玻璃浓度档（0=轻 / 1=标准 / 2=浓）──────────────────────────
    // 单源：档位下标 → 各处系数。设置页存的是档位下标（UI 概念），
    // 这里是唯一把「档位」翻译成「参数」的地方 —— 别处不许再写一份映射。
    //
    // 调参口径（基准 = c227 装机验收过的观感，档位 1 全部 = 1f）：
    //   veilScale   —— 白纱/暗纱浓度倍率。轻 = 玻璃更透（底栏透出更多内容，
    //                  弹层背后字会隐约可见，属「轻」档的语义）；浓 = 更实的磨砂。
    //   blurScale   —— 模糊半径倍率。轻档不降模糊只降浓度：模糊太低鬼影回潮
    //                  （c186 教训：弹层 blur 12dp 时像素边框仍有形状）；浓档略升。
    //   refractScale —— 折射量倍率。跟浓度走同方向，边缘的透镜感随档位增强。
    //
    // ⚠ 浓度上限约束：弹层白纱 = 0.42 × alphaScale(1.7/2.4) × veilScale，
    //   「浓」档弹层白天 = 0.42×1.7×1.25 ≈ 0.89，仍在 coerceAtMost(1f) 之内，
    //   玻璃不会封顶成死白（c186 的护栏留了余量，验过）。
    private val LEVEL_VEIL_SCALE = floatArrayOf(0.72f, 1f, 1.25f)
    private val LEVEL_BLUR_SCALE = floatArrayOf(1f, 1f, 1.15f)
    private val LEVEL_REFRACT_SCALE = floatArrayOf(0.9f, 1f, 1.1f)

    /** 档位下标钳到 [0,2]（脏值兜底与 PrefsRepo.setGlassLevel 同一把尺）。 */
    fun clampLevel(level: Int): Int = level.coerceIn(0, 2)

    /** 白纱/暗纱浓度倍率（档位单源）。 */
    fun veilScale(level: Int): Float = LEVEL_VEIL_SCALE[clampLevel(level)]

    /** 模糊半径倍率（档位单源）。 */
    fun blurScale(level: Int): Float = LEVEL_BLUR_SCALE[clampLevel(level)]

    /** 折射量倍率（档位单源）。 */
    fun refractScale(level: Int): Float = LEVEL_REFRACT_SCALE[clampLevel(level)]
}

/**
 * 一块**独立**的玻璃表面。
 *
 * 底栏胶囊、弹层面板各持有一块：每块有自己的 RenderNode（效果链按尺寸编译，
 * 绝不能共用 —— 一个节点的 RenderEffect 只对应一组尺寸）与自己的窗口坐标
 * （采样偏移 = 表面位置 − 内容区位置）。
 */
class GlassSurface internal constructor(
    /** 表面标识（只用于 RenderNode 命名与调试） */
    val key: String,
    /** 模糊半径（dp） */
    val blurDp: Float = BLUR_RADIUS_DP,
    /** 折射带高度（dp，教程 lens 第一参） */
    val refractionHeightDp: Float = REFRACTION_HEIGHT_DP,
    /** 折射量（dp，教程 lens 第二参；shader 里取负 = 向内采样） */
    val refractionAmountDp: Float = REFRACTION_AMOUNT_DP,
    /**
     * 球面鼓包（教程 lens 第三参）。官方玻璃底弹层用的是 `true`，
     * 底栏胶囊用的是 `false`（教程 demo 默认）。
     */
    val depthEffect: Boolean = false,
) {
    internal val node = RenderNode("glass-surface-$key")
    private var effectKey = QuadKey(-1, -1, -1f, false) // (w, h, density, docked) —— 全变才重建

    /** 表面在窗口里的位置（positionInRoot）。 */
    var origin: Offset by mutableStateOf(Offset.Zero)

    /**
     * 圆角半径（dp）。**负值 = 胶囊**（半径 = 高/2，与教程 CircleShape 同口径）。
     * 圆角矩形给正数即可（四角统一，我们的 shader 只支持统一半径）。
     */
    var radiusDp: Float = -1f

    /**
     * c228：**贴边弹层**模式（docked）。true = 上面两角圆、下面两角直。
     *
     * ## 为什么 shader 能做这个了（c228 之前做不了）
     * 旧 shader 的 SDF 圆角半径由 `radiusAt(coord, radii)` 按坐标选**一个**角半径，
     * 但 `sdRoundedRect` 是**对称函数**（abs(coord)），四角必然同半径 —— SDF
     * 本身不知道「上圆下直」。c228 给 SDF 加了**不对称圆角**变体：coord 不再取
     * abs，而是**按 docked 标志翻转**：贴边模式下下边两个角把 coord.y 取负
     * （y 轴镜像 = 下面两角借用上面两角的半径），下两角的半径于是恒为 0。
     * 退化关系：`docked = false` 时不翻转，与旧 SDF **逐字一致**（verify 钉）。
     */
    var docked: Boolean = false

    /**
     * 采样补偿系数：玻璃表面**被缩放**时（>1），背景要反向缩放，否则玻璃里映出的
     * 背景会跟着表面一起变形、与屏幕对不上。
     *
     * ## 这是官方《Interactive Glass Bottom Bar》教程特意警告的那个坑
     * 教程原文：把缩放从 `graphicsLayer` 挪到 `layerBlock` 里 —— 因为
     * `graphicsLayer` 会把**整个**底栏连同玻璃一起缩放，玻璃里透出的背景也跟着缩，
     * 于是「按下时背景在玻璃里滑动」，看着像玻璃在滑动而不是被按下去。
     * 上游的实现是 `internal/InverseLayerScope.kt`：它读 layerBlock 里的
     * scaleX/scaleY/rotationZ，对 DrawTransform 施加**逆矩阵**（以左上角为原点）。
     *
     * ## 我们怎么做的（宿主层不同，结论相同）
     * 上游用 Compose GraphicsLayer + 逆矩阵；我们用 android.graphics.RenderNode，
     * 于是改成在**录制采样窗口时**直接把背景画大 1/s 倍 —— 外层再乘回 s，净效果 = 1，
     * 背景于是「钉」在屏幕上不动。
     *
     * ## 公式（中心轴缩放，s = 1 时退化成无补偿）
     *   采样点 node-local p 满足：p = t + k·q（q = 背景局部坐标）
     *   要让背景屏幕坐标恒等于 contentOrigin + q，解出：
     *     k = 1/s
     *     t = pad + c − (off + c)/s        （c = 表面中心，off = 表面位置 − 内容区位置）
     *   s = 1 时 t = pad − off ✓ 与改动前逐字一致。
     */
    var sampleScale: Float = 1f

    /**
     * v2.76.0：**玻璃采样到的内容要跟遮罩同步压暗**。
     *
     * ## 为什么
     * 弹层在玻璃**外面**铺了一层 scrim（压暗 45%），但玻璃是从背景录制里采样的，
     * 而那份录制是**压暗之前**的页面 → 面板里透出的内容比它周围（已压暗）更亮，
     * 整块面板像一扇「亮窗」，页面卡片的大边框还会以**有形状的鬼影**显出来
     * （用户真机截图圈出来的就是这个）。
     *
     * ## 修法
     * 让玻璃自己把采到的内容压暗同样的量（scrim 的 alpha），面板内外亮度口径一致，
     * 鬼影就退成均匀的柔光。
     */
    var dimAlpha: Float = 0f

    /**
     * 构造效果链并挂到玻璃节点上：blur → lens（lens 最外层，与教程顺序一致）。
     *
     * 参数全部换算自 dp（density 取绘制时的真值）。只在 (w, h, density, docked)
     * 变化时重建——createRuntimeShaderEffect 每次都要编译 AGSL，绝不能逐帧做。
     */
    internal fun ensureEffect(scope: DrawScope, w: Int, h: Int) {
        if (!GlassLadder.enabled) return
        if (w <= 0 || h <= 0) return
        val key = QuadKey(w, h, scope.density, docked)
        if (key == effectKey) return
        effectKey = key
        val blurPx = blurDp * scope.density
        // 玻璃表面四周的缓冲带：模糊要能采到栏外一圈真内容，否则边缘 Clamp 成硬边。
        // 教程里 lens 负偏移向内采样、不消耗外圈，所以这里只需盖住模糊半径。
        val pad = kotlin.math.ceil(blurPx.toDouble()).toInt() + 2
        // 胶囊：半径 = 高/2（等价教程的 CircleShape）；圆角矩形：固定 dp。
        val radius = if (radiusDp < 0f) h / 2f else radiusDp * scope.density
        // ⚠ 教程效果链是 vibrancy() → blur() → lens()；这里**刻意去掉 vibrancy**。
        //   库教程原话：vibrancy 是「enhances the saturation, giving more visual impact」
        //   （饱和度 ×1.5）——它会把我们木纹+黄卡的底色放大，用户反馈"发绿"的主因。
        //   用户参考图是中性磨砂，视觉目标与教程 demo 相反，这一环按用户目标舍掉。
        //   （将来想要回来：把 VIBRANCY_FLOATS 接回 createColorFilterEffect 链首即可。）
        val blurFx = runCatching {
            android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        }.getOrNull()
        // 任何异常逐级退回：lens 挂不上就用 blur，再不行就无效果。
        var effect: android.graphics.RenderEffect? = blurFx
        // 门槛已保证 33+，lens 必做
        effect = runCatching {
            val shader = RuntimeShader(AGSL_REFRACTION)
            shader.setFloatUniform("size", w.toFloat(), h.toFloat())
            // 库的语义：offset 加到采样坐标上；(-pad, -pad) 使 SDF 盒原点落在
            // node 坐标的 (pad, pad) —— 即缓冲带内缘，栏本体的左上角。
            shader.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
            // cornerRadii 四个分量 = 左上/右上/右下/左下（radiusAt 的选角语义）。
            // c228 贴边弹层：下面两角（右下 z、左下 w）给 0 = 直角，配合 shader 里的
            //   dockFlip（下半平面镜像）实现「上圆下直」。
            if (docked) {
                shader.setFloatUniform("cornerRadii", radius, radius, 0f, 0f)
            } else {
                shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            }
            shader.setFloatUniform("refractionHeight", refractionHeightDp * scope.density)
            // ⚠ 负值 = 边缘向内采样（库源码就是这么设的：-refractionAmount）。
            //   我第一版写成正值往外采，边缘采到栏外的空白 —— 玻璃看起来跟没有一样。
            shader.setFloatUniform("refractionAmount", -refractionAmountDp * scope.density)
            shader.setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
            // c228：贴边弹层的「上圆下直」。floatUniform 没有布尔，用 0/1。
            shader.setFloatUniform("docked", if (docked) 1f else 0f)
            val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            if (effect != null) {
                android.graphics.RenderEffect.createChainEffect(lens, effect)
            } else {
                lens
            }
        }.getOrNull() ?: effect
        node.setRenderEffect(effect)
    }
}

/**
 * 背景层节点：内容区把每一帧画进它，各玻璃表面从它采样脚下那块。
 *
 * [backdrop] = 门槛 ∧ 用户开关（设置里的「底栏样式」）。关掉时返回 null，
 * recordBackdrop 直通、不创建节点——**关开关后零录制开销**，不是"录了不用"。
 *
 * ⚠ 用 android.graphics.RenderNode 而不是 Compose 的 GraphicsLayer：
 *   本项目 Compose 1.7.6 里虽然**确实有** rememberGraphicsLayer()（v2.76.0 已用
 *   javap 复核，原注释说它不存在是错的），但上游那套 drawBackdrop 门面还要改
 *   4 处 record 重载 + Kotlin 2.2 上下文参数 + 多引一个 shapes 依赖；
 *   而本文件这套 RenderNode 实现已经装机验证过 —— 维持现状，别为了「对齐上游」
 *   去换宿主层。
 */
@Composable
fun rememberGlassBackdrop(enabled: Boolean = GlassLadder.enabled): RenderNode? {
    if (!enabled) return null
    return remember { RenderNode("stardew-glass-backdrop") }
}

/**
 * c228：玻璃**性能护栏** —— 低端 33+ 设备扛不住「全内容逐帧录制 + 逐表面采样」
 * 时的自动降级（两档：减模糊 → 本会话停用），不让玻璃拖垮整机流畅度。
 *
 * ## 观测方式
 * Choreographer 帧回调：两帧间隔超过 1.5× 预期帧时长（60Hz ≈ 16.7ms 判 25ms，
 * 90Hz 设备按真实 refreshRate 算）记一次「掉帧」。**只在玻璃开着时观测**
 * （guard.active = glassOn），静止不动的界面理论上零回调，不该被记账。
 *
 * ## 降级状态机（每会话最多两步，可逆）
 *   0 正常 → 1 减模糊（blur 减半，RecordingCanvas 的录制本身不省，省的是
 *     各表面 RenderEffect 的 GPU 模糊采样——blur 是大头）→ 2 停用（本次
 *     进程内回到木纹，App 重启恢复；不断电重置是故意的：温度降下来用户
 *     重启就能拿回玻璃，而不是永久丢失）。
 * 判定窗口：滚动 10 秒内掉帧率 > 40%（10 帧里 4 帧掉）才升档 —— 偶发掉帧
 * （首帧编译 shader、冷启动 IO）不触发；同档 5 秒冷却防抖。
 *
 * ## 为什么放在玻璃层而不是全局 FPS 监控
 * 全局监控会把「页面自身卡」记到玻璃头上，误伤好设备。玻璃层观测只统计
 * 「玻璃在画」时段的掉帧，归因干净。
 */
object GlassPerfGuard {

    /** 降级档：0 正常 / 1 减模糊 / 2 本会话停用。 */
    var level: Int by mutableStateOf(0)
        private set

    /** 观测开关（glassOn 时置 true）。false = 完全不挂帧回调，零开销。 */
    @Volatile var active: Boolean = false
        set(value) {
            field = value
            if (value) start() else stop()
        }

    /** 触发停用后给 UI 一条提示文案的钩子（AppRoot 读它弹一次提示）。 */
    var degradedToOff: Boolean by mutableStateOf(false)
        private set

    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var lastFrameNanos = 0L
    private var frameBudgetMs = 25.0   // 1.5 × 16.67（60Hz 兜底；start() 按真实刷新率改）
    private var windowStartMs = 0L
    private var droppedFrames = 0
    private var totalFrames = 0
    private var lastEscalateMs = 0L

    private fun start() {
        if (choreographer != null) return
        runCatching {
            val c = Choreographer.getInstance()
            // ⚠ Choreographer.display 是 Hidden API（@UnsupportedAppUsage）：灰名单上
            //   能跑但随时可能被收紧，不赌它。帧预算走 60Hz 兜底（25ms）——90/120Hz
            //   设备上预算偏宽（掉帧判定更保守），方向安全：护栏只会晚触发不会误伤。
            frameBudgetMs = 25.0  // 1.5 × 16.67（60Hz 兜底）
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!active) return  // 停了就不再挂回回调
                    val nowMs = frameTimeNanos / 1_000_000
                    if (lastFrameNanos > 0L) {
                        val gapMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                        if (gapMs > frameBudgetMs) droppedFrames++
                        totalFrames++
                        tick(nowMs)
                    }
                    lastFrameNanos = frameTimeNanos
                    c.postFrameCallback(this)
                }
            }
            frameCallback = cb
            choreographer = c
            c.postFrameCallback(cb)
        }.onFailure {
            // Choreographer 不可得（理论不发生）：护栏退化为「不观测」，玻璃照常
        }
    }

    private fun stop() {
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
        frameCallback = null
        choreographer = null
        lastFrameNanos = 0L
        windowStartMs = 0L
        droppedFrames = 0
        totalFrames = 0
    }

    /** 滚动 10 秒窗口：掉帧率超阈值就升一档（同档 5 秒冷却）。 */
    private fun tick(nowMs: Long) {
        if (windowStartMs == 0L) windowStartMs = nowMs
        val elapsed = nowMs - windowStartMs
        if (elapsed < 10_000L || totalFrames < 120) return  // 至少 2 秒的帧量才谈比例
        val ratio = droppedFrames.toFloat() / totalFrames.toFloat()
        if (ratio > 0.4f && nowMs - lastEscalateMs > 5_000L) {
            lastEscalateMs = nowMs
            when (level) {
                0 -> level = 1
                1 -> { level = 2; degradedToOff = true; active = false }
            }
        }
        windowStartMs = nowMs
        droppedFrames = 0
        totalFrames = 0
    }

    /** 档位换算：模糊半径要乘的系数（1 → 减半）。 */
    fun blurFactor(): Float = if (level >= 1) 0.5f else 1f

    /** 档位 2 = 玻璃整体停用（调用方把 enabled 判成 false，走木纹）。 */
    fun disabled(): Boolean = level >= 2
}

/**
 * 把本节点下的全部内容同时录进 [backdrop] 并正常绘制（录制层 + 原样显示）。
 *
 * 机制：把 Compose 的绘制上下文临时换到 RenderNode 的 RecordingCanvas 上跑一遍
 * drawContent()，再把节点画回来——这是库 layerBackdrop 记录机制的同款原理。
 * 任何异常都退回「只画内容」：玻璃是锦上添花，绝不允许弄坏主界面。
 *
 * c228：护栏档 2（本会话停用）时**短路录制**——直接 drawContent()，不进节点。
 * 与「用户关开关」同语义：零录制开销，不是录了不用。
 */
fun Modifier.recordBackdrop(backdrop: RenderNode?): Modifier {
    if (backdrop == null || GlassPerfGuard.disabled()) return this
    return this.drawWithContent {
        var recorded = false
        try {
            backdrop.setPosition(0, 0, size.width.toInt(), size.height.toInt())
            val rec = backdrop.beginRecording()
            val old = drawContext.canvas
            drawContext.canvas = Canvas(rec)
            try {
                drawContent()
            } finally {
                drawContext.canvas = old
                backdrop.endRecording()
            }
            recorded = true
        } catch (_: Throwable) {
            // 录制失败就当没录：内容照画，玻璃退化成无背景
        }
        if (recorded) {
            drawContext.canvas.nativeCanvas.drawRenderNode(backdrop)
        } else {
            drawContent()
        }
    }
}

/** 玻璃状态：底栏与弹层共用的背景录制 + 若干块玻璃表面。 */
class GlassBackdropState internal constructor(
    internal val backdrop: RenderNode,
) {
    /** 内容区在窗口里的位置。采样偏移 = 表面位置 − 内容区位置。 */
    var contentOrigin: Offset by mutableStateOf(Offset.Zero)

    private val surfaces = mutableMapOf<String, GlassSurface>()

    /**
     * 取（或首次创建）一块玻璃表面。同 key 永远返回同一块 —— 调用方要用
     * `remember { glassState.surface(...) }` 固定住，否则每次重组都换节点、
     * 效果链每帧重编（AGSL 编译不是免费的）。
     */
    fun surface(
        key: String,
        blurDp: Float = BLUR_RADIUS_DP,
        refractionHeightDp: Float = REFRACTION_HEIGHT_DP,
        refractionAmountDp: Float = REFRACTION_AMOUNT_DP,
        depthEffect: Boolean = false,
    ): GlassSurface = surfaces.getOrPut(key) {
        GlassSurface(key, blurDp, refractionHeightDp, refractionAmountDp, depthEffect)
    }

    /** 底栏胶囊的表面（旧字段名的兼容入口）。参数 = 底栏那套常量。 */
    val barSurface: GlassSurface get() = surface("bar")

    /**
     * c228：按浓度档取底栏表面。同 key 恒返回同一块（surfaces 池）——
     * 档位变了参数跟着变，但节点复用，effectKey 变化时会重建效果链，不逐帧重编。
     * 调用方要用 remember 固定住返回值（与 surface() 同一纪律）。
     *
     * @param level 浓度档（折射量按档倍率在这里乘——档位翻译成参数的唯一出口）
     * @param blurDp 最终模糊半径（档位倍率与护栏减半由调用方乘好再传；
     *   blur 是「护栏要动态压」的量，从外面进）
     */
    fun barSurface(level: Int, blurDp: Float): GlassSurface = surface(
        "bar",
        blurDp = blurDp,
        refractionAmountDp = REFRACTION_AMOUNT_DP * GlassLadder.refractScale(level),
    )

    /** 底栏胶囊在窗口里的位置（兼容旧字段名）。 */
    var barOrigin: Offset
        get() = barSurface.origin
        set(value) {
            barSurface.origin = value
        }

    /** 采样窗口四周的缓冲带 px（与 ensureEffect 同公式；绘制与录制必须用同一个值） */
    private fun paddingOf(scope: DrawScope, blurDp: Float): Int =
        kotlin.math.ceil((blurDp * scope.density).toDouble()).toInt() + 2

    /**
     * 底栏 drawBehind 里调用：把内容区在胶囊位置的窗口录进玻璃节点、挂效果画出，
     * 再压一层白纱（教程 onDrawSurface）。胶囊外形由调用方的 clip 负责。
     */
    internal fun drawGlass(scope: DrawScope, surface: Color) {
        drawGlassSurface(scope, barSurface, surface)
    }

    /**
     * 通用绘制：把内容区在 [target] 位置的窗口录进该表面自己的节点、挂效果画出，
     * 再压一层 [veil]。
     *
     * @param target 要画的玻璃表面（底栏胶囊 / 弹层面板）
     * @param veil 表面白纱颜色（随主题走，见 [GlassLadder.veilFor]）
     */
    internal fun drawGlassSurface(scope: DrawScope, target: GlassSurface, veil: Color) {
        if (!GlassLadder.enabled) return
        val offX = target.origin.x - contentOrigin.x
        val offY = target.origin.y - contentOrigin.y
        val pad = paddingOf(scope, target.blurDp)
        val w = scope.size.width.toInt()
        val h = scope.size.height.toInt()
        target.ensureEffect(scope, w, h)
        runCatching {
            target.node.setPosition(-pad, -pad, w + pad, h + pad)
            val rec = target.node.beginRecording()
            // ⚠ 符号（第一版写反就是这次的"文字清晰"根因）：库的 recordBackdropBlock 是
            //   translate(+padding) 再 translate(-offset) —— 即 (pad - off)。
            //   含义：内容坐标 off（胶囊脚下那块）要落到 node 坐标的 (pad, pad)（SDF 盒原点）。
            //   写成 (off - pad) 会把内容推到屏幕外，胶囊里画的是空，白纱下透出的
            //   其实是胶囊底下**没经过任何处理的原始内容** —— 看起来就是"没模糊"。
            //
            // v2.76.0：表面被缩放时（sampleScale > 1）加一段反向补偿，背景才不会跟着缩。
            //   t = pad + c − (off + c)/s，k = 1/s（推导见 GlassSurface.sampleScale 注释）。
            //   s = 1 时 t = pad − off，与改动前逐字一致。
            val s = if (target.sampleScale > 0.01f) target.sampleScale else 1f
            val cx = w / 2f
            val cy = h / 2f
            if (s == 1f) {
                rec.translate(pad - offX, pad - offY)
            } else {
                rec.translate(pad + cx - (offX + cx) / s, pad + cy - (offY + cy) / s)
                rec.scale(1f / s, 1f / s)
            }
            rec.drawRenderNode(backdrop)
            target.node.endRecording()
            scope.drawContext.canvas.nativeCanvas.drawRenderNode(target.node)
        }.onFailure {
            // 采样失败：什么都不画，栏本身照常渲染
        }
        // v2.76.0：玻璃采到的内容要和**外面遮罩同步压暗** —— 否则面板里透出的是
        // 没压暗的页面，比周围更亮，整块面板像一扇亮窗（见 GlassSurface.dimAlpha）。
        if (target.dimAlpha > 0f) {
            scope.drawRect(color = Color.Black.copy(alpha = target.dimAlpha))
        }
        scope.drawRect(color = veil)
    }
}

@Composable
fun rememberGlassState(backdrop: RenderNode?): GlassBackdropState? {
    backdrop ?: return null
    return remember { GlassBackdropState(backdrop) }
}

/**
 * v2.70.0 玻璃胶囊在屏幕底部占住的高度（dp）。
 *
 * 悬浮胶囊会盖住列表最后一屏的内容——解法（方案 A，主流 App 的标准处理）是给
 * 主 tab 的滚动列表加 bottom padding = 这个值：滚动中途内容照样从玻璃后面穿过，
 * 但滚到底时最后一条内容能完整停在胶囊上方。
 *
 * 木纹模式 / 低版本 = **0.dp**：布局与 v2.69 逐像素一致，列表 padding 不变。
 * 由 AppRoot 计算并 provide（含导航条高度），各页列表只消费不计算。
 */
val LocalGlassInset = androidx.compose.runtime.compositionLocalOf { androidx.compose.ui.unit.Dp(0f) }

/** 内容区坐标观测：把 positionInRoot 喂给玻璃状态（差值即采样偏移） */
fun Modifier.trackPosition(onPosition: (Offset) -> Unit): Modifier =
    this.onGloballyPositioned { onPosition(it.positionInRoot()) }

/**
 * c228：效果链重建键（w, h, density, docked）。
 * 替代旧的 Triple —— docked 会改 shader 源里的形状语义，必须参与「要不要重编」的判定，
 * 否则贴边/悬浮互切时 shader 还挂着旧形状。
 */
private data class QuadKey(val w: Int, val h: Int, val density: Float, val docked: Boolean)

/**
 * vibrancy 的饱和度矩阵 —— 与库 effects/ColorFilter.kt 的 VibrantColorFilter 逐项一致
 * （colorControls(saturation = 1.5f)：luminance 权重 0.213/0.715/0.072，contrast=1 → t=0）。
 * android.graphics.ColorMatrixColorFilter 直接收 FloatArray（4×5 矩阵）。
 */
private val VIBRANCY_FLOATS = floatArrayOf(
    // saturation = 1.5: r' = (1-invS)*R + S*r_lum …（invS = -0.5）
    1.213f, -0.285f, -0.072f, 0f, 0f,
    -0.213f, 1.715f, -0.072f, 0f, 0f,
    -0.213f, -0.285f, 1.072f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

// ── AGSL shader：Kyant0/AndroidLiquidGlass internal/Shaders.kt 的 ─────────
// RoundedRectRefractionShaderString **逐字移植**（含 offset 加法语义与 radiusAt
// 用原始 coord 选角）。唯一改动：本场景 cornerRadii 四角恒等（胶囊），
// 但 uniform 结构保留，将来换形状零改动。
//
// c228 新增两处改动（其余一个字没动）：
//   ① uniform float docked —— 贴边弹层「上圆下直」的形状开关（0=悬浮，1=贴边）。
//   ② SDF 前的 dockFlip()：贴边时把下半平面的 coord.y 取负（y 镜像），
//      让下面两角借用上面两角的半径；下两角半径再由 uniform 直接给 0。
//      docked=0 时 dockFlip 是恒等 —— 与上游逐字一致（verify 钉退化关系）。
private val AGSL_REFRACTION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float docked;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

// c228：贴边模式把下半平面镜像到上半（y 取负），SDF 只看镜像后的形状 ——
//   下面两角于是与上面两角共享几何，半径由 cornerRadii 的下两个分量给 0 实现「下直角」。
//   docked = 0 时原样返回（恒等），SDF 与上游逐字一致。
float2 dockFlip(float2 centeredCoord, float docked) {
    if (docked > 0.5 && centeredCoord.y > 0.0) {
        return float2(centeredCoord.x, -centeredCoord.y);
    }
    return centeredCoord;
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    // c228：贴边 = 下半平面 y 镜像后求 SDF；形状在镜像坐标系里与上半完全对称。
    //   梯度也要在镜像系里算、再镜像回原系（y 分量翻回来），否则折射方向会指向镜像侧。
    float2 sdCoord = dockFlip(centeredCoord, docked);
    float sd = sdRoundedRect(sdCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(sdCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    // 镜像回原系：下半平面（y > 0）的梯度 y 分量要翻回来
    if (docked > 0.5 && centeredCoord.y > 0.0) {
        grad.y = -grad.y;
    }

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
""".trimIndent()
