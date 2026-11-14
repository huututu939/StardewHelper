package com.stardew.assistant.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.FarmRow
import com.stardew.assistant.data.FarmToday
import com.stardew.assistant.data.FarmTodo
import com.stardew.assistant.data.formatG
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep

/**
 * 「我的农场」视图（今日页第二个分段）。
 *
 * ## 四块，一个都不能少也不能多
 * 今天能收的（含产值） / 马上要熟的 / 枯死待清理 / 牲畜今日待办。
 * **某块没有数据就整块不显示**（不显示「0」——「没有枯死的作物」不是一条消息）。
 *
 * ## 数字是主角
 * 品种 × 数量 × 单价 挤在一行（这是工具页，不是海报），数字用等宽字不抖，
 * 总产值用 Px.big 的大字。
 *
 * ## 两级色板
 * 绿=可收 / 金=马上熟 / 红=枯死 / 中性棕=其它。没有第三种色。
 */
/**
 * c200 A1：农场缓存的**三态**。
 *
 * 原来只有「有模型 / null」两种，而 null 同时是「还在读」与「真没导入」——
 * UI 分不清就会把「还在读」画成「还没有导入存档」，导入过的用户切过来先闪一下
 * 「你没导入」。拆成三态后：读取中画「正在读取…」，读到空才画「还没导入」。
 */
sealed interface FarmLoad {
    /** 正在读缓存（首次进入 / 从设置页返回时重读）。 */
    data object Loading : FarmLoad

    /** 读完了。[model] 为 null = 真的没导入过存档。 */
    data class Loaded(val model: FarmToday?) : FarmLoad
}

/**
 * @param loading 还在读缓存。此时**不许**画「还没导入存档」——
 *   那是把「没读到」说成「没有」，用户会以为导入没成功（c200 A1）。
 * @param profileName 当前存档名（c217）。显示在农场卡头部 ——
 *   缓存按存档分文件后，一眼要能看出「这份数据属于哪个存档」。
 */
@Composable
fun FarmTodayView(
    model: FarmToday?,
    loading: Boolean,
    onImport: () -> Unit,
    profileName: String = "",
) {
    val c = LocalPixel.current
    // P3-a：农场地图的折叠态。**提到 if 外面** —— 放在 if 里的话，
    // 「有没有地图」一变，remember 的组合位置就变了，展开态会被丢掉。
    var mapOpen by remember { mutableStateOf(false) }
    if (loading) {
        PixelCard(title = "🌾 今日农场") {
            PixelText("正在读取存档…", color = c.ink)
            Spacer(Modifier.height(4.dp))
            PixelText(
                "存档只在手机本地读取，不上传。",
                style = Px.caption, color = c.inkDim,
            )
        }
        return
    }
    if (model == null) {
        PixelCard(title = "🌾 今日农场") {
            PixelText("还没有导入存档。", color = c.ink)
            Spacer(Modifier.height(4.dp))
            PixelText(
                "导入你的存档，这里会显示这张农场今天该收什么、收完多少钱、哪些该清理、哪只该摸。",
                style = Px.caption, color = c.inkDim,
            )
            Spacer(Modifier.height(4.dp))
            PixelText("存档只在手机本地读取，不上传。", style = Px.caption, color = c.inkDim)
            Spacer(Modifier.height(8.dp))
            PixelButton("📂 导入存档", onClick = onImport, active = true)
        }
        return
    }

    // ── 头部：这是谁的农场、哪天、多少钱 ────────────────────────────
    PixelCard(title = "🌾 今日农场") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(model.farmer, style = Px.title)
            Spacer(Modifier.width(8.dp))
            PixelText(
                "第${model.year ?: "?"}年 ${model.season}${model.day ?: "?"}日",
                style = Px.caption, color = c.inkDim,
            )
            Spacer(Modifier.weight(1f))
            PixelText("${formatG(model.money ?: 0)}g", style = Px.title, color = c.gold)
        }
        // c217：标出这是**哪个存档**的农场。缓存按存档分文件后，一眼要能看出
        //   「这份数据属于哪个存档」—— 否则串档了也只能靠数字猜。
        if (profileName.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            PixelText("存档：$profileName", style = Px.caption, color = c.inkDim)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            PixelText("${model.readyStocks}", style = Px.big, color = c.gold)
            Spacer(Modifier.width(4.dp))
            PixelText("株今天能收", color = c.inkDim)
        }
    }

    // ── 农场地图（P3-a）────────────────────────────────────────────
    //    放在头部之后、清单之前：先看清「我的地在哪、长什么样」，
    //    再看「今天该收什么」。默认折叠 —— 地图是偶尔看的，今天的清单才是每次看的。
    //    地图数据没有（旧缓存 / 没有可解析的耕地）→ 整块不显示。
    if (model.maps.isNotEmpty()) {
        FarmMapCard(model.maps, mapOpen) { mapOpen = !mapOpen }
    }

    // ── 今天能收的 ────────────────────────────────────────────────
    if (model.harvest.isNotEmpty() || model.unnamedReady > 0) {
        PixelCard(title = "🧺 今天能收的", accent = c.green) {
            Row(verticalAlignment = Alignment.Bottom) {
                PixelText(formatG(model.harvestTotal), style = Px.big, color = c.gold)
                Spacer(Modifier.width(4.dp))
                PixelText("g", color = c.inkDim)
            }
            PixelText("普通品质 · 不含农耕人加成", style = Px.caption, color = c.inkDim)
            Spacer(Modifier.height(4.dp))
            model.harvest.forEachIndexed { i, r ->
                if (i > 0) RowSep()
                HarvestLine(r)
            }
            if (model.unnamedReady > 0) {
                Spacer(Modifier.height(4.dp))
                RowSep()
                Spacer(Modifier.height(4.dp))
                PixelText(
                    // c226 瘦身：「存档里没写物品 id」是实现细节，玩家只需知道这些不计钱。
                    "另有 ${model.unnamedReady} 株野生采集物（查不到名字与售价，不计入产值）",
                    style = Px.caption, color = c.inkDim,
                )
            }
        }
    }

    // ── 马上要熟的 ────────────────────────────────────────────────
    if (model.soon.isNotEmpty()) {
        PixelCard(title = "🌱 马上要熟的", accent = c.gold) {
            model.soon.forEachIndexed { i, r ->
                if (i > 0) RowSep()
                SoonLine(r)
            }
        }
    }

    // ── 枯死待清理 ────────────────────────────────────────────────
    if (model.dead.isNotEmpty()) {
        PixelCard(title = "💀 枯死待清理", accent = c.red) {
            model.dead.forEachIndexed { i, r ->
                if (i > 0) RowSep()
                DeadLine(r)
            }
        }
    }

    // ── 牲畜今日待办（一只都没有 → 整块不显示）─────────────────────
    if (model.animalTotal > 0) {
        PixelCard(title = "🐄 牲畜今日待办") {
            if (model.todos.isEmpty()) {
                PixelText("全部状态良好，无需处理", color = c.ink)
            } else {
                PixelText(
                    "${model.animalTotal} 只 · ${model.todos.size} 只要动手",
                    style = Px.caption, color = c.inkDim,
                )
                Spacer(Modifier.height(2.dp))
                model.todos.forEachIndexed { i, t -> TodoLine(t) }
            }
        }
    }
}

