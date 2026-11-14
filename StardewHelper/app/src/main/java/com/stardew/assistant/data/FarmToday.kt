package com.stardew.assistant.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「我的农场」的**展示模型**：今日页真正要用的那几屏数据。
 *
 * ## 为什么缓存的是它、不是整个 WorldState
 * WorldState 是**存档的原貌**（578 株作物各自带坐标、阶段、产量区间…），
 * 序列化出来几百 KB，字段多、以后一改模型就容易和缓存格式对不上。
 * 而今日页只用到四块汇总（今天能收 / 马上要熟 / 枯死 / 牲畜待办），
 * 折成几十行 JSON，既小又稳 —— 缓存它，重新打开 App 不用再啃 35MB 存档。
 *
 * 真身（[WorldState]）仍然只存在于内存里，解析完立刻丢掉。
 */
data class FarmToday(
    /** 房主角色名。 */
    val farmer: String,
    val year: Int?,
    /** 季节（中文：春/夏/秋/冬）。 */
    val season: String,
    val day: Int?,
    /** 房主现金。 */
    val money: Int?,
    /** 今天能收的**总株数**（含查不到名字的野生采集物）。 */
    val readyStocks: Int,
    /** 今天能收的（按品种分组，带单价与合计）。 */
    val harvest: List<FarmRow>,
    /** 今日可收产值（只算查得到售价的）。 */
    val harvestTotal: Int,
    /** 查不到名字的株数（野生采集物 / mod 产物）→ 显示「野生采集物」，不计入产值。 */
    val unnamedReady: Int,
    /** 马上要熟的（3 天内）。 */
    val soon: List<FarmRow>,
    /** 枯死待清理的。 */
    val dead: List<FarmRow>,
    /** 牲畜今日待办。 */
    val todos: List<FarmTodo>,
    /** 牲畜总数（用于「16 只 · 4 只要动手」这种说明）。 */
    val animalTotal: Int,
    /** 导入时间（毫秒）。 */
    val savedAt: Long,
    /** 来源说明（文件名 / Uri），给「什么时候导入的」用。 */
    val sourceLabel: String,
    /**
     * 小地图（每张地图一份格子清单）。P3-a。
     *
     * ⚠ **默认值空列表**：旧缓存（导入于本功能之前）里没有这一节，
     *   读出来就是空的 → 地图整块不显示，而不是崩。
     */
    val maps: List<FarmMap> = emptyList(),
) {
    /** 今天能收的里，有名字的株数。 */
    val namedReady: Int get() = harvest.sumOf { it.count }
}

/** 「我的农场」的一行。 */
data class FarmRow(
    /** 所在地图（中文：温室 / 农场 / 姜岛…）。 */
    val location: String,
    /** 品种（App 中文名）。 */
    val name: String,
    /** 株数。 */
    val count: Int,
    /** 单株售价；null = 查不到售价 → **不计入产值**（不编价）。 */
    val unitPrice: Int?,
    /** 该组合计（unitPrice 为 null 时恒为 0）。 */
    val total: Int,
    /** 还剩几天成熟（只有「马上要熟」那组有）。 */
    val daysLeft: Int? = null,
)

/** 牲畜今日待办的一条。 */
data class FarmTodo(
    /** 玩家给牲畜起的名字。 */
    val name: String,
    /** 品种：App 动物名；App 没有对应品种时**回退英文原名**（不崩不空不瞎猜）。 */
    val type: String,
    /** 好感（0–1000）。 */
    val friendship: Int?,
    /** 心情（0–255）。 */
    val happiness: Int?,
    /** 要做什么：抚摸（心情 N）/ 可收集产物 / N 天没喂。 */
    val action: String,
)

/**
 * 把 [WorldState] 折成 [FarmToday]。
 *
 * **纯逻辑**：不碰 Context、不读文件、不切线程（单测可直接调）。
 * 名字靠 [WorldItemMap]，售价靠调用方给的表（**App 自己的 crops.json**，
 * 上游的 sell_price 实测有错：甜瓜 App 250 / 索引 120）。
 */
object FarmTodayBuilder {

    /** 地图原名 → 中文显示名（认不出的原样显示，不编）。 */
    fun locationName(raw: String): String = when (raw) {
        "Greenhouse" -> "温室"
        "Farm" -> "农场"
        "IslandWest", "IslandNorth" -> "姜岛"
        "FarmHouse" -> "农舍"
        "Cellar" -> "地窖"
        else -> raw
    }

