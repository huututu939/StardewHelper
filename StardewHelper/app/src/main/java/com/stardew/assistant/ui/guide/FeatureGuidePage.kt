package com.stardew.assistant.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.glass.LocalGlassInset
import com.stardew.assistant.ui.nav.NavRequest
import com.stardew.assistant.ui.nav.navBus
import com.stardew.assistant.ui.pressFeedback

/**
 * v2.99.0：**使用指引**页（「这个功能在哪儿」）。
 *
 * ## 这一页解决什么
 * 群友常问「这个功能在哪儿 / 那个功能在哪儿」——App 里功能不少，但
 * **没有任何一处说它们分别在哪儿**。首启引导（`Intro.kt`）只教「怎么用」，
 * 不教「东西在哪」。这一页把每个功能的**位置**列出来。
 *
 * ## 两个入口，一份数据
 * 设置页的「📖 使用指引」卡与「跳到…」面板里的入口都开这一页，
 * 文案只有一份（`assets/data/feature_guide.json`）⇒ 不会两处对不上。
 *
 * ## 能跳的直接跳，跳不了的写清位置
 * 每条都有 `where`（「在哪儿找得到」）。**能直达**的额外显示「›」，
 * 点一下就跳过去；跳不了的（设置页里的卡没有锚点）就把位置写清楚。
 *
 * ## 数量一律运行时现算
 * 「{分类数} / {册子数} / {村民数} / {条目数}」在页面层替换成真实值，
 * 数据更新了指引不会说谎（v2.17 D9 的老坑）。
 */
@Composable
fun FeatureGuidePage(onBack: () -> Unit, onReplayIntro: () -> Unit = {}) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    // c203：读 assets + 解析 JSON 挪到 IO 线程。
    // 原来 `remember(ctx) { loadFeatureGuide(ctx) }` 在**组合期主线程**做这件事
    // （11.5KB JSON）——与 ReportNames / AssetImage 的 IO 惯例不一致，
    // 进指引页时那一下主线程解析是可以省掉的。
    // ⚠ 解析失败时 loadFeatureGuide 返回「空分组 + 错误说明」而不是抛异常，
    //   所以这里不需要额外的错误分支（空分组页面会自己显示说明）。
    //   初值给一个「读取中…」的占位 ⇒ guide 保持非空，下面的用法一字不用改。
    val guide by produceState(FeatureGuide(emptyList(), listOf("读取中…")), ctx) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadFeatureGuide(ctx) }
    }

    // 运行时现算的四个数（不写进文案，免得说谎）
    val groups = remember { GameData.codexGroupsWithMods }
    val counts = remember(groups) {
        mapOf(
            "{分类数}" to groups.size.toString(),
            "{册子数}" to GUIDE_BOOKS.size.toString(),
            "{村民数}" to GameData.gifts.size.toString(),
            "{条目数}" to groups.sumOf { it.items.size }.toString(),
        )
    }

    fun fill(s: String): String {
        var out = s
        for ((k, v) in counts) out = out.replace(k, v)
        return out
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("📖 使用指引", onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, top = 10.dp, end = 12.dp,
                bottom = 12.dp + LocalGlassInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PixelCard(title = "🧭 这页怎么用") {
                    PixelText(
                        // ⚠ 这里**不能**写 Markdown 强调（两个星号包起来）：Compose 的 Text 没有
                        //   Markdown 解析，星号会原样显示出来（模拟器实测过）。强调靠语序，不靠符号。
                        "下面按组列出 App 里每个功能在哪儿。带「›」的条目点一下直接跳过去；" +
                            "没带「›」的，往下看「📍」那行就写清楚了位置。",
                    )
                }
            }
            item {
                // c227：从设置页挪来——使用指引不再占设置页卡片位，「重新看引导」
                // 跟着帮助语境走（低频入口，放在页头说明之后）。
                PixelButton("重新看引导", onClick = onReplayIntro)
            }

            guide.groups.forEach { g ->
                item(key = "g_${g.id}") {
                    PixelCard(title = "${g.icon} ${fill(g.title)}") {
                        if (g.note.isNotBlank()) {
                            PixelText(fill(g.note), color = c.inkDim, style = Px.caption)
                            Spacer(Modifier.height(4.dp))
                        }
                        g.items.forEachIndexed { i, e ->
                            if (i > 0) {
                                Spacer(Modifier.height(6.dp))
                                RowSep()
                                Spacer(Modifier.height(6.dp))
                            }
                            GuideEntryRow(
                                entry = e,
                                desc = fill(e.desc),
                                where = fill(e.where),
                            )
                        }
                    }
                }
            }

            if (guide.broken.isNotEmpty()) {
                item {
                    PixelCard(title = "⚠ 有几个入口没能解析") {
                        PixelText(
                            "下面这些条目点不动（数据与界面不同步）：",
                            color = c.red,
                        )
                        Spacer(Modifier.height(4.dp))
                        guide.broken.forEach { PixelText("· $it", color = c.inkDim, style = Px.caption) }
                    }
                }
            }

            item {
                PixelText(
                    // ⚠ 这一行也必须过 fill()：占位符写在字符串里但没替换的话，
                    //   屏幕上会直接露出「{分类数}」这种花括号（模拟器实拍抓到过）。
                    fill(
                        "共 ${guide.groups.sumOf { it.items.size }} 条 · " +
                            "{分类数} 个数据分类 · {册子数} 本攻略册 · {村民数} 位村民"
                    ),
                    color = c.inkDim, style = Px.caption,
                )
            }
        }
    }
}

/**
 * 一条指引条目。
 *
 * ⚠ 正文色一律用 [com.stardew.assistant.ui.PixelColors.ink] 压羊皮纸底
 *   （v2.97 的教训：段落标题用木色压木色会直接隐形）。
 */
@Composable
private fun GuideEntryRow(entry: GuideEntry, desc: String, where: String) {
    val c = LocalPixel.current
    val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val jump = entry.jump
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (jump != null) {
                    Modifier
                        .pressFeedback(isrc)
                        .clickable(interactionSource = isrc, indication = null) {
                            navBus.value = jump.toNavRequest()
                        }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(
                    if (entry.icon.isBlank()) entry.name else "${entry.icon} ${entry.name}",
                    color = c.ink,
                )
                if (jump != null) {
                    Spacer(Modifier.width(6.dp))
                    PixelText("›", color = c.gold)
                }
            }
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                PixelText(desc, color = c.ink, style = Px.caption)
            }
            if (where.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                PixelText("📍 $where", color = c.inkDim, style = Px.caption)
            }
        }
    }
}

/** 指引里的跳转目标 → 全局导航指令。 */
private fun GuideJump.toNavRequest(): NavRequest = when (this) {
    is GuideJump.Tab -> NavRequest.Tab(screen)
    is GuideJump.Stack -> NavRequest.Stack(screen)
    is GuideJump.Codex -> NavRequest.Codex(groupId)
    is GuideJump.Book -> NavRequest.Guide(screen)
    is GuideJump.Villager -> NavRequest.Villager(name)
}
