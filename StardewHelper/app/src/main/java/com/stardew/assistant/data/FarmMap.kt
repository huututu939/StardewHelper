package com.stardew.assistant.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「我的农场」的小地图数据（P3-a）。
 *
 * ## 一格 = 一块耕地
 * 数据来自存档里的 `<TerrainFeature xsi:type="HoeDirt">`（**只有耕过的地**才算，
 * 地板/道路/建筑不算）。坐标取 `terrainFeatures` 那一项的 `key/Vector2`。
 *
 * ## 为什么按「连续行组」切分
 * 实测四档真实存档：
 * · ff 姜岛西部跨度 85×58，但有地的地方只有几小簇 —— 整图直画是一张 85 格宽的空白图；
 * · ff 农场 71 块地散成 3 片；重逢农场 174 块散成 2 片。
 * 所以一张地图要切成几片分别画。切分规则选**连续行组**（纵向相邻的行算一片）：
 * · 比「8-邻接簇」稳 —— 8-邻接会把 ff 农场碎成 10 片（7 片是单块地），界面上全是碎格子；
 * · 比「整图一张」紧 —— 整图要连空白画 4930 格，手机上摆不下。
 * 同一张地图里每片各自按自己的跨度算格子大小。
 *
 * ## 一格什么颜色
 * 只用五种状态，不多不少：
 * · [TILE_READY] 今天能收 · [TILE_GROWING] 还在长 · [TILE_DEAD] 枯死待清理
 * · [TILE_EMPTY] 翻过土没种 · [TILE_UNKNOWN] 状态判不出（存档缺字段）
 * **查不到名字就不写名字**（不编）—— 名字由 [WorldItemMap.cropName] 负责。
 */
data class FarmTile(
    /** 地块横坐标（tile）。 */
    val x: Int,
    /** 地块纵坐标（tile）。 */
    val y: Int,
    /** 状态，取 [TILE_READY] 等五个常量之一。 */
    val state: String,
    /** 作物中文名；null = 查不到名字（显示「野生采集物」，不编名）。 */
    val name: String?,
)

/** 一片地：纵向连续的一组行。 */
data class FarmPatch(
    /** 这片地最左的 x。 */
    val x0: Int,
    /** 这片地最右的 x。 */
    val x1: Int,
    /** 这片地最上的 y。 */
    val y0: Int,
    /** 这片地最下的 y。 */
    val y1: Int,
    /** 这片地上的所有格子。 */
    val tiles: List<FarmTile>,
) {
    /** 宽度（格）。 */
    val width: Int get() = x1 - x0 + 1

    /** 高度（格）。 */
    val height: Int get() = y1 - y0 + 1

    /**
     * (x,y) → 格子。渲染与点击命中都查它。
     * 键用 `x * 1000000 + y` 压成一个 Long：y 是 tile 坐标（0~几万），不会撞。
     */
    private val byPos: Map<Long, FarmTile> by lazy {
        tiles.associateBy { it.x * 1000000L + it.y }
    }

    /** 那一格是什么；不在这一片里 → null（点到了空处）。 */
    fun tileAt(x: Int, y: Int): FarmTile? = byPos[x * 1000000L + y]
}

/** 一张地图的小地图。 */
data class FarmMap(
    /** 地图中文名（温室 / 农场 / 姜岛…）。 */
    val location: String,
    /** 这张地图上的耕地总数。 */
    val plotCount: Int,
    /** 切成几片分别画。 */
    val patches: List<FarmPatch>,
)

/** 格子状态常量。与 [WorldCrop.ready] / [WorldCrop.isDead] 同一判据。 */
object TileState {
    /** 今天能收。 */
    const val READY = "ready"

    /** 还在长（没熟，也没枯）。 */
    const val GROWING = "growing"

    /** 枯死待清理。 */
    const val DEAD = "dead"

    /** 翻过土没种。 */
    const val EMPTY = "empty"

    /** 状态判不出（存档缺 `currentPhase` 之类）。**不是「没成熟」。** */
    const val UNKNOWN = "unknown"

    /** 面向用户的中文。 */
    fun label(state: String): String = when (state) {
        READY -> "今天能收"
        GROWING -> "还在长"
        DEAD -> "枯死待清理"
        EMPTY -> "翻过土没种"
        else -> "状态判不出"
    }
}

/**
 * 把 [WorldState] 折成小地图数据。**纯逻辑**：不碰 Context、不读文件（单测可直接调）。
 */
object FarmMapBuilder {

    /**
     * 地图原名 → 小地图上显示的名字。
     *
     * ⚠ **比四块汇总那张表细一档**（[FarmTodayBuilder.locationName] 把 IslandWest 与
     *   IslandNorth 都叫「姜岛」）：那张表的地点列只有 30dp 宽，塞不下 4 个字；
     *   而小地图里**两张姜岛地图并排出现**、同名就分不清谁是谁。
     *   认不出的原样显示（不编）。
     */
    private fun locationName(raw: String): String = when (raw) {
        "IslandWest" -> "姜岛西部"
        "IslandNorth" -> "姜岛北部"
        "Forest" -> "森林"
        else -> FarmTodayBuilder.locationName(raw)
    }

