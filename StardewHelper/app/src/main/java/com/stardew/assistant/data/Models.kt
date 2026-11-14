package com.stardew.assistant.data

/** 通用表格行（保留手册表头） */
typealias Row = Map<String, String>

data class Crop(
    val name: String,
    val seedPrice: Int?,
    val sellPrice: Int?,
    val growth: Int?,
    val regrow: Int?,
    val seasons: List<String>,
    val channels: String,
    val note: String,
    val giant: Boolean,
    val trellis: Boolean,
    // ── v2.73.4 Mod 扩展包作物字段（v2.4 的 crops.json）────────────────
    // 原版 crops.json 没有这些键，全部为 null/false，行为与之前逐像素一致。
    /** 来源包 modId（如 "sve"）。null = 原版作物。UI 用它决定要不要标「SVE」。 */
    val modId: String? = null,
    /** 种子名（包内 `seed`）。原版数据把种子名放在 channels 里，不单列。 */
    val seedName: String? = null,
    /** 收获物名（包内 `harvest`）。与作物名不同时才有值（如「古代蕨」→「古代纤维」）。 */
    val harvestName: String? = null,
    /**
     * 需高级生长激素（包内 `raisedBed`）。
     * v2.73.4 起用 **Boolean?** 而不是 Boolean：null = 数据里没这个键（原版），
     * 而「包里写了 false」与「原版没这项」是两件事——前者说明作者明确否掉了。
     */
    val raisedBed: Boolean? = null,
    /**
     * 包内图标相对路径（如 `images/crops/ancient_ferns.png`）。
     * null = 包里没给图，UI 退回原版图标框（空框）。原版作物恒为 null。
     */
    val iconPath: String? = null,
) {
    /** 来自 Mod 扩展包吗（与 [GiftVillager.fromMod] 同款判据）。 */
    val fromMod: Boolean get() = modId != null
}

data class Fishing(
    val name: String,
    val seasons: List<String>,
    val weather: String,
    val time: String,
    val location: String,
    val type: String,
    val price: Int?,
    val difficulty: Int?,
    val note: String,
)

/**
 * v2.78.0 鱼价排行的一行。
 *
 * ## 两套口径，别混
 * 鱼有**两个**价钱，App 里也一直分开显示（钓鱼图鉴：条目行「基础价」+ 详情「四档售价」）：
 * · **鱼店买价**（fishing.json 的 price）—— 在鱼店买这条鱼要花的钱。**本排行不用它**。
 * · **卖出价**（fishSpots.json 的 prices）—— 钓上来卖给村民能拿到的钱，**本排行用这套**。
 *
 * 两套**不是同一个数**：74 条鱼里有 7 条对不上（稀有鱼尤其，如幽灵鱼鱼店 220 / 普档 60）——
 * 鱼店加价卖、NPC 按基础价收，本来就不同。排行只用一个口径，不并排摆两个价。
 *
 * ⚠ 缺档 = 数据里**没有**这一档（蟹笼鱼只有「普」一档；采集物可能一档都没有）
 *   → 保持 null，**不补 0、不编价**。「没有这个数据」和「价格是 0」是两件事。
 */
data class FishPriceRow(
    val name: String,
    /** 普通品质卖出价（NPC 收购基准）。null = 数据里没有售价 → 不编价，排末尾。 */
    val sellPrice: Int?,
    val silver: Int? = null,
    val gold: Int? = null,
    val iridium: Int? = null,
    val difficulty: Int? = null,
    val seasons: List<String> = emptyList(),
    val crabPot: Boolean = false,
)

