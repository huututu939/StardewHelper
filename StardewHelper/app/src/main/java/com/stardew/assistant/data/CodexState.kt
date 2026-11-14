package com.stardew.assistant.data

import org.json.JSONObject

/**
 * 图鉴「你的状态」折算模型（v2.99.0 / c179，百科 × 存档第 1 层）。
 *
 * ## 与 FarmToday 的关系
 * 同一份 [WorldState] 折出来的**第二份小快照**：今日页要看「今天能收什么」，
 * 图鉴条目要看「这条对上我的什么」。两份在 [FarmTodayCache.refresh] 里一次解析
 * 同时折出、分别落盘 —— 图鉴读取时不用拖着今日页那几十行农场明细。
 *
 * ## 折算纪律（farm_today.json 立下的同一条）
 * 背包 / 好感**只折「我关心的那几项的计数」**，原样背包（几百件物品）绝不进缓存
 * —— WorldState 序列化出来几百 KB，折成几十行 JSON，既小又稳。
 * ⚠ 数量判不出（stack 缺失）的不折，免得把「不知道」折成假数字。
 *
 * ## 「查不到」的三种形态，别混
 * ① 背包计数表里没有这个键 + [unmappedItems] == 0 → 背包里确实没有（映射全成功过）；
 * ② 表里没有键 + unmappedItems > 0 → 不可下结论，UI 必须注明「背包里有未识别物品」；
 * ③ 字段本身是 null（好感点数缺失、日期判不出）→ 显示「未声明 / 以游戏内为准」，**不补 0**。
 */
data class CodexState(
    /** 房主角色名（与 FarmToday.farmer 同源：同一个 host.name）。 */
    val farmer: String,
    val year: Int?,
    /** 季节（中文：春/夏/秋/冬）。与 FarmToday.season 同一折法。 */
    val season: String,
    val day: Int?,
    /** 房主现金（与 FarmToday.money 同源：同一个 host.money）。 */
    val money: Int?,
    /**
     * 背包计数：**只房主背包**，键 = App 中文名（WorldItemMap 映射；映射不到的进
     * [unmappedItems] 计数，不进这张表）。图鉴条目 / 配方材料按名字查这张表。
     */
    val itemCounts: Map<String, Int>,
    /** 背包里映射不到 App 中文名的**件数**（>0 时「没找到」≠「绝对没有」）。 */
    val unmappedItems: Int,
    /** 田里状态：键 = App 中文名（WorldItemMap.cropName，与 FarmToday 同一条映射）。 */
    val crops: Map<String, CodexCropState>,
    /** 村民好感：键 = App 中文名（映射不到的保留英文原名 —— 不编中文名）。 */
    val friendships: Map<String, CodexNpcState>,
    /**
     * **农场（Farm）里翻过土但没种的地块数**（v2.100.0 / c181 增补）。
     *
     * 用途：今日页「今天该做什么」要回答「有没有空地该种点什么」。
     * 空地是 [WorldState.plots] 里 `crop == null` 的 HoeDirt 地块 ——
     * 翻过土没种的才算「空」，没翻过的地不在这份清单里（那是没开的荒地）。
     *
     * ⚠ **只算 Farm**：温室与姜岛的地另有季节规则（温室作物不过季），
     *   混进来会给出错的作物建议，所以只取农场那一份。
     * ⚠ **null = 判不出**（老缓存里没有这一项 / 解析不出）：此时不给「该种什么」
     *   这条建议 —— 「不知道有没有空地」不能当成「没有空地」。
     */
    val emptyPlots: Int? = null,
)

/** 田里状态（按品种折算；枯死不算进 [CodexCropState.total]，另记一列）。 */
data class CodexCropState(
    /** 田里株数（可收 + 生长中 + 成熟度判不出；枯死不算）。 */
    val total: Int,
    /** 今天能收的株数。 */
    val ready: Int,
    /** 最快几天后能收（生长中的最小 daysLeft；判不出 = null，不编）。 */
    val minDays: Int?,
    /** 枯死待清理的株数。 */
    val dead: Int,
)

