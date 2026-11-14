package com.stardew.assistant.ui.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.CodexGroup
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px

/**
 * v2.31：从 `CodexPage.kt` 原样搬来的分类面板分组与配色。
 *
 * ## 为什么搬
 * 批次 4 新增的「跳到…」面板（长按底 tab）也要按这四组上色展示 27 个分类。
 * 不搬的话就是两份 `PANEL_SECTIONS` 硬编码 id 清单——将来新增分类只改一处，
 * 另一处就「凭空消失」（v2.18 已经因为 cheatCodes 漏写被用户反馈过一次）。
 * verify 钉住「`PANEL_SECTIONS` 全项目只有一处定义」。
 *
 * ## 搬了之后没变的
 * 分组归属、上色 token、预览文案与截断规则一律照旧，批次 1 已验收的观感不变。
 */

/** 分类面板分组的色调（v2.28）。
 *  27 个分类原本只靠出现顺序区分，扫一眼全是同款 chip；四组各给一个颜色后
 *  「这是什么类的东西」变成一眼可见，不用逐个点。 */
enum class SectionTone { GROW, MAKE, COLLECT, LIVE, OTHER }

/** 分类面板分组（展示顺序 + 色调；groupIdx 语义与 codexGroups 下标一致）。
 *  ⚠ v2.18：cheatCodes 必须列进来，否则新分类在面板里查不到（用户反馈「没有小鸡代码选项」）。
 *  面板下方有「未分组」兜底段，新增分类漏写这里也能看到。 */
data class PanelSection(
    val label: String,
    val ids: List<String>,
    val tone: SectionTone,
)

/** 组色只取 PixelColors 既有 token（不新增硬编码色值），日/夜主题自动一致。
 *  必须在 composable 里取 LocalPixel——文件顶层拿不到。 */
@Composable
fun toneColor(tone: SectionTone): Color {
    val c = LocalPixel.current
    return when (tone) {
        SectionTone.GROW -> c.green      // 种养
        SectionTone.MAKE -> c.wood       // 制作装备（木色贴合工坊）
        SectionTone.COLLECT -> c.gold    // 收集对照
        SectionTone.LIVE -> c.red        // 生活冒险
        SectionTone.OTHER -> c.inkDim    // 兜底「其他」段保持中性
    }
}

/**
 * v2.29 分类面板里的「里面有什么」预览：分类 chip 下方一行小字，前 2 个条目名。
 *
 * 病因是面板上只有名字和数字（「怪物图鉴 78」），选中之前完全不知道装了什么。
 *
 * ⚠ 宽度用 150.dp 硬上限 + maxLines = 1：不设的话「博物馆·古物」这种长名配上
 * 预览会把 FlowRow 撑爆，360dp 窄屏上尤其明显（本项目已有两处槽位溢出前科）。
 * ⚠ 必须过滤空白名：无名条目会渲染成一行空白，「空行」比「少一个预览」更糟。
 */
@Composable
fun PanelPreview(g: CodexGroup) {
    val c = LocalPixel.current
    val preview = remember(g) {
        g.items.map { it.name }.filter { it.isNotBlank() }.take(2).joinToString(" · ")
    }
    if (preview.isBlank()) return
    // c186：预览小字套羊皮纸底 —— inkDim 裸压木壳（全部分类面板）/玻璃（跳到面板）
    // 日间只有 2.04:1；parchment 底上日/夜都 ≈5:1。宽度上限上移到外层 Box，
    // 防 FlowRow 撑爆的原始约束不变（verify 24.5 仍钉 150.dp / maxLines=1 / caption）。
    Box(
        Modifier
            .width(150.dp)
            .background(c.parchment)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        PixelText(
            preview,
            style = Px.caption,
            color = c.inkDim,
            maxLines = 1,
        )
    }
}

val PANEL_SECTIONS = listOf(
    PanelSection(
        "🌱 种养",
        listOf("crops", "fruitTrees", "animals", "fishPonds", "forage", "seedPrices", "channels"),
        SectionTone.GROW,
    ),
    PanelSection(
        "🔨 制作装备",
        listOf("recipes", "crafting", "processing", "buildings", "toolUpgrades", "tackle", "cheatCodes"),
        SectionTone.MAKE,
    ),
    PanelSection(
        "🏛 收集对照",
        listOf("fishing", "museumMinerals", "museumArtifacts", "geodes", "secretNotes", "shipping", "shops"),
        SectionTone.COLLECT,
    ),
    PanelSection(
        "🎈 生活冒险",
        listOf("skills", "achievements", "festivals", "marriage", "movies", "mines", "monsters", "monsterLoot", "parrotShop", "locations"),
        SectionTone.LIVE,
    ),
)