data class GiftVillager(
    val name: String,
    val birthdaySeason: String?,
    val birthdayDay: Int?,
    val loves: List<String>,
    val likes: List<String>,
    val dislikes: List<String> = emptyList(),
    val hates: List<String> = emptyList(),
    /**
     * v2.73：**中立**段（Mod 包 `neutralGifts`）。
     *
     * ⚠ 别把它当成「没态度」删掉：包作者写 `neutralGifts: ["所有鱼", …]` 是**明确**表态
     * 「这些送礼只是 +45，不加分也不减分」，与「这个人对某类物品没表态」是两件事。
     * 原版村民没有这个键（空列表），所以原版 UI 表现与之前逐像素一致。
     */
    val neutrals: List<String> = emptyList(),
    // ── v2.73.1 Mod 扩展包行程字段 ────────────────────────────
    /**
     * 概要行程（包内 `schedules.json`）：白天典型活动地点，**不是精确到分钟的时段表**。
     * 原版村民无此数据 → null，详情页不渲染行程卡。
     */
    val scheduleSummary: String? = null,
    /** 常去地点。条目可能带季节后缀（如「安迪农场(春)」），解析在 UI 层做。 */
    val scheduleLocations: List<String> = emptyList(),
    // ── v2.72 Mod 扩展包字段 ──────────────────────────────────
    // 原版村民（gifts.json）没有这些键，全部为 null，行为与 v2.71 之前逐像素一致。
    // 「可结婚」不能只看 marriage.json：那里面只有原版 8 人，Mod 包的可结婚标记
    // 只能由包自己的 npcs.json 给，所以单独开一个字段而不是去污染那张表。
    val modId: String? = null,
    /** 包内原始 id（如 Lance）。与 [nameCn] 分开存：中文名是显示键，原 id 是溯源用。 */
    val srcId: String? = null,
    /** 包内头像相对路径（images/Lance.png）。null = 没有头像，UI 走原版图标框。 */
    val portraitPath: String? = null,
    val location: String? = null,
    /** null = 原版村民（沿用 marriageSpouses 判定）；true/false = 包里显式给的。 */
    val marriageable: Boolean? = null,
    // ── c204：不接受友谊礼物的村民 ────────────────────────────────
    /**
     * 这个人接受友谊礼物吗。
     *
     * 吉尔 / 冈瑟 / 马龙这三位在 1.6 起被列入村民名册（游戏内 Social 标签可见），
     * 但**没有好感度系统、也不接受友谊礼物**——上游 new_npc_1_6.json 明写
     * 「不接受友谊礼物、无好感度系统」。
     *
     * ⚠ 这**不是缺数据，是官方设定**。所以不能给他们挂一张空白的礼物卡
     *   （看着像 App 没数据），也不能把他们从名单里删掉（1.6 里明明是村民）。
     *   正解：UI 层写清楚「不接受友谊礼物」。
     */
    val acceptsGifts: Boolean = true,
    /** 不接受礼物时的说明（身份 + 为什么不收）。null = 正常村民，不显示。 */
    val giftNote: String? = null,
) {
    /** 来自 Mod 扩展包吗（UI 用它决定显示头像还是图标框、要不要标「扩展」）。 */
    val fromMod: Boolean get() = modId != null
}

data class Festival(
    val name: String,
    val date: String,
    val location: String,
    val activity: String,
    val rewards: String,
    /** c210：起止时间（官方口径=可入场窗口+返程时刻；夜市/沙漠节/鳟鱼大赛/鱿鱼节是时间流动的 4 个例外）。
     *  null = 旧数据缓存未含此字段，UI 不显示该行。 */
    val hours: String? = null,
) {
    val season: String? by lazy { Season.firstOf(date) }
    val startDay: Int? by lazy { Season.firstDay(date) }
    val endDay: Int? by lazy { Season.lastDay(date) }
}

data class SeedPrice(
    val name: String,
    val year2: Boolean,
    val pierre: Int?,
    val pierreOff: Int?,
    val joja: Int?,
    val jojaMember: Int?,
    val note: String,
)

data class ProcessRow(
    val raw: String,
    val product: String,
    val machine: String,
    val timeText: String,
    val price: Int?,
) {
    val days: Double by lazy {
        val m = Regex("(\\d+)(?:-(\\d+))?\\s*(小时|天)").find(timeText)
        if (m == null) 1.0
        else {
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDoubleOrNull() ?: a
            val avg = (a + b) / 2
            if (m.groupValues[3] == "小时") avg / 24.0 else avg
        }
    }
}

/**
 * v2.58：收集包条目加 [src]（游戏内获取途径）。
 *
 * 起因：电影院的「失踪的收集包」有 6 项里有 3 项（果酒 / 上古水果 / 虚空鲑鱼）在
 * itemSources 三表里查不到来源，而 `usagesOf` 的「📍 获取途径」那条路对它们是空的
 * ——用户问「这鱼哪钓的」时页面上什么都看不到。wiki 逐项写了来源，直接落进数据里
 * 比再去补三张来源表更省、也更权威（官方原文）。
 *
 * 旧数据没有这个键 → 空串 → UI 不渲染，行为与 v2.57 一致。
 */
