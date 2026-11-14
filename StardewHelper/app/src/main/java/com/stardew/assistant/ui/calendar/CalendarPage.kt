package com.stardew.assistant.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.Season
import com.stardew.assistant.data.calcProgress
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.glass.LocalGlassInset
import com.stardew.assistant.ui.progress.unlockReached

/** 星期表头：游戏第 1 天 = 周一（口径同今日页 `(day-1)%7`，勿改） */
private val WEEK_HEADS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 明细区单类事件最多平铺几条，剩余折成「还有 N 种」 */
private const val MAX_FLAT = 8

/**
 * v2.25 年历页：游戏内 112 天历法。
 *
 * 只标**日期确定**的事件（节日 / 生日 / 最晚下种日）。
 * 天气、当天钓什么鱼、今日幸运一概没有——那些是游戏内随机的，App 不知道，编不得。
 *
 * v2.35：[onBack] 由 [GuideSubPage] 传入——年历是从攻略首页压栈进来的二级页，
 * 顶栏与另外九本一样是 BackBar（原来画的是 PageBar，页内没有返回入口）。
 * 右侧「第 N 年」徽章通过 BackBar 的 right 槽保留。
 */
@Composable
fun CalendarPage(snap: PrefsRepo.Snapshot, repo: PrefsRepo, onBack: () -> Unit) {
    val c = LocalPixel.current
    val st = remember(snap.checks) { calcProgress(snap) }

    // 选中季：默认落在存档当前季
    var seasonIdx by rememberSaveable { mutableIntStateOf(snap.season) }
    // 选中日：跨季切换时重置为 1（rememberSaveable 键带 season，避免沿用上一季不存在的日号）
    var selDay by rememberSaveable(seasonIdx) { mutableIntStateOf(1) }

    // 事件索引：按存档职业算一次，切换选中季/日不再重扫 42 作物 × 36 村民
    // v2.38：remember key 必须带 snap.year——肯特第 1 年与第 2 年的生日格子内容不同
    // （第 1 年他不在场），而 seasonIdx/selDay 在两年可能完全一样，不带年份会算错。
    val cal = remember(snap.prof10, snap.year) { buildCalendar(snap.prof10, snap.year) }
    val isCurrentSeason = seasonIdx == snap.season

    Column(Modifier.fillMaxSize()) {
        BackBar("📅 年历", onBack) {
            Chip("第 ${snap.year} 年")
        }

        // v2.72.2：年历是 verticalScroll 不是 LazyColumn，写法对应 contentPadding.bottom——
        // padding 必须**排在 verticalScroll 之后**才是滚动内容的一部分（可滚过去的空白）；
        // 排在前面就成了压缩视口，内容会被拦腰截断（v2.72.2 初稿在攻略页踩过这个坑）。
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalGlassInset.current),
        ) {
            PixelSegmented(
                options = Season.names.toList(),
                selected = seasonIdx,
                onSelect = { seasonIdx = it },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(10.dp))

            // ── 星期表头 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (w in WEEK_HEADS) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        PixelText(w, style = Px.caption, color = c.inkDim, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // ── 28 格：4 行 × 7 列 ──
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (row in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (col in 0 until 7) {
                            val day = row * 7 + col + 1
                            val isToday = isCurrentSeason && day == snap.day
                            DayCell(
                                day = day,
                                events = cal.of(seasonIdx, day),
                                selected = day == selDay,
                                isToday = isToday,
                                onClick = { selDay = day },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            PixelText(
                "${Season.names[seasonIdx]}季 · 有事件 ${cal.seasonDays(seasonIdx)} 天　" +
                    "● 节日　● 生日　● 末种日",
                style = Px.caption, color = c.inkDim,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(12.dp))

            // ── 选中日明细 ──
            val sel = cal.of(seasonIdx, selDay)
            val festivals = sel.filter { it.kind == CalEventKind.FESTIVAL }
            val birthdays = sel.filter { it.kind == CalEventKind.BIRTHDAY }
            val deads = sel.filter { it.kind == CalEventKind.SOW_DEADLINE }

            PixelCard(
                title = "${Season.names[seasonIdx]} ${selDay} 日 · 这天的事件",
                accent = if (isCurrentSeason && selDay == snap.day) c.gold else null,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                if (sel.isEmpty()) {
                    PixelText("这天没有特殊事件", color = c.inkDim)
                }

                if (festivals.isNotEmpty()) {
                    PixelText("🎪 节日", color = c.gold)
                    festivals.forEach { e ->
                        Spacer(Modifier.height(3.dp))
                        PixelText(e.title, style = Px.body)
                        PixelText(e.detail, style = Px.caption, color = c.inkDim)
                    }
                }

                if (birthdays.isNotEmpty()) {
                    if (festivals.isNotEmpty()) RowSep()
                    PixelText("🎂 生日", color = c.blue)
                    birthdays.forEach { e ->
                        Spacer(Modifier.height(3.dp))
                        PixelText(e.title, style = Px.body)
                        PixelText(e.detail, style = Px.caption, color = c.inkDim)
                    }
                }

                if (deads.isNotEmpty()) {
                    if (festivals.isNotEmpty() || birthdays.isNotEmpty()) RowSep()
                    PixelText("🌱 这天是末种日", color = c.red)
                    PixelText(
                        "${deads.size} 种作物这天再不种就赶不上了",
                        style = Px.caption, color = c.inkDim,
                    )
                    Spacer(Modifier.height(4.dp))
                    deads.take(MAX_FLAT).forEach { e ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            PixelText("· ${e.title.removeSuffix(" 末种日")}", style = Px.body)
                            Spacer(Modifier.width(8.dp))
                            PixelText(e.detail, style = Px.caption, color = c.inkDim)
                        }
                    }
                    if (deads.size > MAX_FLAT) {
                        PixelText("…还有 ${deads.size - MAX_FLAT} 种，去「今日 → 播种死线」看全部", style = Px.caption, color = c.inkDim)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 解锁清单（无固定日期，不落格；判定复用进度页口径）──
            PixelCard(
                title = "🔓 解锁清单",
                titleIcon = "community_center",
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                val doneCount = GameData.unlockCards.count { unlockReached(it.checkKey, st, snap) || "unlock:${it.id}" in snap.checks }
                PixelText("已达成 $doneCount / ${GameData.unlockCards.size}", style = Px.caption, color = c.inkDim)
                GameData.unlockCards.forEach { card ->
                    val auto = unlockReached(card.checkKey, st, snap)
                    val done = auto || "unlock:${card.id}" in snap.checks
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelText(if (done) "✔" else "▸", color = if (done) c.green else c.inkDim)
                        Spacer(Modifier.width(6.dp))
                        PixelText(card.name, color = if (done) c.green else c.ink)
                    }
                    PixelText("前置：${card.req}", style = Px.caption, color = c.inkDim, modifier = Modifier.padding(start = 18.dp))
                    if (card.cost.isNotBlank()) {
                        PixelText("花费：${card.cost}", style = Px.caption, color = c.inkDim, modifier = Modifier.padding(start = 18.dp))
                    }
                    if (card.after.isNotBlank()) {
                        PixelText("达成后：${card.after}", style = Px.caption, color = c.inkDim, modifier = Modifier.padding(start = 18.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                PixelText("打勾在「进度 → 解锁」里做，这里只读显示状态。", style = Px.caption, color = c.inkDim)
            }

            Spacer(Modifier.height(10.dp))
            PixelText(
                "天气、今天钓什么鱼、今日幸运这类随机结果不在日历里——游戏里它们是随机的，助手不猜。",
                style = Px.caption, color = c.inkDim,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 日历格子：日号 + 最多 3 个事件点。RowScope 扩展是为了拿到 weight。 */
@Composable
private fun RowScope.DayCell(
    day: Int,
    events: List<CalEvent>,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalPixel.current
    val bg = when {
        selected -> c.slot
        isToday -> c.parchment2
        else -> c.parchment
    }
    Column(
        Modifier
            .weight(1f)
            .height(46.dp)
            .background(bg)
            .border(if (isToday) 2.dp else 1.dp, if (isToday) c.gold else c.woodDark)
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        PixelText(
            "$day",
            style = Px.caption,
            color = if (isToday) c.red else c.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (events.isNotEmpty()) {
            Row(
                Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (e in events.take(3)) {
                    Box(
                        Modifier.size(5.dp).background(dotColor(e.kind, c)),
                    )
                }
            }
            if (events.size > 3) {
                PixelText("+${events.size - 3}", style = Px.caption, color = c.inkDim)
            }
        }
    }
}

private fun dotColor(kind: CalEventKind, c: com.stardew.assistant.ui.PixelColors): Color = when (kind) {
    CalEventKind.FESTIVAL -> c.gold
    CalEventKind.BIRTHDAY -> c.blue
    CalEventKind.SOW_DEADLINE -> c.green
    CalEventKind.UNLOCK -> c.red
}