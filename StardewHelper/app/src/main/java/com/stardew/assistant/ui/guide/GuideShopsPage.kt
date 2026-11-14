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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.CollapsibleCard
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSearchClear
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import org.json.JSONObject

/** 店名 -> 首页 emoji（与 shopHours 的 15 家一一对应；无目录数据的店也在此列） */
private val SHOP_EMOJI = mapOf(
    "皮埃尔的杂货店" to "🥕", "Joja超市" to "🏪", "铁匠铺" to "⚒", "星之果实酒吧" to "🍺",
    "哈维的诊所" to "💊", "冰淇淋摊" to "🍦", "电影院" to "🎬", "木匠的商店" to "🪚",
    "玛妮的牧场" to "🐄", "法师塔" to "🧙", "旅行货车" to "🛒", "冒险家公会" to "⚔",
    "鱼店" to "🎣", "绿洲" to "🌴", "沙漠商人" to "🏜",
)

/** 无商品目录的店的说明（数据去向指引），避免整店空白 */
private val NO_CATALOG_NOTE = mapOf(
    "哈维的诊所" to "只卖 2 种补剂（能量特饮/肌肉修复剂），且无金币价以外的攻略信息",
    "冰淇淋摊" to "夏季限定，只卖冰淇淋一种",
    "电影院" to "小吃清单在「武器收集」旁的攻略没有；电影与小吃见年历页的节日提示",
    "法师塔" to "只卖 6 种特殊建筑（大地/水/沙漠/岛屿方尖碑等），见「建造」相关攻略",
)

private fun priceText(o: JSONObject): String {
    val unit = o.optString("unit")
    val price = o.optInt("price", -1)
    if (price < 0) return ""
    return if (unit.isNotEmpty()) "$price × $unit" else "$price g"
}

/**
 * v2.69：一组商品行。**保持 shopCatalog.json 里的原始顺序，不排序**——
 * 数据顺序本身带信息（官方维基的货架排列），按价格重排会毁掉「这东西在店里排第几」这类对照。
 */
@Composable
private fun ShopRows(rows: List<JSONObject>) {
    val c = LocalPixel.current
    rows.forEachIndexed { j, o ->
        val qual = o.optString("qual")
        val note = o.optString("note")
        val p2 = if (o.has("price2")) "（第2年 ${o.getInt("price2")} g）" else ""
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                PixelText(
                    if (qual.isNotEmpty()) "${o.getString("name")} · $qual"
                    else o.getString("name"),
                )
                if (note.isNotEmpty()) {
                    PixelText(note, style = Px.caption, color = c.inkDim)
                }
            }
            PixelText(priceText(o), color = c.green)
        }
        if (p2.isNotEmpty()) {
            PixelText(
                p2.removePrefix("（").removeSuffix("）"),
                style = Px.caption, color = c.inkDim,
            )
        }
        if (j < rows.size - 1) RowSep()
    }
}

/**
 * v2.69：展开一家店之后的内容 —— 店内搜索 + 按 avail 分组。
 *
 * 分组只在**分组数 ≥2** 时才折：实测皮埃尔/Joja 各 5 组（全年/春/夏/秋 真有季节含义），
 * 而旅行货车只有 2 组、256 行挤在同一组——对它折一层等于没折，展开仍是 256 行。
 * 那种店只能靠**搜索**救，所以搜索框是无条件提供的，且**搜索时不套第二层折叠**：
 * 都命中了还要展开两层是反人类的。
 */
