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
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import org.json.JSONArray
import org.json.JSONObject

private val SEASON_CN = mapOf("spring" to "春", "summer" to "夏", "fall" to "秋", "winter" to "冬")

/** dict 里的字符串数组 → 逐条 bullet 换行；空则 null */
private fun JSONArray?.joinCn(): String? {
    if (this == null || length() == 0) return null
    return (0 until length()).joinToString("\n") { "· ${optString(it)}" }
}

private fun JSONObject.str(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

/** dict 里的一组「标题 → 长文/数组」段落，统一渲染成 KVRow。
 *  标签走 [cnKey] 翻译（原来直接把 ceremony/effects 这种英文 key 上屏，v2.14.1 手测反馈）；
 *  数字也接住——move_in_days=3 是 int，原来 String/JSONArray 都不匹配，被静默丢掉了。 */
private fun section(o: JSONObject?, vararg keys: String, into: MutableList<Pair<String, String>>) {
    if (o == null) return
    keys.forEach { k ->
        when (val v = o.opt(k)) {
            is String -> if (v.isNotBlank()) into.add(cnKey(k) to v)
            is org.json.JSONArray -> v.joinCn()?.let { into.add(cnKey(k) to it) }
            is Number -> into.add(cnKey(k) to v.toString() + (VALUE_SUFFIX[k] ?: ""))
        }
    }
}

/** v2.14 结婚攻略：NPC 名册 / 追求流程 / 婚礼与婚后 / 离婚与室友 */
@Composable
fun GuideMarriagePage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.marriageGuide
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("💍 结婚攻略", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("结婚") }
        }
        return
    }

    var idx by remember { mutableIntStateOf(0) }
    val npcs = remember(root) { root.getJSONArray("marriageable_npcs") }
    val process = remember(root) { root.getJSONArray("process") }

    Column(Modifier.fillMaxSize()) {
        BackBar("💍 结婚攻略", onBack)
        PixelSegmented(
            options = listOf("名册 ${npcs.length()}", "流程 ${process.length()}", "婚后", "离婚"),
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
                0 -> items(npcs.length()) { i ->
                    val o = npcs.getJSONObject(i)
                    val name = o.optString("name")
                    PixelCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FramedIcon(name, 30)
                            Spacer(Modifier.width(10.dp))
                            PixelText(name, modifier = Modifier.weight(1f))
                            // v2.17 D9：数据源是 marriageable_npcs（列表内全部可攻略），
                            // 旧代码 gender 两分支同文案的死条件删除
                            Chip("可攻略", border = c.gold)
                        }
                        Spacer(Modifier.height(4.dp))
                        PixelText(o.optString("name_en"), style = Px.caption, color = c.inkDim)
                    }
                }

                1 -> {
                    item {
                        PixelCard(title = "💐 花舞节（求婚前）", accent = c.gold) {
                            val fd = root.getJSONObject("flower_dance")
                            fd.optJSONObject("date")?.let { d ->
                                KVRow("时间", "${SEASON_CN[d.optString("season")] ?: d.optString("season")} ${d.optInt("day")} 日")
                            }
                            fd.str("location")?.let { KVRow("地点", it) }
                            fd.str("dance_partner_requirements")?.let { KVRow("舞伴要求", it) }
                            fd.str("description")?.let { KVRow("说明", it) }
                        }
                    }
                    items(process.length()) { i ->
                        val o = process.getJSONObject(i)
                        PixelCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Chip("第 ${o.optInt("step")} 步", border = c.green)
                                Spacer(Modifier.width(8.dp))
                                PixelText(o.optString("title"))
                            }
                            Spacer(Modifier.height(6.dp))
                            o.str("description")?.let { PixelText(it) }
                            o.optJSONArray("requirements")?.joinCn()?.let {
                                Spacer(Modifier.height(4.dp))
                                PixelText("要点：$it", style = Px.caption, color = c.inkDim)
                            }
                        }
                    }
                    item {
                        PixelCard(title = "🔮 美人鱼吊坠（求婚道具）", accent = c.blue) {
                            val mp = root.getJSONObject("mermaid_pendant")
                            mp.str("how_to_get")?.let { KVRow("获取", it) }
                            if (mp.optInt("price") > 0) KVRow("价格", "${mp.optInt("price")}g")
                            mp.str("vendor")?.let { KVRow("卖家", it) }
                            mp.optJSONArray("conditions")?.joinCn()?.let { KVRow("条件", it) }
                        }
                    }
                }

                2 -> {
                    item {
                        val rows = mutableListOf<Pair<String, String>>()
                        val wd = root.getJSONObject("wedding")
                        if (wd.optInt("duration_days") > 0) {
                            rows.add("婚礼流程" to "求婚 ${wd.optInt("duration_days")} 天后举行")
                        }
                        section(wd, "ceremony", "after", into = rows)
                        PixelCard(title = "💒 婚礼", accent = c.red) {
                            rows.forEach { (k, v) -> KVRow(k, v) }
                        }
                    }
                    item {
                        val rows = mutableListOf<Pair<String, String>>()
                        val am = root.getJSONObject("after_marriage")
                        section(am, "spouse_room", "daily_gifts", "anniversary", into = rows)
                        am?.optJSONObject("children")?.let { ch ->
                            if (ch.optBoolean("can_have")) rows.add("子女" to "最多 ${ch.optInt("max_children")} 个")
                            ch.optJSONArray("conditions")?.joinCn()?.let { rows.add("生育条件" to it) }
                            if (ch.optBoolean("adoption_for_same_sex")) {
                                rows.add("领养" to "同性伴侣也可领养")
                            }
                        }
                        PixelCard(title = "🏠 婚后生活") {
                            if (rows.isEmpty()) PixelText("—")
                            rows.forEach { (k, v) -> KVRow(k, v) }
                        }
                    }
                }

                else -> {
                    item {
                        val rows = mutableListOf<Pair<String, String>>()
                        val dv = root.getJSONObject("divorce")
                        if (dv.optInt("cost") > 0) rows.add("离婚费用" to "${dv.optInt("cost")}g")
                        section(dv, "where", "effects", "remarriage", "waiting_period",
                            "remarriage_where", "remarriage_effect", into = rows)
                        if (dv.optInt("remarriage_cost") > 0) {
                            rows.add("复婚抹除记忆" to "${dv.optInt("remarriage_cost")}g")
                        }
                        PixelCard(title = "💔 离婚与复婚", accent = c.red) {
                            rows.forEach { (k, v) -> KVRow(k, v) }
                        }
                    }
                    item {
                        val rows = mutableListOf<Pair<String, String>>()
                        val kr = root.getJSONObject("krobus_roommate")
                        section(kr, "description", "pendant", "pendant_how_to_get",
                            "move_in_days", "eviction", "special_notes", into = rows)
                        kr?.optJSONArray("conditions")?.joinCn()?.let { rows.add("条件" to it) }
                        PixelCard(title = "👻 克罗布斯（同居）") {
                            rows.forEach { (k, v) -> KVRow(k, v) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
