package com.stardew.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.stardew.assistant.data.UpdateRepo

/**
 * v2.61 更新提示横幅：挂在**今日页**顶部（节日彩蛋横幅的下一行，v2.69 起两者并存）。
 *
 * ## 为什么放今日页
 * 今日页是唯一**每天必看**的页面。放设置页等于没做——用户几个月不知道有新版。
 *
 * ## 什么时候不显示
 * ① [latest] 为 null（没有新版 / 还没查 / 查失败）；② 用户已经划掉过**这个版本号**
 * （划掉 v2.61 后发 v2.62 还会再出现，这才对）；③ 用户关掉了「更新提示」开关
 * （那就不该有请求发生，横幅自然也没数据）。
 *
 * ## 与节日横幅的关系（v2.69 改）
 * 原来是「节日存在时更新提示**整条不渲染**」——那是我自己写的让位逻辑，理由是节日更有时效。
 * 但"让位"的正确做法是**节日排在上面、更新排在下面**，不是二选一：节日一年就那么几天，
 * 而漏掉一次更新提示的代价（用户停在旧版几个月）远大于今天多占一屏。
 *
 * @param onOpen 点横幅正文 → 跳设置页看完整更新说明
 * @param onDownload 点「点这里下载」→ 直接拉起浏览器下载（v2.69，不再要求用户先进设置页找按钮）
 * @param onDismiss 点 ✕ → 关掉本版本
 */
@Composable
fun UpdateBanner(
    latest: UpdateRepo.Latest?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (latest == null) return
    val c = LocalPixel.current
    Column(
        Modifier
            .fillMaxWidth()
            // v2.69：淡绿通栏改成金色描边卡。原来的 c.green 半透明底与图鉴里一片
            // 绿色卡片同色系，扫一眼就滑过去了，视觉权重≈一张普通卡片。
            // c.gold 是本 App 已有强调色（CollapsibleCard 箭头、UpdateNotes 分类头都用它），
            // 不新增色板，也就不会在两套主题里跑偏。
            .background(c.gold.copy(alpha = 0.16f))
            .border(2.dp, c.gold)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText("⬆", color = c.gold, style = Px.title)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                PixelText("发现新版本 v${latest.versionName}", color = c.ink, style = Px.title)
                // 体积 + 日期放在副标题里：用户决定要不要更新主要看这两项
                // （包大小 = 下载流量，日期 = 新不新）。
                val meta = buildString {
                    if (latest.size > 0) append("%.2f MB".format(latest.size / 1048576.0))
                    if (latest.date.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(latest.date)
                    }
                }
                if (meta.isNotEmpty()) {
                    PixelText(meta, color = c.inkDim, style = Px.caption)
                }
            }
            Spacer(Modifier.width(6.dp))
            // ✕ 单独一个可点区域：整条横幅是「去看详情」，✕ 是「别再提」，
            // 语义相反混在一个 clickable 里会误触。
            PixelText(
                "✕",
                color = c.inkDim,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        // v2.69：写明动作并给一键下载。原先这里只有体积和日期，**一句「点了能干嘛」都没有**，
        // 而点整条横幅只是跳设置页，用户还得在那儿再找一次下载按钮——两级跳转等于劝退。
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ⚠ PixelButton 的 active 在**第三位**（PixelWidgets.kt），尾随 lambda 会落错位，
            //   所以这里两个参数都具名（verify 4810 已全局钉死这条）。
            PixelButton(
                text = "点这里下载",
                active = true,
                onClick = onDownload,
            )
            Spacer(Modifier.width(8.dp))
            PixelText("点空白处看完整更新说明", style = Px.caption, color = c.inkDim)
        }
        if (latest.notes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // v2.71.1 可读性修复（用户反馈「看不清」）：说明文字原来直接压在横幅的
            // 半透明金底上，而金底又透出**随季节变色**的页面背景——春季 gold 是粉色，
            // 粉字压绿底，对比度随季节时好时坏，最小的 caption 号雪上加霜。
            // 现在垫一块米色内衬卡（cream 底 + slotEdge 描边，与像素卡片的内衬同语言）：
            // 文字对比度不再受季节影响，深字压米底在六套主题里都稳定可读。
            Box(
                Modifier
                    .padding(start = 12.dp, end = 6.dp)
                    .background(c.cream)
                    .border(2.dp, c.slotEdge)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                UpdateNotes(latest.notes)
            }
        }
    }
}

