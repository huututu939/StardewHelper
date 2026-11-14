package com.stardew.assistant.data

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * v2.74.0：从存档 XML 里读「农场年度报告」要的累计统计。
 *
 * ## 为什么单独一个解析器，不复用 [SaveImport]
 * [SaveImport] 那个状态机是围绕「五个容器的 key→条目名映射表」写的，逻辑密且踩过
 * 六七个坑（键类型双形态、ZIP 选错文件、BOM、museumPieces 的 key/value 反过来…），
 * 往里塞统计字段等于在雷区里加脚手架。这里另起一个**只读统计**的解析器，
 * 两边互不干扰：一个坏了不影响另一个。
 *
 * ## 两代存档的统计存在**两个不同的地方**（实测四份真档）
 *
 * - **1.5.6**（`ff` 档）：`<player><stats>` 下**直接子元素**就是计数
 *   ```
 *   <stats><seedsSown>2540</seedsSown><itemsShipped>16948</itemsShipped>…</stats>
 *   ```
 *   同一份数据还会以 PascalCase 再存一遍（`<stats><SeedsSown>2540</SeedsSown>`），
 *   另有一个只放 3 个计数的 `<stat_dictionary>`（`trashCansChecked` 等）。
 *
 * - **1.6**（`让我们重逢` 档）：那些直接子元素**全是 `xsi:nil="true"`**，
 *   真值搬进了 `<Values>` 字典：
 *   ```
 *   <stats><Values><item><key><string>averageBedtime</string></key>
 *                    <value><unsignedInt>2454</unsignedInt></value></item>…</Values>
 *          <averageBedtime xsi:nil="true" />…</stats>
 *   ```
 *
 * 所以**同一个键要去两个地方找**，而且键的大小写在两代之间并不统一
 * （`TreesChopped` / `wildtreesplanted` / `trashCansChecked` 各写各的）。
 * 本文件把所有来源的键**统一小写**后塞进一张表，查询时只认小写键。
 *
 * ## 读不到就当没有，绝不编
 * `napsTaken`（午睡）、`timesUnloaded`（退出次数）、`timesEnteredSkullCavern`
 * 在两代存档里**都不存在**——游戏没记这个数。报告里就**不显示这一行**，
 * 不能拿别的计数器顶替（那是在编数据）。
 *
 * ## 只读
 * 全程只读存档，从不写回（与 [SaveImport] 同一条纪律）。
 *
 * ## 线程
 * 解析 4~35MB 的单行 XML 是**秒级**操作，调用方必须在后台线程。
 */
object FarmReportParser {

    private const val TAG = "FarmReport"

    /** 玩家节点上直接挂着的标量字段。 */
    private val PLAYER_SCALARS = setOf(
        "name", "farmName", "favoriteThing", "Gender", "gender", "isMale",
        "money", "millisecondsPlayed", "totalMoneyEarned",
        "deepestMineLevel", "timesReachedMineBottom", "spouse",
        "dayOfMonthForSaveGame", "seasonForSaveGame", "yearForSaveGame",
    )

    /** 存档根节点（`<SaveGame>`）上的标量字段。 */
    private val ROOT_SCALARS = setOf(
        "year", "dayOfMonth", "currentSeason", "whichFarm", "farmName", "goldenWalnutsFound",
    )

    /**
     * `<stats>` 下**不是叶子**的元素（要当容器处理，不能当标量读）。
     *
     * ⚠ 为什么是**黑名单**而不是白名单：
     *   白名单（只列报表要用的那些）会让 1.5.6 档漏掉一堆真有的计数
     *   （奶/蛋/羊毛/煤…实测漏 15 项）——它们当时没显示，但**口径不一致**这件事本身是雷：
     *   将来加一行显示，1.6 档有值、1.5.6 档永远是空，还不报错。
     *   改成「除容器外全读」，两代存档的取数口径就一致了。
     *
     * 读非叶子元素不会破坏深度匹配：[leaf] 只会吃掉那个元素的**第一个子元素的 START_TAG**，
     * 子树内部的 END_TAG 照常配对（深度差一层，不影响任何被跟踪的容器）。
     */
    private val STAT_CONTAINERS = setOf("Values", "stat_dictionary", "specificMonstersKilled")

