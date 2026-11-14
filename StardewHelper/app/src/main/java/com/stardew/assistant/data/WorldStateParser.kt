package com.stardew.assistant.data

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * 存档「世界状态」解析器（纯逻辑，不碰 UI、不写回存档）。
 *
 * ## 它回答什么问题
 * App 的今日页现在是**通用日历**（「春 13 天能种什么」），所有玩家的星露谷助手
 * 都长一个样。存档里却存着玩家**真实农场状态**：哪块地种了什么、几天后熟、
 * 哪只牛该摸、今天能收多少钱。读完存档就能给出「只属于这张存档的今日页」。
 *
 * ## 为什么必须两遍扫描（坑 2）
 * `GameLocation` 的地图名（`<name>Farm</name>`）排在 `terrainFeatures` **之后**：
 *
 * ```
 * <GameLocation xsi:type="Farm">
 *   <buildings>…</buildings>
 *   <terrainFeatures>…<crop>…</crop>…</terrainFeatures>
 *   <name>Farm</name>          ← 地图名在最后
 * </GameLocation>
 * ```
 *
 * 单遍流式解析读到作物时**拿不到地图名**，于是分不出农场和温室
 * （温室作物不因换季枯死，混在一起会误报「夏季枯死」）。
 * 故：第一遍缓存 GameLocation 的**名字序**，第二遍按**序号**归属。
 *
 * ## 为什么数值一律可空（坑 4）
 * 存档里的数值字段有两种包装：`<seedIndex>472</seedIndex>` 与
 * `<phaseDays><int>1</int>…</phaseDays>`；还可能是 mod 字符串（`CarrotSeeds`）。
 * **解析失败必须显式存 null，禁止兜底 0** —— 因为物品 id 0 是「锈剑」，
 * 兜底 0 会让界面一本正经地显示「玩家种出了锈剑」。假数据比崩溃更危险。
 *
 * ## 牲畜只取一条路径（坑 1）
 * 同一份牲畜数据在存档里有**两份序列化**：
 * `indoors/animals/item/value/FarmAnimal` 与
 * `indoors/Animals/SerializableDictionaryOfInt64FarmAnimal/item/value/FarmAnimal`。
 * 不去重会把 ff 档的 16 只读成 32 只。本解析器**只取前一条**，
 * 并把跳过的条数记进 [WorldState.skippedDuplicateAnimals]（不是悄悄丢掉）。
 *
 * ## 成熟判定只认一条（坑 3）
 * `currentPhase >= phaseDays.size - 1`。`fullGrown` **不是**成熟标志，
 * 它只用于野生采集物（没有 `phaseDays` 的那一类）。判定公式写在
 * [WorldCrop.ready] 里，解析层不重复实现，避免两处口径漂移。
 *
 * ## 线程
 * 解析 35MB 单行 XML 是秒级操作，**调用方必须在后台线程**。
 * 本文件不切线程，只保证纯逻辑、无 UI 依赖。
 */
object WorldStateParser {

    /** 解析进度回调。 */
    fun interface Progress {
        /**
         * @param stage 阶段名（见 [STAGE_LOCATIONS] / [STAGE_WORLD]）
         * @param done  这一阶段已处理多少张地图
         * @param total 这一阶段共多少张地图（第一遍跑到末尾才知道，故那里给 0 = 未知）
         */
        fun onProgress(stage: String, done: Int, total: Int)
    }

    /**
     * 开档器：**每一遍扫描都要一个新的解析器实例**。
     *
     * XmlPullParser 不能回退重读，所以两遍扫描必须各开一次。
     * Android 侧用 `android.util.Xml.newPullParser()`，
     * 单元测试用 kxml2 的 `KXmlParser` —— 同一个接口，同一份解析代码。
     */
    fun interface Opener {
        fun open(): XmlPullParser
    }

    /** 第一遍的阶段名。 */
    const val STAGE_LOCATIONS = "扫描地图名"

    /** 第二遍的阶段名。 */
    const val STAGE_WORLD = "读取世界状态"

