package com.stardew.assistant.ui.search

import com.stardew.assistant.data.CodexGroup
import com.stardew.assistant.data.GameData

/**
 * v2.18 全局搜索：跨图鉴全部分类一次搜完（群友反馈「全局搜索」）。
 * v2.29：图鉴页的搜索框也接上这套索引（此前那个框只搜当前分类，占位符却写着
 * 「物品 / 菜谱 / 成就…」，言不由衷）。
 *
 * 索引只搜「条目名」不搜正文行——搜正文会让「60g」「春」这类词命中大量噪音。
 * 索引构建由调用方 remember 一次（v2.17 E3 教训：别在重组里全量扫）。
 */
data class SearchHit(
    val groupId: String,
    val groupTitle: String,
    val itemName: String,
    val index: Int,
    val summary: String,
    /** v2.29：行内来源短串（获取途径 / 收集包）。空串 = 没有可用来源。
     *  ⚠ 只放最便宜的两条，见 [sourceFor] 的成本说明。 */
    val source: String = "",
)

/** 单个分类内的结果上限，防止长列表失控（超出部分由 UI 提示） */
const val SEARCH_GROUP_LIMIT = 20

/**
 * 行内来源短串：**只算两条**。
 *
 * `GameData.usagesOf` 是详情抽屉用的全量速查（获取途径 / 收集包 / 村民最爱 / 村民喜欢 /
 * 可加工 / 由其制成 / 烹饪用料 / 制作材料），后四条要扫 gifts(34 人 × loves/likes)、
 * processing、recipeUses、craftUses。索引有 ~823 条，逐条调全量就是
 * O(823 × 大表)，每次重组都白付一遍——正是 v2.17 E3 那类卡顿。
 *
 * 这里只要 O(1) 的 itemSources 命中 + 小表 bundleRooms 扫描，
 * 村民/加工/烹饪/制作那几条留给抽屉（一次只开一个条目，开销可接受）。
 */
internal fun sourceFor(name: String): String {
    if (name.isBlank()) return ""
    val parts = mutableListOf<String>()
    GameData.itemSources[GameData.stripQuality(name)]?.let { s ->
        val p = buildList {
            if (s.season.isNotBlank()) add(s.season)
            if (s.places.isNotBlank()) add(s.places)
        }
        if (p.isNotEmpty()) parts += "📍 " + p.joinToString("｜")
    }
    // v2.100.0 / c182：收集包反查**两侧都过别名归一**（与 GameData.usagesOf 同一口径）。
    //   收集包里 10 个条目写的是别名侧（「罂粟」「动物毛」「果酒」…），规范名根本不在
    //   bundles.json 里 ⇒ 只按原名精确匹配的话，规范名条目在这条行内来源里查不到
    //   自己的收集包（与「用途速查」「🎒 你的状态」两处对不上）。
    val canon = GameData.canonicalName(name)
    val bundles = GameData.bundleRooms.flatMap { room ->
        // ⚠ filter 与 map 分两步写：`room.bundles.filter { ... }.map { "${room.room}·${it.name}" }`
        // 里 map 的 it 已经出了 filter 的作用域，拿不到被过滤条件用到的那个参数。
        room.bundles.filter { b -> b.items.any { GameData.canonicalName(it.name) == canon } }
            .map { b -> "${room.room}·${b.name}" }
    }
    if (bundles.isNotEmpty()) parts += "📦 " + bundles.joinToString("、")
    return parts.joinToString("　").take(40)
}

fun buildSearchIndex(groups: List<CodexGroup>): List<SearchHit> =
    groups.flatMap { g ->
        g.items.mapIndexedNotNull { i, item ->
            val nm = item.name
            if (nm.isBlank()) return@mapIndexedNotNull null  // 成就等无名条目不参与
            val summary = item.rows.firstOrNull()?.second
                ?: item.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            SearchHit(g.id, g.title, nm, i, summary.take(28), sourceFor(nm))
        }
    }

/** 返回顺序即图鉴分类顺序，便于按分类分组渲染 */
fun searchGlobal(index: List<SearchHit>, q: String): List<SearchHit> {
    val key = q.trim()
    if (key.isEmpty()) return emptyList()
    return index.filter { it.itemName.contains(key, ignoreCase = true) }
}

/**
 * 按分类分组，**顺序由 codexGroups 驱动**。
 *
 * ⚠ 不能用 `groupBy`：那会退化成「命中顺序」——先命中哪个分类就先显示哪个，
 * 同一个查询在不同时间点结果顺序会飘。分组顺序必须与图鉴分类顺序一致。
 */
fun groupHits(
    hits: List<SearchHit>,
    groups: List<CodexGroup>,
): List<Pair<CodexGroup, List<SearchHit>>> {
    val byId = hits.groupBy { it.groupId }
    return groups.mapNotNull { g -> byId[g.id]?.let { g to it } }
}