/** 今天能收的一行：地点 / 品种 / ×数量 / 单价 / 合计。 */
@Composable
private fun HarvestLine(r: FarmRow) {
    val c = LocalPixel.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PixelText(r.location, style = Px.caption, color = c.inkDim, modifier = Modifier.width(30.dp))
        PixelText(
            r.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PixelText(
            "×${r.count}", color = c.inkDim,
            modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
        )
        PixelText(
            r.unitPrice?.let { "$it g" } ?: "—", style = Px.caption, color = c.inkDim,
            modifier = Modifier.width(46.dp), textAlign = TextAlign.End,
        )
        PixelText(
            if (r.unitPrice != null) "${formatG(r.total)}g" else "—",
            color = c.gold, modifier = Modifier.width(66.dp), textAlign = TextAlign.End,
        )
    }
}

/** 马上要熟的一行：地点 / 品种 / ×数量 / 还剩几天。 */
@Composable
private fun SoonLine(r: FarmRow) {
    val c = LocalPixel.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PixelText(r.location, style = Px.caption, color = c.inkDim, modifier = Modifier.width(30.dp))
        PixelText(
            r.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PixelText(
            "×${r.count}", color = c.inkDim,
            modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
        )
        PixelText(
            "还剩 ${r.daysLeft ?: "?"} 天", style = Px.caption, color = c.gold,
            modifier = Modifier.width(60.dp), textAlign = TextAlign.End,
        )
    }
}

/** 枯死待清理的一行：地点 / 品种 / ×数量 / 状态。 */
@Composable
private fun DeadLine(r: FarmRow) {
    val c = LocalPixel.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PixelText(r.location, style = Px.caption, color = c.inkDim, modifier = Modifier.width(30.dp))
        PixelText(
            r.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PixelText(
            "×${r.count}", color = c.red,
            modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
        )
        PixelText(
            "换季没收", style = Px.caption, color = c.red,
            modifier = Modifier.width(60.dp), textAlign = TextAlign.End,
        )
    }
}

/** 牲畜待办的一行：名字 / 品种·好感 / 要做什么。 */
@Composable
private fun TodoLine(t: FarmTodo) {
    val c = LocalPixel.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PixelText(
            t.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PixelText(
            "${t.type} · 好感${t.friendship ?: "?"}",
            style = Px.caption, color = c.inkDim,
            modifier = Modifier.width(112.dp), textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(6.dp))
        PixelText(t.action, style = Px.caption, color = c.gold)
    }
}

/**
 * 千分位（App 里金额都是 g，13,265 比 13265 好扫）。
 *
 * ⚠ v2.100.0 / c181：**这个格式化搬到数据层**（[com.stardew.assistant.data.formatG]）了 ——
 *   今日页的建议区要显示同一批金额（现金、产值），两处各写一份格式化就会出现
 *   「同一屏上同一个数两种写法」（52,250g 与 52250 g），那正是「同源」纪律要防的。
 *   搬完两边共用一份实现，数字与写法都只有一个出处。
 */