data class ItemCount(val name: String, val count: Int, val src: String = "")

/**
 * v2.27：收集包。
 *
 * [choose] 是游戏里的「选 N」规则（0 = 全部提交）：
 *  - 锅炉房·冒险家包 = 4 选 2（黏液 99 / 蝙蝠翅膀 10 / 太阳精华 / 虚空精华）
 *  - 鱼缸·蟹笼包 = 10 选 5
 * 旧数据没这个字段，一律当 0（全交），行为与旧版一致。
 */
data class NamedItems(val name: String, val items: List<ItemCount>, val choose: Int = 0)

/**
 * v2.58：房间加 [note] 与 [optional]。
 *
 * [optional] 的含义是「**不属于社区中心整修的 26 个包**」。电影院（废弃 Joja 超市改建）
 * 就属于这一类：它是社区中心修好之后才出现的**独立建筑**，不是社区中心的一个房间。
 * 不加这个标记，它一旦进了收集包列表就会把 `bundles_all` 判据和 279 分母一起污染——
 * 表现为「社区中心整修」这张卡永远差最后一个包，玩家看着像 bug。
 * 口径与 v2.41 浣熊商店同款：后续内容可勾、可见，但**不进总进度分母**，总览单开一行。
 *
 * ⚠ [note] 里带 `\n` 时 UI 按行渲染，别写成一长段。
 */
data class BundleRoom(
    val room: String,
    val bundles: List<NamedItems>,
    val note: String = "",
    val optional: Boolean = false,
)

data class HeartEvent(val hearts: String, val place: String, val event: String, val options: String)
data class HeartEventVillager(val villager: String, val events: List<HeartEvent>)

/** 图鉴统一条目：名称 + 图标查找名 + 键值行 + 大段文字
 *
 *  v2.79 加 [note]：数据自带的一句提醒（如小鸡代码里「编号待游戏内核对」）。
 *  走独立字段而不是塞进 rows —— rows 上限 5 行（超了就折叠成「展开」），
 *  提醒不该把属性表撑到折叠。 */
data class CodexItem(
    val name: String,
    val iconKey: String = name,
    val rows: List<Pair<String, String>> = emptyList(),
    val text: String = "",
    val note: String = "",
)

data class CodexGroup(
    val id: String,
    val title: String,
    val items: List<CodexItem>,
)

/**
 * v2.42：成品配方（菜谱 / 制作）。
 *
 * 群友 `:෴天劫:` 要「图鉴条目想一键跳怎么制作」。原先**只有反查方向**——
 * [GameData.usagesOf] 里的「🍳 烹饪用料」「🔨 制作材料」回答的是
 * *这个东西能拿去做成什么*（站在**材料**角度看），缺的是对称的另一半：
 * *这个东西是怎么做出来的*（站在**成品**角度看）。
 *
 * ⚠ 这两个方向必须同源：以前 ingredients 被 parseUses 解析一次出反查，
 *   若再造一份正向表就成了第二份真相（同 v2.27「判定口径散落多处必然漂移」）。
 *   故改成 parseRecipes 解析**一次**，正反两个索引都由它导出。
 *
 * 字段全部来自数据本身，无推断：
 *  - [kind] 菜谱 / 制作（两表零重名，一个名字只会命中其一）
 *  - [category] 仅 crafting.json 有（农业/采集/杂项/渔具/采矿/战斗），recipes 全空
 *  - [source] 配方出处（两表 174 条**全部非空**）
 *  - [ingredients] 材料名 + 数量（两表最多各 4 个）
 */
data class RecipeInfo(
    val kind: String,
    val category: String = "",
    val source: String = "",
    val ingredients: List<Pair<String, Int>> = emptyList(),
)

/** 物品来源（forage/mineralSources/artifactSources 统一行） */
data class SourceRow(
    val name: String,
    val season: String,
    val places: String,
    val price: Int?,
    val group: String,
)

