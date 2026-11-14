package com.stardew.assistant.ui.jump

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.stardew.assistant.Screen
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.ParchmentStrip
import com.stardew.assistant.ui.PixelSearchClear
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.codex.PANEL_SECTIONS
import com.stardew.assistant.ui.codex.PanelPreview
import com.stardew.assistant.ui.codex.rememberCodexIndex
import com.stardew.assistant.ui.codex.toneColor
import com.stardew.assistant.ui.glass.GlassSheet
import com.stardew.assistant.ui.glass.LocalGlassState
import com.stardew.assistant.ui.glass.SheetDrag
import com.stardew.assistant.ui.guide.GUIDE_BOOKS
import com.stardew.assistant.ui.nav.NavRequest
import com.stardew.assistant.ui.nav.navBus
import com.stardew.assistant.ui.search.SEARCH_GROUP_LIMIT
import com.stardew.assistant.ui.search.groupHits
import com.stardew.assistant.ui.search.searchGlobal

/**
 * v2.31「跳到…」面板：长按底 tab 弹出的全局跳板。
 *
 * ## 解决什么
 * 27 个分类、10 本册子、34 位村民之间没有任何直接通路，用户只能靠记忆 +
 * 反复切 tab 试。这个面板把「去哪儿」变成一次搜索。
 *
 * ## 与图鉴内「全部分类」面板的关系
 * 两者**故意不合并**：
 * - 图鉴内的面板负责「在图鉴里换分类」，点完就留在图鉴，附带分组预览；
 * - 这里负责「跳去任意目的地」，还要能跳册子和村民。
 * 职责不同，硬合并会让图鉴内那套已验收的交互变形。
 *
 * 配色与分组**直接复用** `PANEL_SECTIONS` / `toneColor` / `PanelPreview`，
 * 而不是另发明一套——这是把它们从 `CodexPage.kt` 搬出来共享的原因。
 *
 * ## v2.76.0：承载换成玻璃弹层
 * 原来走 `DetailDrawer`（Material3 ModalBottomSheet）。ModalBottomSheet 自带
 * **Dialog 窗口**（独立 window），独立窗口采不到主窗口的背景录制 —— 玻璃没法
 * 「透出身后那一层」。所以改用 `GlassSheet`（主窗口内的覆盖层）。
 *
 * ⚠ **没有玻璃时必须回落到 DetailDrawer**：GlassSheet 没有玻璃时是一层**透明**
 *   覆盖层（底下直接是页面内容），跳板的可读内容大多画在透明底上 —— 透明覆盖层
 *   上读不了字。老设备（API < 33）与「关掉底栏样式」的用户都走这条路。
 *
 * ⚠ 搜索索引**必须留在内容 lambda 里**（不能提到本函数体）：GlassSheet 只在
 *   弹层真正显示时才调用内容 lambda，提到外面就等于「App 一启动就建 823 条
 *   的搜索索引」—— 那是 v2.31 特意用 `if (!visible) return` 省掉的。
 */