/** 一个村民的存档好感（折算后，键已是 App 中文名）。 */
data class CodexNpcState(
    /** 好感点数（250 = 一颗心）。null = 存档没写，不编。 */
    val points: Int?,
    /** 本周已送礼次数（0 也如实显示；送礼明细存档里没有，UI 注明以游戏内为准）。 */
    val giftsThisWeek: Int?,
)

/**
 * 把 [WorldState] 折成 [CodexState]。
 *
 * **纯逻辑**：不碰 Context、不读文件、不切线程（单测可直接调）。
 * 名字映射与 [FarmTodayBuilder] 用**同一张** [WorldItemMap] —— 同一个数在
 * 今日页与图鉴两处不许打架，靠的就是这条同源。
 *
 * @param map 作物 / 背包物品的中文名映射（GameData.worldItemMap）
 * @param npcEnToCn 村民英文名（norm 后小写）→ App 中文名（ReportNames 的表）
 * @param npcSrcIdToCn Mod 村民 srcId → App 中文名（GameData.gifts；查不到英文名表时的第二路）
 */
object CodexStateBuilder {

    fun build(
        state: WorldState,
        map: WorldItemMap,
        npcEnToCn: Map<String, String>,
        npcSrcIdToCn: Map<String, String> = emptyMap(),
    ): CodexState {
        val host = state.host

        // ── 背包：只房主，折成「App 中文名 → 件数」────────────────────
        val counts = LinkedHashMap<String, Int>()
        var unmapped = 0
        val inv = state.inventories.firstOrNull { !it.farmhand }
        for (w in inv?.items ?: emptyList()) {
            val stack = w.stack ?: continue   // 数量判不出（工具类常缺 stack）→ 不折，不编数
            val cn = map.nameOfEn(w.name)
                ?: map.nameOfId(w.name?.toIntOrNull())
                ?: map.nameOfId(qualifiedInt(w.itemId))
            if (cn == null) {
                unmapped += stack
                continue
            }
            counts[cn] = (counts[cn] ?: 0) + stack
        }

        // ── 田里：按品种折算（名字与 FarmToday 同一条 cropName 映射）────
        data class Acc(var total: Int, var ready: Int, var minDays: Int?, var dead: Int)
        val acc = LinkedHashMap<String, Acc>()
        for (c in state.crops) {
            val nm = map.cropName(c) ?: continue   // 查不到名字 → 不进清单（与 FarmToday 同款丢弃）
            val a = acc.getOrPut(nm) { Acc(0, 0, null, 0) }
            if (c.isDead) {
                a.dead++
                continue
            }
            a.total++
            if (c.ready == true) a.ready++
            // 最快能收天数只对「还在长且判得出天数」的算；判不出不冒充
            if (c.ready == false) {
                val dl = c.daysLeft
                if (dl != null) a.minDays = minOf(a.minDays ?: dl, dl)
            }
        }
        val crops = acc.mapValues { (_, a) -> CodexCropState(a.total, a.ready, a.minDays, a.dead) }

        // ── 村民好感：英文名 → App 中文名（映射不到保留原名，不编）──────
        val friends = LinkedHashMap<String, CodexNpcState>()
        for (f in state.friendships) {
            val en = f.name?.trim() ?: continue
            if (en.isEmpty()) continue
            val cn = npcSrcIdToCn[en] ?: npcEnToCn[en.lowercase()] ?: en
            friends[cn] = CodexNpcState(points = f.points, giftsThisWeek = f.giftsThisWeek)
        }

        // ── 农场空地（v2.100.0 / c181）：Farm 地图上翻过土但没种的地块 ──
        // ⚠ 只取 Farm：温室 / 姜岛的季节规则不同，混进来会给出错的作物建议。
        val empty = state.plots.count { it.location == "Farm" && it.crop == null }

        return CodexState(
            farmer = host?.name?.takeIf { it.isNotEmpty() } ?: "？",
            year = state.year,
            season = FarmTodayBuilder.seasonName(state.season),
            day = state.dayOfMonth,
            money = host?.money,
            itemCounts = counts,
            unmappedItems = unmapped,
            crops = crops,
            friendships = friends,
            emptyPlots = empty,
        )
    }
}