/**
 * v2.99.0 / c180「缺件溯源」：一条「去哪拿」线索。
 *
 * ## 这不是第二份物品表
 * 每条线索都**由既有数据源派生**（shopCatalog / processingProfit / fishSpots /
 * monsterDrops / animals / crops / bundles / itemSources），
 * 本类只承载**已经算好的那一行文字**，不存价格表、描述、图标等物品属性。
 *
 * @param kind 线索大类（🛒 商店 / 🍶 加工 / 🎣 钓点 / 👾 怪物 / 🐄 养殖 / 🌱 作物 / 📦 收集包 …）
 * @param text 这一行的人类可读文字（已含店名/机器名/地点等上下文）
 * @param summary 是否进「📍 获取途径」那一行汇总。
 *   收集包的原文（bundles.json 的 src）**不进汇总**——进度页已经单独显示过那段原文，
 *   再进汇总就是同一句话显示两遍。
 */
data class SourceClue(
    val kind: String,
    val text: String,
    val summary: Boolean = true,
)

/** 商店营业时间行。
 *  closedDays = 结构化闭店星期（"周一".."周日"），由 tools/fix_v217.py 从 closed 文本提取，
 *  供今日页/小组件做精确判定；closed 仍作展示文本（含「…之外的日子」「下雨天」这类条件语义）。 */
data class ShopHours(
    val name: String,
    val hours: String,
    val keyHours: String,
    val closed: String,
    val note: String,
    val closedDays: List<String> = emptyList(),
)

/** 周循环行 */
data class WeekDay(val day: String, val items: List<String>)

/** v2.14 钓鱼地图·钓点：一个钓点的元信息 */
data class FishSpot(
    val name: String,
    val area: String,
    val region: String,
)

/** v2.14 钓鱼地图·单条鱼的钓点数据。
 *  鱼名沿用 App 现有官方译名，数据包里的机器直译名在构建期已映射掉。 */
data class FishSpotInfo(
    val spots: List<String>,
    val xp: Int?,
    val prices: Map<String, Int>,
    val timeRanges: List<Pair<Int, Int>>,
    val legendary: String? = null,
    val crabPot: Boolean = false,
    val crabNote: String = "",
) {
    val priceText: String
        get() = listOfNotNull(
            prices["normal"]?.let { "普 $it" },
            prices["silver"]?.let { "银 $it" },
            prices["gold"]?.let { "金 $it" },
            prices["iridium"]?.let { "铱 $it" },
        ).joinToString("　")

    val timeText: String
        get() = timeRanges.joinToString("　") { (s, e) -> "${fmtHour(s)}-${fmtHour(e)}" }

    companion object {
        fun fmtHour(h: Int): String = "%d:%02d".format(h / 100, h % 100)
    }
}

/** v2.14 钓鱼地图全表。byFish 的键保证是 App 现有鱼名，UI 可直接查。 */
data class FishSpotTable(
    val spots: List<FishSpot>,
    val byFish: Map<String, FishSpotInfo>,
) {
    fun of(name: String): FishSpotInfo? = byFish[name]
    val spotNames: List<String> get() = spots.map { it.name }
}

/** 解锁进度卡 */
data class UnlockCard(
    val id: String,
    val name: String,
    val req: String,
    val cost: String,
    val after: String,
    val checkKey: String,
)

object Season {
    val names = listOf("春", "夏", "秋", "冬")

    fun firstOf(date: String): String? = names.firstOrNull { date.contains(it) }

    fun firstDay(date: String): Int? =
        Regex("(\\d+)").find(date.substringAfter("春").substringAfter("夏")
            .substringAfter("秋").substringAfter("冬"))?.groupValues?.get(1)?.toIntOrNull()

    fun lastDay(date: String): Int? =
        Regex("(\\d+)\\s*日?(?!.*\\d)").findAll(date).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
}

/** 动物制品基础售价（手册 7.1 核对值），供加工收益反查原料成本。
 *  「羊毛」是 processing.json 的原料名（=动物毛 340g），两键并存防其它表引用断裂。 */
val ANIMAL_PRODUCT_PRICES = mapOf(
    "蛋" to 50, "棕色蛋" to 50, "大鸡蛋" to 95, "棕色大鸡蛋" to 95,
    "虚间蛋" to 65, "虚空蛋" to 65, "恐龙蛋" to 350, "鸭蛋" to 95,
    "动物毛" to 340, "羊毛" to 340, "牛奶" to 125, "大壶牛奶" to 190, "羊奶" to 225,
    "大瓶羊奶" to 345, "松露" to 625, "兔子的脚" to 565,
)

