package com.stardew.assistant.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.74.0「农场年度报告」的数据模型。
 *
 * ## 它是什么
 * 一份「从存档里读出来的累计统计」的**快照**。导入存档时顺手解析一次，
 * 落盘到对应存档位（见 [PrefsRepo.setReport]），之后生成长图不再碰存档文件。
 *
 * ## 为什么字段大多是可空
 * 存档格式分两代（1.5.6 与 1.6），同一项统计在两代里**存放位置不同**，
 * 而且有些计数器**压根没有**（`napsTaken` / `timesUnloaded` 两代都没有）。
 * 「字段不存在」和「字段存在但值是 0」在报告里必须**表现不同**：
 * 前者整行不显示，后者显示 0。所以缺失一律用 null 表达，0 用真实数字表达。
 *
 * ## 为什么不放原始 XML
 * 原始存档 35MB，存一份就是几十 MB；报告只需要几十个数字。
 * 存派生结果，体积小到可以进 DataStore。
 */
data class FarmReport(
    // ── 档案基本信息 ──
    /** 玩家名。存档里没有就空着，不编。 */
    val farmerName: String = "",
    /** 农场名。 */
    val farmName: String = "",
    /**
     * 配偶名（英文内部名，如 "Sebastian"）。空 = 未婚。
     *
     * 它是玩家节点上的字段（不是统计计数），所以不进 [stats]。
     */
    val spouse: String = "",
    /**
     * 1.6 的「最喜欢的东西」。
     *
     * ⚠ 它是**玩家在建档时自己输入的自由文本**，不是固定枚举（官方 wiki 原文：
     * "in the starter menu you have the option to type your favorite thing"）。
     * 所以这里**原样保存、原样显示**，不查表翻译——查不到就显示玩家自己打的字。
     * 唯一有官方含义的是 `ConcernedApe`：打这串字的话，吃星之花时猿猴会说谢谢。
     */
    val favoriteThing: String = "",
    /** "Male" / "Female" / ""。1.6 是字符串（Gender/gender），1.5.6 是布尔（isMale）。 */
    val gender: String = "",
    /** 农场类型：0 标准 / 1 河流 / 2 森林 / 3 山顶 / 4 荒野 / 5 四角落 / 6 海滩。-1 = 存档里没有。 */
    val whichFarm: Int = -1,
    /** 游戏年份。 */
    val year: Int = 0,
    /** 季节：spring / summer / fall / winter。 */
    val season: String = "",
    /** 月内第几天。 */
    val dayOfMonth: Int = 0,
    /** 当前金币（存档里的字段叫 `money`，不是 `currentGold`）。 */
    val money: Long = 0,
    /** 累计赚到的金币。 */
    val totalMoneyEarned: Long = 0,
    /** 累计游玩时长（毫秒）。 */
    val millisecondsPlayed: Long = 0,

    // ── 统计项 ──
    /**
     * 全部统计计数，**键统一小写**。
     *
     * 为什么统一小写：1.6 的 `Values` 字典里键的大小写是混的
     * （`TreesChopped` / `wildtreesplanted` / `trashCansChecked` 各写各的），
     * 1.5.6 又是 `seedsSown` + `SeedsSown` 双份。统一小写后一张表全都能查。
     * 键名见 [FarmStatKey]。
     */
    val stats: Map<String, Long> = emptyMap(),

    /** 杀得最多的怪（按数量降序，最多 3 条）。名字是英文内部名，展示时再翻译。 */
    val topMonsters: List<MonsterKill> = emptyList(),

    /** 好感度最高的村民（按点数降序，最多 3 条）。 */
    val bestFriends: List<FriendBond> = emptyList(),

    /** 存档里记录到的村民总数（用来算「还有多少人没混熟」）。 */
    val friendCount: Int = 0,
) {

    /** 取一个统计值；**没有这个字段**返回 null（区别于「有但等于 0」）。 */
    fun stat(key: String): Long? = stats[key]

    /** 统计值，没有就当 0（用于「至少要显示个数字」的场景）。 */
    fun statOrZero(key: String): Long = stats[key] ?: 0L

    /**
     * 平均睡觉时间（军事时间，如 2350 = 23:50、2454 = 次日 00:54）→ 24 小时制字符串。
     *
     * 军时格式是 HHMM 的**十进制**而不是十六进制：2024 = 20:24，不是 20:24 的十六进制。
     * 所以要先 `% 100` 取分钟、再 `/ 100` 取小时。
     */
    fun bedtimeText(): String? {
        val p = bedtimeParts() ?: return null
        return "%02d:%02d".format(p.first, p.second)
    }

    /**
     * 平均睡觉时间 → (24 小时制的小时, 分钟)。
     *
     * 报告里要把它画成「一天 24 格时钟条」，所以需要单独的数值形式。
     */
    fun bedtimeParts(): Pair<Int, Int>? {
        val v = stats[FarmStatKey.AVERAGE_BEDTIME] ?: return null
        if (v <= 0) return null
        val minutes = (v % 100).toInt()
        val hours24 = (v / 100).toInt()
        return (hours24 % 24) to minutes
    }

    /** 平均睡觉时间是否跨过了午夜（>= 24 点才算）。报告里用来调侃「熬夜冠军」。 */
    fun bedtimeAfterMidnight(): Boolean {
        val v = stats[FarmStatKey.AVERAGE_BEDTIME] ?: return false
        return v / 100 >= 24
    }

    /** 游玩时长（毫秒）→ "73 小时 36 分"。 */
    fun playTimeText(): String? {
        if (millisecondsPlayed <= 0) return null
        val totalMin = millisecondsPlayed / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h 小时 $m 分" else "$m 分钟"
    }

    /** 农场类型中文名。 */
    fun farmTypeName(): String = when (whichFarm) {
        0 -> "标准农场"
        1 -> "河流农场"
        2 -> "森林农场"
        3 -> "山顶农场"
        4 -> "荒野农场"
        5 -> "四角落农场"
        6 -> "海滩农场"
        else -> ""
    }

    /** 性别中文名。 */
    fun genderName(): String = when (gender) {
        "Male" -> "男"
        "Female" -> "女"
        else -> ""
    }

    /** 「第 X 年 · 夏 9 日」。缺少年月日时返回空串（那一行整个不显示）。 */
    fun dateText(): String {
        val se = seasonName() ?: return ""
        if (year <= 0 || dayOfMonth <= 0) return ""
        return "第 $year 年 · $se $dayOfMonth 日"
    }

    /** 季节中文名。 */
    fun seasonName(): String? = when (season) {
        "spring" -> "春"
        "summer" -> "夏"
        "fall" -> "秋"
        "winter" -> "冬"
        else -> null
    }

    // ── JSON ────────────────────────────────────────────────────────────

    fun toJson(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("fn", farmerName)
        o.put("farm", farmName)
        o.put("sp", spouse)
        o.put("fav", favoriteThing)
        o.put("g", gender)
        o.put("wf", whichFarm)
        o.put("y", year)
        o.put("se", season)
        o.put("d", dayOfMonth)
        o.put("money", money)
        o.put("earned", totalMoneyEarned)
        o.put("ms", millisecondsPlayed)
        val st = JSONObject()
        for ((k, v) in stats) st.put(k, v)
        o.put("st", st)
        o.put("mons", JSONArray().also { arr -> topMonsters.forEach { arr.put(JSONArray().put(it.name).put(it.count)) } })
        o.put("frs", JSONArray().also { arr -> bestFriends.forEach { arr.put(JSONArray().put(it.name).put(it.points)) } })
        o.put("frn", friendCount)
        return o.toString()
    }

    companion object {
        /** 反序列化。坏数据一律退化成 null（一条脏记录不该让 App 起不来）。 */
        fun fromJson(raw: String?): FarmReport? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                val st = JSONObject()
                o.optJSONObject("st")?.let { jo ->
                    jo.keys().forEach { k -> st.put(k, jo.optLong(k)) }
                }
                FarmReport(
                    farmerName = o.optString("fn"),
                    farmName = o.optString("farm"),
                    spouse = o.optString("sp"),
                    favoriteThing = o.optString("fav"),
                    gender = o.optString("g"),
                    whichFarm = o.optInt("wf", -1),
                    year = o.optInt("y"),
                    season = o.optString("se"),
                    dayOfMonth = o.optInt("d"),
                    money = o.optLong("money"),
                    totalMoneyEarned = o.optLong("earned"),
                    millisecondsPlayed = o.optLong("ms"),
                    stats = st.keys().asSequence().associateWith { st.optLong(it) },
                    topMonsters = o.optJSONArray("mons").toPairs().map { MonsterKill(it.first, it.second.toInt()) },
                    bestFriends = o.optJSONArray("frs").toPairs().map { FriendBond(it.first, it.second.toInt()) },
                    friendCount = o.optInt("frn"),
                )
            }.getOrNull()
        }

        private fun JSONArray?.toPairs(): List<Pair<String, Long>> {
            if (this == null) return emptyList()
            val out = mutableListOf<Pair<String, Long>>()
            for (i in 0 until length()) {
                val a = optJSONArray(i) ?: continue
                val n = a.optString(0)
                val v = a.optLong(1)
                if (n.isNotBlank()) out.add(n to v)
            }
            return out
        }
    }
}

