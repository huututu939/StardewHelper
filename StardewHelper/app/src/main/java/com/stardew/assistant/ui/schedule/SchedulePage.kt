package com.stardew.assistant.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.NpcSchedule
import com.stardew.assistant.data.NpcSchedules
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.ScheduleContext
import com.stardew.assistant.data.ScheduleSeasons
import com.stardew.assistant.data.ScheduleVariant
import com.stardew.assistant.data.ScheduleWeather
import com.stardew.assistant.data.WEEKDAY_NAMES
import com.stardew.assistant.data.applicable
import com.stardew.assistant.data.marriedVariants
import com.stardew.assistant.data.searchSchedules
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelCheckbox
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelSearchClear
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.WheelDrawer
import com.stardew.assistant.ui.WheelSpec
import com.stardew.assistant.ui.glass.LocalGlassInset
import com.stardew.assistant.ui.mods.VillagerAvatar
import com.stardew.assistant.ui.pressFeedback
import kotlinx.coroutines.launch

/**
 * v2.94.0：NPC 行程表页。
 *
 * ## 这一页回答两个问题
 *
 * ① **「今天他在哪」** —— 给日期（默认取今日页设置的游戏日期）+ 天气，
 *    按整理稿写明的优先级（特殊日期 > 绿雨 > 雨天 > 特殊星期 > 常规）推出每位 NPC 今天的表。
 * ② **「他平时去哪」** —— 搜行程关键词（地点 / 节日 / 行为），一次搜出所有人的相关时段。
 *
 * ## 为什么日期用 [PrefsRepo.Snapshot] 而不是存档
 *
 * 存档里有年/季/日（[com.stardew.assistant.data.WorldStateParser] 解析得出），
 * 但**没有「现在几点」**——游戏每次启动按当天的随机种子重算天气，存档里不留。
 * 所以天气只能让玩家自己选；日期则直接沿用 App 已有的那一份
 * （今日页顶部那个「春 15 · 第 1 年 ▾」），不另造第二份真相。
 *
 * ## 推算结果的性质
 *
 * 行程是**推算**的，不是存档里读出来的。条件分支（好感门槛、事件是否看过、
 * 巴士是否修好、社区中心是否修复）App 读不到，
 * 所以页面上明写「以游戏内为准」，不假装自己知道。
 */