// ══════════════════════════════════════════════════════════════════════════
// 存档「世界状态」模型
//
// 数据来源：玩家自己的游戏存档（只读）。这组模型描述**一张具体存档此刻的样子**
// ——哪块地种了什么、哪只牛该摸、今天能收多少钱——与 App 里原有的「通用日历」
// 是两种东西：日历对所有玩家一模一样，世界状态只对这张存档成立。
//
// ⚠ 三条贯穿全组的纪律（都是真实存档里踩出来的，不是预防性编程）：
//
// ① **数值一律可空**：解析失败 = null，**绝不兜底 0**。
//    「读不出来」和「真的是 0」在游戏里是两件完全不同的事。兜底 0 的典型后果：
//    产出 id 变成 0，而物品 id 0 是「锈剑」——界面会一本正经地显示玩家种出了锈剑。
//    宁可少显示一条，也不要给一条假数据。
//
// ② **「没解析出来」要能和「确实没有」分开**：所以成熟判定给的是 Boolean? 而不是
//    Boolean。判不了就是 null，由上层如实报「未知」，不塞进「生长中」里充数。
//
// ③ **只读**：解析器永不写回存档。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 存档里的一株作物（`<crop>` 节点）。
 *
 * ## 成熟判定只认一条
 * `currentPhase >= phaseDays.size - 1`（`phaseDays` 末位是 99999 哨兵相位，
 * 表示「可收获且永久」）。**`fullGrown` 不是成熟标志**——用它会把已成熟的作物
 * 判成生长中（实测：穿越档 64 株防风草会全错）。`fullGrown` 只用于野生采集物
 * （没有 `phaseDays` 的那一类）。
 *
 * @see WorldStateParser 解析实现
 */