@Composable
private fun ShopBody(
    shopName: String,
    rowList: List<JSONObject>,
) {
    val c = LocalPixel.current
    var query by remember(shopName) { mutableStateOf("") }
    // 名字单向子串匹配。⚠ 不做双向包含——CodexIndex.kt 记着那个坑：
    //   双向包含会把「咖啡」送进「咖啡豆」、「鱿鱼墨汁」送进「鱿鱼」，是错的条目。
    val shown = remember(rowList, query) {
        if (query.isBlank()) rowList
        else rowList.filter { it.optString("name").contains(query.trim(), ignoreCase = true) }
    }

    Spacer(Modifier.height(4.dp))
    // 店内搜索框（写法照 GuideCombatPage 的怪物过滤框）
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.slot)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (query.isEmpty()) {
            PixelText("🔍 在这家店里搜商品（共 ${rowList.size} 种）", color = c.inkDim)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = { query = it },
            textStyle = Px.body.copy(color = c.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink),
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.isNotEmpty()) {
            PixelSearchClear(
                onClear = { query = "" },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    Spacer(Modifier.height(6.dp))

    if (shown.isEmpty()) {
        PixelText(
            "没有匹配「${query.trim()}」的商品",
            style = Px.caption, color = c.inkDim,
        )
        return
    }
    if (query.isNotBlank()) {
        PixelText("命中 ${shown.size} 种", style = Px.caption, color = c.blue)
        Spacer(Modifier.height(4.dp))
        ShopRows(shown)
        return
    }
    // 按 avail 分组，**保持数据原顺序**（groupBy 对 List 保序）
    val groups = shown.groupBy { it.optString("avail") }
    if (groups.size >= 2) {
        groups.forEach { (avail, items) ->
            var gOpen by remember(shopName, avail) { mutableStateOf(false) }
            CollapsibleCard(
                title = "$avail（${items.size} 种）",
                expanded = gOpen,
                onToggle = { gOpen = !gOpen },
                accent = c.blue,
            ) { ShopRows(items) }
            Spacer(Modifier.height(6.dp))
        }
    } else {
        ShopRows(shown)
    }
}

/**
 * 🛒 商店目录（v2.56.0 起的功能，v2.69 重排）。
 *
 * ## 为什么以前看着乱
 * 12 家店 **486 行**商品全平铺在一列 `LazyColumn` 里，店卡片**全部默认展开**。
 * 其中旅行货车一家 257 行，占全部的 53%，而它 256 行的 `avail` 都是「每周随机」——
 * 也就是「这 256 件可能卖、也可能不卖」，信息密度最低却占屏最多。
 *
 * ## v2.69 的三级下钻
 * ① **店卡默认收起**：折叠态一行「营业时间 · N 种」。攻略页的价值是**按需查**，
 *    不是一次看完 486 行。展开状态按店名记忆，换店自动收起。
 *    ⚠ 刻意**不做**「行数少就平铺」的阈值——那是 CodexPage 省首屏空间的逻辑；
 *    冰淇淋摊 1 行、铁匠铺 3 行，套阈值等于白折一层。这里要省的是整页的信息量。
 * ② **按 avail 分组，仅分组数 ≥2 才折**（见 [ShopBody]）。
 * ③ **店内搜索**：无条件提供，救「旅行货车这种只有两组、折了也没用」的店。
 *
 * 数据由 tools/build_shop_catalog.py 从英文 wiki 1.6 商品表生成：
 * 只收录**项目图鉴有对应物品**的行（译名五层对齐：字典/互链/派生/哈希/像素哈希），
 * 家具壁纸图册类无中文口径的直接剔除——宁缺毋滥，点了跳不动或显示生英文比缺一行糟糕得多。
 */
@Composable
fun GuideShopsPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val catalog = GameData.shopCatalog
    val shops = remember(GameData.shopHours, catalog) {
        // 顺序跟 shopHours（15 家），目录数据按店名挂上去
        GameData.shopHours.map { h -> Pair(h, catalog?.optJSONArray(h.name)) }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("🛒 商店目录", onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = guideContentPadding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PixelCard {
                    PixelText(
                        "15 家店的货架清单与金币价。数据来自官方维基 1.6，" +
                            "只收录游戏内有图鉴对应的物品；轮换类以「每周随机」标注，" +
                            "实际每次进店会抽一部分。点店名展开，展开后可搜店内商品。",
                        style = Px.caption, color = c.inkDim,
                    )
                }
            }
            if (catalog == null) {
                item {
                    PixelCard(accent = c.red) {
                        PixelText("商店目录数据加载失败", color = c.ink)
                    }
                }
                return@LazyColumn
            }
            items(shops.size) { i ->
                val (h, rows) = shops[i]
                val name = h.name
                val rowList = remember(rows) {
                    if (rows == null) emptyList() else
                        (0 until rows.length()).map { rows.getJSONObject(it) }
                }
                val closedTxt =
                    if (h.closedDays.isNotEmpty()) " · 休 ${h.closedDays.joinToString("、")}" else ""
                // 折叠态按店名记忆且默认 false：进页面一眼看完所有店，想看哪家点哪家
                var expanded by remember(name) { mutableStateOf(false) }

                CollapsibleCard(
                    title = "${SHOP_EMOJI[name] ?: "🏪"} $name",
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    collapsedHint = "🕐 ${h.hours}$closedTxt · ${rowList.size} 种",
                    accent = c.gold,
                ) {
                    if (rowList.isEmpty()) {
                        PixelText(
                            NO_CATALOG_NOTE[name] ?: "该店没有可列出的商品数据",
                            style = Px.caption, color = c.inkDim,
                        )
                    } else {
                        ShopBody(name, rowList)
                    }
                }
            }
        }
    }
}