@Composable
fun SchedulePage(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    onBack: () -> Unit,
) {
    val c = LocalPixel.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // rememberSaveable：压栈页退栈后重建，选择不该丢（用户退出去看一眼再回来，
    // 还得重新选一遍天气是很烦人的）
    var weather by rememberSaveable { mutableStateOf(ScheduleWeather.CLEAR) }
    var married by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }

    val ctx = ScheduleContext(
        year = snap.year,
        seasonIdx = snap.season,
        day = snap.day,
        weather = weather,
        married = married,
    )

    // 34 位 NPC 的「今天适用哪张表」在上下文不变时算一次就够
    val picks = remember(ctx, NpcSchedules.npcs) {
        NpcSchedules.npcs.map { it to it.applicable(ctx) }
    }
    val hits = remember(query) { searchSchedules(query) }

    Column(Modifier.fillMaxSize()) {
        BackBar("NPC 行程表", onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, top = 10.dp, end = 12.dp,
                bottom = 10.dp + LocalGlassInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                PixelCard(title = "📅 今天") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Box(
                            Modifier
                                .background(c.slot)
                                .border(2.dp, c.slotEdge)
                                .pressFeedback(isrc)
                                .clickable(interactionSource = isrc, indication = null) { showDate = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            PixelText(
                                "第 ${snap.year} 年 · ${com.stardew.assistant.data.Season.names[snap.season]} ${snap.day} 日 · ${WEEKDAY_NAMES[ctx.weekday - 1]}",
                                color = c.ink,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        PixelText("点这里改日期", color = c.inkDim, style = Px.caption)
                    }
                    Spacer(Modifier.height(6.dp))
                    PixelSegmented(
                        options = ScheduleWeather.LABELS.map { it.first },
                        selected = ScheduleWeather.LABELS.indexOfFirst { it.second == weather },
                        onSelect = { weather = ScheduleWeather.LABELS[it].second },
                    )
                    Spacer(Modifier.height(6.dp))
                    PixelCheckbox(married, "婚后行程") { married = it }
                    Spacer(Modifier.height(6.dp))
                    PixelText("优先级：特殊日期 > 绿雨 > 雨天 > 特殊星期 > 常规", color = c.inkDim, style = Px.caption)
                    // c226 瘦身：条件分支以游戏内为准，页尾已有同句（「行程表按原版规则整理…」），不重复。
                }
            }
            item {
                PixelSegmented(
                    options = listOf("按人查", "搜行程"),
                    selected = mode,
                    onSelect = { mode = it },
                )
            }
            if (NpcSchedules.loadError != null) {
                item {
                    PixelCard(title = "⚠ 数据加载失败") {
                        PixelText(NpcSchedules.loadError ?: "", color = c.red)
                        PixelText("行程表读取失败，本页暂时没有内容。", color = c.inkDim)
                    }
                }
            } else if (mode == 0) {
                // c191 性能：稳定 key——npc 名全局唯一（同村民列表同款保证）
                items(picks.size, key = { picks[it].first.name }) { i ->
                    val (npc, pick) = picks[i]
                    NpcScheduleRow(npc, pick.primary, pick.fallbackNote) { target = npc.name }
                }
                item {
                    PixelText(
                        "原版 ${NpcSchedules.npcs.size} 位 NPC · ${NpcSchedules.rowCount} 条时段 · 不含扩展包村民",
                        color = c.inkDim,
                        style = Px.caption,
                    )
                }
            } else {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(c.slot)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        if (query.isEmpty()) {
                            PixelText("输地点 / 节日 / 行为…（如「皮埃尔」「沙漠节」）", color = c.inkDim)
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
                }
                if (query.trim().isNotEmpty()) {
                    item {
                        PixelText(
                            "命中 ${hits.size} 条时段 · 搜的是行程原文，不是抽出来的地名",
                            color = c.inkDim,
                            style = Px.caption,
                        )
                    }
                    // c191 性能：稳定 key——npc+变体 id 复合唯一（变体在 npc 内有 id 字段）；
                    // 同一变体多行命中时按下标去重（同 variant 的多行内容不同但身份相同，
                    // 用下标后缀避免撞 key）。
                    // c191 性能：稳定 key——npc+变体 id 复合唯一（变体在 npc 内有 id 字段）；
                    // 同一变体多行命中时内容不同但身份同源，追加下标后缀防撞 key。
                    items(hits.size, key = { "${hits[it].npc}/${hits[it].variant.id}#$it" }) { i ->
                        val h = hits[i]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(c.parchment)
                                .border(2.dp, c.woodDark)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PixelText(h.npc)
                                Spacer(Modifier.width(6.dp))
                                Chip(h.variant.label.ifBlank { "行程" }, border = c.gold)
                            }
                            if (h.row != null) {
                                PixelText("${h.row.t} · ${h.row.a}")
                            } else {
                                PixelText(h.variant.note.ifBlank { "这一段没有时段数据" }, color = c.inkDim)
                            }
                        }
                    }
                }
            }
        }
    }

    // 日期抽屉：与今日页同一套（改的是同一份游戏日期，不另造真相）
    WheelDrawer(
        visible = showDate,
        title = "调整游戏日期",
        wheels = listOf(
            WheelSpec("年", (1..10).map { "第 $it 年" }, snap.year - 1),
            WheelSpec("季", listOf("春", "夏", "秋", "冬"), snap.season),
            WheelSpec("日", (1..28).map { "$it 日" }, snap.day - 1),
        ),
        onDismiss = { showDate = false },
        onConfirm = { list ->
            showDate = false
            scope.launch { repo.setDate(list[0] + 1, list[1], list[2] + 1) }
        },
    )

    val t = target
    if (t != null) {
        val npc = NpcSchedules.byName(t)
        DetailDrawer(
            visible = true,
            title = t,
            onDismiss = { target = null },
        ) {
            if (npc == null) {
                PixelText("没有这位 NPC 的行程数据。")
            } else {
                NpcScheduleDetail(npc, ctx)
            }
        }
    }
}