/** 一次怪物击杀记录。`name` 是英文内部名（如 "Green Slime"）。 */
data class MonsterKill(val name: String, val count: Int)

/** 一个村民的好感度。`name` 是英文内部名（如 "Robin"），`points` 是 250/心。 */
data class FriendBond(val name: String, val points: Int)

/**
 * 统计项的键名（**全小写**，查 [FarmReport.stat] 用）。
 *
 * 注释里写的是存档里的原始键名。原始键的大小写在 1.5.6 / 1.6 之间不统一
 * （`TreesChopped` 与 `wildtreesplanted` 并存），所以统一小写后查表。
 */
object FarmStatKey {
    const val SEEDS_SOWN = "seedssown"                     // seedsSown
    const val DIRT_HOED = "dirthoed"                        // dirtHoed
    const val CROPS_SHIPPED = "cropsshipped"                // cropsShipped
    const val ITEMS_SHIPPED = "itemsshipped"                // itemsShipped
    const val WILD_TREES_PLANTED = "wildtreesplanted"       // wildtreesplanted
    const val TREES_CHOPPED = "treeschopped"                // TreesChopped
    const val STUMPS_CHOPPED = "stumpschopped"              // stumpsChopped
    const val ROCKS_CRUSHED = "rockscrushed"                // rocksCrushed
    const val STONE_GATHERED = "stonegathered"              // stoneGathered
    const val ITEMS_FORAGED = "itemsforaged"                // itemsForaged
    const val ARTIFACT_SPOTS_DUG = "artifactspotsdug"       // ArtifactSpotsDug
    const val ITEMS_CRAFTED = "itemscrafted"                // itemsCrafted
    const val ITEMS_COOKED = "itemscooked"                  // itemsCooked
    const val COPPER_FOUND = "copperfound"                  // copperFound
    const val IRON_FOUND = "ironfound"                      // ironFound
    const val GOLD_FOUND = "goldfound"                      // goldFound
    const val IRIDIUM_FOUND = "iridiumfound"                // iridiumFound
    const val MONSTERS_KILLED = "monsterskilled"            // monstersKilled
    const val SLIMES_KILLED = "slimeskilled"                // slimesKilled
    const val DEEPEST_MINE_LEVEL = "deepestminelevel"       // deepestMineLevel（玩家节点上）
    const val TIMES_REACHED_MINE_BOTTOM = "timesreachedminebottom" // timesReachedMineBottom
    const val TIMES_ENTERED_SKULL_CAVERN = "timesenteredskullcavern" // timesEnteredSkullCavern
    const val GEODES_CRACKED = "geodescracked"              // geodesCracked
    const val GIFTS_GIVEN = "giftsgiven"                    // giftsGiven
    const val GOLDEN_WALNUTS_FOUND = "goldenwalnutsfound"   // goldenWalnutsFound（存档根节点上）
    const val NOTES_FOUND = "notesfound"                    // notesFound
    const val QUESTS_COMPLETED = "questscompleted"          // questsCompleted
    const val TRASH_CANS_CHECKED = "trashcanschecked"       // trashCansChecked
    const val TICKET_PRIZES_CLAIMED = "ticketprizesclaimed" // ticketPrizesClaimed
    const val AVERAGE_BEDTIME = "averagebedtime"            // averageBedtime
    const val DAYS_PLAYED = "daysplayed"                    // daysPlayed
    const val STEPS_TAKEN = "stepstaken"                    // stepsTaken
    const val NAPS_TAKEN = "napstaken"                      // napsTaken（两代存档都没有）
    const val TIMES_UNLOADED = "timesunloaded"              // timesUnloaded（两代存档都没有）
    const val TIMES_FISHED = "timesfished"                  // timesFished
    const val FISH_CAUGHT = "fishcaught"                    // fishCaught
    const val TIMES_UNCONSCIOUS = "timesunconscious"        // timesUnconscious（昏倒次数）
    const val TRUFFLES_FOUND = "trufflesfound"              // trufflesFound
    const val PRESERVES_MADE = "preservesmade"              // preservesMade
    const val BEVERAGES_MADE = "beveragesmade"              // beveragesMade
    const val WEEDS_ELIMINATED = "weedseliminated"          // weedsEliminated
    const val MYSTIC_STONES_CRUSHED = "mysticstonescrushed"  // mysticStonesCrushed
    const val DIAMONDS_FOUND = "diamondsfound"              // diamondsFound
    const val PRISMATIC_SHARDS_FOUND = "prismaticshardsfound" // prismaticShardsFound
    const val CAVE_CARROTS_FOUND = "cavecarrotsfound"       // caveCarrotsFound
    const val BOULDERS_CRACKED = "boulderscracked"          // bouldersCracked
    const val GOOD_FRIENDS = "goodfriends"                  // goodFriends（满心村民数）
    const val MASTERY_EXP = "masteryexp"                    // MasteryExp
    const val TOTAL_MONEY_GIFTED = "totalmoneygifted"       // totalMoneyGifted
}
