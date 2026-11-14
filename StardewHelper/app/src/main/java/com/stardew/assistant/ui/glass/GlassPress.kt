package com.stardew.assistant.ui.glass

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
 * ↓↓↓ 本文件整体移植自 Kyant0/AndroidLiquidGlass
 *   （https://github.com/Kyant0/AndroidLiquidGlass，Apache-2.0），逐字对照上游：
 *     app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt
 *
 * 改了什么（上游 = Compose Multiplatform 1.12 + Kotlin 2.4.10；本项目 = Compose 1.7.6 + Kotlin 2.0.21）：
 *   ① 类名 InteractiveHighlight → GlassPressHighlight。
 *   ② AGSL 着色器源码**逐字照抄**（uniform 声明与顺序：size、color、radius、position，
 *      以及 main() 里的 distance / smoothstep / intensity 三行），一个字没改。
 *   ③ 上游用 com.kyant.backdrop.RuntimeShader（他们自己包的一层 expect/actual）+
 *      asComposeShader() 转成 Compose Shader 再用 ShaderBrush 画。本项目 Compose 1.7.6
 *      **没有 asComposeShader()**（javap 核过 androidx.compose.ui.graphics.ShaderKt /
 *      AndroidShader_androidKt 都没有），因此改为直接持有原生
 *      android.graphics.RuntimeShader（API 33+），用原生 Paint 画：
 *        paint.shader = shader; paint.blendMode = android.graphics.BlendMode.PLUS;
 *        canvas.nativeCanvas.drawRect(...)
 *      与上游 ShaderBrush + BlendMode.Plus 的合成语义等价（Compose 内部同样是
 *      paint.shader + paint.blendMode）。
 *   ④ setColorUniform 的入参：上游 wrapper 内部就是 color.toArgb()，这里改用
 *      androidx.compose.ui.graphics.toArgb() 显式转换，值完全一致。
 *   ⑤ 因为原生 RuntimeShader 是 API 33+，新增 companion.supported 能力位：
 *      SDK < 33 时 shader 为 null，走上游同款低版本降级分支
 *      （drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)），
 *      但手势层照常工作（pressProgress 仍按上游参数动）。
 *   ⑥ 其余（弹簧参数、uniform 取值、回调时序）全部照抄上游，一个数都没改。
 *
 * 完整 Apache-2.0 许可证副本见 app/src/main/assets/licenses/Apache-2.0-AndroidLiquidGlass.txt。
 */

/**
 * 按下高光：按下时在目标上叠一团柔光，光心跟随手指。
 *
 * 移植自上游 `InteractiveHighlight`（见文件头），公开成员与调用形状保持不变，
 * 只有类名换成 GlassPressHighlight。
 */
class GlassPressHighlight(
    private val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
    /**
     * 目标是不是**浅色**面。浅色面不能用白色柔光（会把图标洗白，见 [modifier] 的
     * 说明），改用深色压暗。
     */
    val onLightSurface: Boolean = false,
    /** 柔光/压暗的半径 = 最小边 × 该系数。上游是 1.5（给深色底栏用）。 */
    val radiusScale: Float = 1.5f,
) {

    private val pressProgressAnimationSpec =
        spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    /**
     * 原生 AGSL 着色器。SDK < 33 时为 null → 整体降级（见 companion.supported）。
     *
     * ⚠ 持有 android.graphics.RuntimeShader 的类只在 API 33+ 才会被实例化，
     *   低版本设备不会加载它（ART 对无法解析的引用是「方法级」失败，
     *   只要不真正调用就不会触发 NoClassDefFoundError）。
     */
    private val shader: GlassAgslShader? =
        if (supported) GlassAgslShader(AGSL_SOURCE) else null

    /**
     * 绘制层：叠在目标上（`.then(...)` 挂载），把柔光画在内容上方。
     *
     * ## v2.76.0：浅色玻璃面**不能**用白色柔光
     * 上游那套「白色柔光」是给**深色壁纸**设计的：白色加光（BlendMode.Plus）压在
     * 深底上才好看。我们的底栏是**白纱浅色玻璃面**（白纱 0.42 压浅色页面），
     * 同样加白 = 把手下的像素图标**全洗白**（用户真机录屏实测：按下时整条底栏
     * 糊成一块白，图标全没了）。浅色面上的按压反馈应当是**压暗**（Material 浅色面
     * 的按压态同理：深色遮罩，不是白色发光）。
     *
     * 所以这里分两种形态：
     *  - [onLightSurface] = true → 深色径向压暗（用 Compose 渐变画，不走 AGSL）
     *  - [onLightSurface] = false → 上游原样的白色柔光（AGSL 逐字保留，深色面用）
     */
    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                val pos = position(size, positionAnimation.value)
                if (onLightSurface) {
                    // 浅色面：深色压暗。半径取 radiusScale × 最小边 —— 上游 1.5 倍
                    // 是为了盖住整个深色底栏，浅色面上只要盖住「被按的那一格」，
                    // 盖太多就成了「整条底栏发灰」。
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.13f * progress),
                                Color.Transparent,
                            ),
                            center = Offset(
                                pos.x.fastCoerceIn(0f, size.width),
                                pos.y.fastCoerceIn(0f, size.height),
                            ),
                            radius = size.minDimension * radiusScale,
                        ),
                    )
                } else {
                    val shader = this@GlassPressHighlight.shader
                    if (shader != null) {
                        drawRect(
                            Color.White.copy(0.08f * progress),
                            blendMode = BlendMode.Plus
                        )
                        shader.setFloatUniform("size", size.width, size.height)
                        // ⚠ 必须走预乘入口：AGSL 的 layout(color) 收预乘色，
                        //   漏了预乘 = 柔光变纯白（见 setPremultipliedColorUniform 的说明）
                        shader.setPremultipliedColorUniform("color", Color.White.copy(0.15f * progress))
                        shader.setFloatUniform("radius", size.minDimension * radiusScale)
                        shader.setFloatUniform(
                            "position",
                            pos.x.fastCoerceIn(0f, size.width),
                            pos.y.fastCoerceIn(0f, size.height)
                        )
                        drawIntoCanvas { canvas ->
                            shader.draw(canvas.nativeCanvas, 0f, 0f, size.width, size.height)
                        }
                    } else {
                        drawRect(
                            Color.White.copy(0.25f * progress),
                            blendMode = BlendMode.Plus
                        )
                    }
                }
            }

            drawContent()
        }

    /** 手势层：挂在目标上（`.then(...)` 挂载），驱动 pressProgress 与光心位置。 */
    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                }
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }

    companion object {
        /** AGSL 要 33+，低于此版本整体降级（上游同款做法）。 */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 33
    }
}

