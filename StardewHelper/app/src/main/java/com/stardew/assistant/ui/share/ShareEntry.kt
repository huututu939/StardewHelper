package com.stardew.assistant.ui.share

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.stardew.assistant.data.CodexItem
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.ui.IconCache
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.LocalAppFont
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelFont
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.slotBevel
import com.stardew.assistant.ui.today.todayFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PageBar / 抽屉标题栏的 📤 槽位按钮（样式照今日页的 ⚙ / 🔍） */
@Composable
fun ShareBarButton(onClick: () -> Unit) {
    val c = LocalPixel.current
    Box(
        Modifier
            .background(c.slot)
            .slotBevel(inset = 2.dp, width = 1.dp)
            .border(2.dp, c.slotEdge)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { PixelText("📤", color = c.ink) }
}

/**
 * v2.26 今日页分享入口：📤 → 预览今日概览卡 → 分享。
 * 一步一档，不再有「三选一」那一层。
 */
@Composable
fun TodayShareEntry(snap: PrefsRepo.Snapshot) {
    var open by remember { mutableStateOf(false) }
    ShareBarButton { open = true }
    if (open) {
        val facts = remember(snap) { todayFacts(snap) }
        CardPreviewDialog(
            title = "今日概览卡",
            hint = "卡上不写天气：游戏里天气是随机的，助手不知道当天下不下雨。",
            shareLabel = "今日概览",
            onDismiss = { open = false },
        ) { TodayCardBody(facts, snap.activeProfile) }
    }
}

/**
 * v2.26 图鉴条目分享入口：条目详情抽屉标题栏的 📤。
 * 图标先在 IO 线程解码好，否则截图里是首字占位块（AssetImage 正常是异步解码的）。
 */
@Composable
fun ItemShareEntry(item: CodexItem, groupTitle: String) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(item.iconKey) {
        withContext(Dispatchers.IO) { IconCache.itemIcon(ctx, item.iconKey) }
    }
    ShareBarButton { open = true }
    if (open) {
        CardPreviewDialog(
            title = "${groupTitle} · ${item.name}",
            hint = "图鉴里任何条目都能这样分享到群里。",
            shareLabel = item.name,
            onDismiss = { open = false },
        ) { ItemCardBody(item, groupTitle) }
    }
}

/**
 * 卡片预览 → 分享。预览必须留，两个理由：
 * 1. 分享出去的图别人删不掉，得让人先看见；
 * 2. 离屏抓图抓的是**已经画过的帧**，不预览就没东西可抓。
 * 分享后预览不自动关闭——系统 chooser 盖上来，回来还能再发一次或改。
 */
@Composable
private fun CardPreviewDialog(
    title: String,
    hint: String,
    shareLabel: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val layer = rememberShareCardLayer()
    var busy by remember { mutableStateOf(false) }

    val screenWpx = with(density) { cfg.screenWidthDp.dp.toPx() }
    // 宽度基准要扣掉 PixelCard 的固定开销（Dialog 12dp×2 + 卡边 3dp×2 + 卡内距 5dp×2
    // + 内衬边 2dp×2 + 内衬内距 12dp×2 ≈ 178px），否则按屏幕宽算出来的卡会撑出内衬。
    // 200 是再留一点的余量。
    val scale = ((screenWpx - 200f) / CardSpec.W).coerceIn(0.2f, 0.82f)

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelCard(title = title, accent = c.gold) {
                PixelText(hint, style = Px.caption, color = c.inkDim)
                Spacer(Modifier.height(10.dp))
                // v2.26.2：按缩放后的密度**重新布局**卡片，而不是位图缩放。
                // 前两版分别踩了 Modifier.scale（参与约束→卡被压扁成 1:2.5）和
                // graphicsLayer+requiredSize（缩放没真正作用于绘制→卡仍是 1080 宽，
                // 溢出内衬被裁）。Density(scale) 让卡里所有 dp/sp 一起缩小，
                // 占位宽高与绘制宽高天然相等，不可能再被父约束压。
                CompositionLocalProvider(
                    LocalDensity provides Density(density = scale, fontScale = scale),
                    // v2.93.0：分享卡走 Compose + PixelText，**会继承 App 的系统字体**。
                    //   用户要求「系统字体只对 App 内生效，分享图不变」⇒ 这里显式钉回像素字体。
                    //   预览与导出是同一处组合 ⇒ 预览里看到的也就是导出的那张。
                    //   （弹窗外壳的按钮仍跟随 App 设置 —— 那是 App 内 UI，不是分享图本身。）
                    LocalAppFont provides PixelFont,
                ) {
                    Box(Modifier.size(CardSpec.W.dp, CardSpec.H.dp)) {
                        ShareCardSurface(layer) { content() }
                    }
                }
                Spacer(Modifier.height(10.dp))
                RowSep()
                Spacer(Modifier.height(8.dp))
                PixelButton(text = if (busy) "正在导出…" else "分享这张", active = true, onClick = {
                    if (busy) return@PixelButton
                    busy = true
                    scope.launch {
                        val res = runCatching {
                            val bmp = layer.captureBitmap()
                            ShareHelper.shareBitmap(ctx, bmp, shareLabel)
                        }.getOrElse { Result.failure(it) }
                        busy = false
                        res.onFailure { Toast.makeText(ctx, it.message ?: "导出失败", Toast.LENGTH_SHORT).show() }
                    }
                })
                Spacer(Modifier.height(6.dp))
                PixelButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}