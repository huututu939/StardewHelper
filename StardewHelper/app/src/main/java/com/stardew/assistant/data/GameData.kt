package com.stardew.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.strList(): List<String> =
    (0 until length()).map { optString(it, "") }.filter { it.isNotEmpty() }

private fun JSONObject.intOr(key: String): Int? = when (val v = opt(key)) {
    is Number -> v.toInt()
    is String -> v.replace(",", "").filter { it.isDigit() }.ifEmpty { null }?.toInt()
    else -> null
}

private fun JSONArray.rows(): List<Row> =
    (0 until length()).map { i ->
        val o = getJSONObject(i)
        o.keys().asSequence().associateWith { k -> o.optString(k, "") }
    }

private fun JSONArray.toRowsTyped(): List<Map<String, String>> = rows()

object GameData {
    @Volatile var ready = false; private set

    var fishing: List<Fishing> = emptyList()

    /**
     * v2.72：**原版**村民（gifts.json）。改名的唯一入口在 [load] 里。
     *
     * ⚠ 不要让任何地方再写 `gifts = ...`——村民表现在是 [baseGifts] 与
     *   Mod 包的合并结果，改成计算属性就是为了让「加包 / 关包」只经过 [applyMods]
     *   一个出口（两处赋值 = 迟早有一处忘掉重算，表现是「开关关了但人还在」）。
     */
    var baseGifts: List<GiftVillager> = emptyList()

    /** v2.72：已装 Mod 包（扫盘结果）。UI 读它显示「已装 N 个」。 */
    /**
     * v2.73.4：**原版**作物（内置 crops.json）。改名的唯一入口在 [load] 里。
     *
     * ⚠ 与 [baseGifts] 同一套理由：`crops` 已改成计算属性（合并结果），
     *   所以任何地方都不许再写 `crops = ...`。
     */
    @Volatile var baseCrops: List<Crop> = emptyList()

    /**
     * v2.81.0（P1）：存档世界状态的**名称映射**（item_id / 英文名 → App 中文名）。
     *
     * 由 `tools/gen_world_item_map.py` 生成（`assets/data/worldItemMap.json`）。
     * 缺了只是「我的农场」显示不出中文名，**不影响其它任何页** → 按非核心表降级。
     */
    @Volatile var worldItemMap: WorldItemMap = WorldItemMap.EMPTY

    /**
     * 全体作物 = 原版 + 已启用 Mod 包的作物。
     *
     * 为什么**并进 crops** 而不是另开一张表：`Calc.plantResults`（计算页按季节/日期
     * 算能种什么）、今日页播种死线、年历播种清单都读 `GameData.crops` 并按
     * `season in crop.seasons` 过滤。SVE 作物另开一张表的话，这三处都得改一遍才看得到
     * 「夏天能种古代蕨」——而漏改任何一处就是**静默少一个季节**（不报错，只是那个季节
     * 的 SVE 作物消失）。并进来则季节筛选自动生效，改一处就够。
     *
     * 重名处理与 [gifts] 同款：包与原版撞名时给 **Mod 那个**加后缀，两边都在、都点得开。
     */
    val crops: List<Crop>
        get() {
            if (enabledModIds.isEmpty()) return baseCrops
            val out = baseCrops.toMutableList()
            val taken = baseCrops.mapTo(mutableSetOf()) { it.name }
            // v2.73.13：crops 模块只由一个包提供（整包 + 分片并存时不重复）
            val pack = ownerPack("crops") ?: return baseCrops
            for (mc in pack.crops) {
                val v = mc.toCrop()
                if (taken.add(v.name)) out += v
                else {
                    val renamed = v.copy(name = "${v.name}（${pack.name}）")
                    taken.add(renamed.name)
                    out += renamed
                }
            }
            return out
        }

    /** 来自 Mod 包的作物数（总开关全关时为 0）。UI 用它判断作物区有没有扩展内容。 */
    val modCropCount: Int get() = crops.size - baseCrops.size

    /**
     * 作物相关的 UI `remember` 缓存键。
     *
     * ## 为什么需要它
     * [crops] 是**计算属性**，装/删包后同一个 remember 键会命中旧值——表现是
     * 「装完 SVE 进计算页/今日页还是看不到新作物」，且**不报错**。
     * 计算页的 results/cycles/cropItems、今日页播种死线都读 crops，各自补键就
     * 是同一个修法散在多处（漏一处漏一种表现），故收在数据层成单源。
     *
     * 取 (包数, Mod 作物数) 而不是时间戳：包内容变了而数量没变（改的是某条作物的
     * 季节）也要重算——所以两个维度都得进键。
     */
    // ── c210 图鉴反查提速：按名字查 Mod 条目的映射（原 CodexPage 每格 firstOrNull 线性扫，
    //    一屏几十格×5 张表 ≈ 每次重组几百次比较；建一次映射后 O(1)。装/删包后由
    //    各自的 *Key 键控 remember 重建，与既有缓存纪律同源）。
    private val modCropByName: Map<String, Crop> by lazy {
        crops.associateBy { it.name }
    }

    /** c210：按名字查作物（合并表=原版+Mod；原 CodexPage 每格对 GameData.crops 全表 firstOrNull）。 */
    fun modCropOf(name: String): Crop? = modCropByName[name]
    private val modRecipeByName: Map<String, ModRepo.ModRecipe> by lazy {
        modRecipes.associateBy { it.name }
    }
    private val modFishByName: Map<String, ModRepo.ModFish> by lazy {
        modFishes.associateBy { it.name }
    }
    private val modLocationByName: Map<String, ModRepo.ModLocation> by lazy {
        modLocations.associateBy { it.name }
    }

    val modCropsKey: Pair<Int, Int> get() = modPacks.size to modCropCount

    /** 该作物所属的已装包；非扩展作物返回 null（与 [modPackOf] 同款用途）。 */
    fun modPackOf(c: Crop): ModRepo.Installed? =
        c.modId?.let { id -> modPacks.firstOrNull { it.modId == id } }

    /** 作物来源短标签（「SVE」），非扩展作物返回 null。与 [modTagOf] 同款判据。 */
    fun modTagOf(c: Crop): String? = modTagOf(c.modId)

    /**
     * 通用来源短标签：给 modId，返回包自己的短名（缺键退 modId 大写）。
     *
     * v2.73.7：菜谱组也要标来源，于是从「只认 Crop」放宽成「认 modId」——
     * 判据只有一处（包自己的短名），不写死「SVE」。
     */
    fun modTagOf(modId: String?): String? =
        modId?.let { id -> modPackOfId(id)?.tag?.ifBlank { null } ?: id.uppercase() }

    /** 按 modId 找已装包（[modPackOf] 的通用版）。 */
    fun modPackOfId(id: String): ModRepo.Installed? =
        modPacks.firstOrNull { it.modId == id }

    /** v2.72：已装 Mod 包（扫盘结果）。UI 读它显示「已装 N 个」。 */
    @Volatile var modPacks: List<ModRepo.Installed> = emptyList()

    /** v2.72：当前启用的 modId。设备态（来自偏好），与 installedCache 一起决定合并结果。 */
    @Volatile private var enabledModIds: Set<String> = emptySet()

    /**
     * 全体村民 = 原版 + 已启用的 Mod 包村民。
     *
     * 重名的处理：包与原版（或另一个包）撞名时给**Mod 那个**加后缀而不是丢弃——
     * 丢弃会让包作者以为「这人已收录」而不再更新，放着不管则列表里两行同名、
     * 点哪一行都说不清是谁。带后缀至少两边都在、都点得开。
     */
    val gifts: List<GiftVillager>
        get() {
            if (enabledModIds.isEmpty()) return baseGifts
            // v2.73.13：npcs 模块**只由一个包提供**（整包 + 分片并存时不重复）。
            val pack = ownerPack("npcs") ?: return baseGifts
            val out = baseGifts.toMutableList()
            val taken = baseGifts.mapTo(mutableSetOf()) { it.name }
            for (v in pack.npcs) {
                if (taken.add(v.name)) out += v
                else {
                    val renamed = v.copy(name = "${v.name}（${pack.name}）")
                    taken.add(renamed.name)
                    out += renamed
                }
            }
            return out
        }

    /** 来自 Mod 包的村民数（总开关全关时为 0）。 */
    val modVillagerCount: Int get() = gifts.size - baseGifts.size

    /**
     * v2.100.0 / c182：**村民中文名全表**（`assets/data/reportNames.json` 的 npcs 段的值）。
     *
     * ## 为什么要有它
     * 「这条图鉴条目是不是村民」有**两个判定源**：礼物表（[gifts]，原版村民的礼物档案）
     * 与这份译名表（存档侧也用它把英文 key 换成中文名）。
     * ⚠ 两张表的名字曾经对不上：`gifts.json` 写「里奥」、这里写「雷欧」、
     *   图鉴「电影院偏好」组（`movies.json`）也写「雷欧」⇒ 查礼物表查不到
     *   ⇒ `isVillager` 判成 false ⇒ **「🎒 你的状态」整张卡不渲染**
     *   （存档里明明有 TA 的好感）。数据侧已统一（c182 改名），
     *   但判定仍以**两张表的并集**为准：礼物表里缺的人（吉尔 / 冈瑟 / 马龙）
     *   照样是村民（存档里有他们的好感）——只显示「好感」那几行，
     *   生日查不到就**不显示那一行**（不编）。
     */
    @Volatile var npcNames: Set<String> = emptySet()

