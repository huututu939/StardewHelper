package com.stardew.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 首次运行**四屏**引导（像素风全屏覆盖层）：跳过或最后一页「开始使用」后置位，只出现一次。
 *
 * ⚠ c201 B3：这里原来写的是「三屏」，但实现是 `rememberPagerState(pageCount = { 4 })`
 *   （圆点也是 4 个）——注释与实现对不上。下次有人照着注释改页数就会改错。
 */
@Composable
fun IntroOverlay(onDone: () -> Unit) {
    val c = LocalPixel.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 4 })
    val page = pager.currentPage

    Box(
        Modifier
            .fillMaxSize()
            .background(c.screen)
            .woodGrain()
            // 吞掉触摸，防止穿透到底层页面。
            // v2.17 A11：旧写法 clickable(enabled = false) 不安装手势检测器 → 根本不消费事件，
            // 覆盖层退化为纯视觉，引导期间点底部会切走底层页签。改为 enabled 的空动作以真正消费。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // v2.17 A11：内容避让刘海/手势条（旧固定 40dp 竖距在异形屏会顶到状态栏）
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { p ->
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PixelText(INTRO_EMOJI[p], style = Px.big, color = c.cream)
                    Spacer(Modifier.height(18.dp))
                    PixelText(INTRO_TITLES[p], style = Px.big, color = c.cream, shadow = true)
                    Spacer(Modifier.height(16.dp))
                    INTRO_BODIES[p].forEach { line ->
                        // v2.39：正文原用 c.inkDim 落在 c.screen 上——**日间配色下亮度只差 25**
                        // （inkDim #8A6538 vs screen #A97D45），对比度 1.43:1，属「看不清」级别
                        // （WCAG 正文 AA 要 4.5:1）。群友 @若凛233 截图报「新手教程的字看不太清」。
                        // 改 c.ink 后：日间 3.44:1、夜间 12.6:1（夜间原本 5.94:1 也没问题，
                        // 换成 ink 只是更好，两边都不倒退）。标题的 cream+shadow 不动。
                        //
                        // v2.39.2：**字号也一起放大**。默认档是 Px.body(12sp)，但引导页是
                        // 一次性全屏覆盖层、每页只有 4~6 行短句，横向余量充足（最长的
                        // 「桌面小组件有 4×1 / 2×2 / 4×2 三种规格。」放大后约 312dp，
                        // 内容区宽 355dp），12sp 在手机上确实偏小。改 Px.title(16sp)，
                        // 与 24sp 的标题拉开一级，层次仍是 24 / 16 / 6。
                        // PixelText 默认 maxLines = Int.MAX_VALUE，放大后放不下的行会**换行**
                        // 而不是被裁掉（overflow 默认 Clip 只在限行时才生效），不会截字。
                        PixelText(
                            line,
                            style = Px.title,
                            color = c.ink,
                            shadow = true,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
            PixelPips(done = page + 1, total = 4, size = 10.dp)
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (page < 3) {
                    PixelButton("跳过", onClick = onDone)
                    Spacer(Modifier.weight(1f))
                    PixelButton("下一页 ▸", onClick = { scope.launch { pager.animateScrollToPage(page + 1) } }, active = true)
                } else {
                    Spacer(Modifier.weight(1f))
                    PixelButton("开始使用 ✔", onClick = onDone, active = true)
                }
            }
        }
    }
}

private val INTRO_EMOJI = listOf("🌱", "📅", "💾", "🔍")
private val INTRO_TITLES = listOf("星露小助手", "先对齐游戏日期", "进度记得保存", "熟手技巧")
private val INTRO_BODIES = listOf(
    listOf(
        // ⚠ v2.91.0：改回远端 2.90.0 的原文「**不用联网也能用的**」。
        //   「完全离线」是**不准确**的措辞 —— App 每次打开会联网查一次更新，
        //   另有「下载电脑脚本」「Mod 扩展清单」「从电脑接收」三处按需联网。
        //   卖点没变（核心功能离线可用），但不能把话说满。
        "不用联网也能用的星露谷物语攻略搭档：",
        "今日待办 · 播种死线 · 社区中心进度",
        "博物馆 / 成就 / 金核桃 · 32 位村民喜好",
        "种植与加工收益计算器",
    ),
    listOf(
        "点今日页右上角「春 X · 第 X 年 ▾」",
        "用滚轮把日期调到你游戏里的进度，",
        "生日提醒、节日、播种死线和计算器",
        "会全部跟着这个日期算。",
    ),
    listOf(
        "进度页支持多个存档（最多 8 个），",
        "和对象 / 朋友联机各记各的。",
        "打勾记录只存在本机 ——",
        "记得去设置页「导出备份」保存进度文件。",
    ),
    listOf(
        "长按今日待办可复制为 Markdown，",
        "发群里即用（已勾显示 [x]）。",
        "长按村民头像看送礼规划；",
        "长按博物馆格子看完整名。",
        "桌面小组件有 4×1 / 2×2 / 4×2 三种规格。",
        "图鉴点条目开底部抽屉，查完返回不弹回列表。",
    ),
)