    /** 季节原名 → 中文。internal：CodexStateBuilder 用同一份折法（两处各写一份必然漂移）。 */
    internal fun seasonName(raw: String?): String = when (raw) {
        "spring" -> "春"
        "summer" -> "夏"
        "fall" -> "秋"
        "winter" -> "冬"
        else -> raw.orEmpty()
    }

    fun build(
        state: WorldState,
        map: WorldItemMap,
        prices: Map<String, Int?>,
        sourceLabel: String = "",
        savedAt: Long = 0L,
    ): FarmToday {
        val host = state.host

        // ── 今天能收的 ──────────────────────────────────────────────
        val harvestAcc = LinkedHashMap<Pair<String, String>, MutableList<WorldCrop>>()
        var unnamedReady = 0
        for (c in state.readyCrops) {
            val nm = map.cropName(c)
            if (nm == null) {
                // 查不到名字 → 整株不显示名字（显示「野生采集物」），但**算数**（它确实能收）
                unnamedReady++
                continue
            }
            harvestAcc.getOrPut(c.location to nm) { ArrayList() }.add(c)
        }
        val harvest = harvestAcc.map { (k, list) ->
            val price = prices[k.second]
            FarmRow(
                location = locationName(k.first),
                name = k.second,
                count = list.size,
                unitPrice = price,
                total = if (price != null) price * list.size else 0,
            )
        }.sortedWith(compareByDescending<FarmRow> { it.total }.thenByDescending { it.count })

        // ── 马上要熟的（3 天内；判不出天数的野生采集物不进来）────────────
        val soonAcc = LinkedHashMap<Triple<String, String, Int>, Int>()
        for (c in state.growingCrops) {
            val left = c.daysLeft ?: continue
            if (left < 1 || left > 3) continue
            val nm = map.cropName(c) ?: continue
            val k = Triple(c.location, nm, left)
            soonAcc[k] = (soonAcc[k] ?: 0) + 1
        }
        val soon = soonAcc.map { (k, n) ->
            FarmRow(
                location = locationName(k.first),
                name = k.second,
                count = n,
                unitPrice = null,
                total = 0,
                daysLeft = k.third,
            )
        }.sortedWith(compareBy<FarmRow> { it.daysLeft ?: 0 }.thenByDescending { it.count })

        // ── 枯死待清理 ──────────────────────────────────────────────
        val deadAcc = LinkedHashMap<Pair<String, String>, Int>()
        for (c in state.deadCrops) {
            val nm = map.cropName(c) ?: continue
            val k = c.location to nm
            deadAcc[k] = (deadAcc[k] ?: 0) + 1
        }
        val dead = deadAcc.map { (k, n) ->
            FarmRow(
                location = locationName(k.first),
                name = k.second,
                count = n,
                unitPrice = null,
                total = 0,
            )
        }.sortedByDescending { it.count }

        // ── 牲畜今日待办 ────────────────────────────────────────────
        val todos = state.animals.mapNotNull { a ->
            val acts = ArrayList<String>()
            if (a.happinessNotFull) acts += "抚摸（心情 ${a.happiness}）"
            if (a.hasProduct) acts += "可收集产物"
            if (a.needsFeed) acts += "${a.daysSinceLastFed} 天没喂"
            if (acts.isEmpty()) return@mapNotNull null
            FarmTodo(
                name = a.name?.takeIf { it.isNotEmpty() } ?: "？",
                type = map.animalName(a.type) ?: a.type ?: "？",
                friendship = a.friendship,
                happiness = a.happiness,
                action = acts.joinToString("、"),
            )
        }

        return FarmToday(
            farmer = host?.name?.takeIf { it.isNotEmpty() } ?: "？",
            year = state.year,
            season = seasonName(state.season),
            day = state.dayOfMonth,
            money = host?.money,
            readyStocks = state.readyCrops.size,
            harvest = harvest,
            harvestTotal = harvest.sumOf { if (it.unitPrice != null) it.total else 0 },
            unnamedReady = unnamedReady,
            soon = soon,
            dead = dead,
            todos = todos,
            animalTotal = state.animals.size,
            savedAt = savedAt,
            sourceLabel = sourceLabel,
            // 小地图：同一份 WorldState 折出来的格子清单（坐标 + 状态 + 名字）
            maps = FarmMapBuilder.build(state, map),
        )
    }
}

// ── 缓存用的 JSON 序列化（org.json，App 与单测都能跑）──────────────────

