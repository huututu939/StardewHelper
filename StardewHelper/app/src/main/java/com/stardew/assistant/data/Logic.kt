package com.stardew.assistant.data

import kotlin.math.ceil

/**
 * 种植推荐引擎。口径沿用《完全手册》0 章算法：
 * 种下当天不计生长 → 首收日 = 种下日 + 生长天数；最晚下种日 = 28 − 生长天数；
 * 再生作物首收后每 regrow 天一茬；利润 = 可收次数 × 售价 − 种子价（基础价口径）。
 * 玉米、小麦、咖啡豆跨季存活：非末季种下只算到首次收获。
 * 职业加成为手册 16 章定义：农耕人 +10% 售价、农业学家 −10% 生长（向上取整）、工匠 +40% 手工品。
 */
object Calc {

    data class CropResult(
        val crop: Crop,
        val firstHarvest: Int,
        val lastPlant: Int,
        val harvests: Int,
        val profitPerPlot: Int,
        val totalProfit: Int,
        val perDay: Double,
    )

    /** 室内（温室 / 姜岛）持续周转口径：无季末死线，按「单株净利 + 日均」排 */
    data class CycleResult(
        val crop: Crop,
        val profitPerPlot: Int,   // 每轮：品质加成售价 − 种子价
        val perDay: Double,       // 再生作物 = 售价/再生天数；非再生 = 每轮利/生长天数
    )

    /** 品质系数：普通 1.0 · 银星 1.25 · 金星 1.5 · 铱星 2.0 · 优质肥期望 ≈1.1 */
    val QUALITY_OPTIONS = listOf("无" to 1.0, "优质肥均" to 1.1, "银星" to 1.25, "金星" to 1.5, "铱星" to 2.0)

    private fun effectiveSell(crop: Crop, prof5: String?, qualityMult: Double): Int {
        var sell = crop.sellPrice ?: 0
        if (prof5 == "农耕人") sell = (sell * 1.1).toInt()
        return (sell * qualityMult).toInt()
    }

    fun cycleResults(island: Boolean, prof5: String?, prof10: String?, qualityMult: Double): List<CycleResult> {
        val out = mutableListOf<CycleResult>()
        for (crop in GameData.crops) {
            if (crop.giant) continue // 巨型作物只能露天
            if (island && "夏" !in crop.seasons) continue // 姜岛全年为夏季
            val growth = crop.growth ?: continue
            val eff = if (prof10 == "农业学家") ceil(growth * 0.9).toInt() else growth
            val sell = effectiveSell(crop, prof5, qualityMult)
            val seed = crop.seedPrice ?: 0
            val perDay = if (crop.regrow != null) sell / crop.regrow.toDouble() else (sell - seed) / eff.toDouble()
            out += CycleResult(crop, sell - seed, perDay)
        }
        return out
    }

    private fun survivesCross(crop: Crop, season: String) =
        crop.seasons.size >= 2 && season != crop.seasons.last()

    /** 最晚下种日（含职业加速） */
    fun lastPlantDay(crop: Crop, season: String, prof10: String?): Int {
        val g = crop.growth ?: return 0
        val eff = if (prof10 == "农业学家") ceil(g * 0.9).toInt() else g
        return if (survivesCross(crop, season)) 28 else 28 - eff
    }

    fun plantResults(
        season: String, day: Int, plots: Int, prof5: String?, prof10: String?,
        qualityMult: Double = 1.0,
    ): List<CropResult> {
        val out = mutableListOf<CropResult>()
        for (crop in GameData.crops) {
            if (season !in crop.seasons) continue
            val growth = crop.growth ?: continue
            val eff = if (prof10 == "农业学家") ceil(growth * 0.9).toInt() else growth
            val cross = survivesCross(crop, season)
            val lastPlant = if (cross) 28 else 28 - eff
            if (day > lastPlant) continue
            val firstHarvest = day + eff
            val harvests = when {
                cross -> 1
                crop.regrow != null -> 1 + (28 - firstHarvest) / crop.regrow
                else -> 1
            }
            val sell = effectiveSell(crop, prof5, qualityMult)
            val seed = crop.seedPrice ?: 0
            val perPlot = harvests * sell - seed
            out += CropResult(
                crop = crop, firstHarvest = firstHarvest, lastPlant = lastPlant,
                harvests = harvests, profitPerPlot = perPlot,
                totalProfit = perPlot * plots,
                perDay = perPlot / (28.0 - day + 1),
            )
        }
        return out
    }

    enum class Deadline { MUST_PLANT, IN_TIME, CROSS_SEASON, TOO_LATE }

    fun deadline(crop: Crop, season: String, day: Int, prof10: String?): Deadline? {
        if (season !in crop.seasons) return null
        val lp = lastPlantDay(crop, season, prof10)
        return when {
            day > lp -> Deadline.TOO_LATE
            day == lp -> Deadline.MUST_PLANT
            survivesCross(crop, season) -> Deadline.CROSS_SEASON
            else -> Deadline.IN_TIME
        }
    }