    /**
     * 这个名字是不是一位村民（**礼物表 ∪ 存档侧译名表**）。
     *
     * v2.100.0 / c182：图鉴「🎒 你的状态」用它判「要不要显示村民段」
     * （好感 / 本周送礼 / 下次生日）。判成 false ⇒ 整张状态卡不渲染 ——
     * 那正是「雷欧」身上发生过的 bug（名字对不上 ⇒ 查不到礼物档案）。
     */
    fun isVillagerName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name in npcNames) return true
        return gifts.any { it.name == name }
    }

    /**
     * 该村民所属的已装包；非扩展村民返回 null。
     *
     * 村民行/详情要判断「礼物几段数据」时用它，而不是自己再写一遍按 modId 找包——
     * 那种查找散在几处，改了包名口径就只改得完一半。
     */
    fun modPackOf(v: GiftVillager): ModRepo.Installed? =
        v.modId?.let { id -> modPacks.firstOrNull { it.modId == id } }

    /**
     * 该村民的来源短标签（用户看到的是「SVE」而不是「扩展包」）。
     *
     * 取包自己声明的短名，缺失时退回 modId 大写——**不写死「SVE」**：
     * 以后装别的 Mod 包时这一列要能自动显示成那个包的名字，否则就成了「无论装什么都叫 SVE」。
     */
    fun modTagOf(v: GiftVillager): String? =
        v.modId?.let { id -> modPackOf(v)?.tag?.ifBlank { null } ?: id.uppercase() }

    /**
     * 该村民的礼物数据是否完整（五段齐全）。
     *
     * UI 用它决定要不要显示「中立」这类新段：**老包（v2.0 只有最爱）不该显示空段**，
     * 一个空着的「👍 喜欢（0）」看着像功能坏了。
     */
    fun giftTiersComplete(v: GiftVillager): Boolean =
        !v.fromMod || (modPackOf(v)?.giftTierCount ?: 0) >= 5

    /**
     * v2.73.13：**模块 → 提供该模块的包 id**（一个模块只由一个包提供）。
     *
     * ## 为什么需要
     * 扩展包拆成多个分片后，「整包」与「分片包」会同时存在（老用户平滑过渡、
     * 用户手滑两个都下）。两个包都提供 crops → 作物在图鉴里出现两遍、
     * 商店出现两遍 —— 不是报错，是**静默的数据重复**。
     *
     * ## 口径
     * 按 modId 排序取**第一个**提供该模块的包。整包的 modId 是 `sve`、
     * 分片是 `sve-xxx`，字典序 `sve` < `sve-xxx` → 整包优先，
     * 正好是「装全的先生效，别的包只补它没有的模块」。
     *
     * ⚠ 这个 map 只影响**合并**，不影响 ModsPage 的显示 ——
     *   用户装了什么包、每个包带多少 NPC，那里照实显示（`modPacks` 原样）。
     */
    private val moduleOwner: Map<String, String>
        get() {
            val out = LinkedHashMap<String, String>()
            if (enabledModIds.isEmpty()) return out
            for (id in enabledModIds.sorted()) {
                val pack = modPacks.firstOrNull { it.modId == id } ?: continue
                val has = listOf(
                    "npcs" to pack.npcs.isNotEmpty(),
                    "crops" to pack.crops.isNotEmpty(),
                    "recipes" to pack.recipes.isNotEmpty(),
                    "fish" to pack.fish.isNotEmpty(),
                    "shops" to pack.shops.isNotEmpty(),
                    "heartEvents" to pack.heartEvents.isNotEmpty(),
                    "locations" to pack.locations.isNotEmpty(),
                )
                for ((m, present) in has) {
                    if (present && !out.containsKey(m)) out[m] = id
                }
            }
            return out
        }

    /**
     * 取某个模块的**唯一**提供包（null = 没人提供这个模块）。
     *
     * 各合并属性都走它 —— 判据只有一处，改一次全部跟着改。
     */
    private fun ownerPack(module: String): ModRepo.Installed? {
        val id = moduleOwner[module] ?: return null
        return modPacks.firstOrNull { it.modId == id }
    }

    /**
     * v2.72：重建 Mod 合并结果。**唯一的重算出口**。
     *
     * 由 [ModRepo.applyEnabled] 在偏好流变化时调用，所以「启用/停用/装包/删包」
     * 四种操作都会落到同一行代码上，不会各写一份合并逻辑。
     */
    fun applyMods(packs: List<ModRepo.Installed>, enabled: Set<String>) {
        // ⚠ v2.73.12：版本号**只在视图真的变了**时才 +1。
        //   applyMods 挂在**偏好流**上（MainActivity 的 snapshot.collect），
        //   而偏好流任何变化都发快照（勾待办、切存档、标记特别关心…）。
        //   若每次都 +1，就会「勾个待办 → 搜索索引全量重建 800+ 条」——
        //   那是 v2.31 专门优化掉的开销，不能又引回来。
        //   判据只看**与图鉴视图有关**的状态：已装包列表 / 启用集合。
        //   （包内容更新也走这里：packs 是新解析的对象，equals 不等 → 照样 +1。）
        val viewChanged = modPacks != packs || enabledModIds != enabled
        modPacks = packs
        enabledModIds = enabled
        if (viewChanged) codexViewVersion++
    }

    var festivals: List<Festival> = emptyList()
    /** c211：材料 → 需要该材料扩容的鱼（鱼塘反查）。空 = pondGates.json 缺失/解析失败。 */
    var pondFishByMaterial: Map<String, List<String>> = emptyMap()
    var seedPrices: List<SeedPrice> = emptyList()
    var processing: List<ProcessRow> = emptyList()
    var bundleRooms: List<BundleRoom> = emptyList()
    var heartEvents: List<HeartEventVillager> = emptyList()
    var marriageSpouses: List<Pair<String, Pair<String, String>>> = emptyList() // 名字 to (活动区, 会送的东西)
    var imageMap: Map<String, String> = emptyMap()

    // v2.5 新资产：来源对照 / 营业时间 / 周循环 / 解锁卡 / 怪物掉落
    // （v2.17：删掉 smelting——键全 mismatch 的死数据，全项目零消费方）
    var itemSources: Map<String, SourceRow> = emptyMap() // 名字（含别名）→ 来源

    /**
     * v2.99.0 / c180：别名表（`assets/data/itemAliases.json`）—— **只存「别名 → 规范名」**。
     *
     * ## 铁律：这绝不能变成第二份物品表
     * 里面**只有名字对**（"果酒": "酒"），没有价格、没有描述、没有图标、没有来源。
     * 来源一律由既有数据源派生（见 [sourceIndex]），别名表只负责把「同一个东西的另一个名字」
     * 归到同一个规范名上。
     *
     * ## 为什么要有它
     * 全 App 各表对同一件东西用了不同的名字（礼物表写「葡萄酒」、商店表写「果酒」、
     * 图鉴写「酒」；收集包写「动物毛」、出货表写「羊毛」）。不归一就出现两种病：
     * ① 点图鉴跳不过去（零命中落空搜索页）；② 查不到来源（按别名查，三表全都对不上）。
     * 归一之后**一处**实现、全 App 复用（[canonicalName]）。
     */
    var itemAliases: Map<String, String> = emptyMap()

    /**
     * v2.99.0 / c180：「去哪拿」线索索引（规范名 → 线索列表），启动时算一次，查询 O(1)。
     *
     * ⚠ 全部由**既有数据源**派生（shopCatalog / processingProfit / fishSpots / monsterDrops /
     *   itemSources / animals 组 / crops / 菜谱·制作·晶洞组的「获取」行 / bundles），
     *   **不新建第二份来源表**。查名字先过 [canonicalName]，所以「果酒」与「酒」查到同一份。
     */
    var sourceIndex: Map<String, List<SourceClue>> = emptyMap()
    var shopHours: List<ShopHours> = emptyList()
    var weekLoop: List<WeekDay> = emptyList()
    var unlockCards: List<UnlockCard> = emptyList()
    var monsterDrops: List<Pair<String, String>> = emptyList() // 怪名 → 掉落｜出没

    var codexGroups: List<CodexGroup> = emptyList()

    /**
     * v2.73.12：**图鉴运行时视图的版本号**。视图内容一变就 +1。
     *
     * ## 为什么需要它
     * 搜索索引（[com.stardew.assistant.ui.codex.codexIndex]）原先用 `codexGroups`
     * 的**引用身份**判缓存（`===`）。而 Mod 数据并进来后，视图是
     * [codexGroupsWithMods] —— 它每次访问都新建对象，引用身份**永远不同**，
     * 缓存等于没有，每次重组都重建上千条索引（v2.31 专门为这个做过优化）。
     *
     * 改用「版本号」判缓存后：
     * - [codexGroupsWithMods] 本身按版本号缓存（省掉每次重组重建几百个条目）；
     * - 搜索索引与正文词典（`nameToHits` / `bucketByFirst`）也都按版本号重建。
     *
     * ## 什么时候 +1
     * [load] 建完静态组之后、以及 [applyMods] 之后（即装包/删包/启停包）。
     * 包内容变化也走 [applyMods]（v2.73.2 修的「装完无条件重算」），所以覆盖到了。
     */
    @Volatile
    var codexViewVersion: Int = 0
        private set

    // ── v2.14 攻略数据 ─────────────────────────────────────────────
    // 各份数据独立解析：任一份损坏只让对应页面显示「数据加载失败」，不会连带其他页签白屏
    //（v2.17 修正：此前注释误称 load() 抛异常「会卡在 LoadingSplash」，实际是直接崩进程；
    //  现在核心表也各自 runCatching，见 load() 内 coreFailed）。
    var fishSpots: FishSpotTable? = null
    var weaponCompendium: JSONObject? = null
    var gingerIslandGuide: JSONObject? = null
    var marriageGuide: JSONObject? = null
    var processingProfit: JSONObject? = null

    // ── v2.15 攻略数据（接自 v9 数据包）─────────────────────────────
    // 同样各自独立 try：任一份损坏只让对应页面显示「数据加载失败」。
    // 这四份来自 wiki 原始模块，已由 tools/import_v9_guide.py 剥掉 {meta,data} 包装。
    var skillLeveling: JSONObject? = null
    var foraging: JSONObject? = null
    var petGuide: JSONObject? = null
    var weatherLuck: JSONObject? = null

    // ── v2.18 群友反馈新增 ───────────────────────────────────────
    /** ⚔ 刷级路线：区域经验聚合 / 冒险家公会目标 / 78 怪明细（由 tools/import_v218.py 加工自 v9 monster_compendium） */
    var monsterFarming: JSONObject? = null

    /** 🛒 商店目录（v2.56 群友反馈）：店名 -> 物品行数组 {name,price,price2?,unit?,qual?,avail,note?,codex?}。
     *  由 tools/build_shop_catalog.py 从英文 wiki 1.6 商品表生成，译名全部走项目既有口径（像素哈希/互链/字典五层对齐）。 */
    var shopCatalog: JSONObject? = null

    /** 核心表（crops/fishing/gifts…）解析失败清单，load() 尾部回填 */
    var coreFailures: List<String> = emptyList()
        private set

    /** 解析失败时的降级位：页面用它决定要不要显示「数据加载失败」 */
    val loadFailures: List<String>
        get() = coreFailures + listOfNotNull(
            "fishSpots".takeIf { fishSpots == null },
            "weaponCompendium".takeIf { weaponCompendium == null },
            "gingerIslandGuide".takeIf { gingerIslandGuide == null },
            "marriageGuide".takeIf { marriageGuide == null },
            "processingProfit".takeIf { processingProfit == null },
            "skillLeveling".takeIf { skillLeveling == null },
            "foraging".takeIf { foraging == null },
            "petGuide".takeIf { petGuide == null },
            "weatherLuck".takeIf { weatherLuck == null },
            "monsterFarming".takeIf { monsterFarming == null },
            "shopCatalog".takeIf { shopCatalog == null },
        )

    /**
     * 用料名 → 菜名/制作品名（用途反查）。
     *
     * v2.73.7 起 `recipeUses` 与 `recipesByName` 都是**计算属性**（原版底座 + 已启用包的料理）：
     * 扩展包装了 20 道 SVE 料理后，「虚空鳗能做哪几道菜」这类反查也该认它们。
     * 底座（[baseRecipeUses]）内容与顺序保持与改版前**逐条一致**——verify 有等价断言。
     */
    val recipeUses: List<Pair<String, String>>
        get() {
            val base = baseRecipeUses
            // 成品名在 map 的 **key** 里（RecipeInfo 本身没有 name 字段）
            val extra = modRecipeInfos.entries.flatMap { (product, r) ->
                r.ingredients.map { (m, _) -> m to product }
            }
            return if (extra.isEmpty()) base else base + extra
        }

    var craftUses: List<Pair<String, String>> = emptyList()

    /**
     * 成品名 → 配方（正向）。群友 `:෴天劫:` 的「怎么制作」就查这两个。
     *  ⚠ 与上面的 recipeUses / craftUses **同源**（都由 parseRecipes 一次解析导出），
     *  不要单独再造一张表——那就是第二份真相了。
     *
     * v2.73.7：同样改成「原版底座 + Mod 料理」的计算属性。
     */
    val recipesByName: Map<String, RecipeInfo>
        get() {
            val base = baseRecipesByName
            val mods = modRecipeInfos
            return if (mods.isEmpty()) base else base + mods
        }

    var craftsByName: Map<String, RecipeInfo> = emptyMap()

    /** v2.73.7：原版菜谱的底座（assets 的 recipes.json 解析结果）。 */
    @Volatile var baseRecipesByName: Map<String, RecipeInfo> = emptyMap()

    /** v2.73.7：原版菜谱的材料反查底座。 */
    @Volatile var baseRecipeUses: List<Pair<String, String>> = emptyList()

    /**
     * v2.73.7：已启用包的料理 → 配方信息。
     *
     * 与 [baseRecipesByName] 同结构（[RecipeInfo]），这样「怎么制作」那张卡
     * 对原版与 SVE 料理是**同一段代码**——两套结构就意味着两处都要改。
     *
     * `source` 取包内 `unlock`（好感门槛）；包里没写门槛 = 空串，
     * UI 显示「自学 / 购买」（**不是**空白——空白会被当成"这条没数据"）。
     */
    private val modRecipeInfos: Map<String, RecipeInfo>
        get() {
            if (enabledModIds.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, RecipeInfo>()
            for (r in modRecipes) {
                out[r.name] = RecipeInfo(
                    kind = "菜谱",
                    category = "",
                    source = r.unlock.ifBlank { RECIPE_NO_UNLOCK },
                    ingredients = r.ingredients,
                )
            }
            return out
        }

    /** 包里没写解锁条件时的显示文案。**空值是「无门槛」，不是「缺数据」。 */
    const val RECIPE_NO_UNLOCK = "自学 / 购买"

    private fun Context.assetText(p: String): String =
        assets.open(p).bufferedReader().use { it.readText() }

    /**
     * 加载全部静态数据表。
     *
     * @param force c200 A2：**强制重载**。原来 `if (ready) return` 是彻底的——
     *   哪怕某张表加载失败（coreFailures 非空），也永远不会再试一次，
     *   用户只能杀进程重开。重载后必须让图鉴运行时视图的缓存失效
     *   （cachedCodexGroups 按 codexViewVersion 判版本，不失效就读到旧数据）。
     */
    fun load(ctx: Context, force: Boolean = false) {
        if (ready && !force) return
        // v2.17 C4：核心表逐份独立 runCatching，任一份损坏只降级对应页（见 loadFailures），
        // 进程不崩——修复「改 JSON 手滑一个键就启动砖机」的地雷。
        val coreFailed = mutableListOf<String>()

        runCatching {
            imageMap = JSONObject(ctx.assetText("image_map.json")).let { jo ->
                jo.keys().asSequence().associateWith { jo.getString(it) }
            }
        }.onFailure { coreFailed += "image_map.json" }

        fun arr(p: String) = JSONArray(ctx.assetText("data/$p"))
        fun obj(p: String) = JSONObject(ctx.assetText("data/$p"))

        // v2.81.0（P1）：存档世界状态的名称映射（只被「我的农场」用，缺了不影响别处）
        runCatching {
            worldItemMap = WorldItemMap.parse(ctx.assetText("data/worldItemMap.json"))
        }.onFailure { coreFailed += "worldItemMap.json" }

        runCatching {
            baseCrops = arr("crops.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Crop(
                        name = o.getString("name"),
                        seedPrice = o.intOr("seedPrice"),
                        sellPrice = o.intOr("sellPrice"),
                        growth = o.intOr("growth"),
                        regrow = o.optInt("regrow", -1).takeIf { it > 0 },
                        seasons = o.getJSONArray("seasons").strList(),
                        channels = o.optString("channels"),
                        note = o.optString("note"),
                        giant = o.optBoolean("giant"),
                        trellis = o.optBoolean("trellis"),
                    )
                }
            }
        }.onFailure { coreFailed += "crops.json" }

        runCatching {
            fishing = arr("fishing.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Fishing(
                        name = o.getString("name"),
                        seasons = o.getJSONArray("seasons").strList(),
                        weather = o.optString("weather"),
                        time = o.optString("time"),
                        location = o.optString("location"),
                        type = o.optString("type"),
                        price = o.intOr("price"),
                        difficulty = o.intOr("difficulty"),
                        note = o.optString("note"),
                    )
                }
            }
        }.onFailure { coreFailed += "fishing.json" }

        runCatching {
            baseGifts = arr("gifts.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    GiftVillager(
                        name = o.getString("name"),
                        birthdaySeason = o.optString("birthdaySeason").takeIf { it.isNotEmpty() && it != "null" },
                        birthdayDay = o.optInt("birthdayDay", -1).takeIf { it > 0 },
                        loves = o.optJSONArray("loves")?.strList() ?: emptyList(),
                        likes = o.optJSONArray("likes")?.strList() ?: emptyList(),
                        // v2.17：接上避雷数据（此前漏读这两个键 → 「送礼避雷」卡恒不渲染）
                        // v2.118：接上**中立**段。此前 gifts.json 从没写过这个键，
                        //   而 UI（VillagersPage）早就有「😐 中立」卡和 GiftVillager.neutrals 字段
                        //   —— 字段在、卡在、数据不在，所以那张卡从来没出现过东西。
                        neutrals = o.optJSONArray("neutral")?.strList() ?: emptyList(),
                        dislikes = o.optJSONArray("dislikes")?.strList() ?: emptyList(),
                        hates = o.optJSONArray("hates")?.strList() ?: emptyList(),
                        // c204：不接受友谊礼物的村民（吉尔/冈瑟/马龙）——不是缺数据，是官方设定。
                        //   五段礼物全空 + acceptsGifts=false ⇒ UI 写「不接受友谊礼物」，
                        //   而不是渲染一张空白的礼物卡。
                        acceptsGifts = o.optBoolean("acceptsGifts", true),
                        giftNote = o.optString("giftNote").takeIf { it.isNotEmpty() },
                    )
                }
            }
        }.onFailure { coreFailed += "gifts.json" }

        // v2.100.0 / c182：村民中文名全表（存档侧那份译名表）。
        //   与礼物表**并集**判「是不是村民」——见 [npcNames] 的注释。
        runCatching {
            npcNames = obj("reportNames.json").optJSONObject("npcs")?.let { jo ->
                jo.keys().asSequence().map { jo.optString(it) }.filter { it.isNotBlank() }.toSet()
            } ?: emptySet()
        }.onFailure { coreFailed += "reportNames.json" }

        runCatching {
            festivals = arr("festivals.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Festival(
                        name = o.getString("name"), date = o.getString("date"),
                        location = o.optString("location"), activity = o.optString("activity"),
                        rewards = o.optString("rewards"),
                        hours = o.optString("hours").takeIf { it.isNotBlank() },
                    )
                }
            }
        }.onFailure { coreFailed += "festivals.json" }

        runCatching {
            // c211：鱼塘扩容门「按材料反查」索引（fit_c211_pond_gates.py 由 fishPonds.json 生成）。
            //   byMaterial：材料名 → 需要它的鱼（含条数门槛与数量）。解析失败行不静默丢
            //   （fit 脚本里已显式记录 unparsed），这里只消费 byMaterial。
            val pg = obj("pondGates.json")
            val bm = pg.optJSONObject("byMaterial") ?: JSONObject()
            pondFishByMaterial = buildMap {
                bm.keys().asSequence().forEach { mat ->
                    val a = bm.optJSONArray(mat) ?: return@forEach
                    val fish = (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.optString("fish")?.takeIf { it.isNotBlank() }
                    }
                    if (fish.isNotEmpty()) put(mat, fish)
                }
            }
        }.onFailure { coreFailed += "pondGates.json" }

        runCatching {
            seedPrices = arr("seedPrices.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    SeedPrice(
                        name = o.getString("name"), year2 = o.optBoolean("year2"),
                        pierre = o.intOr("pierre"), pierreOff = o.intOr("pierreOff"),
                        joja = o.intOr("joja"), jojaMember = o.intOr("jojaMember"),
                        note = o.optString("note"),
                    )
                }
            }
        }.onFailure { coreFailed += "seedPrices.json" }

        runCatching {
            processing = arr("processing.json").rows().map {
                ProcessRow(
                    raw = it["原料"] ?: "", product = it["成品"] ?: "",
                    machine = it["机器"] ?: "", timeText = it["耗时"] ?: "",
                    // 「280（原 25）g」这类口径取第一个数字（旧实现把所有数字拼起来 → 28025）
                    price = it["基础售价"]?.let { p -> Regex("\\d[\\d,]*").find(p)?.value?.replace(",", "")?.toIntOrNull() },
                )
            }
        }.onFailure { coreFailed += "processing.json" }

        runCatching {
            bundleRooms = arr("bundles.json").let { a ->
                (0 until a.length()).map { idx ->
                    val room = a.getJSONObject(idx)
                    BundleRoom(
                        room = room.getString("room"),
                        // v2.58：解锁说明 + 「是否属于社区中心整修 26 包」标记，均为可选字段
                        note = room.optString("note"),
                        optional = room.optBoolean("optional", false),
                        bundles = room.getJSONArray("bundles").let { bs ->
                            (0 until bs.length()).map { bi ->
                                val b = bs.getJSONObject(bi)
                                NamedItems(
                                    name = b.getString("name"),
                                    // v2.27：选 N 规则（缺省 0 = 全部提交，与旧数据行为一致）
                                    choose = b.optInt("choose", 0),
                                    items = b.getJSONArray("items").let { is_ ->
                                        (0 until is_.length()).map { ii ->
                                            val x = is_.getJSONObject(ii)
                                            // v2.58：src = 游戏内获取途径（官方 wiki 逐项原文，缺省空）
                                            ItemCount(x.getString("name"), x.optInt("count", 1), x.optString("src"))
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }.onFailure { coreFailed += "bundles.json" }

        runCatching {
            heartEvents = arr("heartEvents.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    HeartEventVillager(
                        villager = o.getString("villager"),
                        events = o.getJSONArray("events").let { es ->
                            (0 until es.length()).map { ei ->
                                val e = es.getJSONObject(ei)
                                HeartEvent(
                                    hearts = e.optString("hearts"), place = e.optString("place"),
                                    event = e.optString("event"), options = e.optString("options"),
                                )
                            }
                        },
                    )
                }
            }
        }.onFailure { coreFailed += "heartEvents.json" }

        runCatching {
            marriageSpouses = obj("marriage.json").getJSONArray("spouses").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    o.getString("name") to Pair(o.optString("area"), o.optString("gifts"))
                }
            }
        }.onFailure { coreFailed += "marriage.json" }

        // ── v2.14 攻略数据：每份独立 try，失败只降级自己 ──
        runCatching { fishSpots = parseFishSpots(obj("fishSpots.json")) }
        runCatching { weaponCompendium = obj("weaponCompendium.json") }
        runCatching { gingerIslandGuide = obj("gingerIslandGuide.json") }
        runCatching { marriageGuide = obj("marriageGuide.json") }
        runCatching { processingProfit = obj("processingProfit.json") }

        // ── v2.15 攻略数据 ──
        runCatching { skillLeveling = obj("skillLeveling.json") }
        runCatching { foraging = obj("foraging.json") }
        runCatching { petGuide = obj("petGuide.json") }
        runCatching { weatherLuck = obj("weatherLuck.json") }
        runCatching { monsterFarming = obj("monsterFarming.json") }
            .onFailure { coreFailed += "monsterFarming.json" }
        runCatching { shopCatalog = obj("shopCatalog.json") }

        // ── 顺序关键①：monsterDrops 必须在 buildCodexGroups 之前准备好，否则图鉴「怪物」分类为空 ──
        runCatching {
            monsterDrops = arr("monsterDrops.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    o.getString("name") to "${o.optString("drops")}｜出没：${o.optString("where")}"
                }
            }
        }.onFailure { coreFailed += "monsterDrops.json" }

        // ── 顺序关键②：itemSources 必须在 buildCodexGroups 之前建好，否则博物馆矿物/古物详情的
        //    「来源」行恒为空（v2.17 修复：此前此处时序颠倒）──
        // ── v2.5 新资产 ──
        // v2.99.0 / c180：别名表**必须先于** itemSources 载入——itemSources 的别名回填要用它。
        runCatching {
            val raw = obj("itemAliases.json").let { jo ->
                jo.keys().asSequence().associateWith { jo.getString(it).trim() }
            }
            // 别名链归一：值若本身还是别名，递归指到最终规范名（防「别名指别名」绕圈）。
            itemAliases = raw.mapValues { (_, v0) ->
                var t = v0
                var guard = 0
                while (raw.containsKey(t) && guard < 8) { t = raw.getValue(t); guard++ }
                t
            }
        }.onFailure { coreFailed += "itemAliases.json" }

        runCatching {
            fun sources(p: String, hasSeason: Boolean): List<SourceRow> = arr(p).let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    SourceRow(
                        name = o.getString("name"),
                        season = if (hasSeason) o.optString("season") else o.optString("group"),
                        places = o.optString("places"),
                        price = o.intOr("price"),
                        group = o.optString("group"),
                    )
                }
            }
            itemSources = buildMap {
                sources("forage.json", true).forEach { put(it.name, it) }
                sources("mineralSources.json", true).forEach { if (it.name !in this) put(it.name, it) }
                sources("artifactSources.json", false).forEach { put(it.name, it.copy(season = "古物")) }
                // 别名 → 盘点名：**唯一映射源**是 assets/data/itemAliases.json
                // （v2.99.0 / c180：原先写死在这里的 7 条内联别名已全部搬进那个文件）。
                // 只接**已经有来源行**的别名，别的一律跳过 —— 不凭空造来源。
                itemAliases.forEach { (alias, canon) ->
                    val row = this[canon]
                    if (row != null) put(alias, row)
                }
            }
        }.onFailure { coreFailed += "itemSources" }

        runCatching { codexGroups = buildCodexGroups(::arr, ::obj) }
            .onSuccess {
                // v2.73.12：静态组建好 → 视图版本 +1（搜索索引据此重建）
                codexViewVersion++
            }
            .onFailure {
                coreFailed += "codexGroups"
                // v2.18 排障：图鉴页会在 groups 为空时崩，这里把真实异常打出来而不是静默吞掉
                android.util.Log.e("StardewApp", "buildCodexGroups 失败", it)
            }
        runCatching {
            // v2.42：一次解析，正反两个索引都从这里出（原先只有反查）。
            // ⚠ recipeUses / craftUses 仍是两份**独立**列表，不合并——
            //   usagesOf 靠它们区分「🍳 烹饪用料」与「🔨 制作材料」两个不同标签。
            // v2.73.7：菜谱侧改成「底座 + Mod 料理」的计算属性，这里只存**底座**。
            baseRecipesByName = parseRecipes(arr("recipes.json"), "菜谱")
            craftsByName = parseRecipes(arr("crafting.json"), "制作")
            baseRecipeUses = usesOf(baseRecipesByName)
            craftUses = usesOf(craftsByName)
        }.onFailure { coreFailed += "recipes.json|crafting.json" }

        runCatching {
            shopHours = arr("shopHours.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    ShopHours(
                        name = o.getString("name"), hours = o.optString("hours"),
                        keyHours = o.optString("keyHours"), closed = o.optString("closed"),
                        note = o.optString("note"),
                        // v2.17：结构化闭店星期（tools/fix_v217.py 从 closed 文本提取），
                        // 今日页/小组件据此精确判定，替代旧的 when(dow) 硬编码错位映射
                        closedDays = o.optJSONArray("closedDays")?.strList() ?: emptyList(),
                    )
                }
            }
        }.onFailure { coreFailed += "shopHours.json" }
        runCatching {
            weekLoop = arr("weekLoop.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    WeekDay(o.getString("day"), o.getJSONArray("items").strList())
                }
            }
        }.onFailure { coreFailed += "weekLoop.json" }
        runCatching {
            unlockCards = arr("unlocks.json").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    UnlockCard(o.getString("id"), o.getString("name"), o.optString("req"), o.optString("cost"), o.optString("after"), o.optString("checkKey"))
                }
            }
        }.onFailure { coreFailed += "unlocks.json" }
        // monsterDrops 已在 buildCodexGroups 之前加载（见函数顶部），此处不再重复
        // v2.99.0 / c180：「去哪拿」线索索引（规范名 → 线索）。
        // ⚠ 必须在 codexGroups / shopCatalog / processingProfit / fishSpots / monsterDrops /
        //   bundleRooms 之后建 —— 它们全都是线索的来源，少一个就少一类线索。
        runCatching { sourceIndex = buildSourceIndex() }
            .onFailure { coreFailed += "sourceIndex" }
        // v2.94.0：原版 NPC 行程表。独立对象（结构与其它扁平表不同），
        //   自带 try：坏掉只让行程页显示「数据加载失败」，不影响别的页。
        NpcSchedules.load(ctx)
        // v2.17 C4：回填核心表失败清单，供 loadFailures 汇总展示
        coreFailures = coreFailed.toList()
        ready = true
        if (force) {
            // c200 A2：强制重载后图鉴运行时视图的缓存必须失效——
            // cachedCodexGroups 按 codexViewVersion 判版本，版本没变就会继续发旧数据
            // （用户点了「重新加载」却看到一模一样的旧图鉴，比不点还气人）。
            cachedCodexGroups = null
            codexViewVersion++
        }
    }

    /**
     * v2.42：解析一张配方表，返回 **成品名 → 配方**（正向，图鉴详情「怎么制作」用）。
     * 菜谱与制作走同一个函数，只靠 [kind] 区分——两表各自内部零重名、零空名，
     * 且两表之间也零重名（verify 钉死），所以一个名字只会命中其中一张表。
     */
    private fun parseRecipes(a: JSONArray, kind: String): Map<String, RecipeInfo> {
        val out = LinkedHashMap<String, RecipeInfo>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            val ings = mutableListOf<Pair<String, Int>>()
            o.optJSONArray("ingredients")?.let { ing ->
                for (j in 0 until ing.length()) {
                    val e = ing.optJSONObject(j) ?: continue
                    val nm = e.optString("name")
                    if (nm.isNotEmpty()) ings += nm to e.optInt("count", 1)
                }
            }
            out[name] = RecipeInfo(kind, o.optString("category"), o.optString("source"), ings)
        }
        return out
    }

    /** 反向导出：配料名 → 成品名（用途速查「🍳 烹饪用料」「🔨 制作材料」用）。
     *  ⚠ 这份列表的**内容与顺序必须与改版前逐条一致**：LinkedHashMap 保持插入序
     *  （= 数组顺序），每个 RecipeInfo.ingredients 保持材料原序。verify 有等价断言。 */
    private fun usesOf(idx: Map<String, RecipeInfo>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((product, r) in idx) for ((mat, _) in r.ingredients) out += mat to product
        return out
    }

    /**
     * v2.42：查某个成品**怎么做的**。图鉴详情现算，查不到返回 null（那就不显示那张卡）。
     * 菜谱优先于制作——两表零重名，实际不会同时命中。
     */
    fun recipeOf(item: String): RecipeInfo? =
        if (item.isBlank()) null else recipesByName[item] ?: craftsByName[item]

    /**
     * 用途速查：收集包 / 村民好感 / 加工原料与成品 / 烹饪 / 制作 / **去哪拿**。
     * 类别（矿物→宝石与矿物、鱼→鱼与海鲜类）推断口径同村民页。
     *
     * v2.99.0 / c180：**名字先过 [canonicalName] 再查**（别名归一）。
     * 各表对同一件东西用了不同名字（礼物表写「葡萄酒」、图鉴写「酒」；收集包写「动物毛」、
     * 出货表写「羊毛」），不归一就会出现「点图鉴落空」「查不到来源」两种病。
     * ⚠ 归一只影响**查询**，不改名：勾选键仍用完整名（老用户已勾的键不能失效）。
     */
    fun usagesOf(item: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (item.isBlank()) return out
        val canon = canonicalName(item)
        // 「📍 获取途径」：v2.99.0 / c180 起由既有数据源派生（商店/加工/钓点/采集/怪物/养殖/
        // 作物/菜谱·制作·晶洞的「获取」行），不再只是 forage·mineral·artifact 三表 join。
        val clues = sourceCluesOf(item).filter { it.summary }
        if (clues.isNotEmpty()) {
            val head = clues.take(4).joinToString("｜") { "${it.kind} · ${it.text}" }
            out += "📍 获取途径" to (head + if (clues.size > 4) "…等 ${clues.size} 项" else "")
        }
        val bundles = bundleRooms.flatMap { room ->
            room.bundles.filter { b -> b.items.any { canonicalName(it.name) == canon } }
                .map { b -> "${room.room}·${b.name}" }
        }
        if (bundles.isNotEmpty()) out += "📦 收集包" to bundles.joinToString("、")
        val inMineral = codexGroups.firstOrNull { it.id == "museumMinerals" }?.items?.any { it.name == item } == true
        val inFish = !inMineral && fishing.any { it.name == item }
        val cat = if (inMineral) "宝石与矿物" else if (inFish) "鱼与海鲜类" else null
        val loves = mutableListOf<String>()
        val likes = mutableListOf<String>()
        gifts.forEach { g ->
            val lovesHit = g.loves.any { canonicalName(it) == canon }
            val likesHit = g.likes.any { canonicalName(it) == canon }
            when {
                lovesHit -> loves += g.name
                likesHit -> likes += g.name
                cat != null && cat in g.loves -> loves += g.name
                cat != null && cat in g.likes -> likes += g.name
            }
        }
        if (loves.isNotEmpty()) out += "❤ 村民最爱" to loves.joinToString("、")
        if (likes.isNotEmpty())
            out += "👍 村民喜欢" to (likes.take(14).joinToString("、") + if (likes.size > 14) "…等 ${likes.size} 人" else "")
        val asRaw = processing.filter { canonicalName(it.raw) == canon }.map { "${it.machine}→${it.product}" }
        if (asRaw.isNotEmpty()) out += "🍶 可加工" to asRaw.joinToString("、")
        val asProduct = processing.filter { canonicalName(it.product) == canon }.map { it.raw }
        if (asProduct.isNotEmpty()) out += "⬅ 由其制成" to asProduct.joinToString("、")
        recipeUses.filter { canonicalName(it.first) == canon }.map { it.second }.let { r ->
            if (r.isNotEmpty()) out += "🍳 烹饪用料" to (r.take(10).joinToString("、") + if (r.size > 10) "…等 ${r.size} 道" else "")
        }
        craftUses.filter { canonicalName(it.first) == canon }.map { it.second }.let { r ->
            if (r.isNotEmpty()) out += "🔨 制作材料" to (r.take(10).joinToString("、") + if (r.size > 10) "…等 ${r.size} 项" else "")
        }
        return out
    }

    /**
     * v2.99.0 / c180：某个东西的「去哪拿」线索（全部由既有数据源派生）。
     *
     * 名字先过 [canonicalName]（别名归一），所以「果酒」「葡萄酒」「酒」查到同一份。
     * Mod 线索是**运行时**才有的（装包后才有），所以单独在这里补，不进启动时的索引。
     */
    fun sourceCluesOf(item: String): List<SourceClue> {
        if (item.isBlank()) return emptyList()
        val out = sourceIndex[canonicalName(item)].orEmpty().toMutableList()
        // Mod 料理：包里的配方带「在哪解锁」，原版表里没有这条。
        // ⚠ unlock 空 = 没有好感门槛（自学 / 购买），**空值不是缺数据**，所以不显示。
        modRecipeOf(canonicalName(item))?.unlock?.takeIf { it.isNotBlank() }?.let {
            out += SourceClue("🧪 Mod 料理", "配方解锁：$it")
        }
        return out.sortedBy { CLUE_ORDER.indexOf(it.kind).let { i -> if (i < 0) CLUE_ORDER.size else i } }
    }

    /**
     * v2.99.0 / c180：图鉴里**同名条目**的所在分组（一条一个）。
     *
     * 重名不是小事：一个来源指向多个同名条目时，**不许随便挑一个**——
     * 必须明说「有 N 个同名条目」并给出去重名的入口（搜索页）。
     * 走的是同一份 [codexGroupsWithMods]（装包后的运行时视图），与用户实际看到的一致。
     *
     * @return (分组 id, 分组标题) 列表；空 = 图鉴里没有这一条
     */
    fun codexDupGroups(name: String): List<Pair<String, String>> {
        if (name.isBlank()) return emptyList()
        val n = canonicalName(name)
        return codexGroupsWithMods.flatMap { g ->
            g.items.filter { it.name == n }.map { g.id to g.title }
        }
    }

    /**
     * v2.99.0 / c180：算一遍「去哪拿」线索索引（规范名 → 线索列表）。
     *
     * ## 铁律：不新建第二份来源表
     * 每一条线索都从**既有数据源**抽出来：
     * · shopCatalog（商店目录）· processingProfit（加工利润表）· fishSpots（钓点）
     * · monsterDrops（怪物掉落）· itemSources（采集·矿物·古物三表 join）
     * · animals 组（养殖产出）· crops（作物种子）· 菜谱/制作/晶洞组的「获取」行
     * · bundles（收集包原文，只进详情卡、不进汇总行）
     *
     * ## 名字一律先过 [canonicalName]
     * 所以「果酒」与「酒」落在同一格；别名侧查得到来源，图鉴侧也跳得过去。
     */
    private fun buildSourceIndex(): Map<String, List<SourceClue>> {
        val out = LinkedHashMap<String, MutableList<SourceClue>>()
        val seen = HashSet<String>()

        fun add(canon: String, kind: String, text: String, summary: Boolean = true, dedupe: String = "") {
            if (canon.isBlank() || text.isBlank()) return
            val key = canon + "\u0001" + kind + "\u0001" + (if (dedupe.isBlank()) text else dedupe)
            if (!seen.add(key)) return
            out.getOrPut(canon) { mutableListOf() } += SourceClue(kind, text, summary)
        }

        // ① 采集 / 矿物 / 古物（forage · mineralSources · artifactSources 三表 join）
        itemSources.forEach { (nm, s) ->
            val parts = buildList {
                if (s.season.isNotBlank()) add(s.season)
                if (s.places.isNotBlank()) add(s.places)
            }
            if (parts.isEmpty()) return@forEach
            val kind = when {
                s.season == "古物" -> "🏺 古物"
                s.group.isNotBlank() -> "⛏ 矿物"
                else -> "🌿 采集"
            }
            add(canonicalName(nm), kind, parts.joinToString("｜"))
        }

        // ② 商店（shopCatalog.json：店名 → 商品行）
        shopCatalog?.let { sc ->
            sc.keys().asSequence().forEach { shop ->
                val rows = sc.optJSONArray(shop) ?: return@forEach
                for (i in 0 until rows.length()) {
                    val o = rows.optJSONObject(i) ?: continue
                    val nm = o.optString("name")
                    if (nm.isBlank()) continue
                    val price = o.optInt("price", 0)
                    val avail = o.optString("avail")
                    val note = o.optString("note")
                    val txt = buildString {
                        append(shop)
                        if (price > 0) append(" · ${price}g")
                        if (avail.isNotBlank()) append(" · $avail")
                        if (note.isNotBlank()) append(" · $note")
                    }
                    add(canonicalName(nm), "🛒 商店", txt)
                }
            }
        }

        // ③ 加工（processingProfit.json：机器 → 配方 input→output）
        processingProfit?.optJSONArray("machines")?.let { ms ->
            for (i in 0 until ms.length()) {
                val m = ms.optJSONObject(i) ?: continue
                val machine = m.optString("name")
                val unlock = m.optString("recipe_source")
                val rs = m.optJSONArray("recipes") ?: continue
                for (j in 0 until rs.length()) {
                    val r = rs.optJSONObject(j) ?: continue
                    val outp = r.optString("output")
                    if (outp.isBlank()) continue
                    val inp = r.optString("input")
                    val txt = buildString {
                        append(machine).append(" · ").append(inp).append("→").append(outp)
                        if (unlock.isNotBlank()) append("（").append(unlock).append("）")
                    }
                    add(canonicalName(outp), "🍶 加工", txt, dedupe = "$machine|$inp|$outp")
                }
            }
        }
        // ③' 加工（processing.json：同一批机器的精简版，去重后基本被上一段覆盖）
        processing.forEach { p ->
            if (p.product.isBlank()) return@forEach
            add(canonicalName(p.product), "🍶 加工", "${p.machine} · ${p.raw}→${p.product}",
                dedupe = "${p.machine}|${p.raw}|${p.product}")
        }

        // ④ 钓点（fishSpots.json 的 byFish）
        fishSpots?.byFish?.forEach { (fish, info) ->
            val spots = info.spots
            if (spots.isEmpty()) return@forEach
            val txt = if (spots.size <= 3) spots.joinToString("、")
            else spots.take(3).joinToString("、") + " 等 ${spots.size} 处"
            add(canonicalName(fish), "🎣 钓点", txt)
        }

        // ⑤ 怪物掉落（monsterDrops.json：怪物 → 掉落｜出没）
        monsterDrops.forEach { (monster, blob) ->
            val parts = blob.split("｜")
            val where = parts.lastOrNull()?.takeIf { it.startsWith("出没：") }?.removePrefix("出没：").orEmpty()
            val drops = if (where.isNotBlank()) parts.dropLast(1) else parts
            drops.forEach { d ->
                // 掉落串带概率后缀（「稀有圆盘0.1%」）和括号备注（「紫水晶（极低）」）
                val nm = d.substringBefore("（").trim().replace(Regex("[0-9%.]+$"), "").trim()
                if (nm.isBlank()) return@forEach
                val txt = if (where.isNotBlank()) "$monster · $where" else monster
                add(canonicalName(nm), "👾 怪物掉落", txt)
            }
        }

        // ⑥ 养殖产出（animals 组的「产出（基础售价）」行）
        codexGroups.firstOrNull { it.id == "animals" }?.items?.forEach { ci ->
            val outText = ci.rows.firstOrNull { it.first == "产出（基础售价）" }?.second.orEmpty()
            if (outText.isBlank()) return@forEach
            val building = ci.rows.firstOrNull { it.first == "建筑要求" }?.second.orEmpty()
            val period = ci.rows.firstOrNull { it.first == "产出周期" }?.second.orEmpty()
            outText.split("/").map { it.trim() }.filter { it.isNotBlank() }.forEach { token ->
                val nm = token.substringBefore(" ").trim()
                if (nm.isBlank()) return@forEach
                val txt = listOf(ci.name, building, period)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ") + " · 出产 $token"
                add(canonicalName(nm), "🐄 养殖", txt)
            }
        }

        // ⑦ 作物（crops.json：买种子种出来）
        crops.forEach { c ->
            val price = c.seedPrice ?: return@forEach
            if (price <= 0) return@forEach
            val seasons = c.seasons.joinToString("")
            val txt = buildString {
                append("买种子 ").append(price).append("g")
                if (seasons.isNotBlank()) append(" · ").append(seasons).append("季")
            }
            add(canonicalName(c.name), "🌱 作物", txt)
        }

        // ⑧ 菜谱 / 制作 / 晶洞的「获取」行（这三张表自带「哪来的」原文）
        listOf("recipes" to "🧪 菜谱", "crafting" to "🔨 制作", "geodes" to "🔮 晶洞").forEach { (gid, kind) ->
            val g = codexGroups.firstOrNull { it.id == gid } ?: return@forEach
            g.items.forEach { ci ->
                val src = ci.rows.firstOrNull { it.first == "获取" }?.second.orEmpty()
                if (src.isBlank()) return@forEach
                add(canonicalName(ci.name), kind, src)
            }
        }

        // ⑨ 收集包原文（bundles.json 的 src）——**只进详情卡，不进汇总行**
        //   （进度页已经单独显示过这段原文，进汇总就是同一句话显示两遍）。
        bundleRooms.forEach { room ->
            room.bundles.forEach { b ->
                b.items.forEach { it ->
                    if (it.src.isBlank()) return@forEach
                    add(canonicalName(it.name), "📦 收集包", "${b.name} · ${it.src}", summary = false)
                }
            }
        }

        return out.mapValues { (_, v) -> v.toList() }
    }

    /**
     * v2.99.0 / c180：线索大类的展示顺序（越靠前越「能立刻动手」）。
     * 菜谱/制作/晶洞的「获取」行排最前——那是官方原文，最直接。
     */
    private val CLUE_ORDER = listOf(
        "🧪 菜谱", "🔨 制作", "🔮 晶洞", "🛒 商店", "🍶 加工",
        "🌿 采集", "⛏ 矿物", "🏺 古物", "🎣 钓点", "👾 怪物掉落", "🐄 养殖", "🌱 作物",
        "📦 收集包", "🧪 Mod 料理",
    )

    /** 名字 → assets 图片路径（无则 null，用占位） */
    fun icon(name: String): String? = imageMap[name]?.let { "img/$it" }

    /**
     * 品质前缀剥离：金星防风草 → 防风草、银星果酒 → 果酒。
     *
     * v2.58 从只认「金星」扩到三种品质前缀。原实现只 `removePrefix("金星")`，
     * 因为那时只有储物间「高品质作物」包用到金星；电影院的失踪的收集包里有
     * 「银星果酒」和「金星虚空鲑鱼」，银星那项不剥就查不到任何来源。
     * ⚠ 只剥前缀不改名——勾选键用的是完整名，剥了会让老用户已勾的键失效。
     */
    private val QUALITY_PREFIXES = listOf("金星", "银星", "铱星")

    fun stripQuality(name: String): String =
        QUALITY_PREFIXES.firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) } ?: name

    /**
     * v2.99.0 / c180：名字归一 —— **全 App 唯一实现点**。
     *
     * 顺序固定：先剥品质前缀（银星果酒 → 果酒），再查别名表（果酒 → 酒）。
     * 别名表 = `assets/data/itemAliases.json`，只存「别名 → 规范名」。
     *
     * ## 为什么要有这一步
     * 全 App 各表对同一件东西用了不同的名字：礼物表写「葡萄酒」、商店表写「果酒」、
     * 图鉴写「酒」；收集包写「动物毛」、出货表写「羊毛」。不归一就有两种病：
     * ① 点图鉴零命中落空搜索页；② 按别名查来源，三表全对不上。
     *
     * ⚠ 归一只影响**查询**，不改名：勾选键仍然用完整名
     * （剥了会让老用户已勾的条目失效，见 stripQuality 的注释）。
     */
    fun canonicalName(raw: String): String {
        val s = stripQuality(raw)
        return itemAliases[s] ?: s
    }

    /** 节日当天判定：返回今日节日名（春/夏/秋/冬 0-3 → "春"/"夏"/"秋"/"冬"），非节日返回 null。
     *  复用 Festival.startDay/endDay 字段（GameData 加载时已解析好），不重新解析中文日期文本。 */
    fun festivalToday(season: Int, day: Int): String? {
        if (season !in 0..3) return null
        val seasonName = listOf("春", "夏", "秋", "冬")[season]
        return festivals.firstOrNull { f ->
            f.season == seasonName && f.startDay != null && day in f.startDay!!..(f.endDay ?: f.startDay!!)
        }?.name
    }

    /** v2.14：解析 fishSpots.json（钓鱼地图钓点）。
     *  结构：{spots:[{name,area,region}], byFish:{鱼名:{spots,xp,prices,timeRanges,legendary}},
     *          crabPot:{鱼名:{price,water,note,chance}}}
     *  byFish 的鱼名在构建期已保证是 App 现有官方鱼名（tools/build_fishspots_v214.py 做过映射）。 */
    private fun parseFishSpots(root: JSONObject): FishSpotTable {
        val spots = root.getJSONArray("spots").let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                FishSpot(o.getString("name"), o.optString("area"), o.optString("region"))
            }
        }
        val byFishJson = root.optJSONObject("byFish") ?: JSONObject()
        val byFish = mutableMapOf<String, FishSpotInfo>()
        byFishJson.keys().forEach { fish ->
            val o = byFishJson.getJSONObject(fish)
            val ranges = o.optJSONArray("timeRanges")?.let { a ->
                (0 until a.length()).map {
                    val r = a.getJSONObject(it)
                    r.getInt("start") to r.getInt("end")
                }
            } ?: emptyList()
            val spotArr = o.optJSONArray("spots")
            val fishSpots = if (spotArr == null) emptyList()
                             else (0 until spotArr.length()).map { spotArr.getString(it) }
            val prices = mutableMapOf<String, Int>()
            o.optJSONObject("prices")?.let { p ->
                p.keys().forEach { k -> prices[k] = p.getInt(k) }
            }
            byFish[fish] = FishSpotInfo(
                spots = fishSpots,
                xp = if (o.has("xp") && !o.isNull("xp")) o.getInt("xp") else null,
                prices = prices,
                timeRanges = ranges,
                legendary = o.optString("legendary").ifBlank { null },
            )
        }
        // 蟹笼鱼没有「钓点」语义，合进同一张表并打标记，UI 才知道该显示什么
        root.optJSONObject("crabPot")?.let { cp ->
            cp.keys().forEach { fish ->
                if (byFish.containsKey(fish)) return@forEach
                val o = cp.getJSONObject(fish)
                byFish[fish] = FishSpotInfo(
                    spots = emptyList(),
                    xp = null,
                    prices = if (o.has("price") && !o.isNull("price"))
                        mapOf("normal" to o.getInt("price")) else emptyMap(),
                    timeRanges = emptyList(),
                    crabPot = true,
                    crabNote = o.optString("note"),
                )
            }
        }
        return FishSpotTable(spots, byFish)
    }

    /**
     * 一个 [Crop] → 图鉴条目。**图鉴作物区的行构造单源**。
     *
     * 抽出来是因为 crops 组现在有两个消费点：加载期构造静态 [codexGroups]，
     * 运行时还要按启用包重建一次（[codexGroupsWithMods]）。写两份行构造的话，
     * SVE 专属行（种子/收获/生长激素）会只在其中一边出现——
     * 表现是「装没装包看到的行不一样」，而这恰恰是本轮要修的事。
     */
    private fun cropCodexItems(list: List<Crop>): List<CodexItem> = list.map { it ->
        CodexItem(it.name, it.name, rows = buildList {
            it.seedPrice?.let { p -> add("种子价" to "${p}g") }
            it.sellPrice?.let { p -> add("售价" to "${p}g") }
            // v2.73.4：SVE 作物没有价格，别显示「售价 0g」——包里**没有**这个数据，
            //   不是价格为 0。缺失即不渲染这一行（与原版 sellPrice 为 null 同款处理）。
            it.growth?.let { g -> add("生长" to "$g 天") }
            it.regrow?.let { g -> add("再生" to "$g 天/次") }
            add("季节" to it.seasons.joinToString("+"))
            if (it.channels.isNotBlank()) add("购买渠道" to it.channels)
            // v2.73.4：SVE 作物三行专属（种子/收获物/生长激素）。
            it.seedName?.let { s -> if (it.fromMod) add("种子" to s) }
            it.harvestName?.let { h -> add("收获物" to h) }
            if (it.raisedBed == true) add("种植要求" to "需高级生长激素")
            if (it.note.isNotBlank()) add("备注" to it.note)
        })
    }

    /**
     * v2.73.7：图鉴菜谱条目构造单源（原版 + Mod 料理同一套行）。
     *
     * ⚠ 行名与原版**逐字一致**（材料 / 获取 / 效果），这样图鉴里 81 条原版菜谱
     *   与 20 条 SVE 料理是同一种卡片，不会有两套版式。
     * ⚠ Mod 料理**没有效果行**（包里没有 effect）——不编造，缺就是缺。
     *
     * @param base 原版菜谱条目（assets recipes.json 解析出来的）
     * @param mods 已启用包的料理条目
     */
    private fun recipeCodexItems(base: List<CodexItem>, mods: List<CodexItem>): List<CodexItem> =
        base + mods

    /** v2.73.7：一条 Mod 料理 → 图鉴条目。行 = 材料 / 获取。 */
    private fun modRecipeCodexItem(r: ModRepo.ModRecipe): CodexItem = CodexItem(
        name = r.name,
        iconKey = r.name,
        rows = buildList {
            if (r.ingredients.isNotEmpty()) {
                add("材料" to r.ingredients.joinToString("、") { (n, c) -> "$n ×$c" })
            }
            // 「获取」= 好感门槛。包里没写 = 无门槛（自学 / 购买），不是缺数据。
            add("获取" to r.unlock.ifBlank { RECIPE_NO_UNLOCK })
        },
    )

    /**
     * v2.73.7：已启用包的**原始**料理条目（带 modId，图鉴角标要靠它取来源标签）。
     *
     * 顺序 = 按 modId 排序、包内原序——与图鉴格子下标契约一致（顺序即契约）。
     */
    val modRecipes: List<ModRepo.ModRecipe>
        get() {
            if (enabledModIds.isEmpty()) return emptyList()
            // v2.73.13：recipes 模块只由一个包提供（整包 + 分片并存时不重复）
            val pack = ownerPack("recipes") ?: return emptyList()
            return pack.recipes
        }

    /** 按菜名找它来自哪个包（null = 原版菜谱）。图鉴角标用它取来源标签。 */
    fun modRecipeOf(name: String): ModRepo.ModRecipe? =
        modRecipeByName[name]

    /**
     * v2.73.7：已启用包的料理 → 图鉴条目。
     */
    val modRecipeItems: List<CodexItem>
        get() = modRecipes.map { modRecipeCodexItem(it) }

    /** 来自 Mod 包的料理数（总开关全关时为 0）。 */
    val modRecipeCount: Int get() = modRecipes.size

    /**
     * v2.73.7：菜谱相关的 UI `remember` 缓存键。与 [modCropsKey] 同款两维度
     * （包数 + Mod 条数）——改一条料理的解锁条件也要重算。
     */
    val modRecipesKey: Pair<Int, Int> get() = modPacks.size to modRecipeCount

    /**
     * v2.73.9：已启用包的**鱼**（原始条目，带 modId 与包内图路径）。
     *
     * ⚠ **与原版重名的跳过**：SVE 的「虚空鲑鱼」在原版 1.6 钓鱼图鉴里已经有了，
     *   而且原版那条信息**更全**（全年 / 女巫沼泽 / 150g / 难度 80），
     *   SVE 那条只有 `where=沼泽`。两条都显示会让用户看到重复条目，
     *   而保留信息更少的那条纯属倒退 —— 故按「原版优先」跳过。
     *   （与作物那套「撞名加后缀、两条都在」不同：那里是**不同的东西**撞名，
     *   这里就是**同一条鱼**。）
     */
    val modFishes: List<ModRepo.ModFish>
        get() {
            if (enabledModIds.isEmpty()) return emptyList()
            val vanilla = fishing.mapTo(mutableSetOf()) { it.name }
            // v2.73.13：fish 模块只由一个包提供（整包 + 分片并存时不重复）
            val pack = ownerPack("fish") ?: return emptyList()
            return pack.fish.filter { it.name !in vanilla }
        }

    /**
     * SVE 鱼 → 图鉴条目（v2.75.1 按新 fish.json 重做：字段齐了就能显示齐）。
     *
     * ⚠ 字段缺失就**不显示那一行** —— 老包只有 name/id/where，就只显示「地点」。
     *   编一个包里没有的季节 = 把"不知道"说成"春/夏"，会把人坑到白跑。
     * ⚠ 「难度」这个键名和原版鱼**一致** —— 图鉴列表格子的行尾显示的就是它
     *   （`rows.firstOrNull { it.first == "难度" }`），同名才能自动显示难度。
     */
    private fun modFishCodexItem(f: ModRepo.ModFish): CodexItem = CodexItem(
        name = f.name,
        iconKey = f.name,
        rows = buildList {
            if (f.aliases.isNotEmpty()) add("又名" to f.aliases.joinToString("、"))
            f.difficulty?.let { add("难度" to "$it") }
            if (f.time.isNotBlank()) add("可钓时段" to f.time)
            if (f.seasons.isNotEmpty()) add("季节" to f.seasons.joinToString("、"))
            if (f.weather.isNotBlank()) add("天气" to f.weather)
            val spot = f.spotText
            if (spot.isNotBlank()) add("地点" to f.spotText)
            if (f.isForage) {
                add("说明" to "采集物，不是钓的")
            } else if (f.seasons.isEmpty() && f.weather.isBlank() &&
                f.time.isBlank() && f.difficulty == null
            ) {
                // ⚠ 老包只有 name/id/where → 季节/天气/时段/难度**它没有**。
                //   不许编（编了就等于把"包里没有"说成"春。夏。"，会把人坑到白跑），
                //   明说以游戏内为准。新包字段齐了就不加这句。
                add("说明" to "Mod 扩展鱼，季节与天气以游戏内为准")
            }
        },
    )

    /** v2.73.9：SVE 鱼 → 图鉴条目列表。 */
    val modFishItems: List<CodexItem> get() = modFishes.map { modFishCodexItem(it) }

    /** 按鱼名找它来自哪个包（null = 原版鱼或没这条）。图鉴角标用它取来源标签。 */
    fun modFishOf(name: String): ModRepo.ModFish? =
        modFishByName[name]

    /** 来自 Mod 包的鱼数。 */
    val modFishCount: Int get() = modFishes.size

    /**
     * v2.75.1：包里自述的「这个包没有传奇鱼王」说明（fish.json 顶层 `noLegendary`）。
     *
     * 图鉴顶部要**照着它说**，**没有就不说** —— 自己断言「确实没有传奇鱼」是编数据。
     */
    val modFishNoLegendary: String
        get() = ownerPack("fish")?.fishNoLegendary.orEmpty()

    /**
     * v2.73.10：已启用包的红心事件链概要。
     *
     * 顺序 = 按 modId 排序、包内原序。
     */
    val modHeartEvents: List<ModRepo.ModHeartEvent>
        get() {
            if (enabledModIds.isEmpty()) return emptyList()
            // v2.73.13：heartEvents 模块只由一个包提供（整包 + 分片并存时不重复）
            val pack = ownerPack("heartEvents") ?: return emptyList()
            return pack.heartEvents
        }

    /**
     * v2.73.10：按**包内主键**（srcId）找某位村民的红心事件链。
     *
     * ⚠ 关联用主键不用中文名：包作者改显示名不该静默丢掉整条红心事件链
     *   （与行程 [ModRepo] 的 srcId 关联同款纪律）。
     *   中文名对不上时回退按名字找一次——包里同时给了 id 与 name，
     *   老包只给名字时仍能对上。
     */
    fun modHeartEventOf(srcId: String?, name: String): ModRepo.ModHeartEvent? {
        val all = modHeartEvents
        if (all.isEmpty()) return null
        if (!srcId.isNullOrEmpty()) all.firstOrNull { it.id == srcId }?.let { return it }
        return all.firstOrNull { it.name == name }
    }

    /**
     * v2.73.10：包级「婚后」说明（整包一份）。
     */
    val modPostMarriage: String
        get() {
            if (enabledModIds.isEmpty()) return ""
            // v2.73.13：包级字段也只认**一个**包（整包优先），避免两个包各说一套。
            for (id in enabledModIds.sorted()) {
                val pack = modPacks.firstOrNull { it.modId == id } ?: continue
                if (pack.postMarriage.isNotBlank()) return pack.postMarriage
            }
            return ""
        }

    /**
     * v2.73.11：已启用包的主要地点。
     *
     * 顺序 = 按 modId 排序、包内原序。
     */
    val modLocations: List<ModRepo.ModLocation>
        get() {
            if (enabledModIds.isEmpty()) return emptyList()
            // v2.73.13：locations 模块只由一个包提供（整包 + 分片并存时不重复）
            val pack = ownerPack("locations") ?: return emptyList()
            return pack.locations
        }

    /**
     * v2.73.11：地点 → 关联的 NPC 名单。
     *
     * ⚠ 包内的 `who` 是**自由描述文字**（如「索菲娅家，酿酒/售酒」），不是结构化 NPC 列表。
     *   所以关联只能靠**子串精确匹配包内 NPC 名单**（`gifts` 里来自 Mod 的那些）：
     *   命中就是关联，**命中不了就不关联** —— 从文字里**猜**谁相关是编造。
     *   （实测 20 条里只有「蓝月亮酒庄」「城堡村前哨」「利刃公会」能对上，
     *   其余是区域描述如「新城镇，商店/住宅」「安德鲁/苏珊农场方向」。）
     */
    fun locationsOf(loc: ModRepo.ModLocation): List<String> {
        val names = gifts.filter { it.fromMod }.map { it.name }
        if (names.isEmpty()) return emptyList()
        return names.filter { n -> loc.who.contains(n) }
    }

    /**
     * v2.73.11：包级「SVE 给原版节日加的新剧情」说明（整包一份）。
     */
    val modFestivals: String
        get() {
            if (enabledModIds.isEmpty()) return ""
            for (id in enabledModIds.sorted()) {
                val pack = modPacks.firstOrNull { it.modId == id } ?: continue
                if (pack.festivals.isNotBlank()) return pack.festivals
            }
            return ""
        }

    /**
     * v2.73.11：地点 → 图鉴条目。
     *
     * 行 = 说明（包内原文）/ 提示。
     * ⚠ 地点**没有图**（包里没给）→ 图鉴格子是首字占位块，不是缺数据。
     * ⚠ 「相关 NPC」**不放这里**：它要能点（跳村民页），只有详情页能做，
     *   写成 rows 里的纯文字就点不动了 —— 详见 [modLocationOf] 与详情页。
     */
    private fun modLocationCodexItem(loc: ModRepo.ModLocation): CodexItem = CodexItem(
        name = loc.name,
        iconKey = loc.name,
        rows = buildList {
            if (loc.who.isNotBlank()) add("说明" to loc.who)
            add("提示" to "Mod 扩展地点，触发与解锁条件以游戏内剧情推进为准")
        },
    )

    /** v2.73.11：按地点名找它（详情页要用它算「相关 NPC」）。 */
    fun modLocationOf(name: String): ModRepo.ModLocation? =
        modLocationByName[name]

    /** v2.73.11：地点 → 图鉴条目列表。 */
    val modLocationItems: List<CodexItem> get() = modLocations.map { modLocationCodexItem(it) }

    /** 来自 Mod 包的地点数。 */
    val modLocationCount: Int get() = modLocations.size

    /**
     * v2.73.11：地点相关的 UI `remember` 缓存键。
     */
    val modLocationsKey: Pair<Int, Int> get() = modPacks.size to modLocationCount

    /**
     * v2.73.9：已启用包的商店概要。
     *
     * 顺序 = 按 modId 排序、包内原序（与图鉴格子下标契约一致）。
     */
    val modShops: List<ModRepo.ModShop>
        get() {
            if (enabledModIds.isEmpty()) return emptyList()
            // v2.73.13：shops 模块只由一个包提供（整包 + 分片并存时不重复）
            val pack = ownerPack("shops") ?: return emptyList()
            return pack.shops
        }

    /**
     * v2.73.9：商店 → 图鉴条目。
     *
     * 行名与原版图鉴既有口径一致：位置 / 店主 / 商品数 / 类别。
     * ⚠ 商店**没有图**（包里没给），所以图鉴格子是首字占位块 —— 不是缺数据，
     *   是这个分类本来就是文字表。
     */
    private fun modShopCodexItem(s: ModRepo.ModShop): CodexItem = CodexItem(
        name = s.name,
        iconKey = s.name,
        rows = buildList {
            if (s.location.isNotBlank()) add("位置" to s.location)
            if (s.owner.isNotBlank()) add("店主" to s.owner)
            s.items?.let { add("商品数" to "$it 件") }
            if (s.sells.isNotBlank()) add("类别" to s.sells)
            add("说明" to "Mod 扩展商店概要，具体商品以游戏内商店为准")
        },
    )

    /** v2.73.9：商店 → 图鉴条目列表。 */
    val modShopItems: List<CodexItem> get() = modShops.map { modShopCodexItem(it) }

    /** 来自 Mod 包的商店数。 */
    val modShopCount: Int get() = modShops.size

    /**
     * v2.73.9：鱼相关的 UI `remember` 缓存键（两维度：包数 + Mod 鱼数）。
     */
    val modFishesKey: Pair<Int, Int> get() = modPacks.size to modFishCount

    /**
     * v2.73.9：商店相关的 UI `remember` 缓存键。
     */
    val modShopsKey: Pair<Int, Int> get() = modPacks.size to modShopCount

    /**
     * 图鉴分组（运行时视图）= 静态 [codexGroups] + crops/recipes/fishing 组按启用包重建
     *                          + 有 Mod 商店/地点时**追加**新组。
     *
     * ⚠ 为什么只有这几个组要重建：其余组的条目全来自内置 assets，Mod 包不提供；
     *   而作物/料理/鱼要「按季节筛选自动带上 SVE」，那条链路已经是运行时合并的，
     *   图鉴这里必须跟它**同源**——两者各自算一份就会在「装包开关」那一刻不同步。
     *
     * ⚠ 下标稳定性：crops 组内条目顺序与 [crops] 严格一致（先原版、后按 modId 排序），
     *   而详情页用 `(groupId, idx)` 取条目。顺序一旦与 [crops] 脱节，
     *   点第 N 个打开的会是另一个作物。顺序即契约，见 69/70 节断言。
     *
     * ⚠ 「商店」「地点」是**新组**，追加在末尾 —— 这样已有组的下标一个都不动
     *   （否则老用户的「上次打开位置」记忆会错位）。
     */
    val codexGroupsWithMods: List<CodexGroup>
        get() {
            val v = codexViewVersion
            val cur = cachedCodexGroups
            if (cur != null && cachedCodexGroupsVersion == v) return cur
            val built = buildCodexGroupsWithMods()
            cachedCodexGroups = built
            cachedCodexGroupsVersion = v
            return built
        }

    /** v2.73.12：[codexGroupsWithMods] 的缓存（按 [codexViewVersion] 判）。 */
    @Volatile private var cachedCodexGroups: List<CodexGroup>? = null
    @Volatile private var cachedCodexGroupsVersion: Int = -1

    /** [codexGroupsWithMods] 的真正构造（每次版本变化才走这里）。 */
    private fun buildCodexGroupsWithMods(): List<CodexGroup> {
        if (enabledModIds.isEmpty() ||
            (modCropCount == 0 && modRecipeCount == 0 && modFishCount == 0 &&
                modShopCount == 0 && modLocationCount == 0)
        ) return codexGroups
        val out = codexGroups.map { g ->
            when (g.id) {
                "crops" -> g.copy(items = cropCodexItems(crops))
                "recipes" -> g.copy(items = recipeCodexItems(g.items, modRecipeItems))
                "fishing" -> g.copy(items = g.items + modFishItems)
                else -> g
            }
        }.toMutableList()
        if (modShopCount > 0) out += CodexGroup("shops", "商店", modShopItems)
        if (modLocationCount > 0) out += CodexGroup("locations", "地点", modLocationItems)
        return out
    }

    private fun buildCodexGroups(arr: (String) -> JSONArray, obj: (String) -> JSONObject): List<CodexGroup> {
        // 英文键 → 中文显示名（这些 JSON 键为英文，此前详情直接露出原始键）
        val KEY_CN = mapOf(
            "name" to "名称", "sapling" to "树苗", "price" to "售价", "seasons" to "季节",
            "source" to "获取", "effect" to "效果", "category" to "分类",
            "year2" to "第二年上市", "pierre" to "应季价", "pierreOff" to "非应季价",
            "joja" to "Joja 价", "jojaMember" to "Joja会员价", "note" to "备注",
        )
        // ingredientKey：把该 JSON 数组字段（[{name,count}]）拼成「材料」行置顶（通用行解析读不了数组）
        // textKey：把该字段当**大段文字**（CodexItem.text）而不是键值行。
        //   v2.98.0 加：图鉴条目详情里 rows 超过 5 行会被折进「📋 详细属性」，
        //   而 v2.41 的教训正是「建造要什么材料」被折叠过一次 —— 所以长内容
        //   （解锁条件 / 备齐总量 / 易混）必须走独立的段落卡（text），不能挤进 rows。
        fun rowsGroup(id: String, title: String, file: String, nameKey: String, drop: Set<String> = emptySet(), ingredientKey: String? = null, textKey: String? = null): CodexGroup {
            val ja = arr(file)
            return CodexGroup(id, title, (0 until ja.length()).map { i ->
                val o = ja.getJSONObject(i)
                val firstKey = o.keys().next()
                val byNameKey = o.optString(nameKey)
                val name = if (byNameKey.isNotBlank()) byNameKey else o.optString(firstKey)
                // 实际当标题用的键不再作为行重复显示（仅回退用 firstKey 时才排除它）
                val titleKeys = if (byNameKey.isNotBlank()) setOf(nameKey) else setOf(nameKey, firstKey)
                val ing = ingredientKey?.let { k ->
                    o.optJSONArray(k)?.let { a ->
                        (0 until a.length()).joinToString("、") { j ->
                            val e = a.getJSONObject(j)
                            "${e.optString("name")} ×${e.optInt("count", 1)}"
                        }.takeIf { it.isNotBlank() }
                    }
                }
                CodexItem(
                    name = name,
                    iconKey = name,
                    rows = buildList {
                        if (ing != null) add("材料" to ing)
                        o.keys().asSequence()
                            .filter { it !in titleKeys && it !in drop && it != ingredientKey && it != textKey }
                            .forEach { k ->
                                val v = if (k == "year2") (if (o.optBoolean(k)) "是" else "否") else o.optString(k, "")
                                if (v.isNotBlank()) add((KEY_CN[k] ?: k) to v)
                            }
                    },
                    text = textKey?.let { o.optString(it, "") } ?: "",
                )
            })
        }

        val fruitTrees = arr("fruitTrees.json").toRowsTyped()
        val skills = obj("skills.json")
        val island = obj("gingerIsland.json")
        val mines = obj("mines.json")
        val museum = obj("museum.json")
        val secretNotes = arr("secretNotes.json")

        val groups = mutableListOf<CodexGroup>()

        groups += CodexGroup("fishing", "钓鱼图鉴", fishing.map {
            CodexItem(it.name, it.name, rows = buildList {
                add("季节" to it.seasons.joinToString("、"))
                add("天气" to it.weather); add("时间" to it.time)
                add("地点" to it.location); add("类型" to it.type)
                it.price?.let { p -> add("基础价" to "${p}g") }
                it.difficulty?.let { d -> add("难度" to "$d") }
                if (it.note.isNotBlank()) add("备注" to it.note)
            })
        })
        groups += CodexGroup("crops", "作物", cropCodexItems(baseCrops))
        groups += rowsGroup("fruitTrees", "果树", "fruitTrees.json", "果实")
        groups += rowsGroup("recipes", "菜谱", "recipes.json", "菜名", ingredientKey = "ingredients")
        groups += rowsGroup("crafting", "制作", "crafting.json", "名称", ingredientKey = "ingredients")
        groups += rowsGroup("animals", "动物", "animals.json", "动物")
        // v2.98.0：建筑分类多一条 textKey —— 法师塔 6 件魔法建筑的解锁条件 / 备齐总量 /
        //   易混辨析走**段落卡**（text），不挤进 rows。理由：rows 超过 5 行会被折进
        //   「📋 详细属性」，而 v2.41 的教训正是「建造要什么材料」被折叠过一次。
        groups += rowsGroup("buildings", "建筑", "buildings.json", "建筑", textKey = "说明")
        groups += rowsGroup("fishPonds", "鱼塘", "fishPonds.json", "鱼")
        // c212：加工图鉴——processing.json（原料/成品/机器/耗时/基础售价）。
        //   成品作为条目名；机器/原料召回/耗时/售价显示为属性行。原料与成品名都归一到
        //   官方中文名（sourceIndex 的「🍶 可加工 / ⬅ 由其制成」反查靠它对齐）。图标走 image_map。
        groups += rowsGroup("processing", "加工", "processing.json", "成品")
        groups += rowsGroup("toolUpgrades", "工具升级", "toolUpgrades.json", "升级")
        groups += rowsGroup("geodes", "晶洞", "geodes.json", "晶洞")
        groups += rowsGroup("seedPrices", "种子价格", "seedPrices.json", "种子")
        groups += CodexGroup("tackle", "渔具鱼饵", arr("tackle.json").toRowsTyped().map {
            val name = it.entries.first().value
            CodexItem(name, name, rows = it.entries.filter { e -> e.value != name && e.value.isNotBlank() }.map { e -> e.key to e.value })
        })
        groups += CodexGroup("museumMinerals", "博物馆·矿物", museum.getJSONArray("minerals").let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val src = itemSources[o.getString("name")]
                CodexItem(o.getString("name"), o.getString("name"), rows = buildList {
                    add("类别" to o.optString("category"))
                    src?.let { s -> if (s.places.isNotBlank()) add("来源" to s.places) }
                })
            }
        })
        groups += CodexGroup("museumArtifacts", "博物馆·古物", museum.getJSONArray("artifacts").let { a ->
            (0 until a.length()).map {
                val nm = a.getString(it)
                val src = itemSources[stripQuality(nm)]
                CodexItem(nm, nm, rows = src?.takeIf { s -> s.places.isNotBlank() }?.let { s -> listOf("来源" to s.places) } ?: emptyList())
            }
        })
        // 觅食图鉴（来源表 join，含出现地点概率）
        groups += CodexGroup("forage", "觅食", arr("forage.json").toRowsTyped().map {
            val nm = it["name"] ?: ""
            CodexItem(nm, nm, rows = buildList {
                it["season"]?.takeIf { s -> s.isNotBlank() }?.let { s -> add("季节" to s) }
                it["places"]?.takeIf { s -> s.isNotBlank() }?.let { s -> add("出现地点" to s) }
                it["price"]?.takeIf { p -> p.isNotBlank() }?.let { p -> add("售价" to "${p}g") }
            })
        })
        groups += CodexGroup("achievements", "成就", arr("achievements.json").toRowsTyped().map {
            CodexItem(it["成就"] ?: "", "", rows = listOf("条件" to (it["条件"] ?: ""), "类别" to (it["类别"] ?: "")))
        })
        groups += CodexGroup("movies", "电影院偏好", arr("movies.json").toRowsTyped().map {
            CodexItem(it["村民"] ?: "", it["村民"] ?: "", rows = it.entries.filter { e -> e.key != "村民" && e.value.isNotBlank() }.map { e -> e.key to e.value })
        })
        groups += CodexGroup("festivals", "节日", festivals.map {
            CodexItem(it.name, it.name, rows = listOf(
                "日期" to it.date, "地点" to it.location,
                // c210：起止时间（官方口径=可入场窗口+返程时刻）
                "时间" to (it.hours ?: ""),
                "活动" to it.activity, "奖励 / 摊位" to it.rewards,
            ).filter { e -> e.second.isNotBlank() })
        })
        // v2.117：出货图鉴条目**对象化** —— 每条带官方售价与可否出货。
        //
        // ⚠ 数据形态变了（groups[].items 从字符串数组改成对象数组），
        //   改这里必须同步改 verify 38.1 / 58.70 两处读取（那边按字符串取名，
        //   条目变对象后会取到空 → 断言空转）。
        //
        // ⚠ 「售价」必须放 rows **第一行**：图鉴网格的状态行只认 rows 的第一行
        //   （CodexPage 的 statusText = item.rows.firstOrNull()?.second），
        //   放后面就只显示「分组」，玩家看不到价格。
        //
        // v2.118：品质价给**绝对价**，不再只给倍率。
        //
        // 口径来源：官方源码 Object.cs:7182
        //     float startPrice = (int)((float)price.Value * (1f + (float)base.Quality * 0.25f));
        //   品质等级 0/1/2/4（Object.cs:323-329）⇒ 银×1.25、金×1.5、铱×2.0，
        //   且是 **(int) 截断**（不是四舍五入）—— 数据侧已按此算好，这里直接读。
        //
        // ⚠ 只有**有品质资格**的物品才有三档价（作物/水果/花/蘑菇/蛋/奶/鱼等，
        //   共 89 条）。矿物宝石、金属锭、工匠品、加工品在游戏里就一个价，
        //   给它们编三档是**假数据** —— 所以没有 quality 键就是没有，不补零。
        groups += CodexGroup("shipping", "出货图鉴", arr("shipping.json").let { a ->
            (0 until a.length()).flatMap {
                val g = a.getJSONObject(it)
                val grp = g.getString("group")
                val cnt = g.optInt("count")
                val ia = g.getJSONArray("items")
                (0 until ia.length()).map { j ->
                    val o = ia.getJSONObject(j)
                    val nm = o.optString("name")
                    val price = o.optInt("price", 0)
                    val shippable = o.optBoolean("shippable", true)
                    val q = o.optJSONObject("quality")
                    CodexItem(nm, nm, rows = buildList {
                        add("售价" to if (shippable) "${price}g" else "不可出货")
                        if (shippable && q != null) {
                            add("银品质" to "${q.optInt("silver", 0)}g")
                            add("金品质" to "${q.optInt("gold", 0)}g")
                            add("铱品质" to "${q.optInt("iridium", 0)}g")
                        }
                        add("分组" to "$grp（$cnt 项）")
                    })
                }
            }
        })
        groups += CodexGroup("parrotShop", "姜岛·鹦鹉兑换", island.getJSONArray("parrotShop").toRowsTyped().map {
            CodexItem(it["兑换项"] ?: "", "", rows = it.entries.filter { e -> e.value.isNotBlank() && e.key != "兑换项" }.map { e -> e.key to e.value })
        })
        // v2.41 浣熊商店（1.6 大树桩那只浣熊的老婆开的店，12 项以物易物）。
        // ⚠ iconKey 传**兑换项名本身**（鹦鹉兑换传的是空串）：浣熊商店这 12 项的
        // iconKey 走 image_map 才能显示；缺图时自动落「首字占位块」，
        // 与全项目缺图时的既有表现一致（AssetImage 内部处理，不用在这里特判）。
        // v2.43：12 项现已全部有图（霜瓜种子/浣熊日记/浣熊帽/丛林鱼缸/仙尘/谜之盒
        // 当初没图，v2.43 从 wiki 补齐）。**此处刻意不写死「有几项有图」**——
        // 那种数字一换版本就会过期，写成断言不如让 image_map 自己兜底。
        groups += CodexGroup("raccoonShop", "浣熊商店", arr("raccoon.json").toRowsTyped().map {
            val nm = it["兑换项"] ?: ""
            CodexItem(nm, nm, rows = it.entries.filter { e -> e.value.isNotBlank() && e.key != "兑换项" }.map { e -> e.key to e.value })
        })
        groups += CodexGroup("skills", "技能与职业", skills.getJSONArray("list").let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val sb = StringBuilder(o.optString("intro")).append("\n")
                o.getJSONArray("unlocks").strList().forEach { sb.append("· ").append(it).append("\n") }
                o.getJSONArray("branches").let { bs ->
                    (0 until bs.length()).forEach { bi ->
                        val b = bs.getJSONObject(bi)
                        sb.append("\n【").append(b.getString("name")).append("】").append(b.optString("desc")).append("\n")
                        b.getJSONArray("options").let { os ->
                            (0 until os.length()).forEach { oi ->
                                val op = os.getJSONObject(oi)
                                sb.append("  → ").append(op.getString("name")).append("：").append(op.optString("desc")).append("\n")
                            }
                        }
                    }
                }
                CodexItem(o.getString("name"), "", text = sb.toString().trim())
            }
        } + CodexItem("精通山洞（1.6）", "", text = skills.optString("mastery")))
        groups += CodexGroup("mines", "矿井与骷髅洞穴", buildList {
            add(CodexItem("矿点出现层段", "", rows = mines.getJSONArray("oreLayers").toRowsTyped()
                .map { r -> (r.entries.firstOrNull()?.value ?: "") to r.entries.drop(1).joinToString("　") { "${it.key}:${it.value}" } }))
            add(CodexItem("奖励层（每 10 层）", "", rows = mines.getJSONArray("rewardFloors").toRowsTyped()
                .map { r -> (r["层"] ?: "") to "${r["常规奖励"] ?: ""}（重混：${r["重新混合后的可能"] ?: ""}）" }))
            add(CodexItem("特殊木箱与木桶", "", text = mines.optString("specialCrates")))
            add(CodexItem("骷髅洞穴怪物", "", rows = mines.getJSONArray("skullMonsters").toRowsTyped()
                .map { r -> (r.entries.firstOrNull()?.value ?: "") to r.entries.drop(1).joinToString("　") { "${it.key}:${it.value}" } }))
            mines.getJSONArray("rules").let { rs ->
                (0 until rs.length()).forEach {
                    val r = rs.getJSONObject(it)
                    add(CodexItem(r.getString("topic"), "", text = r.optString("text")))
                }
            }
        })
        groups += CodexGroup("monsters", "怪物图鉴", monsterDrops.map {
            CodexItem(it.first, it.first, rows = buildList {
                // 拆分 "史莱姆泥｜紫水晶（极低）" 为多行（顿号分隔）
                val drops = it.second.substringBefore("｜出没：")
                if (drops.isNotBlank()) add("掉落" to drops)
                add("出没" to it.second.substringAfter("｜出没：", "").ifEmpty { "—" })
            })
        })
        // v2.68.2 怪物掉落物（群友反馈：法师最爱「太阳精华」在图鉴里根本没有条目）。
        //   这些名字此前只以「某怪物的掉落文字」和「配方材料」两种身份存在，
        //   两者都不是图鉴条目 -> 村民页点最爱只能落到空搜索页。
        //   ⚠ 只收**别的分组还没有条目**的那几种（史莱姆泥/太阳精华/虚空精华/蝙蝠翅膀/虫肉）。
        //     把已有条目的名字（五彩碎片、紫水晶、泪晶…）再收一遍会让它们变成重名，
        //     而 resolveItemJump 对重名的行为是「跳搜索列表」而不是「直达详情」——
        //     那样补数据反而把原有跳转弄坏。成员名单由 verify 58.31 钉住。
        groups += CodexGroup("monsterLoot", "怪物掉落物", arr("monsterLoot.json").toRowsTyped().map {
            val nm = it["名称"] ?: ""
            CodexItem(nm, nm, rows = it.entries.filter { e -> e.key != "名称" && e.value.isNotBlank() }.map { e -> e.key to e.value })
        })
        groups += CodexGroup("marriage", "婚姻与婚后", buildList {
            add(CodexItem("婚姻通用机制", "", text = obj("marriage.json").optString("general")))
            add(CodexItem("科罗布斯（同居）", "", text = obj("marriage.json").optString("krobus")))
        })
        groups += CodexGroup("channels", "种子购买渠道", buildList {
            arr("seedChannels.json").toRowsTyped().forEach {
                add(CodexItem(it["who"] ?: "", "", text = it["text"] ?: ""))
            }
            arr("nonbuyableSeeds.json").toRowsTyped().forEach {
                add(CodexItem(it["name"] ?: "", it["name"] ?: "", text = it["text"] ?: ""))
            }
        })
        // v2.18 群友反馈：🎲 小鸡代码（作弊模式 spawn code）
        // v2.79：28 条 → 162 条（补群友的 134 条清单），并**按编号形态标出用法**：
        //   纯数字 = 物品 object id，给动物/角色取名时输入这个数字就出物品；
        //   带 (W)/(B)/(BC) 前缀 = 取名无效，得用 SMAPI/CJB 作弊菜单或聊天调试命令。
        //   不标清楚，玩家照着取名刷半天没反应，还以为 App 编号错了。
        groups += CodexGroup("cheatCodes", "🎲 小鸡代码", arr("cheatCodes.json").let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val code = o.getString("spawn_code")
                // method 缺失/写错时按编号形态兜底：纯数字即取名可用
                val naming = o.optString("method").takeIf { it.isNotBlank() }
                    ?.let { it == "naming" } ?: code.all { it.isDigit() }
                CodexItem(
                    o.getString("name"), o.getString("name"),
                    rows = buildList {
                        add("代码" to code)
                        add(
                            "用法" to if (naming) "取名可用：给动物取名时输入这个数字"
                            else "取名无效：需用作弊菜单或聊天命令"
                        )
                        o.optString("name_en").takeIf { it.isNotBlank() }?.let { add("英文名" to it) }
                        o.optString("category_cn").takeIf { it.isNotBlank() }?.let { add("类别" to it) }
                        o.optString("description").takeIf { it.isNotBlank() }?.let { add("说明" to it) }
                    },
                    text = code,
                    note = o.optString("note"),
                )
            }
        })
        groups += CodexGroup("secretNotes", "秘密纸条", secretNotes.let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                CodexItem("#${o.optInt("num")} ${o.optString("text").take(12)}", "", text = o.optString("text"))
            }
        })
        return groups
    }
}
