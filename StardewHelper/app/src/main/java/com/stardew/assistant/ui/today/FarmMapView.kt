package com.stardew.assistant.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.FarmMap
import com.stardew.assistant.data.FarmPatch
import com.stardew.assistant.data.FarmTile
import com.stardew.assistant.data.TileState
import com.stardew.assistant.ui.CollapsibleCard
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px

/**
 * 「我的农场」的农场地图（P3-a · 形态 B：按连续行组切）。
 *
 * ## 为什么用 Canvas 画格子、不是一个格子一个 Box
 * 四档真实存档里最多的一档有 582 块地，摊到片上（连空白）约 1200 格。
 * 1200 个 Compose Box 会在滚动时明显掉帧，而 Canvas 一次画完只花一次绘制。
 * 代价是**点击命中要自己算**（见 [PatchGrid]）—— 换来的是流畅。
 *
 * ## 格子大小
 * 每片按自己的宽度自适应（4~9dp），宽片格子小一点才放得下。
 * ⚠ **下限 4dp 是硬约束**：ff 姜岛有一片 73 格宽，4dp × 73 = 292dp 刚好在手机上放得下。
 *
 * ## 颜色只有五种
 * 绿=可收 / 蓝=还在长 / 红=枯死 / 深棕=翻过土没种 / 金=状态判不出。
 * 浅米色 = 那里根本没有耕地（空槽）。**没有第三种色。**
 */
@Composable
fun FarmMapCard(
    maps: List<FarmMap>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (maps.isEmpty()) return
    val c = LocalPixel.current
    val totalPlots = maps.sumOf { it.plotCount }
    CollapsibleCard(
        title = "🗺 农场地图",
        expanded = expanded,
        onToggle = onToggle,
        collapsedHint = "${maps.size} 张地图 · $totalPlots 块地",
    ) {
        maps.forEachIndexed { i, m ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            MapBlock(m)
        }
        Spacer(Modifier.height(6.dp))
        MapLegend()
    }
}

/** 一张地图：标题 + 它的每一片。 */
@Composable
private fun MapBlock(m: FarmMap) {
    val c = LocalPixel.current
    PixelText(
        "${m.location} · ${m.plotCount} 块地 · ${m.patches.size} 片",
        style = Px.caption, color = c.inkDim,
    )
    Spacer(Modifier.height(3.dp))
    m.patches.forEach { p ->
        PatchGrid(p)
        Spacer(Modifier.height(4.dp))
    }
}

/** 一片地：一张 1:1 的网格 + 点格子的详情。 */
@Composable
private fun PatchGrid(p: FarmPatch) {
    val c = LocalPixel.current
    val density = LocalDensity.current
    // 格子大小：按这一片的宽度自适应（4~9dp）。
    val cs = (300 / p.width).coerceIn(4, 9).dp
    val w: Dp = cs * p.width.toFloat()
    val h: Dp = cs * p.height.toFloat()
    var picked by remember(p) { mutableStateOf<FarmTile?>(null) }

    // ⚠ 颜色先在这里取好：Canvas 的绘制块**不是** @Composable，
    //   在里面调 @Composable 函数会编译不过（"invocations can only happen from
    //   the context of a @Composable function"）。
    val colReady = c.green
    val colGrowing = c.blue
    val colDead = c.red
    val colEmpty = c.inkDim
    val colUnknown = c.gold
    val colBlank = c.parchment
    val colGap = c.slotEdge
    fun colorOf(state: String): Color = when (state) {
        TileState.READY -> colReady
        TileState.GROWING -> colGrowing
        TileState.DEAD -> colDead
        TileState.EMPTY -> colEmpty
        else -> colUnknown
    }

    Box {
        Canvas(
            Modifier
                .width(w)
                .height(h)
                .pointerInput(p) {
                    detectTapGestures { off ->
                        val csPx = with(density) { cs.toPx() }
                        if (csPx <= 0f) return@detectTapGestures
                        val gx = p.x0 + (off.x / csPx).toInt()
                        val gy = p.y0 + (off.y / csPx).toInt()
                        picked = p.tileAt(gx, gy)
                    }
                }
        ) {
            val csPx = with(density) { cs.toPx() }
            val gapPx = csPx / 6f
            // 底 = 深棕：格子之间的缝就是它露出来的样子（像素风的描边感）
            drawRect(color = colGap, size = size)
            for (gy in 0 until p.height) {
                for (gx in 0 until p.width) {
                    val tile = p.tileAt(p.x0 + gx, p.y0 + gy)
                    val col = if (tile == null) colBlank else colorOf(tile.state)
                    drawRect(
                        color = col,
                        topLeft = Offset(gx * csPx + gapPx, gy * csPx + gapPx),
                        size = Size(csPx - gapPx * 2, csPx - gapPx * 2),
                    )
                }
            }
        }
        // 点中哪格 → 下面一行说清楚那格是什么（不弹窗，不打断）
        picked?.let { t ->
            Spacer(Modifier.height(2.dp))
            PixelText(
                "${tileLabel(t)} · ${TileState.label(t.state)} · (${t.x},${t.y})",
                style = Px.caption, color = colorOf(t.state),
            )
        }
    }
}

/** 那一格显示成什么名字。查不到名字就说「野生采集物」（与四块汇总同一口径，不编名）。 */
private fun tileLabel(t: FarmTile): String = when {
    t.state == TileState.EMPTY -> "空地"
    t.name != null -> t.name
    else -> "野生采集物"
}

/** 图例：四种状态 + 空槽。 */
@Composable
private fun MapLegend() {
    val c = LocalPixel.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LegendDot(c.green, "可收")
        LegendDot(c.blue, "生长中")
        LegendDot(c.red, "枯死")
        LegendDot(c.inkDim, "空地")
        LegendDot(c.parchment, "无地")
    }
}

/** 图例里的一颗小色块 + 文字。 */
@Composable
private fun LegendDot(color: Color, text: String) {
    val c = LocalPixel.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(7.dp)
                .height(7.dp)
                .padding(0.dp)
        ) {
            Canvas(Modifier.width(7.dp).height(7.dp)) {
                drawRect(color = color)
            }
        }
        Spacer(Modifier.width(3.dp))
        PixelText(text, style = Px.caption, color = c.inkDim)
    }
}