    /**
     * 解析一份存档的世界状态。
     *
     * @param opener 每遍扫描各开一个新解析器（见 [Opener]）
     * @param progress 进度回调（可为 null）
     */
    fun parse(opener: Opener, progress: Progress? = null): WorldState {
        val names = scanLocationNames(opener, progress)
        return scanWorldState(opener, names, progress)
    }

    // ── 第一遍：缓存 GameLocation 的名字序 ────────────────────────────

    private fun scanLocationNames(opener: Opener, progress: Progress?): List<String> {
        val pull = opener.open()
        configure(pull)
        val names = ArrayList<String>()
        val stack = ArrayList<String>()
        var locIdx = -1
        var cap: Cap? = null
        var event = pull.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = pull.name ?: ""
                    stack.add(name)
                    // 上一个捕获的元素有子元素 → 它不是叶子，捕获作废
                    cap = null
                    if (name == "GameLocation") {
                        locIdx++
                        names.add("")
                        progress?.onProgress(STAGE_LOCATIONS, names.size, 0)
                    }
                    // 地图名是 GameLocation 的**直接子节点** `<name>`；
                    // 里面还有别的 `<name>`（建筑、牲畜、物品），靠父节点判别。
                    if (name == "name" && stack.size >= 2 && stack[stack.size - 2] == "GameLocation") {
                        cap = Cap(stack.size, Sink.LOCATION, name)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val c = cap
                    if (c != null && c.depth == stack.size) {
                        if (c.sink == Sink.LOCATION && locIdx >= 0) {
                            val t = c.buf.toString().trim()
                            // 第一份非空名字为准；重复出现的 <name> 不覆盖
                            if (t.isNotEmpty() && names[locIdx].isEmpty()) names[locIdx] = t
                        }
                        cap = null
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                XmlPullParser.TEXT -> {
                    val c = cap
                    if (c != null && c.depth == stack.size) c.buf.append(pull.text ?: "")
                }
                else -> Unit
            }
            event = pull.next()
        }
        return names
    }

    // ── 第二遍：按序号归属，读作物 / 地块 / 牲畜 / 背包 ──────────────────

    private fun scanWorldState(
        opener: Opener,
        locationNames: List<String>,
        progress: Progress?,
    ): WorldState {
        val pull = opener.open()
        configure(pull)
        val scan = WorldScan(locationNames, progress)
        var event = pull.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> scan.onStart(pull)
                XmlPullParser.END_TAG -> scan.onEnd(pull)
                XmlPullParser.TEXT -> scan.onText(pull)
                else -> Unit
            }
            event = pull.next()
        }
        return scan.build()
    }

    /** 关掉命名空间处理：存档里的 `xsi:type` 会按字面名存成 "xsi:type"。 */
    private fun configure(pull: XmlPullParser) {
        try {
            pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        } catch (e: Exception) {
            // 某些实现不支持这个开关；带前缀的属性名本来就按字面存，影响不大
        }
    }

    // 文本捕获的落点与容器定义在文件末尾（顶层），好让状态机类 [WorldScan] 也能用。
}

// ── 文本捕获 ──────────────────────────────────────────────────────────

/** 文本捕获的落点。 */
private enum class Sink {
    LOCATION, SAVE, CROP, PHASE_DAYS, SEASONS, ANIMAL, PLAYER, ITEM, BUILDING, VECTOR, TILE,
    // v2.99.0 / c179：村民好感（friendshipData 的 key 与 Friendship 子字段）
    FD_KEY, FD_POINT, FD_GIFT,
}

/** 一次文本捕获。`depth` 是被捕获元素在栈里的深度（1 起）。 */
private class Cap(val depth: Int, val sink: Sink, val tag: String) {
    val buf = StringBuilder()
}

// ── 品质 id 的两种包装 ────────────────────────────────────────────────

/** `(O)22` 这类带前缀的 id。 */
private val QUALIFIED_ID = Regex("""^\(([^)]*)\)(.*)$""")