@Composable
fun JumpPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    // LocalGlassState 为 null = 没有玻璃（低版本 / 用户关了开关）
    if (LocalGlassState.current != null) {
        GlassSheet(
            visible = visible,
            title = "跳到…",
            onDismiss = onDismiss,
            // c228：贴边模式（上圆下直、贴住屏幕左右与底）——原生 BottomSheet 的站姿。
            //   c227 前是悬浮卡片（左右 12dp 边距）；这轮弹层形状升级的落点。
            docked = true,
        ) { sheetDrag ->
            JumpPanelContent(onDismiss = onDismiss, sheetDrag = sheetDrag)
        }
    } else {
        DetailDrawer(visible = visible, title = "跳到…", onDismiss = onDismiss) {
            JumpPanelContent(onDismiss = onDismiss)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JumpPanelContent(
    onDismiss: () -> Unit,
    /**
     * c229：内容区**不再消费**这个接口做手势接管（方案 A：列表内下拉 = 滚列表，
     * 下滑关闭只留在 GlassSheet 头部窄条）。参数保留是为了与 DetailDrawer 路径
     * 共用一个签名——值传进来也不读；哪天头部拖拽也要内容方配合时再用。
     */
    sheetDrag: SheetDrag? = null,
) {
    var query by remember { mutableStateOf("") }
    // v2.98.0：列表滚动状态。跟手下滑的判定要用它 —— 「列表已到顶」只有这里知道。
    val listState = rememberLazyListState()
    val c = LocalPixel.current
    // ⚠ v2.73.12：必须读**运行时视图**（含 Mod 包的作物/料理/鱼/商店/地点）。
    //   搜索索引 rememberCodexIndex() 已经是运行时视图了，而这里的 groups 负责
    //   「把搜索结果按分类分组」—— 两边口径不一致的话，shops/locations 的命中
    //   会因为找不到对应组而**整组掉出来**（搜得到、列不出来）。
    val groups = GameData.codexGroupsWithMods
    val gifts = GameData.gifts
    val index = rememberCodexIndex()
    val key = query.trim()
    val searching = key.isNotEmpty()

    // 搜索结果全部 remember：每敲一个字重算 823 条会让输入发涩
    val hitGroupDefs = remember(key, groups) {
        if (searching) groups.filter { it.title.contains(key, ignoreCase = true) } else emptyList()
    }
    val hitGroups = remember(key, index, groups) {
        if (searching) groupHits(searchGlobal(index, key), groups) else emptyList()
    }
    val hitBooks = remember(key) {
        if (searching) GUIDE_BOOKS.filter { it.title.contains(key, ignoreCase = true) } else emptyList()
    }
    val hitGifts = remember(key, gifts) {
        if (searching) gifts.filter { it.name.contains(key, ignoreCase = true) } else emptyList()
    }

    // 关闭与发令的顺序：先关面板再发导航。反过来的话面板的退出动画会盖住
    // 「已经跳过去了」那一帧，用户看着像没反应。
    fun go(req: NavRequest) {
        onDismiss()
        navBus.value = req
    }

    Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .background(c.slot)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (query.isEmpty()) {
                    // c223：占位符 inkDim→ink —— inkDim/slot 最低 3.54，ink/slot 16 套 ≥4.5（137.11）
                    PixelText("🔍 搜物品 / 分类 / 册子 / 村民…", color = c.ink)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = Px.body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isNotEmpty()) {
                    PixelSearchClear(
                        onClear = { query = "" },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // c229：内容区**只滚列表，不再下拉面板**（方案 A，用户拍板）。
            //   v2.98.0 的「列表到顶还往下拉 → 拉面板」在 c228 贴边后成了主诉 bug：
            //   面板占 86% 屏高、列表一进来就在顶，第一次下划就吃满 96dp 关闭阈值
            //   → 「跳到页面往下滑会闪」（面板被误关）。原生 BottomSheet 的语义是
            //   **只有头部窄条能下拉关闭**，列表内下拉永远是滚列表 —— 现在回到这个
            //   语义：内容区不挂任何面板拖拽手势，下滑关闭只留在 GlassSheet 的头部。
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!searching) {
                        item(key = "hint") {
                            // c219：面板底是玻璃，裸压的说明/段落标题在日间只有 1.0~2.2:1。
                            //   统一落到米黄纸条上（ink/inkDim/gold/green 压 parchment 已由
                            //   verify 46.1 + 46.5 在 16 套配色钉死 ≥4.5:1）。
                            ParchmentStrip {
                                PixelText(
                                    "长按任意底栏 tab 随时回到这里。共 ${groups.size} 个分类 · " +
                                        "${GUIDE_BOOKS.size} 本册子 · ${gifts.size} 位村民。",
                                    style = Px.caption, color = c.inkDim,
                                )
                            }
                        }
                        // ── v2.99.0 使用指引（设置页里的同一页）──
                        // 放在最上面：这个面板本身就是「不知道东西在哪儿」时的落点，
                        // 给个「功能在哪儿」的入口才顺手。设置页与这里是**同一份数据**。
                        item(key = "guide") {
                            JumpRow(
                                "📖 使用指引",
                                hint = "功能在哪儿",
                                onClick = { go(NavRequest.Stack(Screen.FeatureGuide)) },
                            )
                        }
                        // ── 图鉴分类：按批次 1 的四组上色 ──
                        // ⚠ toneColor 是 @Composable，不能在 LazyListScope 的 forEach 里调
                        //   （forEach 的 lambda 不是 composable 作用域），只能在 item 内取。
                        PANEL_SECTIONS.forEach { sec ->
                            item(key = "sec_${sec.label}") {
                                // v2.97.0：段落标题统一用 ink，分组色交给 chip 边框
                                //   （旧写法把 tone 当文字色，MAKE 段是 c.wood 压木色底会直接隐形）。
                                // c219：标题再落到米黄纸条上 —— 面板底是玻璃，
                                //   ink 裸压只有 1.2~4.2:1，多数配色读不清。
                                ParchmentStrip(Modifier.padding(top = 4.dp)) {
                                    PixelText(sec.label, color = c.ink)
                                }
                            }
                            item(key = "flow_${sec.label}") {
                                val tone = toneColor(sec.tone)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    sec.ids.forEach { id ->
                                        val g = groups.firstOrNull { it.id == id } ?: return@forEach
                                        Column(
                                            Modifier.clickable { go(NavRequest.Codex(groupId = g.id)) }.padding(2.dp),
                                            horizontalAlignment = Alignment.Start,
                                        ) {
                                            Chip("${g.title} ${g.items.size}", border = tone)
                                            Spacer(Modifier.height(1.dp))
                                            PanelPreview(g)
                                        }
                                    }
                                }
                            }
                        }
                        // 兜底：白名单外的分类也要能跳到（新增分类漏写 PANEL_SECTIONS 时）
                        val listed = PANEL_SECTIONS.flatMap { it.ids }.toSet()
                        val extra = groups.filter { it.id !in listed }
                        if (extra.isNotEmpty()) {
                            item(key = "sec_extra") {
                                // c219：面板底是玻璃，段标题裸压读不清 → 米黄纸条。
                                ParchmentStrip {
                                    PixelText("🧩 其他", color = c.ink)
                                }
                            }
                            item(key = "flow_extra") {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    extra.forEach { g ->
                                        Column(
                                            Modifier.clickable { go(NavRequest.Codex(groupId = g.id)) }.padding(2.dp),
                                            horizontalAlignment = Alignment.Start,
                                        ) {
                                            Chip("${g.title} ${g.items.size}", border = c.inkDim)
                                            Spacer(Modifier.height(1.dp))
                                            PanelPreview(g)
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "sep1") { RowSep() }

                        // ── 攻略册 ──
                        item(key = "sec_books") {
                            ParchmentStrip { PixelText("📖 攻略", color = c.gold) }
                        }
                        items(GUIDE_BOOKS.size, key = { "b_${GUIDE_BOOKS[it].key}" }) { i ->
                            val b = GUIDE_BOOKS[i]
                            JumpRow("${b.emoji} ${b.title}", hint = "跳到这一本", onClick = { go(b.nav) })
                        }
                        item(key = "sep2") { RowSep() }

                        // ── 村民 ──
                        item(key = "sec_gifts") {
                            ParchmentStrip { PixelText("👥 村民", color = c.green) }
                        }
                        items(gifts.size, key = { "v_${gifts[it].name}" }) { i ->
                            val v = gifts[i]
                            JumpRow(
                                v.name,
                                // c204：吉尔/冈瑟/马龙没有生日，写「无生日」不如写清楚
                                //   「不接受友谊礼物」——这是速查面板，一眼能看出差别。
                                hint = if (!v.acceptsGifts) "不接受友谊礼物"
                                else birthdayText(v.birthdaySeason, v.birthdayDay),
                                onClick = { go(NavRequest.Villager(v.name)) },
                            )
                        }
                    } else {
                        if (hitGroupDefs.isEmpty() && hitGroups.isEmpty() && hitBooks.isEmpty() && hitGifts.isEmpty()) {
                            item(key = "none") {
                                // c219：空态/分组标题都不再裸压玻璃底。
                                ParchmentStrip {
                                    PixelText("没找到「$key」——试试物品名（史莱姆泥）、分类名（鱼）或册名（姜岛）。", color = c.inkDim)
                                }
                            }
                        }
                        // 分类整体（搜「鱼」既能命中分类也能命中鱼，两种都列）
                        hitGroupDefs.forEach { g ->
                            item(key = "gd_${g.id}") {
                                JumpRow(
                                    "📕 ${g.title}",
                                    hint = "整个分类 · ${g.items.size} 条",
                                    onClick = { go(NavRequest.Codex(groupId = g.id)) },
                                )
                            }
                        }
                        // 条目（按分类分组，顺序由 codexGroups 驱动）
                        hitGroups.forEach { (g, hits) ->
                            item(key = "gh_${g.id}") {
                                ParchmentStrip(Modifier.padding(top = 4.dp)) {
                                    PixelText(
                                        "📂 ${g.title}（${hits.size}）",
                                        color = c.gold,
                                    )
                                }
                            }
                            val shown = hits.take(SEARCH_GROUP_LIMIT)
                            items(shown.size, key = { "hi_${g.id}_${shown[it].index}" }) { i ->
                                val hit = shown[i]
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(c.parchment)
                                        .clickable {
                                            go(NavRequest.Codex(groupId = hit.groupId, targetIndex = hit.index))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FramedIcon(hit.itemName, 26)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        PixelText(hit.itemName)
                                        if (hit.summary.isNotBlank()) {
                                            PixelText(hit.summary, style = Px.caption, color = c.inkDim)
                                        }
                                    }
                                }
                            }
                            if (hits.size > shown.size) {
                                item(key = "hm_${g.id}") {
                                    // c223：inkDim 裸压玻璃/木壳面板底 1.03 → 上米黄纸条
                                    ParchmentStrip(Modifier.padding(top = 2.dp)) {
                                        PixelText(
                                            "…还有 ${hits.size - shown.size} 条",
                                            style = Px.caption, color = c.inkDim,
                                        )
                                    }
                                }
                            }
                        }
                        hitBooks.forEach { b ->
                            item(key = "hb_${b.key}") {
                                JumpRow("${b.emoji} ${b.title}", hint = "攻略册", onClick = { go(b.nav) })
                            }
                        }
                        hitGifts.forEach { v ->
                            item(key = "hv_${v.name}") {
                                JumpRow(
                                    v.name,
                                    hint = birthdayText(v.birthdaySeason, v.birthdayDay),
                                    onClick = { go(NavRequest.Villager(v.name)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

/** 面板里的一行：左标题 + 右灰字提示，整行可点 */
@Composable
private fun JumpRow(title: String, hint: String, onClick: () -> Unit) {
    val c = LocalPixel.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.parchment)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(title, modifier = Modifier.weight(1f))
        if (hint.isNotBlank()) PixelText(hint, style = Px.caption, color = c.inkDim)
    }
}

/** 生日 → 「春 14 日」。season 直接用数据包里的原文，不自己编月份名
 *  （编错一个月份比不显示更糟）；缺任一半就不显示。 */
private fun birthdayText(season: String?, day: Int?): String {
    if (season.isNullOrBlank() || day == null) return ""
    return "$season $day 日"
}