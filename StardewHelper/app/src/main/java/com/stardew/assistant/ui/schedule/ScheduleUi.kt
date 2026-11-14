package com.stardew.assistant.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.NpcSchedule
import com.stardew.assistant.data.ScheduleSeasons
import com.stardew.assistant.data.ScheduleVariant
import com.stardew.assistant.data.browseVariants
import com.stardew.assistant.data.Season
import com.stardew.assistant.data.WEEKDAY_NAMES
import com.stardew.assistant.data.scheduleClock
import com.stardew.assistant.data.scheduleMinute
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep

/**
 * v2.94.0：行程表的**共用渲染件**。
 *
 * ## 为什么单独一个文件
 *
 * 行程表有两处消费点：独立「NPC 行程表」页，和村民详情里的「🗺 行程」卡。
 * 两处都画同一张时间线——各画一份的话，改了样式只改一处，
 * 于是「独立页的表有高亮、详情页的没有」这种漂移迟早发生。
 * 与其等它发生再回头补，不如一开始就只留一个渲染函数。
 *
 * ## 表格形态
 *
 * 左列是**原始显示串**（`8:00 AM`），不是换算后的 24 小时制。
 * 整理稿给的就是 12 小时制，玩家在游戏里看到的也是 12 小时制；
 * 自己换成 24 小时制等于给用户换了一套需要重新学的读法。
 * 24 小时制只出现在「此刻在哪」这类需要算的地方（[scheduleClock]）。
 */
@Composable
fun ScheduleTable(
    variant: ScheduleVariant,
    modifier: Modifier = Modifier,
    /** 高亮的行下标（-1 = 不高亮）。用于「这个点他在哪」。 */
    highlight: Int = -1,
) {
    val c = LocalPixel.current
    Column(modifier.fillMaxWidth()) {
        if (variant.rows.isEmpty()) {
            PixelText(
                variant.note.ifBlank { "这一段没有时段数据" },
                color = c.inkDim,
                style = Px.caption,
            )
            return@Column
        }
        variant.rows.forEachIndexed { i, row ->
            if (i > 0) RowSep()
            val on = i == highlight
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) c.parchment2 else Color.Transparent)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PixelText(
                    row.t,
                    style = Px.body,
                    color = if (on) c.gold else c.inkDim,
                    modifier = Modifier.width(66.dp),
                )
                PixelText(row.a, style = Px.body)
            }
        }
    }
}

/**
 * 变体的标签行（条件 / 星期 / 日期）。
 *
 * 行程表整理稿把条件写在标题的括号里（「周三（条件：…）」）。
 * 这些条件 App 读不到（好感门槛、事件是否看过），
 * 所以**原样显示**而不是替用户判——判错比不判更坏。
 */