    data class ProcessResult(
        val row: ProcessRow,
        val productPrice: Int,
        val rawPrice: Int?,
        val profit: Int?,
        val perDay: Double?,
        val artisanPrice: Int,
    )

    /**
     * v2.78.0 鱼价排行：所有鱼按**卖出价**（普通品质）从高到低。
     *
     * ## 口径：只用「卖出价」这一套
     * 鱼其实有两个价钱：鱼店买价（fishing.json 的 price，鱼店加价卖）与卖出价
     * （fishSpots.json 的 prices，钓上来卖给村民拿到的钱）。74 条鱼里有 **7 条
     * 两个价对不上**（幽灵鱼鱼店 220 / 普档 60 之类）—— 它们本来就是两码事。
     * 本排行只按**卖出价**排（与钓鱼图鉴详情里的「四档售价」同一个源），
     * 另一个口径去图鉴看「基础价」，不并排摆两个价。
     *
     * ⚠ **缺档就缺着**：`prices` 里没有的档位保持 null —— 蟹笼鱼只有「普」一档，
     *   采集物（大海参）一档都没有。「数据里没有」≠「价格是 0」，不补零也不编价。
     *
     * 排序：普档价降序 → 铱档降序 → 难度降序 → 名称。
     *   难度做第三键是因为同价的一堆鱼里，「难钓的那条」才是玩家真正会纠结的；
     *   没有售价的排在**末尾**（用 -1 兜的是排序键，不是价格）。
     */
    fun fishPriceRanking(): List<FishPriceRow> {
        val spots = GameData.fishSpots?.byFish ?: emptyMap()
        return GameData.fishing.map { f ->
            val info = spots[f.name]
            FishPriceRow(
                name = f.name,
                sellPrice = info?.prices?.get("normal"),
                silver = info?.prices?.get("silver"),
                gold = info?.prices?.get("gold"),
                iridium = info?.prices?.get("iridium"),
                difficulty = f.difficulty,
                seasons = f.seasons,
                crabPot = info?.crabPot == true || f.type == "蟹笼",
            )
        }.sortedWith(
            compareByDescending<FishPriceRow> { it.sellPrice ?: -1 }
                .thenByDescending { it.iridium ?: -1 }
                .thenByDescending { it.difficulty ?: 0 }
                .thenBy { it.name },
        )
    }

    private val rawPriceIndex: Map<String, Int> by lazy {
        val m = mutableMapOf<String, Int>()
        GameData.crops.forEach { c -> c.sellPrice?.let { m[c.name] = it } }
        GameData.fishing.forEach { f -> f.price?.let { m[f.name] = it } }
        m.putAll(ANIMAL_PRODUCT_PRICES)
        m
    }

    fun processResults(artisan: Boolean): List<ProcessResult> =
        GameData.processing.map { row ->
            val base = row.price ?: 0
            val art = if (artisan) (base * 1.4).toInt() else base
            val raw = rawPriceIndex[row.raw]
            val profit = raw?.let { art - it }
            val perDay = profit?.let { it / row.days }
            ProcessResult(row, art, raw, profit, perDay, art)
        }.sortedByDescending { it.perDay ?: Double.MIN_VALUE }

    /** 距离目标日还有几天（季节循环制） */
    fun daysUntil(season: String, day: Int, targetSeason: String, targetDay: Int): Int {
        val idx = { s: String, d: Int -> Season.names.indexOf(s) * 28 + d }
        var diff = idx(targetSeason, targetDay) - idx(season, day)
        if (diff < 0) diff += 4 * 28
        return diff
    }

    /**
     * v2.38：**带年份**的倒计时，返回「(目标日所在年, 距离天数)」。
     *
     * [daysUntil] 只在 112 天的年循环里算，跨年时会折回下一年却不告诉你——
     * 调用方就无从判断「这个生日落在第几年」，而肯特的问题恰恰在这里：
     * 第 1 年冬 28 说「7 天后肯特生日」，那次生日其实落在**第 2 年**春 4，
     * 那时他已经在场，说得通；第 1 年春 1 说「3 天后肯特生日」，
     * 落在第 1 年春 4，那时他还在部队，说不通。
     *
     * 同一个 7 天窗口，两个答案只差在「哪一年」。
     */
    fun daysUntilWithYear(
        year: Int, season: String, day: Int, targetSeason: String, targetDay: Int,
    ): Pair<Int, Int> {
        val cur = Season.names.indexOf(season) * 28 + day
        val tgt = Season.names.indexOf(targetSeason) * 28 + targetDay
        return if (tgt >= cur) {
            year to (tgt - cur)
        } else {
            (year + 1) to (4 * 28 - cur + tgt)
        }
    }
}
