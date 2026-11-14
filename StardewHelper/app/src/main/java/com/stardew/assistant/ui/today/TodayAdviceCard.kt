package com.stardew.assistant.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.AdviceTarget
import com.stardew.assistant.data.TodayAdvice
import com.stardew.assistant.ui.CollapsibleCard
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.pressFeedback

/**
 * 「🎯 今天该做什么」建议卡（v2.100.0 / c181，百科 × 存档第 3 层；
 * v2.100.0 / c182 修注脚与日期来源）。
 *
 * ## 它只做三件事
 * ① 渲染 [TodayAdvice]（**最多 3 条**，条数由数据层定死，UI 不自己加）；
 * ② 每条可点：**图鉴跳转一律走 `resolveItemJump`**（全 App 唯一图鉴跳转入口，
 *    重名的退化成搜索结果，不自己构造 `NavRequest.Codex`）；
 *    村民跳转走 `navBus` 的 `NavRequest.Villager`（与今日页生日卡同一条通道）；
 * ③ 底部两句注脚：**依据**（这条数从哪儿来）与**日期来源**（这个「今天」是哪天）。
 *
 * ## ⚠ v2.100.0 / c182 两处改口径
 * · **注脚不再写「背包」**：四条建议的依据分别是枯死行 / 能收行 / 空地数 + 作物表 +
 *   现金 / 生日名单，**没有一条读背包** ⇒ 列个没用的「背包」等于骗人。
 * · **显式标注日期来源**（[dateLabel]）：建议的「今天」是**存档里的那天**
 *   （与「今日农场」卡头部同一个日期）—— 同屏还有一个转盘日期，
 *   不标来源就又是「两个今天」。
 *
 * ## 折叠
 * 整段能收起来（[CollapsibleCard]）：建议最多 3 条，但每条带一行副文案，
 * 展开时是 6~7 行。不折会把今日页撑太长，所以默认展开、但随时能收。
 *
 * ## 空列表
 * **一条都没有 ⇒ 整张卡不出现**（没有存档 / 没有算得出来的事），
 * 绝不用「今天也可以什么都不做」这类泛泛话术凑数。
 */
@Composable
fun TodayAdviceCard(
    advice: List<TodayAdvice>,
    expanded: Boolean,
    /** 日期来源标签（如「按存档 第3年 夏6日」）；空串 = 判不出，不显示那一行。 */
    dateLabel: String,
    onToggle: () -> Unit,
) {
    if (advice.isEmpty()) return
    val c = LocalPixel.current
    CollapsibleCard(
        title = "🎯 今天该做什么",
        expanded = expanded,
        onToggle = onToggle,
        collapsedHint = "${advice.size} 条建议 · 点标题展开",
        accent = c.gold,
    ) {
        advice.forEachIndexed { i, a ->
            if (i > 0) RowSep()
            AdviceLine(a)
        }
        Spacer(Modifier.height(6.dp))
        // ⚠ 依据只列**真的用到**的：田里 / 现金 / 作物表 / 生日（原注脚列了「背包」，
        //   可没有一条建议读背包 —— 列没用的依据等于骗人）。
        PixelText(
            "依据：导入的存档（田里 / 现金 / 作物表 / 生日）",
            style = Px.caption, color = c.inkDim,
        )
        if (dateLabel.isNotBlank()) {
            PixelText(
                "日期：$dateLabel",
                style = Px.caption, color = c.inkDim,
            )
        }
    }
}

/** 一条建议：一句人话 + 副文案（数字与位置）+ 可点的箭头。 */
@Composable
private fun AdviceLine(a: TodayAdvice) {
    val c = LocalPixel.current
    val isrc = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(isrc)
            .padding(vertical = 7.dp)
            .clickable(interactionSource = isrc, indication = null) { jumpAdvice(a.target) },
    ) {
        Column(Modifier.weight(1f)) {
            PixelText(a.title)
            val note = a.note
            if (!note.isNullOrBlank()) {
                PixelText(note, style = Px.caption, color = c.inkDim)
            }
        }
        Spacer(Modifier.width(6.dp))
        // 跳不了的建议不画箭头（免得点了没反应）
        if (a.target != null) PixelText("▸", color = c.inkDim)
    }
}

/**
 * 建议的跳转：**只有这两种**，且都不新建第二套逻辑。
 * · 图鉴 → `resolveItemJump`（唯一入口；重名退化成搜索结果）；
 * · 村民 → `navBus` 的 `NavRequest.Villager`（今日页生日卡同一条通道）。
 */
private fun jumpAdvice(target: AdviceTarget?) {
    when (target) {
        null -> Unit
        is AdviceTarget.Codex -> com.stardew.assistant.ui.codex.resolveItemJump(target.name)
        is AdviceTarget.Villager ->
            com.stardew.assistant.ui.nav.navBus.value =
                com.stardew.assistant.ui.nav.NavRequest.Villager(target.name)
    }
}