/**
 * 金币数字的千分位写法（App 里金额都是 g，13,265 比 13265 好扫）。
 *
 * ## 为什么提到数据层
 * v2.100.0 / c181 之前它是 `ui/today/FarmTodayView.kt` 里的一个 private 函数；
 * 今日页的建议区（`data/TodayAdvice.kt`）要显示**同一批金额**（现金、今天能收的产值），
 * 两处各写一份格式化 ⇒ 同一屏上同一个数出现两种写法（`52,250g` 与 `52250 g`）——
 * 那正是「数字同源」纪律要防的病。现在**一份实现、两处调用**。
 *
 * 纯函数：不碰 Context、不读文件（单测可直接调）。
 */
fun formatG(n: Int): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    var c = 0
    for (i in s.indices.reversed()) {
        sb.append(s[i])
        c++
        if (c % 3 == 0 && i != 0) sb.append(',')
    }
    return sb.reverse().toString()
}

internal fun FarmRow.toJson(): JSONObject = JSONObject().apply {
    put("location", location)
    put("name", name)
    put("count", count)
    if (unitPrice != null) put("unitPrice", unitPrice) else put("unitPrice", JSONObject.NULL)
    put("total", total)
    if (daysLeft != null) put("daysLeft", daysLeft) else put("daysLeft", JSONObject.NULL)
}

internal fun FarmTodo.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("type", type)
    if (friendship != null) put("friendship", friendship) else put("friendship", JSONObject.NULL)
    if (happiness != null) put("happiness", happiness) else put("happiness", JSONObject.NULL)
    put("action", action)
}

internal fun FarmToday.toJson(): String {
    val root = JSONObject()
    root.put("farmer", farmer)
    if (year != null) root.put("year", year) else root.put("year", JSONObject.NULL)
    root.put("season", season)
    if (day != null) root.put("day", day) else root.put("day", JSONObject.NULL)
    if (money != null) root.put("money", money) else root.put("money", JSONObject.NULL)
    root.put("readyStocks", readyStocks)
    root.put("harvest", JSONArray().also { arr -> harvest.forEach { arr.put(it.toJson()) } })
    root.put("harvestTotal", harvestTotal)
    root.put("unnamedReady", unnamedReady)
    root.put("soon", JSONArray().also { arr -> soon.forEach { arr.put(it.toJson()) } })
    root.put("dead", JSONArray().also { arr -> dead.forEach { arr.put(it.toJson()) } })
    root.put("todos", JSONArray().also { arr -> todos.forEach { arr.put(it.toJson()) } })
    root.put("animalTotal", animalTotal)
    root.put("savedAt", savedAt)
    root.put("sourceLabel", sourceLabel)
    root.put("maps", JSONArray().also { arr -> maps.forEach { arr.put(it.toJson()) } })
    return root.toString()
}

internal fun farmTodayFromJson(text: String): FarmToday? = try {
    val root = JSONObject(text)
    fun rows(key: String): List<FarmRow> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<FarmRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                FarmRow(
                    location = o.optString("location"),
                    name = o.optString("name"),
                    count = o.optInt("count"),
                    unitPrice = if (o.isNull("unitPrice")) null else o.optInt("unitPrice"),
                    total = o.optInt("total"),
                    daysLeft = if (o.isNull("daysLeft")) null else o.optInt("daysLeft"),
                )
            )
        }
        return out
    }
    fun todos(): List<FarmTodo> {
        val arr = root.optJSONArray("todos") ?: return emptyList()
        val out = ArrayList<FarmTodo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                FarmTodo(
                    name = o.optString("name"),
                    type = o.optString("type"),
                    friendship = if (o.isNull("friendship")) null else o.optInt("friendship"),
                    happiness = if (o.isNull("happiness")) null else o.optInt("happiness"),
                    action = o.optString("action"),
                )
            )
        }
        return out
    }
    FarmToday(
        farmer = root.optString("farmer"),
        year = if (root.isNull("year")) null else root.optInt("year"),
        season = root.optString("season"),
        day = if (root.isNull("day")) null else root.optInt("day"),
        money = if (root.isNull("money")) null else root.optInt("money"),
        readyStocks = root.optInt("readyStocks"),
        harvest = rows("harvest"),
        harvestTotal = root.optInt("harvestTotal"),
        unnamedReady = root.optInt("unnamedReady"),
        soon = rows("soon"),
        dead = rows("dead"),
        todos = todos(),
        animalTotal = root.optInt("animalTotal"),
        savedAt = root.optLong("savedAt"),
        sourceLabel = root.optString("sourceLabel"),
        // ⚠ 旧缓存没有 "maps" 键 → 空列表 → 地图整块不显示（不是崩）
        maps = farmMapsFromJson(root.optJSONArray("maps")),
    )
} catch (e: Exception) {
    null
}
