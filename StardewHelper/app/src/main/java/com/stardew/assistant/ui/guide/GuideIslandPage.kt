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
import org.json.JSONObject

/** 把 dict 里所有非空值渲染成「中文标签 → 值」行；嵌套 dict 展开为「父 · 子」。
 *  - key 走 [cnKey] 翻译，未收录的保留英文（宁可露英文不显示错中文）
 *  - 数字拼 [VALUE_SUFFIX] 单位
 *  - 字符串数组 → 单行标签，值列逐条 bullet 换行（原来 joinToString 会挤成一坨）
 *  - 对象数组 → 逐条递归并加序号（stock 走火山卡专门渲染，这里只是兜底，避免原始 JSON 上屏） */
private fun JSONObject.kvLines(prefix: String = ""): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    keys().forEach { k ->
        val v = opt(k)
        val label = if (prefix.isEmpty()) cnKey(k) else "$prefix · ${cnKey(k)}"
        when (v) {
            is String -> if (v.isNotBlank()) out.add(label to v)
            is Number -> out.add(label to v.toString() + (VALUE_SUFFIX[k] ?: ""))
            is Boolean -> if (v) out.add(label to "是")
            is JSONObject -> out.addAll(v.kvLines(label))
            is org.json.JSONArray -> {
                if (v.length() == 0) return@forEach
                if (v.opt(0) is JSONObject) {
                    for (i in 0 until v.length()) {
                        v.optJSONObject(i)?.kvLines("$label ${i + 1}")?.let { out.addAll(it) }
                    }
                } else {
                    out.add(label to (0 until v.length()).joinToString("\n") { "· ${v.optString(it)}" })
                }
            }
        }
    }
    return out
}