/** 剥掉 `(O)` 之类前缀后的主体文本。 */
private fun qualifiedBody(raw: String?): String? {
    val t = raw?.trim() ?: return null
    if (t.isEmpty()) return null
    val m = QUALIFIED_ID.matchEntire(t)
    return (if (m != null) m.groupValues[2] else t).trim()
}

/**
 * 剥掉前缀后的**数字** id。
 *
 * mod 字符串（`CarrotSeeds`）不是数字 → null（**不硬翻、不瞎猜**）。
 */
internal fun qualifiedInt(raw: String?): Int? = qualifiedBody(raw)?.toIntOrNull()

/**
 * 剥掉前缀后的**名字**。
 *
 * MOD 档的 `indexOfHarvest` 写的是物品名（"Carrot"）而不是 id，
 * 这种情况下这里给出名字，数字形态则返回 null（名字用 id 查表即可）。
 */
internal fun qualifiedName(raw: String?): String? {
    val body = qualifiedBody(raw) ?: return null
    if (body.isEmpty()) return null
    return if (body.toIntOrNull() == null) body else null
}

/**
 * 跳过 UTF-8 BOM。**四份真实存档全部带 BOM**，不跳 XmlPullParser 会直接炸。
 *
 * 与 [SaveImport] 里的同名私有函数规则一致，但**不共用**：
 * 改 [SaveImport] 等于动既有文件（用户明确要求不改它的行为），
 * 这里独立一份更稳。
 */
internal fun skipSaveBom(input: InputStream): InputStream {
    val pb = PushbackInputStream(input, 3)
    val b = ByteArray(3)
    val n = pb.read(b, 0, 3)
    val isBom = n == 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()
    if (!isBom && n > 0) pb.unread(b, 0, n)
    return pb
}

/**
 * 按存档的规矩配置一个解析器：**跳过 BOM** + 关闭命名空间处理。
 *
 * Android 侧：`configureSaveParser(android.util.Xml.newPullParser(), file.inputStream())`
 * 单元测试：`configureSaveParser(KXmlParser(), FileInputStream(file))`
 */
internal fun configureSaveParser(pull: XmlPullParser, input: InputStream): XmlPullParser {
    try {
        pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    } catch (e: Exception) {
        // 同上：不支持就按字面属性名处理
    }
    pull.setInput(skipSaveBom(input), "UTF-8")
    return pull
}

// ── 第二遍的状态机 ────────────────────────────────────────────────────

/**
 * 第二遍扫描的状态机。
 *
 * 单独成类是因为要跟踪的状态很多（地图序号、地块坐标、作物字段、牲畜字段、
 * 玩家字段、物品字段），塞在一个函数里会变成一堆互相干扰的局部变量。
 */
