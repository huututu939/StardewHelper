package com.stardew.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/** 一个滚轮的配置：标签 + 选项 + 初始下标 */
data class WheelSpec(val label: String, val options: List<String>, val initial: Int)

/**
 * 底部抽屉：任意多个滚轮选择器（像素风）。
 * 打开时以 initial 初始化，「确定」统一回调各滚轮的选中下标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WheelDrawer(
    visible: Boolean,
    title: String,
    wheels: List<WheelSpec>,
    onDismiss: () -> Unit,
    onConfirm: (indices: List<Int>) -> Unit,
) {
    if (!visible) return
    val c = LocalPixel.current
    // 系统返回手势优先收起抽屉，而不是退 App / 跳 tab
    BackHandler(onBack = onDismiss)

    val idx = remember(wheels) { wheels.map { mutableIntStateOf(it.initial) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = c.wood,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.wood)
                .woodGrain()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            // ── 抽屉头部 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(title, color = c.cream, shadow = true)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .background(c.slot)
                        .slotBevel(inset = 2.dp, width = 1.dp)
                        .border(2.dp, c.slotEdge)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) { PixelText("✕", color = c.ink) }
            }
            Spacer(Modifier.height(12.dp))

            // ── 滚轮区 ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, c.woodDark)
                    .background(c.parchment)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    wheels.forEachIndexed { wi, spec ->
                        if (wi > 0) Box(Modifier.width(2.dp).background(c.parchment2))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            PixelText(spec.label, color = c.inkDim)
                            Spacer(Modifier.height(2.dp))
                            PixelWheel(
                                options = spec.options,
                                initial = spec.initial,
                                onSettled = { idx[wi].intValue = it },
                                modifier = Modifier.fillMaxWidth(),
                                // 直接点某个值 = 选中并关闭；转轮保留给微调场景
                                onTap = {
                                    idx[wi].intValue = it
                                    onConfirm(idx.map { v -> v.intValue })
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 确定按钮 ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.green)
                    .slotBevel(inset = 2.dp, width = 1.dp)
                    .border(2.dp, c.slotEdge)
                    .clickable { onConfirm(idx.map { it.intValue }) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                PixelText("确定", color = Color.White)
            }
        }
    }
}

/** 可点击的取值徽章：用于打开滚轮抽屉 */
@Composable
fun ValueBadge(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPixel.current
    Box(
        modifier
            .background(c.slot)
            .slotBevel(inset = 2.dp, width = 1.dp)
            .border(2.dp, c.slotEdge)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { PixelText(text, color = c.ink) }
}

/** 快捷构造：数字序列滚轮选项（"3 日" / "10 块" 之类） */
fun numberOptions(from: Int, to: Int, unit: String = ""): List<String> =
    (from..to).map { if (unit.isEmpty()) "$it" else "$it $unit" }