data class WorldCrop(
    /** 所在地图（`GameLocation` 的名字）：Farm / Greenhouse / IslandWest… */
    val location: String,
    /** 地块坐标（tile）。取不到就是 null——(0,0) 也是一个真实地块，不能拿 0 顶替。 */
    val plotX: Int?,
    /** 地块坐标（tile），纵轴。 */
    val plotY: Int?,
    /** 种子 id 的**原始文本**：数字（"472"）、mod 名（"CarrotSeeds"）、缺失（null）。 */
    val seedRaw: String?,
    /** 种子 id（数字形态，剥掉 `(O)` 之类前缀后的值）。mod 名 → null（它本来就不是 id）。 */
    val seedId: Int?,
    /** 产出 id 的原始文本（`indexOfHarvest`）。**可能是数字，也可能是名字**（MOD 档写 "Carrot"）。 */
    val harvestRaw: String?,
    /** 产出 id（数字形态）。`0` / `-1` 一律当「未知」→ null。 */
    val harvestId: Int?,
    /** 覆盖产出 id 的原始文本（`overrideHarvestItemId`，形如 `(O)22`）。 */
    val overrideRaw: String?,
    /** 覆盖产出 id（剥前缀后的数字）。 */
    val overrideId: Int?,
    /** 野生采集物 id（`whichForageCrop`）。存档里写 0 = 没有采集物 → null。 */
    val forageId: Int?,
    /** 是否野生采集物（`forageCrop`）。 */
    val forageCrop: Boolean?,
    /** 是否用了生长激素（`raisedSeeds`）。 */
    val raisedSeeds: Boolean?,
    /** 生长日程（`phaseDays`）。**末位是 99999 哨兵**，不是天数。 */
    val phaseDays: List<Int>,
    /** 当前阶段序号（`currentPhase`）。 */
    val currentPhase: Int?,
    /** 当前阶段内第几天（`dayOfCurrentPhase`）。 */
    val dayOfCurrentPhase: Int?,
    /** `fullGrown`：**不是成熟标志**，只作野生采集物的判据。 */
    val fullGrown: Boolean?,
    /** 已枯死（换季未收导致）→ 可清理。 */
    val dead: Boolean?,
    /** 再生间隔天数；-1 = 不再生。 */
    val regrowAfterHarvest: Int?,
    /** 单株产量下限（`minHarvest`）。存档里没写就是 null，不补 1。 */
    val minHarvest: Int?,
    /** 单株产量上限（`maxHarvest`）。 */
    val maxHarvest: Int?,
    /** 每级耕种 +N 产量。 */
    val maxHarvestIncreasePerFarmingLevel: Int?,
    /** 可生长季节（`seasonsToGrowIn`）。 */
    val seasons: List<String>,
) {
    /** 最终生效的产出 id：覆盖 > 产出 > 野生采集物。null = 判不出产出是什么。 */
    val produceId: Int? get() = overrideId ?: harvestId ?: forageId

    /**
     * 产出**名字**（存档直接写名字时才有；数字形态为 null）。
     * MOD 档的 `indexOfHarvest` 写的是物品名（如 "Carrot"），不是 id。
     */
    val produceName: String?
        get() = overrideRaw?.let { qualifiedName(it) } ?: harvestRaw?.let { qualifiedName(it) }

    /** 种子是 mod 的字符串 id（"CarrotSeeds"）？这类**原样显示英文名，不硬翻**。 */
    val seedIsMod: Boolean get() = seedRaw != null && seedId == null

    /**
     * 成熟判定。**null = 判不了**（缺 `currentPhase` 之类），不是「没成熟」。
     *
     * 规则（与《世界状态解析可行性报告》第三节一致）：
     * · 有 `phaseDays`：成熟 ⟺ `currentPhase >= phaseDays.size - 1`
     * · 无 `phaseDays`（野生采集物）：看 `fullGrown`
     */
    val ready: Boolean?
        get() {
            if (phaseDays.isNotEmpty()) {
                val cur = currentPhase ?: return null
                return cur >= phaseDays.size - 1
            }
            return fullGrown
        }

    /** 距离成熟还有几天。null = 判不了（野生采集物没有日程，或缺字段）。 */
    val daysLeft: Int?
        get() {
            if (phaseDays.isEmpty()) return null
            val cur = currentPhase ?: return null
            val din = dayOfCurrentPhase ?: return null
            if (cur < 0) return null
            if (cur >= phaseDays.size - 1) return 0
            val tail = phaseDays.subList(cur, phaseDays.size - 1).sum()
            return maxOf(0, tail - din)
        }

    /** 已种植天数。null = 判不了。 */
    val plantedDays: Int?
        get() {
            if (phaseDays.isEmpty()) return null
            val cur = currentPhase ?: return null
            val din = dayOfCurrentPhase ?: return null
            if (cur < 0) return null
            return phaseDays.take(cur.coerceAtMost(phaseDays.size)).sum() + din
        }

    /** 已枯死（可清理）。字段缺失时按「没死」处理，但 [dead] 原值仍保留可查。 */
    val isDead: Boolean get() = dead == true
}

/**
 * 一块耕地（`HoeDirt` 地块）。
 *
 * 作物挂在 [crop] 上；翻过土但没种的是空地（[crop] = null）。
 * 四档实测 HoeDirt 数都多于作物数（穿越 162 vs 160），差额就是空地。
 */
data class WorldPlot(
    /** 所在地图（Farm / Greenhouse…）。**温室与农场必须分开统计**：温室作物不因换季枯死。 */
    val location: String,
    /** 地块横坐标（tile）。 */
    val x: Int?,
    /** 地块纵坐标（tile）。 */
    val y: Int?,
    /** 这块地上的作物；空地为 null。 */
    val crop: WorldCrop?,
)

/**
 * 存档里的一只牲畜（`<FarmAnimal>` 节点）。
 *
 * ⚠ 同一份数据在存档里有**两份序列化**：`indoors/animals/…` 与
 * `indoors/Animals/SerializableDictionaryOfInt64FarmAnimal/…`。
 * 不去重会读出双倍数量（实测 ff 档 16 只会被读成 32 只）。解析器只取前者，
 * 并把跳过的条数记进 [WorldState.skippedDuplicateAnimals]。
 */