/**
 * v2.69：发现新版时的启动弹窗（每个版本只打断一次）。
 *
 * ## 为什么只弹一次、且只在读落盘快照时弹
 * 打断是这版里唯一的强刺激，用多了就是骚扰：
 * ① **只在启动读 `update_pending_json` 时弹**，不在启动后 2 秒那次网络检查的结果上弹——
 *    否则用户每次开 App 都被拦一下，而那次检查本来就只在节流窗口外才发生；
 * ② [latest] 的版本号必须大于 `updateDialogVersion`，点过「稍后」就不再弹；
 * ③ 用户主动点过 ✕（`updateSeenVersion >= versionCode`）时**不弹**——尊重「别再提我」。
 *
 * ## 「稍后」不等于「别再提」
 * [onLater] 只关闭弹窗，**不写** `markUpdateSeen`。写了就等于永久屏蔽这一版，
 * 横幅也跟着消失，那这个功能就白做了：用户点「稍后」的意思是"我现在不想被打断"，
 * 不是"我不想更新"。
 */
@Composable
fun UpdateDialog(
    latest: UpdateRepo.Latest?,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    if (latest == null) return
    val c = LocalPixel.current
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.parchment)
                .border(2.dp, c.gold)
                .padding(14.dp),
        ) {
            PixelText("⬆ 发现新版本 v${latest.versionName}", color = c.ink, style = Px.title)
            val meta = buildString {
                if (latest.size > 0) append("%.2f MB".format(latest.size / 1048576.0))
                if (latest.date.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(latest.date)
                }
            }
            if (meta.isNotEmpty()) {
                PixelText(meta, color = c.inkDim, style = Px.caption)
            }
            if (latest.notes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                UpdateNotes(latest.notes.take(3), modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(text = "现在更新", active = true, onClick = onDownload)
                PixelButton(text = "稍后", onClick = onLater)
            }
            Spacer(Modifier.height(4.dp))
            PixelText(
                "随时可在「设置 → 在线更新」里再查",
                style = Px.caption, color = c.inkDim,
            )
        }
    }
}

/**
 * 更新说明的分层渲染（第一层「提示做细」的落点）。
 *
 * ## 规则（刻意不硬编码词表）
 * 行首是数字+点（`1.` `2.` `①`…）→ 归为**条目**，缩进显示，保留原编号；
 * 其余短行 → 归为**小标题**（发版说明里的「新增」「优化」这类分类头），用金色强调。
 *
 * 为什么不写死「新增/优化」两个词去匹配：分类名会随发版人习惯变，
 * 硬编码词表必然有一天对不上，然后所有分类头都退化成普通条目。**按形状判断更耐用。**
 *
 * v2.71.1：字号 caption(10sp) → body(12sp)（用户反馈「看不清」）。
 * 这是给用户读的**正文**，不是角落里的元信息——10sp 像素字在手机上就是糊的。
 * 横幅侧同步垫了米色内衬卡（见 UpdateBanner），两件事一起才够清楚。
 */
@Composable
fun UpdateNotes(notes: List<String>, modifier: Modifier = Modifier) {
    val c = LocalPixel.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        notes.forEach { raw ->
            val t = raw.trim()
            if (t.isEmpty()) return@forEach
            val isItem = t.matches(Regex("^([0-9]+[.、)]|[①-⑳])"))
            if (isItem) {
                Row {
                    Spacer(Modifier.width(10.dp))
                    PixelText(t, color = c.ink, style = Px.body)
                }
            } else {
                PixelText(
                    t,
                    color = c.gold,
                    style = Px.body,
                    modifier = Modifier.padding(top = 3.dp),
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