private class WorldScan(
    private val locationNames: List<String>,
    private val progress: WorldStateParser.Progress?,
) {
    private val stack = ArrayList<String>()

    // 地图
    private var locIdx = -1
    private var currentLocation = ""
    private var scannedLocations = 0

    // 地块 / 作物
    private val plots = ArrayList<WorldPlot>()
    private var crop: CropBuf? = null
    /** 当前 TerrainFeature 里刚收好的作物（crop 结束时才知道，TerrainFeature 结束时才落块）。 */
    private var cropsHolder: WorldCrop? = null
    private var tfIsHoeDirt = false
    private var tfTileX: Int? = null
    private var tfTileY: Int? = null
    private var keyX: Int? = null
    private var keyY: Int? = null
    private var inTfItem = false
    private var inTfKey = false
    private var vecDepth = -1
    private var tileDepth = -1
    private var phaseDaysDepth = -1
    private var seasonsDepth = -1

    // 牲畜
    private val animals = ArrayList<WorldAnimal>()
    private var animal: AnimalBuf? = null
    private var buildingType: String? = null
    private var skippedDuplicateAnimals = 0

    // 玩家 / 背包
    private val players = ArrayList<WorldPlayer>()
    private val inventories = ArrayList<WorldInventory>()
    private var player: PlayerBuf? = null

    // 村民好感（v2.99.0 / c179）：只收房主 player 下的 friendshipData
    private val friendships = ArrayList<WorldNpcFriendship>()
    private var inFriendshipData = false
    private var inFdItem = false
    private var fdKey: String? = null
    private var fdPoints: Int? = null
    private var fdGifts: Int? = null

    // 存档级
    private var year: Int? = null
    private var season: String? = null
    private var dayOfMonth: Int? = null
    private var goldenWalnuts: Int? = null

    private var cap: Cap? = null
    private var sawRoot = false

    // ── 事件处理 ──────────────────────────────────────────────────────

    fun onStart(pull: XmlPullParser) {
        val name = pull.name ?: ""
        stack.add(name)
        val parent = if (stack.size >= 2) stack[stack.size - 2] else ""
        val depth = stack.size

        if (!sawRoot) {
            if (name == "SaveGame") sawRoot = true
            // 根节点之前不抓任何东西
            cap = null
            return
        }

        // 上一个捕获的元素出现了子元素 → 它不是叶子，捕获作废
        // （例：<phaseDays> 里装的是 <int>，不能把 int 的文本拼成 phaseDays 的值）
        cap = null

        when {
            name == "GameLocation" -> {
                locIdx++
                currentLocation = locationNames.getOrNull(locIdx)?.takeIf { it.isNotEmpty() } ?: ""
                scannedLocations++
                progress?.onProgress(
                    WorldStateParser.STAGE_WORLD, scannedLocations, locationNames.size
                )
            }
            name == "TerrainFeature" -> {
                tfIsHoeDirt = attrType(pull) == "HoeDirt"
                tfTileX = null
                tfTileY = null
            }
            name == "Building" -> buildingType = null
            name == "item" && parent == "terrainFeatures" -> {
                inTfItem = true
                keyX = null
                keyY = null
            }
            name == "key" && inTfItem && parent == "item" -> inTfKey = true
            name == "Vector2" && inTfKey -> vecDepth = depth
            name == "Tile" && parent == "TerrainFeature" -> tileDepth = depth
            name == "crop" -> crop = CropBuf()
            name == "phaseDays" -> phaseDaysDepth = depth
            name == "seasonsToGrowIn" -> seasonsDepth = depth
            name == "FarmAnimal" -> animal = AnimalBuf()
            name == "player" && parent == "SaveGame" -> player = PlayerBuf(farmhand = false)
            name == "Farmer" && parent == "farmhands" -> player = PlayerBuf(farmhand = true)
            name == "Item" && parent == "items" && player != null -> {
                player!!.item = ItemBuf().also { it.type = attrType(pull) }
            }
            // v2.99.0 / c179：村民好感。friendshipData 挂在 player / Farmer 下，
            // 只认房主（player）那份 —— 农场手（Farmer）的不收，口径见 WorldNpcFriendship。
            name == "friendshipData" && parent == "player" -> inFriendshipData = true
            name == "item" && inFriendshipData -> {
                inFdItem = true
                fdKey = null
                fdPoints = null
                fdGifts = null
            }
            else -> Unit
        }

        // 只有「有意思的父节点」下的叶子才取文本
        val sink = when {
            parent == "SaveGame" && name in SAVE_FIELDS -> Sink.SAVE
            parent == "crop" -> Sink.CROP
            parent == "phaseDays" && phaseDaysDepth == depth - 1 -> Sink.PHASE_DAYS
            parent == "seasonsToGrowIn" && seasonsDepth == depth - 1 -> Sink.SEASONS
            parent == "FarmAnimal" -> Sink.ANIMAL
            parent == "player" || parent == "Farmer" -> Sink.PLAYER
            parent == "Item" -> Sink.ITEM
            parent == "Building" && name == "buildingType" -> Sink.BUILDING
            parent == "Vector2" && vecDepth == depth - 1 -> Sink.VECTOR
            parent == "Tile" && tileDepth == depth - 1 -> Sink.TILE
            // v2.99.0 / c179：好感字段。key 的叶子是 <string>英文名</string>；
            // Points / GiftsThisWeek 在实测存档里都是直接文本（无 int 包装）。
            inFriendshipData && inFdItem && parent == "key" -> Sink.FD_KEY
            inFriendshipData && parent == "Friendship" && name == "Points" -> Sink.FD_POINT
            inFriendshipData && parent == "Friendship" && name == "GiftsThisWeek" -> Sink.FD_GIFT
            else -> null
        }
        if (sink != null) cap = Cap(depth, sink, name)
    }

    fun onText(pull: XmlPullParser) {
        val c = cap ?: return
        if (c.depth == stack.size) c.buf.append(pull.text ?: "")
    }

    fun onEnd(pull: XmlPullParser) {
        val name = pull.name ?: ""
        val depth = stack.size

        // 文本捕获收尾
        val c = cap
        if (c != null && c.depth == depth) {
            store(c)
            cap = null
        }

        when (name) {
            "phaseDays" -> phaseDaysDepth = -1
            "seasonsToGrowIn" -> seasonsDepth = -1
            "Vector2" -> vecDepth = -1
            "Tile" -> tileDepth = -1
            "key" -> inTfKey = false
            "crop" -> {
                val b = crop
                crop = null
                if (b != null) {
                    // 坐标：优先用字典键里的 Vector2（地块坐标的权威来源），
                    // 没有就用 TerrainFeature 里的 <Tile>。
                    cropsHolder = buildCrop(b, keyX ?: tfTileX, keyY ?: tfTileY)
                }
            }
            "TerrainFeature" -> {
                if (tfIsHoeDirt) {
                    plots.add(
                        WorldPlot(
                            location = currentLocation,
                            x = keyX ?: tfTileX,
                            y = keyY ?: tfTileY,
                            crop = cropsHolder,
                        )
                    )
                }
                cropsHolder = null
                tfIsHoeDirt = false
            }
            "item" -> {
                if (parentName() == "terrainFeatures") inTfItem = false
                // v2.99.0 / c179：friendshipData 的 item 收口（一个村民一条）
                if (inFdItem) {
                    inFdItem = false
                    finishFriendship()
                }
            }
            "friendshipData" -> {
                inFriendshipData = false
                inFdItem = false
            }
            "FarmAnimal" -> finishAnimal()
            "Item" -> finishItem()
            "player", "Farmer" -> finishPlayer()
            else -> Unit
        }

        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
    }

    // ── 捕获落库 ──────────────────────────────────────────────────────

    private fun store(c: Cap) {
        val text = c.buf.toString().trim()
        when (c.sink) {
            Sink.LOCATION -> Unit
            Sink.SAVE -> when (c.tag) {
                "year" -> year = parseInt(text)
                "currentSeason" -> season = text.ifEmpty { null }
                "dayOfMonth" -> dayOfMonth = parseInt(text)
                "goldenWalnuts" -> goldenWalnuts = parseInt(text)
            }
            Sink.CROP -> crop?.set(c.tag, text)
            Sink.PHASE_DAYS -> parseInt(text)?.let { crop?.phaseDays?.add(it) }
            Sink.SEASONS -> if (text.isNotEmpty()) crop?.seasons?.add(text)
            Sink.ANIMAL -> animal?.set(c.tag, text)
            Sink.PLAYER -> player?.set(c.tag, text)
            Sink.ITEM -> player?.item?.set(c.tag, text)
            Sink.BUILDING -> buildingType = text.ifEmpty { null }
            Sink.VECTOR -> if (c.tag == "X") keyX = parseInt(text) else keyY = parseInt(text)
            Sink.TILE -> if (c.tag == "X") tfTileX = parseInt(text) else tfTileY = parseInt(text)
            Sink.FD_KEY -> if (fdKey == null) fdKey = text.ifEmpty { null }
            Sink.FD_POINT -> fdPoints = parseInt(text)
            Sink.FD_GIFT -> fdGifts = parseInt(text)
        }
    }

    // ── 组装 ──────────────────────────────────────────────────────────

    private fun buildCrop(b: CropBuf, x: Int?, y: Int?): WorldCrop {
        val harvestRaw = b.indexOfHarvest?.takeIf { it.isNotEmpty() }
        val overrideRaw = b.overrideHarvestItemId?.takeIf { it.isNotEmpty() }
        val seedRaw = b.seedIndex?.takeIf { it.isNotEmpty() }
        // 0 / -1 一律当「未知」：物品 id 0 是「锈剑」，拿它去查表只会显示错东西
        val harvestId = qualifiedInt(harvestRaw)?.takeIf { it != 0 && it != -1 }
        val overrideId = qualifiedInt(overrideRaw)?.takeIf { it != 0 && it != -1 }
        val forageId = b.whichForageCrop?.takeIf { it != 0 && it != -1 }
        val seedId = qualifiedInt(seedRaw)
        return WorldCrop(
            location = currentLocation,
            plotX = x,
            plotY = y,
            seedRaw = seedRaw,
            seedId = seedId,
            harvestRaw = harvestRaw,
            harvestId = harvestId,
            overrideRaw = overrideRaw,
            overrideId = overrideId,
            forageId = forageId,
            forageCrop = b.forageCrop,
            raisedSeeds = b.raisedSeeds,
            phaseDays = b.phaseDays.toList(),
            currentPhase = b.currentPhase,
            dayOfCurrentPhase = b.dayOfCurrentPhase,
            fullGrown = b.fullGrown,
            dead = b.dead,
            regrowAfterHarvest = b.regrowAfterHarvest,
            minHarvest = b.minHarvest,
            maxHarvest = b.maxHarvest,
            maxHarvestIncreasePerFarmingLevel = b.maxHarvestIncreasePerFarmingLevel,
            seasons = b.seasons.toList(),
        )
    }

    /**
     * 牲畜收尾。
     *
     * **只取 `indoors/animals/item/value/FarmAnimal` 这一条路径**；
     * `indoors/Animals/SerializableDictionaryOfInt64FarmAnimal/…` 是同一份数据的
     * 旧序列化副本，**显式跳过**（不去重会把 ff 档 16 只读成 32 只）。
     */
    private fun finishAnimal() {
        val b = animal
        animal = null
        if (b == null) return
        val chain = stack.dropLast(1) // 去掉 "FarmAnimal" 自己
        val isDuplicate = chain.any { it == "SerializableDictionaryOfInt64FarmAnimal" }
        if (isDuplicate) {
            skippedDuplicateAnimals++
            return
        }
        val isRealPath = chain.size >= 4 &&
            chain[chain.size - 4] == "indoors" &&
            chain[chain.size - 3] == "animals" &&
            chain[chain.size - 2] == "item" &&
            chain[chain.size - 1] == "value"
        if (!isRealPath) return
        animals.add(
            WorldAnimal(
                name = b.name,
                type = b.type,
                building = b.buildingTypeILiveIn ?: buildingType,
                location = currentLocation,
                happiness = b.happiness,
                fullness = b.fullness,
                friendship = b.friendshipTowardFarmer,
                age = b.age,
                daysOwned = b.daysOwned,
                ageWhenMature = b.ageWhenMature,
                daysToLay = b.daysToLay,
                daysSinceLastLay = b.daysSinceLastLay,
                daysSinceLastFed = b.daysSinceLastFed,
                produceQuality = b.produceQuality,
                currentProduce = b.currentProduce,
                defaultProduceIndex = b.defaultProduceIndex,
                deluxeProduceIndex = b.deluxeProduceIndex,
                harvestType = b.harvestType,
                wasPet = b.wasPet,
                wasAutoPet = b.wasAutoPet,
                allowReproduction = b.allowReproduction,
                price = b.price,
            )
        )
    }

    /**
     * 物品收尾。
     *
     * ⚠ 空槽位是 `<Item xsi:nil="true" />`——**没有 name**。
     * 不收它，否则背包件数会按槽位数算（实测能把农场手算成 12 件）。
     */
    private fun finishItem() {
        val p = player ?: return
        val b = p.item ?: return
        p.item = null
        val nm = b.name?.takeIf { it.isNotEmpty() } ?: return
        p.items.add(
            WorldItem(
                name = nm,
                displayName = b.displayName,
                itemId = b.itemId,
                stack = b.stack,
                quality = b.quality,
                price = b.price,
                type = b.type,
            )
        )
    }

    private fun finishPlayer() {
        val p = player ?: return
        player = null
        val name = p.name?.takeIf { it.isNotEmpty() }
        val hours = p.millisecondsPlayed?.let { Math.round(it / 3600000.0 * 10.0) / 10.0 }
        players.add(
            WorldPlayer(
                name = name,
                farmhand = p.farmhand,
                money = p.money,
                hoursPlayed = hours,
                skills = LinkedHashMap(p.skills),
                spouse = p.spouse,
            )
        )
        inventories.add(
            WorldInventory(
                owner = name ?: "",
                farmhand = p.farmhand,
                money = p.money,
                items = p.items.toList(),
            )
        )
    }

    private fun parentName(): String = if (stack.size >= 2) stack[stack.size - 2] else ""

    /**
     * 一个村民的好感收口（`<item>` 结束时）。
     *
     * ⚠ **只收房主**：player buf 存在且不是农场手。农场手（联机档 Farmer）的
     * friendshipData 走到这里时 `player.farmhand == true`，直接丢。
     * key 拿不到（空 / 缺失）也丢 —— 键都没有的名字折进去只会变成假数据。
     */
    private fun finishFriendship() {
        if (!inFriendshipData) return
        val p = player ?: return
        if (p.farmhand) return
        val k = fdKey?.takeIf { it.isNotEmpty() } ?: return
        friendships.add(
            WorldNpcFriendship(name = k, points = fdPoints, giftsThisWeek = fdGifts)
        )
    }

    fun build(): WorldState = WorldState(
        year = year,
        season = season,
        dayOfMonth = dayOfMonth,
        goldenWalnuts = goldenWalnuts,
        locations = locationNames,
        plots = plots,
        animals = animals,
        inventories = inventories,
        friendships = friendships.toList(),
        players = players,
        skippedDuplicateAnimals = skippedDuplicateAnimals,
    )

    companion object {
        private val SAVE_FIELDS = setOf("year", "currentSeason", "dayOfMonth", "goldenWalnuts")
    }
}

