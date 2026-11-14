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
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import org.json.JSONArray
import org.json.JSONObject

/** 季节数组转成「春夏秋冬」顺序的一段文字；「全季」单列 */
private fun seasonText(a: JSONArray?): String {
    if (a == null || a.length() == 0) return "—"
    val set = (0 until a.length()).map { k -> a.optString(k) }.toSet()
    if (set.contains("全季")) return "全季"
    val buf = StringBuilder()
    for (s in listOf("春", "夏", "秋", "冬")) if (set.contains(s)) buf.append(s)
    return if (buf.isEmpty()) "—" else buf.toString()
}

private fun bodyNum(o: JSONObject, key: String): String? {
    val v = o.optInt(key, Int.MIN_VALUE)
    if (v == Int.MIN_VALUE) return null
    return if (v > 0) "+$v" else "$v"
}

/** v2.15 采集攻略：42 种采集物的季节 / 地点 / 售价 / 体力 / 用途 + 采集技能 */
@Composable
fun GuideForagingPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.foraging
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("🍄 采集攻略", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("采集") }
        }
        return
    }

    val items = remember(root) { root.optJSONArray("items") }
    val all = remember(items) {
        (0 until (items?.length() ?: 0)).map { k -> items!!.getJSONObject(k) }
    }
    var filter by remember { mutableStateOf("全部") }
    // 只列出确实有该季节采集物的季节，避免出现空选项
    val seasons: List<String> = remember(all) {
        val list = mutableListOf("全部")
        for (s in listOf("春", "夏", "秋", "冬")) {
            if (all.any { o -> seasonText(o.optJSONArray("season")).contains(s) }) list.add(s)
        }
        list
    }
    val shown = remember(all, filter) {
        if (filter == "全部") all
        // v2.17 A7：全季采集物（树液/蛤/珊瑚等 17 种）必须放行——旧 contains 判定让它们
        // 在任何季节筛选下整批消失
        else all.filter { o ->
            val st = seasonText(o.optJSONArray("season"))
            st == "全季" || st.contains(filter)
        }
    }
    var idx by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        BackBar("🍄 采集攻略", onBack)
        PixelSegmented(
            options = listOf("采集物 ${all.size}", "技能"),
            selected = idx,
            onSelect = { idx = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (idx == 0) {
            PixelSegmented(
                options = seasons,
                selected = seasons.indexOf(filter).coerceAtLeast(0),
                onSelect = { filter = seasons[it] },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = guideContentPadding(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (idx == 0) {
                items(shown.size) { i ->
                    val o = shown[i]
                    PixelCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelText(o.optString("name_cn"), modifier = Modifier.weight(1f))
                            o.optString("name_en").takeIf { it.isNotBlank() }
                                ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("季 ${seasonText(o.optJSONArray("season"))}", border = c.green)
                            if (o.optInt("sell_price") > 0) {
                                Chip("${o.optInt("sell_price")}g", border = c.gold)
                            }
                            bodyNum(o, "energy")?.let { Chip("体力 $it", border = c.blue) }
                            if (o.optBoolean("is_new_1_6")) Chip("1.6 新增", border = c.red)
                        }
                        o.optJSONArray("locations")?.let { la ->
                            if (la.length() > 0) {
                                Spacer(Modifier.height(6.dp))
                                PixelText("地点", color = c.gold)
                                PixelText(
                                    (0 until la.length()).joinToString("\n") { "· ${la.optString(it)}" },
                                    style = Px.caption,
                                )
                            }
                        }
                        o.optJSONArray("uses")?.let { us ->
                            if (us.length() > 0) {
                                Spacer(Modifier.height(6.dp))
                                PixelText("用途 ${us.length()} 项", color = c.gold)
                                PixelText(
                                    (0 until us.length()).joinToString("、") { k -> us.optString(k) },
                                    style = Px.caption, color = c.inkDim,
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    val si = root.optJSONObject("skill_info")
                    PixelCard(title = "🎯 采集技能", accent = c.green) {
                        si?.let { s ->
                            KVRow("每件经验", "+${s.optInt("foraging_xp_per_item")} XP")
                            fun join(key: String): String? =
                                s.optJSONArray(key)?.takeIf { it.length() > 0 }
                                    ?.let { a ->
                                        (0 until a.length()).joinToString("、") { k -> a.optString(k) }
                                    }
                            join("profession_level5")?.let { KVRow("5 级职业", it) }
                            join("profession_level10_forester")?.let { KVRow("10 级·伐木工线", it) }
                            join("profession_level10_gatherer")?.let { KVRow("10 级·采集者线", it) }
                            s.optString("note").takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(6.dp))
                                PixelText(it, style = Px.caption, color = c.inkDim)
                            }
                        }
                    }
                }
                item {
                    PixelCard(title = "📊 采集物一览") {
                        for (o in all) {
                            if (o.optString("name_cn").isBlank()) continue
                            KVRow(
                                seasonText(o.optJSONArray("season")),
                                "${o.optString("name_cn")} · ${o.optInt("sell_price")}g",
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        RowSep()
                        PixelText("共 ${all.size} 种", style = Px.caption, color = c.inkDim)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}