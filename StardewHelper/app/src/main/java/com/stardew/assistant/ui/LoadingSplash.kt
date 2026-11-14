package com.stardew.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 冷启动加载画面（v2.12）：木框 + "星露小助手" 标题 + 3 个错开 300ms 变金的像素方块 + "加载中…" 副标题。
 *  dataReady=true 后整个 LoadingSplash 从 composition 移除，LaunchedEffect 自动取消，零 CPU 残留。 */
@Composable
fun LoadingSplash() {
    val c = LocalPixel.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(300); tick = (tick + 1) % 3 }
    }
    Box(Modifier.fillMaxSize().background(c.screen), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .border(3.dp, c.woodDark)
                .background(c.wood)
                .padding(horizontal = 32.dp, vertical = 18.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PixelText("星露小助手", style = Px.title, color = c.cream, shadow = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { i ->
                        val filled = i == tick
                        Box(
                            Modifier
                                .size(16.dp)
                                .background(if (filled) c.gold else c.slot)
                                .slotBevel(inset = 2.dp, width = 1.dp)
                                .border(2.dp, c.slotEdge),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                PixelText("加载中…", color = c.inkDim, style = Px.caption)
            }
        }
    }
}
