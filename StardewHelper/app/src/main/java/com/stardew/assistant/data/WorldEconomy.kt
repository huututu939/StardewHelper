package com.stardew.assistant.data

import org.json.JSONObject

/**
 * 存档世界状态的「名称 + 产值」层（P1）。
 *
 * ## 为什么要有这一层
 * P0 的解析器给出的是**原始 id 与英文名**（`190`、`"Carrot"`、`"Brown Cow"`）。
 * 这一层把它们换成 App 自己的**中文名**与**可算的售价**，界面才能显示
 * 「花椰菜 ×54（175g/株）」而不是「190 ×54」。
 *
 * ## 两条铁律
 *
 * 1. **查不到就返回 null，绝不瞎猜。** 映射错了显示成「锈剑」比不显示更糟
 *    （物品 id 0 是锈剑；存档里写 0 / -1 的地方一律当「未知」）。
 * 2. **售价只有一个来源：App 自己的 `crops.json`。** 上游索引的 `sell_price`
 *    实测有错（甜瓜：App 250 / 索引 120，官方值是 250），所以映射表里**不存价格**，
 *    价格由调用方按 App 的表传入 —— 免得出现第二个真相。
 *
 * @see WorldItemMap 名称映射（由 tools/gen_world_item_map.py 生成）
 */
data class WorldItemMap(
    /** 数字 item_id → App 中文名。 */
    val byId: Map<String, String>,
    /** 英文名 → App 中文名（含去空格变体，MOD 档写 `SummerSquash` 也能命中）。 */
    val byNameEn: Map<String, String>,
    /** 存档里的 `FarmAnimal/type`（英文）→ App 动物名。 */
    val animalType: Map<String, String>,
) {
    /** 产出 id → 中文名。查不到 = null。 */
    fun nameOfId(id: Int?): String? = id?.let { byId[it.toString()] }

    /** 产出英文名 → 中文名。查不到 = null。 */
    fun nameOfEn(en: String?): String? = en?.let { byNameEn[it] }

    /**
     * 一株作物的产物中文名。
     *
     * 三条路依次试：**数字 id → 英文名 → 野生采集物 id**。
     * MOD 档的 `indexOfHarvest` 有时写的是名字而不是数字（实测穿越档 19 株写 `Carrot`），
     * 所以英文名这条路不是摆设。
     */
    fun cropName(crop: WorldCrop): String? =
        nameOfId(crop.produceId) ?: nameOfEn(crop.produceName) ?: nameOfId(crop.forageId)

    /** 牲畜品种 → App 动物名。查不到 = null（运行期回退显示英文原名）。 */
    fun animalName(type: String?): String? = type?.let { animalType[it] }

    companion object {
        val EMPTY = WorldItemMap(emptyMap(), emptyMap(), emptyMap())

        /** 解析 `assets/data/worldItemMap.json`（由 tools/gen_world_item_map.py 生成）。 */
        fun parse(text: String): WorldItemMap {
            val root = JSONObject(text)
            return WorldItemMap(
                byId = root.getJSONObject("byId").toStringMap(),
                byNameEn = root.getJSONObject("byNameEn").toStringMap(),
                animalType = root.getJSONObject("animalType").toStringMap(),
            )
        }

        private fun JSONObject.toStringMap(): Map<String, String> {
            val out = LinkedHashMap<String, String>(length())
            keys().forEach { k -> out[k] = getString(k) }
            return out
        }
    }
}

/**
 * 存档世界状态的产值计算（**纯逻辑**：不碰 Context、不读文件、不切线程）。
 *
 * ## 与现有计算页的关系
 * 售价口径与 [Calc.effectiveSell] **完全一致**（农耕人 +10%、品质系数相乘），
 * 但这里**另起一份实现**，不去改 [Calc] —— 那个函数是 private 且被现有计算页依赖，
 * 动它等于动别人。两者的一致性由 `WorldEconomyTest` 用**反射调用** private 的
 * `effectiveSell` 逐项对账（不改 Logic.kt 一个字）。
 *
 * ## 「今日可收总额」的口径
 * **株数 × 单株售价**（每株按 1 份产出计，保守下限）。
 * 单株产量区间（`minHarvest .. maxHarvest + 耕种加成`）**另算**，见 [worldYieldRange]：
 * 存档里 `minHarvest` / `maxHarvest` 常常整个字段缺失 → 返回 null（**不补 1**），
 * 所以「最多能收多少」在多数存档里是**算不出来**的，而「今天收多少钱」是能算的。
 */
object WorldEconomy {