// ── 字段缓冲区 ────────────────────────────────────────────────────────

private class CropBuf {
    val phaseDays = ArrayList<Int>()
    val seasons = ArrayList<String>()
    var indexOfHarvest: String? = null
    var overrideHarvestItemId: String? = null
    var seedIndex: String? = null
    var currentPhase: Int? = null
    var dayOfCurrentPhase: Int? = null
    var whichForageCrop: Int? = null
    var regrowAfterHarvest: Int? = null
    var minHarvest: Int? = null
    var maxHarvest: Int? = null
    var maxHarvestIncreasePerFarmingLevel: Int? = null
    var fullGrown: Boolean? = null
    var dead: Boolean? = null
    var forageCrop: Boolean? = null
    var raisedSeeds: Boolean? = null

    fun set(tag: String, text: String) {
        when (tag) {
            "indexOfHarvest" -> indexOfHarvest = text
            "overrideHarvestItemId" -> overrideHarvestItemId = text
            "seedIndex" -> seedIndex = text
            "currentPhase" -> currentPhase = parseInt(text)
            "dayOfCurrentPhase" -> dayOfCurrentPhase = parseInt(text)
            "whichForageCrop" -> whichForageCrop = parseInt(text)
            "regrowAfterHarvest" -> regrowAfterHarvest = parseInt(text)
            "minHarvest" -> minHarvest = parseInt(text)
            "maxHarvest" -> maxHarvest = parseInt(text)
            "maxHarvestIncreasePerFarmingLevel" -> maxHarvestIncreasePerFarmingLevel = parseInt(text)
            "fullGrown" -> fullGrown = parseBool(text)
            "dead" -> dead = parseBool(text)
            "forageCrop" -> forageCrop = parseBool(text)
            "raisedSeeds" -> raisedSeeds = parseBool(text)
        }
    }
}

