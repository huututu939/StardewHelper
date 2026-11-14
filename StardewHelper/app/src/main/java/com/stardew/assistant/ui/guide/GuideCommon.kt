package com.stardew.assistant.ui.guide

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stardew.assistant.ui.glass.LocalGlassInset
import org.json.JSONObject

/**
 * v2.72.2：攻略正文列表的 contentPadding（横向 12dp，纵向由各册自己定）。
 *
 * ## 为什么不是「入口处套一层 padding」
 * 上一版（v2.72.2 初稿）在 CodexPage / GuideSubPage 外面包了个 `Modifier.padding(bottom=inset)`。
 * 看着等价、实际不是：那样做是**压缩视口**——视口外的玻璃区是块死区，卡在半路的卡片被拦腰截断，
 * 下面一片空白但**滑不过去**（用户截图：天气与幸运那栏断掉，下面一片绿）。
 * 要的是**预留可滚动空白**：contentPadding 的 bottom 属于列表内容，能滚过去，
 * 最后一条停在胶囊上方、下面还留着一段空白（与今日/计算/图鉴/村民/进度五页同款做法）。
 *
 * ## 为什么要这个函数而不是十三个页面各写一遍 `bottom = 4.dp + LocalGlassInset.current`
 * 十三个 LazyColumn 各写一份公式，迟早有一份漏改或写错（本仓库「判定口径散落必然漂移」的老坑）。
 * 这里把公式收成一处，各册只声明自己那一个**纵向间距**（4 / 6 / 10dp 不等，保持原样不动）。
 * 结果与主 tab 五页的 contentPadding 逐像素同构。
 *
 * 木纹模式 / 低版本 [LocalGlassInset] = 0.dp，布局与改前完全一致。
 */
@Composable
internal fun guideContentPadding(vertical: Dp): PaddingValues = PaddingValues(
    start = 12.dp, top = vertical, end = 12.dp,
    bottom = vertical + LocalGlassInset.current,
)

/** 攻略四册通用：JSON key → 中文标签。
 *
 *  数据源是英文 wiki 直出的原始 key，UI 若直接把 key 当标签上屏，就会出现
 *  「name / stock / ceremony」满屏英文的问题（v2.14.1 手测反馈）。
 *  这里统一收口；未收录的 key 原样返回——宁可露出英文，也不能显示一个猜错的中文。 */
internal val GUIDE_KEY_CN = mapOf(
    // ── 通用 ──
    "name" to "名称", "name_en" to "英文名", "description" to "说明",
    "note" to "备注", "location" to "位置", "effect" to "效果", "cost" to "花费",
    "total" to "总数", "total_spent" to "已花费", "reward" to "奖励",
    "features" to "要点", "unlock" to "解锁方式", "requires" to "前置条件",
    "conditions" to "条件", "method" to "方式", "amount" to "数量",
    "price" to "价格", "hp" to "生命", "damage" to "伤害",

    // ── 姜岛概览 / 金核桃 ──
    "how_to_unlock" to "如何解锁", "boat_repair" to "修船材料",
    "travel_cost" to "船费", "travel_cost_note" to "船费说明", "regions" to "区域",
    "sources" to "来源", "spending" to "消耗", "extra_trade" to "额外兑换",
    "bug_note" to "备注", "golden_joja_parrot" to "金色乔贾鹦鹉",

    // ── 鹦鹉服务 ──
    "service" to "服务", "service_en" to "服务英文名",

    // ── 火山地牢 ──
    "floors" to "层数", "random_floors" to "随机楼层", "monsters" to "怪物",
    "floor_5_shop" to "5 层商店", "floor_10_forge" to "10 层锻造台",
    "treasure" to "宝箱", "metal_crates" to "金属箱掉落", "hidden_exit" to "隐藏出口",
    "walnuts_inside" to "地牢内金核桃", "stock" to "在售商品", "available" to "供应",
    "item" to "物品", "item_en" to "物品英文名",
    "common_chest_first" to "普通宝箱首开", "rare_chest_first" to "稀有宝箱首开",
    "common_loot" to "普通掉落", "rare_loot" to "稀有掉落", "secrets" to "隐藏要素",

    // ── 其他地点 ──
    "locations" to "位置分布", "north" to "北", "east" to "东", "south" to "南",
    "west" to "西", "puzzle" to "谜题", "rain_note" to "雨天提示",
    "special_loot" to "额外掉落", "unlock_cost" to "解锁费用",
    "actual_required" to "实际需要", "qi_challenges_examples" to "齐先生任务示例",
    "shop_items_examples" to "商店商品示例", "currency" to "货币",
    "exclusive_items" to "独家商品", "plantable_tiles" to "可种植格数",
    "cave" to "青蛙洞",

    // ── 结婚攻略 ──
    "ceremony" to "婚礼仪式", "after" to "婚后安排", "spouse_room" to "配偶房间",
    "daily_gifts" to "每日礼物", "anniversary" to "结婚纪念日", "where" to "办理地点",
    "effects" to "影响", "remarriage" to "复婚", "waiting_period" to "等待期",
    "remarriage_where" to "复婚地点", "remarriage_effect" to "复婚影响",
    // pendant 只在克罗布斯同居段出现，值是虚空幽灵吊坠，不能写成美人鱼吊坠
    "pendant" to "同居吊坠", "pendant_how_to_get" to "吊坠获取方式",
    "move_in_days" to "搬入天数", "eviction" to "驱逐", "special_notes" to "特别说明",
)

/** key 转中文；未收录的原样返回，避免显示猜测出来的错误中文 */
internal fun cnKey(k: String): String = GUIDE_KEY_CN[k] ?: k

/** 数值型 key 的单位/注释，拼在值后面（标签列只有 92dp，单位放不下） */
internal val VALUE_SUFFIX = mapOf(
    "unlock_cost" to " 个金核桃",
    // 值本身就是 101，后缀只解释「为什么比 100 多 1」
    "actual_required" to " 个金核桃（首颗不计数）",
    "plantable_tiles" to " 格",
    "total" to " 个",
    "total_spent" to " 个",
    "cost" to " 🥥",
    "move_in_days" to " 天",
    "duration_days" to " 天",
)

/** v2.17 A1：「值为对象」的字段渲染助手。
 *  直接 optString 会把 JSONObject 字符串化 → 原始 JSON 上屏（weather_effects 踩中，
 *  与 v2.14.2 修过的「数组当字符串读」同型，这是第三次）。
 *  对象取 effect 主文，fish_bonus / notes 各起一行；纯字符串原样返回。 */
internal fun effectText(root: JSONObject, key: String): String {
    val o = root.optJSONObject(key) ?: return root.optString(key)
    val out = StringBuilder()
    o.optString("effect").takeIf { it.isNotBlank() }?.let { out.append(it) }
    o.optString("fish_bonus").takeIf { it.isNotBlank() }?.let {
        if (out.isNotEmpty()) out.append('\n')
        out.append("🎣 ").append(it)
    }
    o.optString("notes").takeIf { it.isNotBlank() }?.let {
        if (out.isNotEmpty()) out.append('\n')
        out.append("📌 ").append(it)
    }
    return out.toString().ifEmpty { root.optString(key) }
}