// ── 缓存用的 JSON 序列化（org.json，App 与单测都能跑）──────────────────

internal fun CodexState.toJson(): String {
    val root = JSONObject()
    root.put("farmer", farmer)
    if (year != null) root.put("year", year) else root.put("year", JSONObject.NULL)
    root.put("season", season)
    if (day != null) root.put("day", day) else root.put("day", JSONObject.NULL)
    if (money != null) root.put("money", money) else root.put("money", JSONObject.NULL)
    val co = JSONObject()
    itemCounts.forEach { (k, v) -> co.put(k, v) }
    root.put("counts", co)
    root.put("unmapped", unmappedItems)
    val cj = JSONObject()
    crops.forEach { (k, s) ->
        cj.put(
            k,
            JSONObject()
                .put("t", s.total)
                .put("r", s.ready)
                .put("d", s.minDays ?: JSONObject.NULL)
                .put("x", s.dead),
        )
    }
    root.put("crops", cj)
    val fj = JSONObject()
    friendships.forEach { (k, s) ->
        fj.put(
            k,
            JSONObject()
                .put("p", s.points ?: JSONObject.NULL)
                .put("g", s.giftsThisWeek ?: JSONObject.NULL),
        )
    }
    root.put("friends", fj)
    // v2.100.0 / c181：空地数。老缓存（c179 写的）没有这个键 ⇒ 读出来是 null
    //（「判不出」而不是「0 块空地」—— 两者在建议区里的后果完全不同）。
    if (emptyPlots != null) root.put("emptyPlots", emptyPlots) else root.put("emptyPlots", JSONObject.NULL)
    return root.toString()
}

internal fun codexStateFromJson(text: String): CodexState? = try {
    val root = JSONObject(text)
    fun counts(): Map<String, Int> {
        val o = root.optJSONObject("counts") ?: return emptyMap()
        val out = LinkedHashMap<String, Int>(o.length())
        o.keys().forEach { k -> out[k] = o.optInt(k) }
        return out
    }
    fun crops(): Map<String, CodexCropState> {
        val o = root.optJSONObject("crops") ?: return emptyMap()
        val out = LinkedHashMap<String, CodexCropState>(o.length())
        o.keys().forEach { k ->
            val s = o.getJSONObject(k)
            out[k] = CodexCropState(
                total = s.optInt("t"),
                ready = s.optInt("r"),
                minDays = if (s.isNull("d")) null else s.optInt("d"),
                dead = s.optInt("x"),
            )
        }
        return out
    }
    fun friends(): Map<String, CodexNpcState> {
        val o = root.optJSONObject("friends") ?: return emptyMap()
        val out = LinkedHashMap<String, CodexNpcState>(o.length())
        o.keys().forEach { k ->
            val s = o.getJSONObject(k)
            out[k] = CodexNpcState(
                points = if (s.isNull("p")) null else s.optInt("p"),
                giftsThisWeek = if (s.isNull("g")) null else s.optInt("g"),
            )
        }
        return out
    }
    CodexState(
        farmer = root.optString("farmer"),
        year = if (root.isNull("year")) null else root.optInt("year"),
        season = root.optString("season"),
        day = if (root.isNull("day")) null else root.optInt("day"),
        money = if (root.isNull("money")) null else root.optInt("money"),
        itemCounts = counts(),
        unmappedItems = root.optInt("unmapped"),
        crops = crops(),
        friendships = friends(),
        emptyPlots = if (root.isNull("emptyPlots")) null else root.optInt("emptyPlots"),
    )
} catch (e: Exception) {
    null
}