data class WorldAnimal(
    /** 牲畜名字（玩家起的，如 "松子"）。 */
    val name: String?,
    /** 品种，英文原名（"Brown Cow" / "Goat"…）。中文名由映射表负责，解析层不翻译。 */
    val type: String?,
    /** 所在建筑（Barn / Coop…）。取不到时回退到建筑节点的 `buildingType`。 */
    val building: String?,
    /** 所在地图。 */
    val location: String?,
    /** 心情，0–255（255 = 满）。 */
    val happiness: Int?,
    /** 饱食度（鸡等）。 */
    val fullness: Int?,
    /** 好感，0–1000。 */
    val friendship: Int?,
    /** 年龄（天）。 */
    val age: Int?,
    /** 拥有天数。 */
    val daysOwned: Int?,
    /** 成年所需天数。 */
    val ageWhenMature: Int?,
    /** 距产蛋/挤奶还有几天（0 = 今天就有）。 */
    val daysToLay: Int?,
    /** 距上次产蛋过了几天。 */
    val daysSinceLastLay: Int?,
    /** 几天没喂了。 */
    val daysSinceLastFed: Int?,
    /** 产出品质（0 普通 / 1 银 / 2 金 / 4 铱）。 */
    val produceQuality: Int?,
    /** 当前产出物品 id。 */
    val currentProduce: Int?,
    /** 普通建筑产出 id。 */
    val defaultProduceIndex: Int?,
    /** 高级建筑产出 id。 */
    val deluxeProduceIndex: Int?,
    /** 0 = 不可收，1 = 可挤奶/捡蛋/剪毛。 */
    val harvestType: Int?,
    /** 今天是否已被抚摸。 */
    val wasPet: Boolean?,
    /** 今天是否被自动喂食器摸过。 */
    val wasAutoPet: Boolean?,
    /** 是否允许繁殖。 */
    val allowReproduction: Boolean?,
    /** 售价。 */
    val price: Int?,
) {
    /** 今天还没被摸（两个标记都明确为 false 才算）。字段缺失时不当成「要摸」。 */
    val needsPet: Boolean get() = wasPet == false && wasAutoPet != true
    /** 心情未满（255 = 满）。 */
    val happinessNotFull: Boolean get() = happiness != null && happiness < 255
    /** 有产物可收。 */
    val hasProduct: Boolean get() = daysToLay == 0 && harvestType != null && harvestType != 0
    /** 饿了两天以上没喂。 */
    val needsFeed: Boolean get() = daysSinceLastFed != null && daysSinceLastFed >= 2
}

/**
 * 背包里的一件东西（`<Item>` 节点）。
 *
 * ⚠ 存档里的空槽位写成 `<Item xsi:nil="true" />`——**没有 name**。
 * 解析层直接不收（否则背包件数会按槽位数算，实测能把农场手算成 12 件）。
 */
data class WorldItem(
    /** 物品内部名（英文名或数字 id 文本）。 */
    val name: String?,
    /** 显示名（`DisplayName`，通常带品质星号后缀）。 */
    val displayName: String?,
    /** 物品 id（`itemId`，数字或字符串）。 */
    val itemId: String?,
    /** 堆叠数量。存档没写就是 null（**不是 0**，也不是 1）。 */
    val stack: Int?,
    /** 品质（0 普通 / 1 银 / 2 金 / 4 铱）。 */
    val quality: Int?,
    /** 售价。 */
    val price: Int?,
    /** `xsi:type`（BasicWeapon / Object / BigCraftable…）。 */
    val type: String?,
)

/**
 * 一个背包（房主或农场手各一个）。
 *
 * 联机档里房主与农场手**各有独立背包**，不能合并成一个数字
 * （实测 KKK 档：房主 1 件、农场手 0 件）。
 */
data class WorldInventory(
    /** 主人：房主名，或农场手名。 */
    val owner: String,
    /** 是农场手（联机档的第二个人）吗？ */
    val farmhand: Boolean,
    /** 现金。 */
    val money: Int?,
    /** 背包物品（**只收有名字的**，空槽位不算）。 */
    val items: List<WorldItem>,
)

/**
 * 一个村民的存档好感（v2.99.0 / c179 图鉴「你的状态」）。
 *
 * 数据来自玩家节点下的 `<friendshipData>`：每个 `<item>` 是一个村民，
 * key 是存档里的**英文内部名**（如 "Abigail"），中文名由映射层负责
 * （原版走 [ReportNames] 的表，Mod 村民走 gifts 数据里的 `srcId`），
 * 解析层不翻译。
 *
 * ⚠ **只解析房主**的 friendshipData：联机档里每个农场手各有一份，
 * 「你的状态」的口径全部跟房主走（背包、现金、技能同款），农场手的不折。
 */