@Composable
fun ScheduleVariantTags(variant: ScheduleVariant, modifier: Modifier = Modifier) {
    val c = LocalPixel.current
    Column(modifier.fillMaxWidth()) {
        if (variant.weekdays.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText("适用：", color = c.inkDim, style = Px.caption)
                Spacer(Modifier.width(4.dp))
                variant.weekdays.sorted().forEach { w ->
                    Chip(WEEKDAY_NAMES[(w - 1).coerceIn(0, 6)], border = c.gold)
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
        if (variant.dates.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText("日期：", color = c.inkDim, style = Px.caption)
                Spacer(Modifier.width(4.dp))
                variant.dates.forEach { d ->
                    val se = ScheduleSeasons.idxOf(d.season)
                    val name = if (se in 0..3) Season.names[se] else d.season
                    val text = if (d.day == d.day2) "$name ${d.day} 日" else "$name ${d.day}-${d.day2} 日"
                    Chip(text, border = c.gold)
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
        if (variant.year > 0) {
            PixelText("仅第 ${variant.year} 年", color = c.inkDim, style = Px.caption)
        }
        if (variant.cond.isNotBlank()) {
            PixelText("条件：${variant.cond}", color = c.inkDim, style = Px.caption)
        }
        if (variant.note.isNotBlank()) {
            PixelText(variant.note, color = c.inkDim, style = Px.caption)
        }
    }
}

/**
 * 一组互斥的变体选择按钮（晴天 / 雨天 / 周三 / 沙漠节…）。
 *
 * 用 Chip 而不是 PixelSegmented：变体数量不定（有的 NPC 一天有 8 个变体），
 * 塞进分段控件会被压成一排看不清的字；Chip 可以换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleVariantPicker(
    variants: List<ScheduleVariant>,
    selectedId: String?,
    onSelect: (ScheduleVariant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPixel.current
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        variants.forEach { v ->
            val on = v.id == selectedId
            // ⚠ [Chip] 本身没有 onClick 参数（它被 20 多处调用，加参数等于让
            //   「哪些 chip 可点」散落到每个调用点）。这里外面包一层 Box 承接手势。
            Box(
                Modifier
                    .clickable { onSelect(v) }
                    .padding(0.dp),
            ) {
                Chip(
                    text = v.label.ifBlank { "（无标题）" },
                    border = if (on) c.gold else c.slotEdge,
                    content = if (on) c.gold else c.inkDim,
                    fill = if (on) c.parchment2 else c.slot,
                )
            }
        }
    }
}

/** 变体的时段摘要：「8:00 AM – 10:00 PM · 6 段」。 */
fun ScheduleVariant.summary(): String {
    val timed = rows.filter { scheduleMinute(it.t) >= 0 }
    if (timed.isEmpty()) return "${rows.size} 条"
    val first = timed.first().t
    val last = timed.last().t
    return if (first == last) "$first · ${timed.size} 段" else "$first – $last · ${timed.size} 段"
}

/** 变体的 24 小时制时段摘要（用于「此刻在哪」旁的说明）。 */
fun ScheduleVariant.clockSpan(): String {
    val timed = rows.filter { scheduleMinute(it.t) >= 0 }
    if (timed.isEmpty()) return ""
    return "${scheduleClock(timed.first().t)} – ${scheduleClock(timed.last().t)}"
}

/**
 * 列表里的显示文字：同名多条时补上条件。
 *
 * 源稿里同一个节日常被拆成两条（「沙漠节·偶数年作为摊主」/「沙漠节·奇数年作为游客」），
 * 标题一样。不补条件的话列表会显示成「沙漠节 / 沙漠节」—— 用户以为重复了。
 */
fun ScheduleVariant.displayLabel(siblings: List<ScheduleVariant>): String {
    val dup = siblings.count { it.label == label } > 1
    return if (dup && cond.isNotBlank()) "$label（$cond）" else label
}

/**
 * 「全部行程」浏览卡：切季 → 选变体 → 看时间线。
 *
 * ## 为什么独立页与村民详情卡共用这一个
 *
 * 两处都要「按季切换、按变体挑、看逐条时段」。各写一份的话，
 * 改了样式只改一处，于是出现「独立页的表有条件标签、详情页的没有」这种漂移——
 * 而且漂移是**静默**的：截图看不出来，用户点得到。
 * 与其等它发生再回头补，不如一开始就只留一个。
 */
@Composable
fun ScheduleBrowseCard(npc: NpcSchedule, modifier: Modifier = Modifier) {
    val c = LocalPixel.current
    var seasonIdx by remember(npc) { mutableStateOf(0) }
    var selectedId by remember(npc) { mutableStateOf<String?>(null) }

    // 只列真有数据的季（有的 NPC 四季都有，有的只有一两季）
    val seasons = remember(npc) {
        listOf(
            ScheduleSeasons.SPRING, ScheduleSeasons.SUMMER,
            ScheduleSeasons.FALL, ScheduleSeasons.WINTER,
        ).filter { s -> npc.browseVariants(s).isNotEmpty() }
    }
    if (seasons.isEmpty()) {
        PixelText("没有分季的行程数据。", color = c.inkDim)
        return
    }
    val season = seasons[seasonIdx.coerceIn(0, seasons.size - 1)]
    val vars = npc.browseVariants(season)
    val cur = vars.firstOrNull { it.id == selectedId } ?: vars.firstOrNull()

    PixelSegmented(
        options = seasons.map { Season.names[ScheduleSeasons.idxOf(it)] },
        selected = seasonIdx.coerceIn(0, (seasons.size - 1).coerceAtLeast(0)),
        onSelect = { seasonIdx = it },
    )
    Spacer(Modifier.height(8.dp))
    ScheduleVariantPicker(vars, cur?.id, onSelect = { selectedId = it.id })
    Spacer(Modifier.height(8.dp))
    if (cur != null) {
        PixelText(cur.label.ifBlank { "行程" })
        ScheduleVariantTags(cur)
        Spacer(Modifier.height(4.dp))
        ScheduleTable(cur)
    }
}