/** v2.14 姜岛攻略：分段浏览解锁 / 金核桃 / 鹦鹉 / 火山 / 其他 */
@Composable
fun GuideIslandPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.gingerIslandGuide
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("🏝 姜岛攻略", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("姜岛") }
        }
        return
    }

    // v2.17 D8：删除未使用的 sec（旧分段 id 列表，现由 titles 直接驱动 UI）
    val titles = listOf("解锁", "金核桃", "鹦鹉", "火山", "其他")
    var idx by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        BackBar("🏝 姜岛攻略", onBack)
        PixelSegmented(
            options = titles,
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
                    val ov = root.getJSONObject("island_overview")
                    item {
                        PixelCard(title = "🚢 如何去姜岛", accent = c.gold) {
                            PixelText(ov.optString("how_to_unlock"))
                            Spacer(Modifier.height(8.dp))
                            ov.optJSONObject("boat_repair")?.let { b ->
                                PixelText("修船材料", color = c.gold)
                                RowSpacer2()
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    b.keys().forEach { k ->
                                        Chip("${ITEM_CN[k] ?: k} ×${b.getInt(k)}", border = c.gold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            ov.optJSONArray("regions")?.let { r ->
                                PixelText("六大区域", color = c.gold)
                                Spacer(Modifier.height(4.dp))
                                PixelText((0 until r.length()).joinToString("、") { r.getString(it) },
                                    style = Px.caption)
                            }
                        }
                    }
                    item {
                        PixelCard(title = "💰 往返船票") {
                            KVRow("单程费用", "${ov.optInt("travel_cost")}g")
                            ov.optString("travel_cost_note").takeIf { it.isNotBlank() }
                                ?.let { KVRow("说明", it) }
                        }
                    }
                }

                1 -> {
                    val gw = root.getJSONObject("golden_walnuts")
                    item {
                        PixelCard(title = "🥥 金核桃 合计 ${gw.optInt("total")} 个", accent = c.gold) {
                            gw.optString("description").takeIf { it.isNotBlank() }
                                ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                            val src = gw.optJSONArray("sources")
                            val spend = gw.optJSONArray("spending")
                            PixelText(
                                "来源 ${src?.length() ?: 0} 条 · 消费 ${spend?.length() ?: 0} 项 · 已花 ${gw.optInt("total_spent")}",
                                style = Px.caption, color = c.inkDim,
                            )
                        }
                    }
                    // sources 挂在 golden_walnuts 下，不在根节点（写成 root 会抛 JSONException 闪退）
                    val src = gw.optJSONArray("sources") ?: org.json.JSONArray()
                    items(src.length()) { i ->
                        val o = src.getJSONObject(i)
                        PixelCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PixelText(o.optString("method"), modifier = Modifier.weight(1f))
                                Chip("+${o.optInt("amount")}", border = c.gold)
                            }
                            o.optString("location").takeIf { it.isNotBlank() }
                                ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                        }
                    }
                }

                2 -> {
                    val ps = root.optJSONArray("parrot_services") ?: org.json.JSONArray()
                    items(ps.length()) { i ->
                        val o = ps.getJSONObject(i)
                        PixelCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PixelText("🦜 ${o.optString("name")}", modifier = Modifier.weight(1f))
                                Chip("${o.optInt("cost")} 🥥", border = c.green)
                            }
                            Spacer(Modifier.height(4.dp))
                            KVRow("地点", o.optString("location"))
                            o.optString("effect").takeIf { it.isNotBlank() }
                                ?.let { KVRow("效果", it) }
                        }
                    }
                }

                3 -> {
                    val vd = root.getJSONObject("volcano_dungeon")
                    item {
                        PixelCard(title = "🌋 火山地牢", accent = c.red) {
                            vd.optString("unlock").takeIf { it.isNotBlank() }
                                ?.let { KVRow("解锁", it) }
                            val floors = vd.optString("floors")
                            if (floors.isNotBlank()) KVRow("层数", floors)
                            // features 是字符串数组：optString 会把整个 JSON 原样吐上屏（v2.14.1 手测反馈）
                            vd.optJSONArray("features")?.let { f ->
                                if (f.length() > 0) {
                                    KVRow(
                                        "要点",
                                        (0 until f.length()).joinToString("\n") { "· ${f.optString(it)}" },
                                    )
                                }
                            }
                        }
                    }
                    val shop = vd.optJSONObject("floor_5_shop")
                    if (shop != null) {
                        item {
                            // stock 每条是 {item, item_en, price, available}，price 还混着数字和
                            // 「火晶石x100」两种写法，走通用 kvLines 会变成原始 JSON（v2.14.1 手测反馈）
                            val shopName = shop.optString("name")
                            PixelCard(
                                title = if (shopName.isBlank()) "🛒 5 层商店" else "🛒 5 层商店 · $shopName",
                            ) {
                                shop.optString("requires").takeIf { it.isNotBlank() }
                                    ?.let { KVRow("前置条件", it) }
                                val stock = shop.optJSONArray("stock") ?: org.json.JSONArray()
                                if (stock.length() > 0) {
                                    PixelText("在售 ${stock.length()} 件", color = c.gold)
                                    for (i in 0 until stock.length()) {
                                        val s = stock.optJSONObject(i) ?: continue
                                        if (i > 0) RowSep()
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                PixelText(s.optString("item"))
                                                val sub = listOf(
                                                    s.optString("item_en"),
                                                    s.optString("available"),
                                                ).filter { it.isNotBlank() }.joinToString(" · ")
                                                if (sub.isNotEmpty()) {
                                                    PixelText(sub, style = Px.caption, color = c.inkDim)
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            val price = s.opt("price")
                                            Chip(
                                                if (price is Number) "${price}g" else s.optString("price"),
                                                border = c.gold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // 每地点独立成卡；原来挤在一张大卡里且 take(5) 截断，宝石鸟的
                    // 谜题/奖励/雨天提示根本没显示出来（v2.14.1 手测反馈）
                    listOf(
                        "gem_birds" to "💎 宝石鸟",
                        "pirate_cove" to "🏴 海盗湾",
                        "mr_qi_walnut_room" to "🥥 齐先生核桃房",
                        "island_trader" to "🤝 岛民商人",
                        "island_farm" to "🌱 姜岛农场",
                    ).forEach { (key, label) ->
                        val o = root.optJSONObject(key) ?: return@forEach
                        item {
                            PixelCard(title = label) {
                                o.kvLines().forEach { (k, v) -> KVRow(k, v) }
                            }
                        }
                    }
                    val hs = root.optJSONArray("hidden_secrets") ?: org.json.JSONArray()
                    item {
                        PixelCard(title = "🔍 隐藏要素 ${hs.length()} 处", accent = c.gold) {
                            if (hs.length() == 0) {
                                PixelText("—")
                            } else {
                                for (i in 0 until hs.length()) {
                                    val o = hs.getJSONObject(i)
                                    if (i > 0) Spacer(Modifier.height(8.dp))
                                    PixelText("· ${o.optString("name")}")
                                    o.optString("location").takeIf { it.isNotBlank() }
                                        ?.let { PixelText("位置 $it", style = Px.caption, color = c.inkDim) }
                                    o.optString("how_to_trigger").takeIf { it.isNotBlank() }
                                        ?.let { PixelText("触发 $it", style = Px.caption) }
                                    o.optString("reward").takeIf { it.isNotBlank() }
                                        ?.let { PixelText("奖励 $it", style = Px.caption) }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private val ITEM_CN = mapOf(
    "hardwood" to "硬木", "iridium_bar" to "铱锭", "battery_pack" to "电池组",
    "gold" to "金锭", "refined_quartz" to "精制石英", "coconut" to "椰子",
)

@Composable
private fun RowSpacer2() = Spacer(Modifier.height(4.dp))