    /** 一块地的状态。空地 → empty；枯死 → dead；熟 → ready；没熟 → growing；判不出 → unknown。 */
    private fun tileState(crop: WorldCrop?): String = when {
        crop == null -> TileState.EMPTY
        crop.isDead -> TileState.DEAD
        crop.ready == true -> TileState.READY
        crop.ready == false -> TileState.GROWING
        else -> TileState.UNKNOWN
    }

    /**
     * 按**连续行组**切分：y 连续的行归一片，中间隔了空行就断开。
     * 面积大的片排前面（视觉重心在前）。
     */
    private fun splitPatches(tiles: List<FarmTile>): List<FarmPatch> {
        val byY = tiles.groupBy { it.y }.toSortedMap()
        val out = ArrayList<FarmPatch>()
        var cur = ArrayList<FarmTile>()
        for ((y, row) in byY) {
            if (cur.isNotEmpty() && y != cur.last().y + 1) {
                out.add(cur.toPatch())
                cur = ArrayList()
            }
            cur.addAll(row)
        }
        if (cur.isNotEmpty()) out.add(cur.toPatch())
        return out.sortedByDescending { it.tiles.size }
    }

    private fun List<FarmTile>.toPatch(): FarmPatch = FarmPatch(
        x0 = minOf { it.x },
        x1 = maxOf { it.x },
        y0 = minOf { it.y },
        y1 = maxOf { it.y },
        tiles = this,
    )

    /**
     * 组装一张存档的全部小地图。
     *
     * @param state 世界状态（解析器产出）
     * @param map 名称映射（App 自己的 worldItemMap.json）
     */
    fun build(state: WorldState, map: WorldItemMap): List<FarmMap> {
        // ⚠ 取不到坐标的地直接跳过：(0,0) 也是真实地块，不能拿 0 顶替。
        val byLoc = LinkedHashMap<String, MutableList<WorldPlot>>()
        for (p in state.plots) {
            val x = p.x ?: continue
            val y = p.y ?: continue
            byLoc.getOrPut(p.location) { ArrayList() }.add(p)
        }
        return byLoc.map { (loc, plots) ->
            val tiles = plots.map { p ->
                FarmTile(
                    x = p.x ?: 0,
                    y = p.y ?: 0,
                    state = tileState(p.crop),
                    name = p.crop?.let { map.cropName(it) },
                )
            }
            FarmMap(
                location = locationName(loc),
                plotCount = tiles.size,
                patches = splitPatches(tiles),
            )
        }.sortedByDescending { it.plotCount }
    }
}

// ── 缓存用的 JSON 序列化（org.json，App 与单测都能跑）──────────────────

internal fun FarmTile.toJson(): JSONObject = JSONObject().apply {
    put("x", x)
    put("y", y)
    put("s", state)
    if (name != null) put("n", name) else put("n", JSONObject.NULL)
}

internal fun FarmPatch.toJson(): JSONObject = JSONObject().apply {
    put("x0", x0)
    put("x1", x1)
    put("y0", y0)
    put("y1", y1)
    put("tiles", JSONArray().also { arr -> tiles.forEach { arr.put(it.toJson()) } })
}

internal fun FarmMap.toJson(): JSONObject = JSONObject().apply {
    put("location", location)
    put("plotCount", plotCount)
    put("patches", JSONArray().also { arr -> patches.forEach { arr.put(it.toJson()) } })
}

internal fun farmTileFromJson(o: JSONObject): FarmTile = FarmTile(
    x = o.optInt("x"),
    y = o.optInt("y"),
    state = o.optString("s", TileState.UNKNOWN),
    name = if (o.isNull("n")) null else o.optString("n").takeIf { it.isNotEmpty() },
)

internal fun farmPatchFromJson(o: JSONObject): FarmPatch {
    val arr = o.optJSONArray("tiles")
    val tiles = ArrayList<FarmTile>(if (arr == null) 0 else arr.length())
    if (arr != null) {
        for (i in 0 until arr.length()) {
            tiles.add(farmTileFromJson(arr.getJSONObject(i)))
        }
    }
    return FarmPatch(
        x0 = o.optInt("x0"),
        x1 = o.optInt("x1"),
        y0 = o.optInt("y0"),
        y1 = o.optInt("y1"),
        tiles = tiles,
    )
}

/**
 * 读小地图列表。**null / 缺字段 → 空列表**（旧缓存没有这一节时地图整块不显示，
 * 而不是崩）。
 */
internal fun farmMapsFromJson(arr: JSONArray?): List<FarmMap> {
    if (arr == null) return emptyList()
    val out = ArrayList<FarmMap>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val parr = o.optJSONArray("patches")
        val patches = ArrayList<FarmPatch>(if (parr == null) 0 else parr.length())
        if (parr != null) {
            for (j in 0 until parr.length()) patches.add(farmPatchFromJson(parr.getJSONObject(j)))
        }
        out.add(
            FarmMap(
                location = o.optString("location"),
                plotCount = o.optInt("plotCount"),
                patches = patches,
            )
        )
    }
    return out
}
