package com.stardew.assistant.ui.guide

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelSearchClear
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import kotlin.math.ceil

/**
 * v2.18 ⚔ 刷级路线：战斗等级怎么冲最快。
 * 数据来自 tools/import_v218.py 加工的 monsterFarming.json（v9 monster_compendium）。
 *
 * 口径（重要）：XP 是「每只怪」的经验，不是每分钟——击杀耗时没有权威数据，不做估算。
 */
@Composable
fun GuideCombatPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.monsterFarming
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("⚔ 刷级路线", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("刷级路线") }
        }
        return
    }

    val areas = remember(root) { root.optJSONArray("xp_by_area") }
    val goals = remember(root) { root.optJSONArray("guild_goals") }
    val notes = remember(root) { root.optJSONArray("notes") }
    val monsters = remember(root) { root.optJSONArray("monsters") }
    var idx by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        BackBar("⚔ 刷级路线", onBack)
        PixelSegmented(
            options = listOf("速刷路线", "区域经验", "公会目标", "怪物明细"),
            selected = idx,
            onSelect = { idx = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        when (idx) {
            // ── 1. 速刷路线 ──
            0 -> {
                val sk = GameData.skillLeveling?.optJSONArray("skills")
                val combat = remember(sk) {
                    (0 until (sk?.length() ?: 0)).map { sk!!.getJSONObject(it) }
                        .firstOrNull { it.optString("name") == "战斗" }
                }
                val et = GameData.skillLeveling?.optJSONObject("exp_table")
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = guideContentPadding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        PixelCard(title = "🎯 战斗 1→10 需要多少经验", accent = c.red) {
                            val lv1 = et?.optInt("level_1", 0) ?: 0
                            val lv10 = et?.optInt("level_10", 0) ?: 0
                            KVRow("0 → 1 级", "$lv1 XP")
                            KVRow("0 → 10 级", "$lv10 XP")
                            Spacer(Modifier.height(4.dp))
                            PixelText(
                                "口径：按 0 经验起算；游戏里经验不会掉，所以这是一次性总量。",
                                style = Px.caption, color = c.inkDim,
                            )
                        }
                    }
                    item {
                        PixelCard(title = "📈 每级经验递增表", accent = c.gold) {
                            val inc = et?.optJSONObject("per_level_increment")
                            if (inc == null) {
                                PixelText("—")
                            } else {
                                var prev: Int? = null
                                for (lv in 1..9) {
                                    val cur = et?.optInt("level_${lv + 1}", 0) ?: 0
                                    val k = inc.optInt("${lv}_to_${lv + 1}", 0)
                                    val delta = if (prev != null) "（+${cur - prev}）" else ""
                                    KVRow("$lv → ${lv + 1} 级", "$k XP$delta")
                                    prev = cur
                                }
                            }
                        }
                    }
                    item {
                        PixelCard(title = "🔥 速刷路线（官方速记）", accent = c.green) {
                            combat?.optString("fastest_leveling")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { PixelText(it) }
                                ?: PixelText("—", color = c.inkDim)
                            Spacer(Modifier.height(8.dp))
                            RowSep()
                            Spacer(Modifier.height(8.dp))
                            PixelText("按经验/只排序的结论：", color = c.gold)
                            Spacer(Modifier.height(4.dp))
                            // 直接从区域数据里点名经验最高的三个怪，避免手写常量
                            val top3 = remember(areas) {
                                val all = mutableListOf<Pair<String, Int>>()
                                for (i in 0 until (areas?.length() ?: 0)) {
                                    val a = areas!!.getJSONObject(i)
                                    val ms = a.optJSONArray("monsters") ?: continue
                                    for (j in 0 until ms.length()) {
                                        val m = ms.getJSONObject(j)
                                        all += m.optString("name") to m.optInt("xp")
                                    }
                                }
                                all.distinctBy { it.first }.sortedByDescending { it.second }.take(3)
                            }
                            top3.forEachIndexed { i, (nm, xp) ->
                                if (i > 0) RowSep()
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PixelText("${i + 1}. $nm", modifier = Modifier.weight(1f))
                                    Chip("+$xp XP", border = c.green)
                                }
                            }
                        }
                    }
                    item {
                        PixelCard(title = "📖 别忘了读书", accent = c.blue) {
                            // 战斗季刊 250XP 是单次最大收益，取自 exp_sources 最后一条
                            val book = combat?.optJSONArray("exp_sources")?.let { es ->
                                (0 until es.length()).map { es.getJSONObject(it) }
                                    .firstOrNull { it.optInt("exp") >= 100 }
                            }
                            if (book != null) {
                                KVRow(book.optString("action"), "+${book.optInt("exp")} XP")
                                book.optString("note").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                            } else {
                                PixelText("—", color = c.inkDim)
                            }
                            Spacer(Modifier.height(6.dp))
                            PixelText(
                                "⚠ 季刊一天只能读一次，适合每天早上顺手读完再下矿。",
                                style = Px.caption, color = c.gold,
                            )
                        }
                    }
                    if (notes != null && notes.length() > 0) {
                        items(notes.length()) { i ->
                            val nt = notes.getJSONObject(i)
                            PixelCard(title = "📌 ${nt.optString("topic")}") {
                                PixelText(nt.optString("text"))
                            }
                        }
                    }
                }
            }

            // ── 2. 区域经验 ──
            1 -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = guideContentPadding(4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    PixelCard(accent = c.gold) {
                        PixelText(
                            "⚠ 下面都是「每只怪」的经验，不是每分钟。",
                            color = c.red,
                        )
                        Spacer(Modifier.height(4.dp))
                        PixelText(
                            "击杀耗时没有权威数据，所以不估速率。想比「效率」请自己套上你的手感。",
                            style = Px.caption, color = c.inkDim,
                        )
                    }
                }
                items(areas?.length() ?: 0) { i ->
                    val a = areas!!.getJSONObject(i)
                    PixelCard(title = "📍 ${a.optString("area")}", accent = c.red) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Chip("均 ${a.optDouble("avg_xp")} XP/只", border = c.red)
                            Spacer(Modifier.width(6.dp))
                            Chip("最高 ${a.optInt("max_xp")} XP", border = c.gold)
                            Spacer(Modifier.width(6.dp))
                            Chip("${a.optInt("monster_count")} 种", border = c.slotEdge)
                        }
                        val ms = a.optJSONArray("monsters") ?: return@PixelCard
                        Spacer(Modifier.height(6.dp))
                        for (j in 0 until ms.length()) {
                            if (j > 0) RowSep()
                            val m = ms.getJSONObject(j)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    PixelText(m.optString("name"))
                                    val fl = m.optString("floors_text")
                                    val cond = m.optString("conditions")
                                    val sub = listOfNotNull(
                                        fl.takeIf { it.isNotBlank() }?.let { "层 $it" },
                                        cond.takeIf { it.isNotBlank() },
                                        m.optString("type").takeIf { it.isNotBlank() },
                                    ).joinToString(" · ")
                                    if (sub.isNotBlank()) {
                                        PixelText(sub, style = Px.caption, color = c.inkDim)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Chip("+${m.optInt("xp")} XP", border = c.red)
                            }
                        }
                    }
                }
            }

            // ── 3. 公会目标 ──
            2 -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = guideContentPadding(4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    PixelCard(accent = c.blue) {
                        PixelText(
                            "冒险家公会：杀够数量换奖励，奖励还能重复购买。",
                            color = c.gold,
                        )
                    }
                }
                items(goals?.length() ?: 0) { i ->
                    val g = goals!!.getJSONObject(i)
                    PixelCard {
                        PixelText(g.optString("monster"), color = c.gold)
                        Spacer(Modifier.height(4.dp))
                        KVRow("击杀目标", "${g.optInt("kill_goal")} 只")
                        KVRow("奖励", g.optString("reward"))
                        g.optString("reward_cn_desc").takeIf { it.isNotBlank() }
                            ?.let { KVRow("奖励效果", it) }
                        if (g.optInt("repurchase_price_g") > 0) {
                            KVRow("重复购买", "${g.optInt("repurchase_price_g")}g")
                        }
                    }
                }
            }

            // ── 4. 怪物明细 ──
            else -> {
                var q by remember { mutableStateOf("") }
                val shown = remember(monsters, q) {
                    val list = (0 until (monsters?.length() ?: 0)).map { monsters!!.getJSONObject(it) }
                    if (q.isBlank()) list else list.filter { it.optString("name").contains(q.trim(), ignoreCase = true) }
                }
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .background(c.slot)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        if (q.isEmpty()) {
                            PixelText("🔍 输入怪名过滤（共 ${monsters?.length() ?: 0} 种，按经验降序）", color = c.inkDim)
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = q,
                            onValueChange = { q = it },
                            textStyle = Px.body.copy(color = c.ink),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (q.isNotEmpty()) {
                            PixelSearchClear(
                                onClear = { q = "" },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = guideContentPadding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (shown.isEmpty()) {
                        item {
                            PixelCard {
                                PixelText("没有匹配「$q」的怪物", color = c.inkDim)
                            }
                        }
                    }
                    items(shown.size) { i ->
                            val m = shown[i]
                            PixelCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PixelText(m.optString("name"), modifier = Modifier.weight(1f))
                                    if (m.optString("variant") == "dangerous") {
                                        Chip("危险版", border = c.red)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Chip("+${m.optInt("xp")} XP", border = c.gold)
                                }
                                Spacer(Modifier.height(4.dp))
                                Row {
                                    KVRow("类型", m.optString("type"))
                                    KVRow("HP", "${m.optInt("hp")}")
                                    KVRow("伤害", "${m.optInt("damage")}")
                                    KVRow("防御", "${m.optInt("defense")}")
                                    KVRow("速度", "${m.optInt("speed")}")
                                }
                                val locs = m.optJSONArray("locations")
                                if (locs != null && locs.length() > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    PixelText("出没：", color = c.gold, style = Px.caption)
                                    for (k in 0 until locs.length()) {
                                        val loc = locs.getJSONObject(k)
                                        val sub = listOfNotNull(
                                            loc.optString("floors_text").takeIf { it.isNotBlank() }?.let { "层 $it" },
                                            loc.optString("conditions").takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        PixelText(
                                            "· ${loc.optString("area")}${if (sub.isNotBlank()) "（$sub）" else ""}",
                                            style = Px.caption, color = c.inkDim,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 供首页/verify 复用：战斗 10 级所需 XP（0 起算） */
internal fun combatXpToLevel10(): Int {
    val et = GameData.skillLeveling?.optJSONObject("exp_table") ?: return 0
    return et.optInt("level_10", 0)
}

/** 供首页/verify 复用：按最高单怪 XP 估算刷到 10 级需要的只数 */
internal fun monstersToLevel10(): Int {
    val ms = GameData.monsterFarming?.optJSONArray("monsters") ?: return 0
    var maxXp = 0
    for (i in 0 until ms.length()) {
        val x = ms.getJSONObject(i).optInt("xp")
        if (x > maxXp) maxXp = x
    }
    if (maxXp <= 0) return 0
    return ceil(combatXpToLevel10().toDouble() / maxXp).toInt()
}