private class AnimalBuf {
    var name: String? = null
    var type: String? = null
    var buildingTypeILiveIn: String? = null
    var happiness: Int? = null
    var fullness: Int? = null
    var friendshipTowardFarmer: Int? = null
    var age: Int? = null
    var daysOwned: Int? = null
    var ageWhenMature: Int? = null
    var daysToLay: Int? = null
    var daysSinceLastLay: Int? = null
    var daysSinceLastFed: Int? = null
    var produceQuality: Int? = null
    var currentProduce: Int? = null
    var defaultProduceIndex: Int? = null
    var deluxeProduceIndex: Int? = null
    var harvestType: Int? = null
    var wasPet: Boolean? = null
    var wasAutoPet: Boolean? = null
    var allowReproduction: Boolean? = null
    var price: Int? = null

    fun set(tag: String, text: String) {
        when (tag) {
            "name" -> name = text
            "type" -> type = text
            "buildingTypeILiveIn" -> buildingTypeILiveIn = text
            "happiness" -> happiness = parseInt(text)
            "fullness" -> fullness = parseInt(text)
            "friendshipTowardFarmer" -> friendshipTowardFarmer = parseInt(text)
            "age" -> age = parseInt(text)
            "daysOwned" -> daysOwned = parseInt(text)
            "ageWhenMature" -> ageWhenMature = parseInt(text)
            "daysToLay" -> daysToLay = parseInt(text)
            "daysSinceLastLay" -> daysSinceLastLay = parseInt(text)
            "daysSinceLastFed" -> daysSinceLastFed = parseInt(text)
            "produceQuality" -> produceQuality = parseInt(text)
            "currentProduce" -> currentProduce = parseInt(text)
            "defaultProduceIndex" -> defaultProduceIndex = parseInt(text)
            "deluxeProduceIndex" -> deluxeProduceIndex = parseInt(text)
            "harvestType" -> harvestType = parseInt(text)
            "wasPet" -> wasPet = parseBool(text)
            "wasAutoPet" -> wasAutoPet = parseBool(text)
            "allowReproduction" -> allowReproduction = parseBool(text)
            "price" -> price = parseInt(text)
        }
    }
}