    /** 1.5.6 的 `seasonForSaveGame` 是下标（0 春 1 夏 2 秋 3 冬），不是名字。 */
    private val SEASON_BY_INDEX = listOf("spring", "summer", "fall", "winter")

    /**
     * 解析。**必须在后台线程调用。**
     *
     * @return 解析结果；文件不是存档 / 结构对不上时返回 null（调用方按「没有报告」处理）。
     */
    fun parse(input: InputStream): FarmReport? {
        val p = Xml.newPullParser()
        return try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(skipBom(input), "UTF-8")
            val c = Collector(p)
            c.run()
            c.build()
        } catch (t: Throwable) {
            Log.w(TAG, "解析农场报告统计失败：${t.message}")
            null
        }
    }

    /** 跳 UTF-8 BOM。真档全部带 BOM，不跳会炸。 */
    private fun skipBom(input: InputStream): InputStream {
        val pb = PushbackInputStream(input, 3)
        val b = ByteArray(3)
        val n = pb.read(b, 0, 3)
        val isBom = n == 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()
        if (!isBom && n > 0) pb.unread(b, 0, n)
        return pb
    }

    /**
     * 读一个**叶子元素**的文本。调用时解析器必须停在该元素的 START_TAG。
     *
     * ⚠ 与 [SaveImport.readLeaf] 同一个坑：START_TAG 上直接读 `parser.text` 恒为 null
     * （XmlPullParser 契约：只有 TEXT 事件才有文本），所以要手工推进一格到 TEXT，
     * **停在 TEXT 上不往下走**——让下一轮循环正常拿到 END_TAG，深度匹配才不错位。
     *
     * 元素为空（`<int/>`、`<int xsi:nil="true"/>`）时 next() 直接返回 END_TAG，
     * 这里返回 null，同时**吃掉**了这个元素的 END_TAG。它的深度比任何被跟踪的
     * 容器都深，所以少一个 END_TAG 不影响配对。
     */
    private fun leaf(p: XmlPullParser): String? = try {
        if (p.next() == XmlPullParser.TEXT) p.text?.trim()?.takeIf { it.isNotEmpty() } else null
    } catch (e: Exception) {
        null
    }

    /** 字典种类。 */
    private const val KIND_NONE = 0
    private const val KIND_STATS = 1     // <Values> / <stat_dictionary>
    private const val KIND_MONSTERS = 2  // <specificMonstersKilled>
    private const val KIND_FRIENDS = 3   // <friendshipData>

    /**
     * 状态机。
     *
     * 靠**深度**判断「当前在哪个容器里」，不建完整树、不把几十 MB 读成 String。
     */
    private class Collector(val p: XmlPullParser) {
        var rootDepth = -1
        var playerDepth = -1
        var statsDepth = -1
        var dictDepth = -1
        var itemDepth = -1
        var keyDepth = -1
        var valDepth = -1
        var friendDepth = -1
        var dictKind = KIND_NONE

        var itemKey: String? = null
        var itemValue: Long? = null
        var itemPoints: Long? = null
        /** 当前在 <key> 里还是在 <value> 里（两者是兄弟，深度相同，只能靠状态区分）。 */
        var inKey = false
        var inValue = false

        val rootScalars = LinkedHashMap<String, String>()
        val playerScalars = LinkedHashMap<String, String>()
        val stats = LinkedHashMap<String, Long>()
        val monsters = mutableListOf<MonsterKill>()
        val friends = mutableListOf<FriendBond>()

        fun run() {
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                val name = p.name ?: continue
                when (ev) {
                    XmlPullParser.START_TAG -> onStart(name, p.depth)
                    XmlPullParser.END_TAG -> onEnd(name, p.depth)
                }
            }
        }

        private fun onStart(name: String, d: Int) {
            if (rootDepth < 0) {
                rootDepth = d
                return
            }
            // 根节点上的标量（year / dayOfMonth / currentSeason / whichFarm …）
            if (d == rootDepth + 1) {
                if (name in ROOT_SCALARS) rootScalars[name] = leaf(p) ?: return
            }
            if (playerDepth < 0) {
                if (name == "player") playerDepth = d
                return
            }
            // <player> 的直接子节点
            if (d == playerDepth + 1) {
                when {
                    name == "stats" -> statsDepth = d
                    name == "friendshipData" -> { dictKind = KIND_FRIENDS; dictDepth = d }
                    name in PLAYER_SCALARS -> playerScalars[name] = leaf(p) ?: ""
                }
                return
            }
            // <stats> 的直接子节点
            if (statsDepth >= 0 && d == statsDepth + 1) {
                when {
                    name == "Values" || name == "stat_dictionary" -> { dictKind = KIND_STATS; dictDepth = d }
                    name == "specificMonstersKilled" -> { dictKind = KIND_MONSTERS; dictDepth = d }
                    else -> {
                        val t = leaf(p)
                        if (t != null) putStat(name, t)
                    }
                }
                return
            }
            // 字典内部
            if (dictDepth < 0) return
            if (d == dictDepth + 1 && name == "item") {
                itemDepth = d
                itemKey = null; itemValue = null; itemPoints = null
                return
            }
            if (itemDepth < 0) return
            if (d == itemDepth + 1) {
                // ⚠ <key> 与 <value> 是**兄弟**节点，它们的子元素深度完全相同
                //   （都是 itemDepth+2）。所以不能只靠深度判断「现在在 key 里还是 value 里」——
                //   靠深度分的结果是：<value><int>152</int></value> 里的 <int> 被 key 分支抢走，
                //   文本永远读不到 → 整只怪被丢掉（不报错，只是那行永远不显示）。
                //   必须用**显式状态**记住当前在哪个容器里。
                when (name) {
                    "key" -> { keyDepth = d; inKey = true; inValue = false }
                    "value" -> { valDepth = d; inValue = true; inKey = false }
                }
                return
            }
            // <key> 里的叶子：NPC 名 / 怪名 / 统计键名
            if (inKey && d == keyDepth + 1) {
                if (itemKey == null) itemKey = leaf(p)
                return
            }
            // <value> 里的叶子：数量（int / unsignedInt），或好感度的 <Friendship> 对象
            if (inValue && d == valDepth + 1) {
                if (name == "Friendship") {
                    friendDepth = d
                    return
                }
                if (itemValue == null) itemValue = leaf(p)?.toLongOrNull()
                return
            }
            // <Friendship><Points>好感点数</Points></Friendship>
            if (d == friendDepth + 1 && name == "Points") {
                if (itemPoints == null) itemPoints = leaf(p)?.toLongOrNull()
            }
        }

        private fun onEnd(name: String, d: Int) {
            if (itemDepth >= 0 && d == itemDepth) {
                finishItem()
                itemDepth = -1
                keyDepth = -1; valDepth = -1; friendDepth = -1
                inKey = false; inValue = false
                return
            }
            if (dictDepth >= 0 && d == dictDepth) {
                dictKind = KIND_NONE
                dictDepth = -1
                return
            }
            if (statsDepth >= 0 && d == statsDepth) {
                statsDepth = -1
                return
            }
            if (playerDepth >= 0 && d == playerDepth) {
                playerDepth = -1
            }
        }

        /** 一个字典条目读完，落到它该去的地方。 */
        private fun finishItem() {
            val k = itemKey
            when (dictKind) {
                KIND_STATS -> {
                    val v = itemValue ?: return
                    if (k != null) putStat(k, v.toString())
                }
                KIND_MONSTERS -> {
                    val v = itemValue ?: return
                    if (k != null) monsters.add(MonsterKill(k, v.toInt()))
                }
                KIND_FRIENDS -> {
                    // 好感度的值在 <value><Friendship><Points> 里，不是 <value> 直接的 int
                    val v = itemPoints ?: return
                    if (k != null) friends.add(FriendBond(k, v.toInt()))
                }
            }
        }

        /**
         * 统计计数入表。**键统一小写**，先到先得。
         *
         * 先到先得是对的：1.5.6 里 `<stats><seedsSown>` 与 `<stats><SeedsSown>` 值相同，
         * 1.6 里直接子元素是 nil（读不到值，不入表），真值只在字典里。
         * 两种情况都不会出现「两个来源打架」需要挑一个的情况。
         */
        private fun putStat(rawKey: String, rawValue: String) {
            val key = rawKey.trim().lowercase()
            if (key.isEmpty()) return
            if (stats.containsKey(key)) return
            val v = rawValue.trim().toLongOrNull() ?: return
            stats[key] = v
        }

        // ── 组装 ────────────────────────────────────────────────────────

        fun build(): FarmReport {
            val gender = resolveGender()
            val year = rootScalars["year"]?.toIntOrNull()
                ?: playerScalars["yearForSaveGame"]?.toIntOrNull() ?: 0
            val day = rootScalars["dayOfMonth"]?.toIntOrNull()
                ?: playerScalars["dayOfMonthForSaveGame"]?.toIntOrNull() ?: 0
            val season = rootScalars["currentSeason"]?.takeIf { it.isNotBlank() }
                ?: playerScalars["seasonForSaveGame"]?.toIntOrNull()
                    ?.let { SEASON_BY_INDEX.getOrNull(it) }
                ?: ""
            val deepest = playerScalars["deepestMineLevel"]?.toLongOrNull()
                ?: stats[FarmStatKey.DEEPEST_MINE_LEVEL]
            val mineBottom = playerScalars["timesReachedMineBottom"]?.toLongOrNull()
                ?: stats[FarmStatKey.TIMES_REACHED_MINE_BOTTOM]
            val walnuts = rootScalars["goldenWalnutsFound"]?.toLongOrNull()
                ?: stats[FarmStatKey.GOLDEN_WALNUTS_FOUND]

            // 补两个只在玩家节点上的字段进统计表，报告层就不用分两处取
            if (deepest != null) stats.putIfAbsent(FarmStatKey.DEEPEST_MINE_LEVEL, deepest)
            if (mineBottom != null) stats.putIfAbsent(FarmStatKey.TIMES_REACHED_MINE_BOTTOM, mineBottom)
            if (walnuts != null) stats.putIfAbsent(FarmStatKey.GOLDEN_WALNUTS_FOUND, walnuts)

            val topMon = monsters.sortedByDescending { it.count }.take(3)
            val topFr = friends.sortedByDescending { it.points }.take(3)

            return FarmReport(
                farmerName = playerScalars["name"]?.trim() ?: "",
                farmName = playerScalars["farmName"]?.trim() ?: rootScalars["farmName"]?.trim() ?: "",
                spouse = playerScalars["spouse"]?.trim() ?: "",
                favoriteThing = playerScalars["favoriteThing"]?.trim() ?: "",
                gender = gender,
                whichFarm = rootScalars["whichFarm"]?.toIntOrNull() ?: -1,
                year = year,
                season = season,
                dayOfMonth = day,
                money = playerScalars["money"]?.toLongOrNull() ?: 0L,
                totalMoneyEarned = playerScalars["totalMoneyEarned"]?.toLongOrNull() ?: 0L,
                millisecondsPlayed = playerScalars["millisecondsPlayed"]?.toLongOrNull() ?: 0L,
                stats = stats.toMap(),
                topMonsters = topMon,
                bestFriends = topFr,
                friendCount = friends.size,
            )
        }

        /**
         * 性别。
         *
         * 1.6 是字符串（`<Gender>Female</Gender>`，1.6.15 档里 `Gender` 与 `gender` **两个都有**），
         * 1.5.6 是布尔（`<isMale>false</isMale>`）。
         * `<gender>0</gender>` 那种是**别的节点**（NPC）上的，深度不对，这里读不到。
         */
        private fun resolveGender(): String {
            playerScalars["Gender"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            playerScalars["gender"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            playerScalars["isMale"]?.trim()?.lowercase()?.let {
                return if (it == "true") "Male" else "Female"
            }
            return ""
        }
    }
}