    /**
     * 单株售价（含职业与品质加成）。
     *
     * 与 [Calc.effectiveSell] 同公式：
     * `sell = (prof5 == "农耕人" ? sell × 1.1 : sell)`，再 `× qualityMult` 后取整。
     *
     * @param sellPrice 基础售价（**非空**：查不到售价的东西不进总额，不编价）
     * @param prof5 5 级职业名（"农耕人" 才有加成；null / 其它值 = 无加成）
     * @param qualityMult 品质系数（1.0 普通 / 1.25 银星 / 1.5 金星 / 2.0 铱星 / 1.1 优质肥期望）
     */
    fun worldSell(sellPrice: Int, prof5: String?, qualityMult: Double): Int {
        var sell = sellPrice
        if (prof5 == "农耕人") sell = (sell * 1.1).toInt()
        return (sell * qualityMult).toInt()
    }

    /**
     * 单株产量区间：`minHarvest .. (maxHarvest + maxHarvestIncreasePerFarmingLevel × 耕种等级)`。
     *
     * ⚠ 存档里这几个字段**常常整个缺失**（四份真实存档的作物大多没有 min/maxHarvest）
     * → 返回 null，**不补 1、不补 0**：「算不出来」和「就是 1」是两件事。
     *
     * @param farmingLevel 耕种等级；null = 读不到 → 整段算不出（不按 0 级顶替）
     */
    fun worldYieldRange(
        minHarvest: Int?,
        maxHarvest: Int?,
        plusPerLevel: Int?,
        farmingLevel: Int?,
    ): IntRange? {
        val lo = minHarvest ?: return null
        val hi = maxHarvest ?: return null
        val lvl = farmingLevel ?: return null
        if (lo < 0 || hi < lo || lvl < 0) return null
        val extra = plusPerLevel ?: 0
        return lo..(hi + extra * lvl)
    }

    /**
     * 一组同产物的今日可收汇总。
     *
     * @param unitPrice 单株售价；null = **查不到售价** → 不计入总额（不编价）
     * @param total 该组合计（unitPrice 为 null 时恒为 0）
     */
    data class WorldHarvestGroup(
        /** 所在地图（Farm / Greenhouse…）。温室与农场分开统计。 */
        val location: String,
        /** App 中文名。 */
        val name: String,
        /** 株数。 */
        val count: Int,
        /** 单株售价。null = 查不到。 */
        val unitPrice: Int?,
        /** 该组合计。 */
        val total: Int,
    ) {
        /** 有售价吗？（没有就不进总额） */
        val priced: Boolean get() = unitPrice != null
    }

    /**
     * 今日能收的：按（地图, 品种）分组，给出株数、单株售价与合计。
     *
     * @param crops 要统计的作物（一般传 [WorldState.readyCrops]）
     * @param map 名称映射（查不到名字的作物**整组丢弃**，不显示半截名字）
     * @param priceOf App 中文名 → 售价（**调用方给 App 自己的表**，本函数不碰价格来源）
     * @param farmingLevel 耕种等级（进单株产量区间；null = 算不出产量）
     * @param prof5 5 级职业名
     * @param qualityMult 品质系数
     */
    fun worldHarvestGroups(
        crops: List<WorldCrop>,
        map: WorldItemMap,
        priceOf: (String) -> Int?,
        farmingLevel: Int? = null,
        prof5: String? = null,
        qualityMult: Double = 1.0,
    ): List<WorldHarvestGroup> {
        val acc = LinkedHashMap<Pair<String, String>, MutableList<WorldCrop>>()
        for (c in crops) {
            val name = map.cropName(c) ?: continue   // 查不到名字 → 不进清单（不瞎猜）
            acc.getOrPut(c.location to name) { ArrayList() }.add(c)
        }
        val out = ArrayList<WorldHarvestGroup>()
        for ((key, list) in acc) {
            val (location, name) = key
            val base = priceOf(name)
            val unit = base?.let { worldSell(it, prof5, qualityMult) }
            out.add(
                WorldHarvestGroup(
                    location = location,
                    name = name,
                    count = list.size,
                    unitPrice = unit,
                    total = if (unit != null) unit * list.size else 0,
                )
            )
        }
        // 排序：有售价的按合计从多到少在前，无售价的排末尾（App 既有列表的同类口径）
        return out.sortedWith(
            compareByDescending<WorldHarvestGroup> { it.priced }
                .thenByDescending { it.total }
                .thenBy { it.name }
        )
    }

    /**
     * 今日可收总额：**只算查得到售价的**。
     *
     * 查不到售价的组不计（它们的 [WorldHarvestGroup.total] 恒为 0）——
     * 宁可少算，也不能拿编出来的价凑一个更大的数字。
     */
    fun worldHarvestTotal(groups: List<WorldHarvestGroup>): Int =
        groups.sumOf { if (it.priced) it.total else 0 }
}
