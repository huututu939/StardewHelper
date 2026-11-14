package com.stardew.assistant.ui.guide

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
import androidx.compose.runtime.mutableIntStateOf
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
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import kotlin.math.ceil
import kotlin.math.roundToInt

/** v2.15 技能升级：5 技能经验来源 / 1→10 升级表 / 1.6 精通系统 */
@Composable
fun GuideSkillsPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.skillLeveling
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("⭐ 技能升级", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("技能升级") }
        }
        return
    }

    val skills = remember(root) { root.optJSONArray("skills") }
    var idx by remember { mutableIntStateOf(0) }
    // v2.18 冲级计算器的选择状态：必须声明在 LazyListScope 之外（Composable 作用域）
    val skillsN = skills?.length() ?: 0
    var calcSkillIdx by remember { mutableIntStateOf(0) }
    var calcGoalLv by remember { mutableIntStateOf(10) }

    Column(Modifier.fillMaxSize()) {
        BackBar("⭐ 技能升级", onBack)
        PixelSegmented(
            // v2.18：插入「冲级计算」（群友要的「刷技能最快路线计算器」）
            options = listOf("经验来源", "升级表", "冲级计算", "精通系统"),
            selected = idx,
            onSelect = { idx = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = guideContentPadding(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (idx) {
                0 -> {
                    val n = skills?.length() ?: 0
                    items(n) { i ->
                        val o = skills!!.getJSONObject(i)
                        PixelCard(title = o.optString("name"), accent = c.green) {
                            o.optJSONArray("exp_sources")?.let { es ->
                                PixelText("经验来源 ${es.length()} 种", color = c.gold)
                                for (k in 0 until es.length()) {
                                    if (k > 0) RowSep()
                                    val e = es.getJSONObject(k)
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            PixelText(e.optString("action"))
                                            e.optString("note").takeIf { it.isNotBlank() }
                                                ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Chip("+${e.optInt("exp")} XP", border = c.green)
                                    }
                                }
                            }
                            o.optString("fastest_leveling").takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(8.dp))
                                KVRow("速成路线", it)
                            }
                        }
                    }
                }

                1 -> {
                    val et = root.optJSONObject("exp_table")
                    item {
                        PixelCard(title = "📈 升级所需经验", accent = c.gold) {
                            if (et == null) {
                                PixelText("—")
                            } else {
                                for (lv in 1..10) {
                                    val xp = et.optInt("level_$lv", -1)
                                    if (xp > 0) KVRow("$lv 级", "$xp XP")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            KVRow("单技能 0→10", "${root.optJSONObject("exp_table")?.optInt("level_10") ?: 0} XP")
                        }
                    }
                    val inc = et?.optJSONObject("per_level_increment")
                    item {
                        PixelCard(title = "↔ 每级增量") {
                            inc?.let { o ->
                                val keys = o.keys().asSequence().toList()
                                val rows = keys.map { k ->
                                    // v2.17 D9：键形如 1_to_2，旧 replace("_"," → ") 会得到「1 → to → 2」
                                    val seg = if ("_to_" in k) {
                                        k.substringBefore("_to_") + " → " + k.substringAfterLast("_")
                                    } else k.replace("_", " ")
                                    seg to "${o.optInt(k)} XP"
                                }
                                rows.forEach { (k, v) -> KVRow(k, v) }
                            }
                        }
                    }
                }

                // ── v2.18 冲级计算器（群友：刷技能最快路线计算器）──
                // 注：选择状态必须在 LazyListScope 之外（Composable 作用域）声明
                2 -> {
                    item {
                        PixelCard(title = "🎯 选技能", accent = c.gold) {
                            PixelSegmented(
                                options = (0 until skillsN).map { skills!!.getJSONObject(it).optString("name") },
                                selected = calcSkillIdx.coerceIn(0, (skillsN - 1).coerceAtLeast(0)),
                                onSelect = { calcSkillIdx = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            PixelText("目标等级", color = c.ink)
                            Spacer(Modifier.height(4.dp))
                            PixelSegmented(
                                options = listOf("5 级", "10 级"),
                                selected = if (calcGoalLv == 5) 0 else 1,
                                onSelect = { calcGoalLv = if (it == 0) 5 else 10 },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    val et = root.optJSONObject("exp_table")
                    val sk = skills?.optJSONObject(calcSkillIdx.coerceIn(0, (skillsN - 1).coerceAtLeast(0)))
                    val needed = (et?.optInt("level_$calcGoalLv", 0) ?: 0) -
                        (et?.optInt("level_1", 0) ?: 0)
                    item {
                        PixelCard(title = "🔢 还需 XP", accent = c.red) {
                            KVRow("技能", sk?.optString("name") ?: "—")
                            KVRow("目标", "$calcGoalLv 级")
                            KVRow("还需", "$needed XP")
                            Spacer(Modifier.height(4.dp))
                            PixelText(
                                "口径：按 0 经验起算（游戏内经验不会掉）。",
                                style = Px.caption, color = c.inkDim,
                            )
                        }
                    }
                    // 来源按经验降序排行
                    item {
                        val es = sk?.optJSONArray("exp_sources")
                        PixelCard(title = "📊 来源性价比排行", accent = c.green) {
                            if (es == null || es.length() == 0) {
                                PixelText("—", color = c.inkDim)
                            } else {
                                val rows = (0 until es.length()).map { es.getJSONObject(it) }
                                    .sortedByDescending { it.optInt("exp") }
                                rows.forEachIndexed { i, e ->
                                    if (i > 0) RowSep()
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            PixelText(e.optString("action"))
                                            e.optString("note").takeIf { it.isNotBlank() }
                                                ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Chip("+${e.optInt("exp")} XP", border = c.green)
                                    }
                                }
                            }
                        }
                    }
                    // 「每天要刷多少」：用最高单源反推数量
                    item {
                        val es = sk?.optJSONArray("exp_sources")
                        var best: org.json.JSONObject? = null
                        for (i in 0 until (es?.length() ?: 0)) {
                            val e = es!!.getJSONObject(i)
                            if (best == null || e.optInt("exp") > best!!.optInt("exp")) best = e
                        }
                        val maxExp = best?.optInt("exp") ?: 0
                        PixelCard(title = "🧮 每天要刷多少", accent = c.blue) {
                            if (maxExp <= 0 || needed <= 0) {
                                PixelText("数据不足", color = c.inkDim)
                            } else {
                                val cnt = ceil(needed.toDouble() / maxExp).toInt()
                                PixelText(
                                    "${best!!.optString("action")} → $maxExp XP/次",
                                )
                                Spacer(Modifier.height(4.dp))
                                KVRow("刷到 $calcGoalLv 级", "$cnt 次")
                                Spacer(Modifier.height(4.dp))
                                PixelText(
                                    "口径：经验为每单位收益，不含季节/天气/工具修正。",
                                    style = Px.caption, color = c.inkDim,
                                )
                            }
                        }
                    }
                }

                else -> {
                    val m = root.optJSONObject("mastery")
                    item {
                        PixelCard(title = "🏆 1.6 精通系统", accent = c.gold) {
                            if (m == null) {
                                PixelText("—")
                            } else {
                                m.optString("important_note").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                                Spacer(Modifier.height(6.dp))
                                m.optString("location").takeIf { it.isNotBlank() }
                                    ?.let { KVRow("位置", it) }
                                m.optString("unlock_condition").takeIf { it.isNotBlank() }
                                    ?.let { KVRow("解锁条件", it) }
                                m.optString("point_source").takeIf { it.isNotBlank() }
                                    ?.let { KVRow("精通点来源", it) }
                            }
                        }
                    }
                    val cr = m?.optJSONObject("conversion_rate")
                    item {
                        PixelCard(title = "🔄 经验转化率") {
                            cr?.let { o ->
                                o.keys().asSequence().filter { it != "note" }.forEach { k ->
                                    // v2.17 D9：键是「耕种Farming」中英混排 → 只留中文段；
                                    // toInt 截断改 roundToInt（0.5×100 之外的边界值不掉位）
                                    val label = k.replace(Regex("[A-Za-z ]+$"), "").ifEmpty { k }
                                    KVRow(label, "${(o.optDouble(k) * 100).roundToInt()}%")
                                }
                                o.optString("note").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                            }
                        }
                    }
                    val costs = m?.optJSONObject("costs")
                    item {
                        PixelCard(title = "🕯 石柱解锁花费") {
                            costs?.let { o ->
                                KVRow("第 1 根", "${o.optInt("first_claim")} 点")
                                KVRow("第 2 根", "${o.optInt("second_claim")} 点")
                                KVRow("第 3 根", "${o.optInt("third_claim")} 点")
                                KVRow("第 4 根", "${o.optInt("fourth_claim")} 点")
                                KVRow("第 5 根", "${o.optInt("fifth_claim")} 点")
                                Spacer(Modifier.height(6.dp))
                                KVRow("累计", "${o.optInt("total")} 点")
                                // v2.17 D9：cumulative 数组此前只存不渲染，补为「解锁到第 N 根共花多少」
                                o.optJSONArray("cumulative")?.let { a ->
                                    if (a.length() > 0) {
                                        KVRow("逐根累计", (0 until a.length()).joinToString(" → ") { a.optInt(it).toString() } + " 点")
                                    }
                                }
                                o.optString("note").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                            }
                        }
                    }
                    val acq = m?.optJSONObject("acquisition_speed_estimate")
                    item {
                        PixelCard(title = "⏱ 攒点速度") {
                            acq?.let { o ->
                                KVRow("单技能升满", "${o.optInt("xp_to_level10_per_skill")} XP")
                                KVRow("五技能全满", "${o.optInt("xp_to_unlock_cave_all5_skills")} XP")
                                o.optString("note").takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(6.dp))
                                    PixelText(it, style = Px.caption, color = c.inkDim)
                                }
                            }
                        }
                    }
                    val pillars = m?.optJSONArray("mastery_pillars")
                    items(pillars?.length() ?: 0) { i ->
                        val p = pillars!!.getJSONObject(i)
                        PixelCard(title = "🗿 ${p.optString("name")}", accent = c.gold) {
                            val rw = p.optJSONArray("rewards")
                            for (k in 0 until (rw?.length() ?: 0)) {
                                if (k > 0) RowSep()
                                val r = rw!!.getJSONObject(k)
                                PixelText("· ${r.optString("item")}")
                                r.optString("effect").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption) }
                                r.optString("type").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                            }
                        }
                    }
                    m?.optString("completion")?.takeIf { it.isNotBlank() }?.let { fin ->
                        item {
                            PixelCard(title = "🎩 全部精通后", accent = c.gold) {
                                PixelText(fin)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}