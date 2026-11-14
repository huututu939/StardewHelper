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
import org.json.JSONArray

private const val PET_EMOJI = "🐱"

/** v2.15 宠物攻略：猫/狗/乌龟的品种、好感度养成、宠物碗与领养 */
@Composable
fun GuidePetsPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.petGuide
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("$PET_EMOJI 宠物攻略", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("宠物") }
        }
        return
    }

    val pets = remember(root) { root.optJSONArray("pets") }
    val n = pets?.length() ?: 0
    var idx by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        BackBar("$PET_EMOJI 宠物攻略", onBack)
        PixelSegmented(
            options = (0 until n).map { k ->
                pets!!.getJSONObject(k).optString("type_cn")
            } + listOf("规则"),
            selected = idx,
            onSelect = { idx = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = guideContentPadding(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (idx < n) {
                val p = pets!!.getJSONObject(idx)
                item {
                    PixelCard(title = "${p.optString("type_cn")} · 好感度", accent = c.red) {
                        p.optString("how_to_get").takeIf { it.isNotBlank() }
                            ?.let { KVRow("怎么获得", it) }
                        if (p.has("max_affection") && !p.isNull("max_affection")) {
                            KVRow("满好感", "${p.optInt("max_affection")} 点")
                        }
                        // v2.17 A9：乌龟无此字段，optInt 兜底 0 会显示「每心需要 0 点」假规则
                        if (p.has("affection_per_heart") && !p.isNull("affection_per_heart")) {
                            KVRow("每心需要", "${p.optInt("affection_per_heart")} 点")
                        } else {
                            PixelText("${p.optString("type_cn")}没有心数系统", style = Px.caption, color = c.inkDim)
                        }
                        p.optString("grandpa_evaluation").takeIf { it.isNotBlank() }
                            ?.let { KVRow("爷爷评价", it) }
                    }
                }
                item {
                    PixelCard(title = "🤚 每日互动") {
                        val di = p.optJSONArray("daily_interactions")
                        for (k in 0 until (di?.length() ?: 0)) {
                            if (k > 0) RowSep()
                            val d = di!!.getJSONObject(k)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    PixelText(d.optString("action"))
                                    d.optString("note").takeIf { it.isNotBlank() }
                                        ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                                }
                                Spacer(Modifier.width(8.dp))
                                // 缺字段时不显示「+0」假数值（v2.17 D9）
                                if (d.has("affection_gain") && !d.isNull("affection_gain")) {
                                    Chip("+${d.optInt("affection_gain")}", border = c.red)
                                }
                            }
                        }
                        p.optString("affection_decay").takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(8.dp))
                            KVRow("不互动", it)
                        }
                    }
                }
                val hm = p.optJSONArray("heart_milestones")
                item {
                    PixelCard(title = "💗 心数里程碑") {
                        if (hm == null || hm.length() == 0) {
                            PixelText("—")
                        } else {
                            for (k in 0 until hm.length()) {
                                if (k > 0) RowSep()
                                val h = hm.getJSONObject(k)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Chip("${h.optInt("hearts")} 心", border = c.red)
                                    Spacer(Modifier.width(8.dp))
                                    PixelText("${h.optInt("affection")} 点", style = Px.caption, color = c.inkDim)
                                }
                                h.optString("effect").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption) }
                            }
                        }
                    }
                }
                val br = p.optJSONArray("breeds")
                if (br != null && br.length() > 0) {
                    item {
                        PixelCard(title = "🎨 品种 ${br.length()} 种") {
                            for (k in 0 until br.length()) {
                                if (k > 0) RowSep()
                                val b = br.getJSONObject(k)
                                PixelText("· ${b.optString("name")}")
                                b.optString("description").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                            }
                        }
                    }
                }
                val gf = p.optJSONArray("gifts")
                if (gf != null && gf.length() > 0) {
                    item {
                        PixelCard(title = "🎁 会送你的东西") {
                            for (k in 0 until gf.length()) {
                                if (k > 0) RowSep()
                                val g = gf.getJSONObject(k)
                                PixelText("· ${g.optString("item")}")
                                PixelText(
                                    "概率 ${"%.1f".format(g.optDouble("chance") * 100)}%",
                                    style = Px.caption, color = c.gold,
                                )
                                g.optString("note").takeIf { it.isNotBlank() }
                                    ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                            }
                        }
                    }
                }
                item {
                    PixelCard(title = "✨ 特殊行为") {
                        p.optString("special_behaviors").takeIf { it.isNotBlank() }
                            ?.let { PixelText(it) } ?: PixelText("—")
                        p.optString("1_6_updates").takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(6.dp))
                            PixelText("1.6 更新", color = c.gold)
                            PixelText(it, style = Px.caption)
                        }
                    }
                }
            } else {
                val bowl = root.optJSONObject("pet_bowl")
                item {
                    PixelCard(title = "🥣 宠物碗", accent = c.blue) {
                        bowl?.let {
                            it.optString("location").takeIf { s -> s.isNotBlank() }?.let { s -> KVRow("位置", s) }
                            it.optString("function").takeIf { s -> s.isNotBlank() }?.let { s -> KVRow("作用", s) }
                            it.optString("note").takeIf { s -> s.isNotBlank() }?.let { s ->
                                Spacer(Modifier.height(6.dp))
                                PixelText(s, style = Px.caption, color = c.inkDim)
                            }
                        }
                    }
                }
                val ad = root.optJSONObject("pet_adoption")
                item {
                    PixelCard(title = "🏠 领养规则", accent = c.gold) {
                        ad?.let {
                            it.optString("initial_pet").takeIf { s -> s.isNotBlank() }?.let { s -> KVRow("初始宠物", s) }
                            it.optString("additional_pets").takeIf { s -> s.isNotBlank() }?.let { s -> KVRow("额外宠物", s) }
                            it.optString("year_two_adoption").takeIf { s -> s.isNotBlank() }?.let { s -> KVRow("第二年", s) }
                            it.optString("removing_pets").takeIf { s -> s.isNotBlank() }?.let { s -> KVRow("移除宠物", s) }
                        }
                    }
                }
                val dn = root.optJSONObject("default_names")
                item {
                    PixelCard(title = "📛 默认名字") {
                        val labelCn = mapOf(
                            "male_farmer_cat" to "男农场主·猫",
                            "male_farmer_dog" to "男农场主·狗",
                            "female_farmer_cat" to "女农场主·猫",
                            "female_farmer_dog" to "女农场主·狗",
                        )
                        dn?.let { o ->
                            for (k in o.keys().asSequence().sorted().toList()) {
                                KVRow(labelCn[k] ?: k.replace("_", " "), o.optString(k))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}