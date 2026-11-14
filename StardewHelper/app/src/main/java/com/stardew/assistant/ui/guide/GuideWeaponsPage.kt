package com.stardew.assistant.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.pressFeedback
import org.json.JSONObject
import java.text.DecimalFormat

/** 获取方式枚举 → 中文标签。weapon_compendium.acquisition.type 的取值。 */
private val ACQ_CN = mapOf(
    "initial" to "初始装备",
    "chest" to "宝箱",
    "purchase" to "商店购买",
    "quest" to "任务奖励",
    "drop" to "怪物掉落",
    "event" to "事件获得",
    "forge" to "铁匠铺锻造",
)
private fun acqCn(t: String): String = ACQ_CN[t] ?: t

private fun pct(v: Double): String = "%.1f%%".format(v * 100)

/** v2.14 武器收集：63 件武器 + 分类筛选 + 详情抽屉 + 5 条收集提示 */
@Composable
fun GuideWeaponsPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.weaponCompendium
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("⚔ 武器收集", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("武器") }
        }
        return
    }

    val weapons = remember(root) { root.getJSONArray("weapons") }
    val types = remember(root) { root.optJSONObject("weapon_types") }
    val tips = remember(root) { root.optJSONArray("collection_tips") }

    val typeIds = remember(types) {
        listOf("") + (types?.keys()?.asSequence()?.toList()?.sorted() ?: emptyList())
    }
    var typeIdx by remember { mutableIntStateOf(0) }
    var detail by remember { mutableIntStateOf(-1) }

    // 选中筛选后，把数组下标重新映射回原始下标（抽屉翻页/图标都要用原始下标）
    val shown = remember(weapons, typeIdx) {
        val out = mutableListOf<Int>()
        for (i in 0 until weapons.length()) {
            if (typeIdx == 0 || weapons.getJSONObject(i).optString("type") == typeIds[typeIdx]) {
                out.add(i)
            }
        }
        out
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("⚔ 武器收集", onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // v2.17 E4：类型计数 remember 缓存——旧实现每次重组对 63 件 × 4 类型全量重扫
            val typeOptions = remember(weapons, types, typeIds) {
                listOf("全部 ${weapons.length()}") +
                    typeIds.drop(1).map { "${types?.optString(it) ?: it} ${countOf(weapons, it)}" }
            }
            PixelSegmented(
                options = typeOptions,
                selected = typeIdx,
                onSelect = { typeIdx = it },
                modifier = Modifier.fillMaxWidth(),
            )
            PixelText(
                "当前 ${shown.size} 件 · 点条目看获取方式",
                style = Px.caption, color = c.inkDim,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = guideContentPadding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown.size) { n ->
                val rawIdx = shown[n]
                val o = weapons.getJSONObject(rawIdx)
                val name = o.optString("name")
                val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().background(c.parchment)
                        .pressFeedback(isrc)
                        .clickable(interactionSource = isrc, indication = null) { detail = rawIdx }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 63 件武器暂无图标素材，走 FramedIcon 的占位分支（不崩）
                    FramedIcon(name, 26)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        PixelText(name)
                        PixelText(
                            "${o.optString("damage")} 伤害 · ${acqCn(o.optJSONObject("acquisition")?.optString("type") ?: "")}",
                            style = Px.caption, color = c.inkDim,
                        )
                    }
                    PixelText("${o.optInt("sell_price")}g", style = Px.caption, color = c.inkDim)
                }
            }
            if (tips != null) {
                item {
                    Spacer(Modifier.height(10.dp))
                    PixelCard(title = "💡 容易错过的", accent = c.gold) {
                        for (i in 0 until tips.length()) {
                            val t = tips.getJSONObject(i)
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            PixelText(t.optString("title"), color = c.gold)
                            PixelText(t.optString("content"), style = Px.caption)
                        }
                    }
                }
            }
        }
    }

    if (detail >= 0 && detail < weapons.length()) {
        val o = weapons.getJSONObject(detail)
        DetailDrawer(visible = true, title = o.optString("name"), onDismiss = { detail = -1 }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                PixelCard(title = "⚔ 属性") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FramedIcon(o.optString("name"), 34)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            PixelText(o.optString("name_en"), style = Px.caption, color = c.inkDim)
                            PixelText("类型 ${types?.optString(o.optString("type")) ?: o.optString("type")}")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    KVRow("伤害", "${o.optInt("damage_min")}-${o.optInt("damage_max")}")
                    if (o.has("crit_chance")) KVRow("暴击率", pct(o.optDouble("crit_chance", 0.0)))
                    if (o.has("crit_power")) KVRow("暴击倍率", "×%.1f".format(o.optDouble("crit_power", 1.0)))
                    if (o.has("speed")) KVRow("速度", "${o.optInt("speed")}")
                    if (o.has("defense")) KVRow("防御", "${o.optInt("defense")}")
                    if (o.has("weight")) KVRow("重量", "${o.optInt("weight")}")
                    if (o.optInt("sell_price") > 0) KVRow("售价", "${o.optInt("sell_price")}g")
                    if (o.optBoolean("can_be_forged")) KVRow("锻造", "可在铁匠铺用材料锻造")
                }
                Spacer(Modifier.height(10.dp))
                val acq = o.optJSONObject("acquisition")
                PixelCard(title = "🔑 获取方式", accent = c.gold) {
                    if (acq == null) {
                        PixelText("—")
                    } else {
                        KVRow("方式", acqCn(acq.optString("type")))
                        acq.optString("description").takeIf { it.isNotBlank() }
                            ?.let { KVRow("说明", it) }
                        acq.optString("location").takeIf { it.isNotBlank() }
                            ?.let { KVRow("地点", it) }
                        acq.optString("npc").takeIf { it.isNotBlank() }
                            ?.let { KVRow("相关 NPC", it) }
                        if (acq.optInt("price") > 0) KVRow("价格", "${acq.optInt("price")}g")
                        acq.optString("drop_from").takeIf { it.isNotBlank() }
                            ?.let { KVRow("掉落自", it) }
                        acq.optJSONArray("forge_materials")?.let { m ->
                            if (m.length() > 0) {
                                KVRow("锻造材料", (0 until m.length()).map { m.getString(it) }.joinToString("、"))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun countOf(weapons: org.json.JSONArray, type: String): Int {
    var n = 0
    for (i in 0 until weapons.length()) {
        if (weapons.getJSONObject(i).optString("type") == type) n++
    }
    return n
}