data class WorldNpcFriendship(
    /** 存档 friendshipData 的 key（英文内部名）。 */
    val name: String?,
    /** 好感点数（250 点 = 一颗心）。存档没写就是 null，**不补 0**。 */
    val points: Int?,
    /** 本周已送礼次数。 */
    val giftsThisWeek: Int?,
)

/**
 * 玩家（房主 / 农场手）。
 *
 * @see WorldInventory 背包另算一份，两者不是一回事
 */
data class WorldPlayer(
    /** 角色名。 */
    val name: String?,
    /** 是农场手吗？ */
    val farmhand: Boolean,
    /** 现金。 */
    val money: Int?,
    /** 游戏时长（小时，保留 1 位小数）。 */
    val hoursPlayed: Double?,
    /** 技能等级（耕 / 钓 / 采 / 矿 / 战）。⚠ MOD 档可能是 99，别假设上限是 10。 */
    val skills: Map<String, Int?>,
    /** 配偶。 */
    val spouse: String?,
)

/**
 * 一份存档的「世界状态」快照。
 *
 * @see WorldStateParser 解析入口
 */
data class WorldState(
    /** 第几年。 */
    val year: Int?,
    /** 当前季节（"spring" / "summer" / "fall" / "winter"）。 */
    val season: String?,
    /** 当月第几天。 */
    val dayOfMonth: Int?,
    /** 金核桃总数。 */
    val goldenWalnuts: Int?,
    /**
     * 地图名，按存档里的出现顺序。
     *
     * ⚠ **查不到名字的位置留空串**，不编名字——两遍扫描数量对不上时要能看出来，
     * 见 [unresolvedLocations]。
     */
    val locations: List<String>,
    /** 所有耕地（含空地）。 */
    val plots: List<WorldPlot>,
    /** 所有牲畜（已去掉重复序列化副本）。 */
    val animals: List<WorldAnimal>,
    /** 所有背包（房主 + 农场手）。 */
    val inventories: List<WorldInventory>,
    /**
     * 村民好感（**只房主**；键是英文内部名，由上层映射成中文名）。
     * v2.99.0 / c179：图鉴「你的状态」用。默认空列表 = 旧调用方不用改构造。
     */
    val friendships: List<WorldNpcFriendship> = emptyList(),
    /** 所有玩家（房主 + 农场手）。 */
    val players: List<WorldPlayer>,
    /** 明确识别并跳过的「牲畜旧序列化副本」条数（同一份数据的第二份序列化）。 */
    val skippedDuplicateAnimals: Int,
) {
    /** 全部作物（从 [plots] 摊平而来——**作物只有一个来源**，不另存一份）。 */
    val crops: List<WorldCrop> by lazy { plots.mapNotNull { it.crop } }

    /** 没查到名字的地图数。>0 说明两遍扫描的地图数量对不上，必须如实报出来。 */
    val unresolvedLocations: Int get() = locations.count { it.isEmpty() }

    /** 今天能收的（成熟且没枯死）。 */
    val readyCrops: List<WorldCrop> by lazy { crops.filter { it.ready == true && !it.isDead } }

    /** 还在长的（没成熟也没枯死）。 */
    val growingCrops: List<WorldCrop> by lazy { crops.filter { it.ready == false && !it.isDead } }

    /** 枯死待清理的。 */
    val deadCrops: List<WorldCrop> by lazy { crops.filter { it.isDead } }

    /**
     * 成熟度**判不出来**的作物。
     *
     * 单独成一类而不是塞进「生长中」——判不出来就说判不出来，
     * 否则「生长中」这个数字会掺进假数据。
     */
    val unknownCrops: List<WorldCrop> by lazy { crops.filter { it.ready == null } }

    /** 按地图分组的作物（温室与农场必须分开看）。 */
    fun cropsByLocation(): Map<String, List<WorldCrop>> = crops.groupBy { it.location }

    /** 房主（第一个非农场手玩家）。 */
    val host: WorldPlayer? get() = players.firstOrNull { !it.farmhand }

    /** 农场手列表。 */
    val farmhands: List<WorldPlayer> get() = players.filter { it.farmhand }
}
