package com.stardew.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** 节日彩蛋横幅（v2.12）：节日当天显示在 Today 顶部，跳动彩带 + 礼盒图标 + 节日名。
 *  festival=null 时直接 return——不进入 composition、不占 layout 空间、LaunchedEffect 不会启动。
 *  LaunchedEffect 绑 festival key：节日切换时重启循环保证动画同步。 */
@Composable
fun FestivalBanner(festival: String?) {
    if (festival == null) return
    val c = LocalPixel.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(festival) {
        while (true) { delay(450); tick = (tick + 1) % 4 }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.gold.copy(alpha = 0.9f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val sparkle = listOf("✦", "✺", "❖", "✦")[tick]
            PixelText(sparkle, color = c.cream, style = Px.title)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                PixelText("🎉 ${festival}！", color = c.cream, shadow = true, style = Px.title)
                PixelText("节日快乐，祝游戏顺利～", color = c.cream.copy(alpha = 0.9f), style = Px.caption)
            }
            Spacer(Modifier.width(8.dp))
            // 礼盒图标：assets/img/ui/gift.png 不存在时 UiIcon 静默不渲染，组件仍可见（不影响功能）
            UiIcon("gift", 28.dp)
        }
    }
}