/**
 * 列表行：头像 + 名字 + 今天适用哪张表 + 时段跨度。
 *
 * ⚠ 列表**不显示行为原文**：34 位 × 每位 4~8 段行为，首屏会被拉成长条，
 *   扫读不下去。这里只给「哪张表 + 什么时候到什么时候」，点开才看逐条行为。
 */
@Composable
private fun NpcScheduleRow(
    npc: NpcSchedule,
    primary: List<ScheduleVariant>,
    fallbackNote: String,
    onClick: () -> Unit,
) {
    val c = LocalPixel.current
    val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.parchment)
            .border(2.dp, c.woodDark)
            .pressFeedback(isrc)
            .clickable(interactionSource = isrc, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VillagerAvatar(npc.name, 34)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelText(npc.name)
                    if (primary.size > 1) {
                        Spacer(Modifier.width(5.dp))
                        Chip("多套", border = c.gold)
                    }
                }
                if (primary.isEmpty()) {
                    PixelText("今天没有行程数据", color = c.inkDim, style = Px.caption)
                } else {
                    PixelText(
                        primary.joinToString(" / ") { it.displayLabel(primary) },
                        color = c.ink,
                        style = Px.caption,
                    )
                    PixelText(
                        primary.joinToString(" · ") { it.summary() },
                        color = c.inkDim,
                        style = Px.caption,
                    )
                }
                if (fallbackNote.isNotBlank()) {
                    PixelText(fallbackNote, color = c.inkDim, style = Px.caption)
                }
            }
        }
    }
}

/**
 * 抽屉正文：今天适用什么 + 全季所有变体（可切季、可换表）。
 *
 * 两块都在，因为它们回答的是**两个不同的问题**：
 * 「今天他在哪」（第一块）与「他这一季都在哪、有哪些分支」（第二块）。
 * 只给前者，用户问「周三他在哪」就没法答；只给后者，等于把判断推回用户。
 */
@Composable
private fun NpcScheduleDetail(npc: NpcSchedule, ctx: ScheduleContext) {
    val c = LocalPixel.current
    val pick = remember(ctx, npc) { npc.applicable(ctx) }
    val marriedVars = remember(npc) { npc.marriedVariants() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (npc.note.isNotBlank()) {
            item {
                PixelText(npc.note, color = c.inkDim, style = Px.caption)
            }
        }
        item {
            PixelCard(title = "📅 今天适用") {
                if (pick.primary.isEmpty()) {
                    PixelText("这一季没有行程数据。", color = c.inkDim)
                } else {
                    pick.primary.forEachIndexed { i, v ->
                        if (i > 0) {
                            Spacer(Modifier.height(6.dp))
                            RowSep()
                            Spacer(Modifier.height(6.dp))
                        }
                        PixelText(v.label.ifBlank { "行程" })
                        ScheduleVariantTags(v)
                        Spacer(Modifier.height(4.dp))
                        ScheduleTable(v)
                    }
                    if (pick.fallbackNote.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        PixelText(pick.fallbackNote, color = c.inkDim, style = Px.caption)
                    }
                }
            }
        }
        if (ctx.married && marriedVars.isNotEmpty()) {
            item {
                PixelCard(title = "💍 婚后") {
                    marriedVars.forEachIndexed { i, v ->
                        if (i > 0) {
                            Spacer(Modifier.height(6.dp))
                            RowSep()
                            Spacer(Modifier.height(6.dp))
                        }
                        PixelText(v.label.ifBlank { "婚后行程" })
                        ScheduleVariantTags(v)
                        Spacer(Modifier.height(4.dp))
                        ScheduleTable(v)
                    }
                }
            }
        }
        item {
            PixelCard(title = "📖 全部行程") {
                ScheduleBrowseCard(npc)
            }
        }
        item {
            PixelText(
                "行程表按原版规则整理；条件分支与节日临时安排以游戏内为准。",
                color = c.inkDim,
                style = Px.caption,
            )
        }
    }
}