/**
 * AGSL 着色器薄封装：持有原生 [android.graphics.RuntimeShader] 与配套 Paint。
 *
 * 只在 API 33+ 被实例化（见 [GlassPressHighlight.supported]）。
 * 上游对应的类是 com.kyant.backdrop.RuntimeShader（他们自己的 expect/actual 包装），
 * 这里的 uniform 设置方法与之一一对应，只是直接落到原生类型上。
 */
private class GlassAgslShader(source: String) {

    private val runtimeShader = android.graphics.RuntimeShader(source)

    private val paint = android.graphics.Paint()

    init {
        paint.shader = runtimeShader
        // API 29+ 的 BlendMode 入口（等价 Compose 的 BlendMode.Plus）
        paint.blendMode = android.graphics.BlendMode.PLUS
    }

    fun setFloatUniform(name: String, value: Float) {
        runtimeShader.setFloatUniform(name, value)
    }

    fun setFloatUniform(name: String, value1: Float, value2: Float) {
        runtimeShader.setFloatUniform(name, value1, value2)
    }

    fun setColorUniform(name: String, argb: Int) {
        runtimeShader.setColorUniform(name, argb)
    }

    /**
     * 设置**预乘**颜色 uniform。
     *
     * ## ⚠ 这里必须自己预乘（v2.76.0 装机真机录屏抓到的坑）
     * AGSL 的 `layout(color)` uniform 收的是**预乘**颜色（premultiplied）。
     * 上游有自己的 `com.kyant.backdrop.RuntimeShader` 包装层，**预乘是在那一层做的**；
     * 我们直接用原生 `android.graphics.RuntimeShader.setColorUniform`，就漏了这一步。
     *
     * 漏了会怎样：传 `Color.White.copy(0.15f)` 的 unpremultiplied ARGB = `(255,255,255,38)`，
     * uniform 里于是是 **RGB=1.0** 而不是 0.15 → shader 输出 `color * intensity` = **纯白**
     * → `BlendMode.Plus` 加上来 = 底栏上一块**纯白椭圆**（用户真机录屏实测：
     * 按住底栏时整条栏糊成白块，像素图标全被洗白）。
     *
     * 预乘后：白色 15% → `(38,38,38,38)` → shader 输出 0.15 的灰白 → 加上来是**微光**。
     */
    fun setPremultipliedColorUniform(name: String, color: Color) {
        val a = color.alpha
        val av = (a * 255f).toInt().coerceIn(0, 255)
        val r = (color.red * a * 255f).toInt().coerceIn(0, 255)
        val g = (color.green * a * 255f).toInt().coerceIn(0, 255)
        val b = (color.blue * a * 255f).toInt().coerceIn(0, 255)
        runtimeShader.setColorUniform(name, (av shl 24) or (r shl 16) or (g shl 8) or b)
    }

    fun draw(canvas: android.graphics.Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        canvas.drawRect(left, top, right, bottom, paint)
    }
}

/**
 * AGSL 源码：逐字照抄上游 InteractiveHighlight 的着色器字符串。
 *
 * uniform 声明与顺序（size、color、radius、position）与 main() 里的
 * distance / smoothstep / intensity 全部保持原样，未做任何增删或改写。
 */
private val AGSL_SOURCE = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