private class PlayerBuf(val farmhand: Boolean) {
    var name: String? = null
    var money: Int? = null
    var millisecondsPlayed: Int? = null
    var spouse: String? = null
    var item: ItemBuf? = null
    val items = ArrayList<WorldItem>()
    val skills = LinkedHashMap<String, Int?>()

    fun set(tag: String, text: String) {
        when (tag) {
            "name" -> name = text
            "money" -> money = parseInt(text)
            "millisecondsPlayed" -> millisecondsPlayed = parseInt(text)
            "spouse" -> spouse = text
            "farmingLevel" -> skills["耕"] = parseInt(text)
            "fishingLevel" -> skills["钓"] = parseInt(text)
            "foragingLevel" -> skills["采"] = parseInt(text)
            "miningLevel" -> skills["矿"] = parseInt(text)
            "combatLevel" -> skills["战"] = parseInt(text)
        }
    }
}

private class ItemBuf {
    var name: String? = null
    var displayName: String? = null
    var itemId: String? = null
    var stack: Int? = null
    var quality: Int? = null
    var price: Int? = null
    var type: String? = null

    fun set(tag: String, text: String) {
        when (tag) {
            "name" -> name = text
            "DisplayName" -> displayName = text
            "itemId" -> itemId = text
            "stack" -> stack = parseInt(text)
            "quality" -> quality = parseInt(text)
            "price" -> price = parseInt(text)
        }
    }
}

private fun parseInt(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return t.toIntOrNull() ?: t.toDoubleOrNull()?.toInt()
}

private fun parseBool(text: String): Boolean? = when (text.trim()) {
    "true" -> true
    "false" -> false
    else -> null
}

/** 读存档里的 `xsi:type` 属性（关掉命名空间处理后属性名按字面存）。 */
private fun attrType(pull: XmlPullParser): String? {
    for (i in 0 until pull.getAttributeCount()) {
        if (pull.getAttributeName(i) == "xsi:type") return pull.getAttributeValue(i)
    }
    return null
}
