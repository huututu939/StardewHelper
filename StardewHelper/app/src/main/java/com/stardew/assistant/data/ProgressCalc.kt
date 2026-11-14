package com.stardew.assistant.data

/**
 * v2.25：进度统计单源。
 *
 * 起因：分享「进度成就卡」需要和进度页显示完全相同的数字。两处各写一份必然漂移
 * （历史上已出过 web 287 / App 279 的分母分歧），所以把 ProgressPage 里的计数原样搬过来，
 * 进度页与分享卡共用这一个函数。
 *
 * 注意：分母是**实时算出**的（收集包条目 + 博物馆 + 成就 + 鹦鹉服务），
 * 不允许写死常量——数据包一扩充，写死的分母立刻变成谎话。
 */
data class ProgressStats(
    val bundleChecked: Int,
    val bundleTotal: Int,
    val totalBundles: Int,
    val fullBundles: Int,
    val museumDone: Int,
    val museumTotal: Int,
    val achDone: Int,
    val achTotal: Int,
    val walnutDone: Int,
    val walnutTotal: Int,
    // v2.41 浣熊商店。⚠ **刻意不进 totalDone / totalAll**：
    // 用户 2026-10-13 拍板过「新勾选不计入总进度」，所以这两项只给总览单开一行看，
    // 279 分母一个字不动——改了就把「已拍板的口径」偷偷改掉了。
    val raccoonDone: Int,
    val raccoonTotal: Int,
    // v2.58 电影院（废弃 Joja 超市改建的「失踪的收集包」）。同款口径：
    // 它是社区中心**修好之后**才出现的独立建筑，不是整修的前置之一，
    // 所以既不进 totalDone / totalAll，也不进 totalBundles / fullBundles
    // （进了会让「社区中心整修」永远差一个包）。总览单开一行看。
    val laterDone: Int,
    val laterTotal: Int,
) {
    val totalDone: Int get() = bundleChecked + museumDone + achDone + walnutDone
    val totalAll: Int get() = bundleTotal + museumTotal + achTotal + walnutTotal
    val percent: Int get() = if (totalAll <= 0) 0 else totalDone * 100 / totalAll
}

/**
 * v2.27：单个收集包的完成判定，**单源**。
 *
 * 起因：游戏里有两个包不是「全部提交」而是「选 N」——锅炉房·冒险家 4 选 2、
 * 鱼缸·蟹笼 10 选 5。此前 ProgressCalc / UnlockDone / ProgressPage 三处各写一份
 * `done == items.size`，天然会把这两个包判成永远做不完（记忆里「金库/宝石册恒空」那类
 * 同源问题的翻版）。现在只有这里说了算，别处一律调它。
 *
 * [choose] <= 0 表示旧数据/普通包 → 全部提交才算完成。
 * 分母口径不变：bundleTotal 仍按 items 总数算，选 N 的包照旧能勾满 4/4、10/10，
 * 「是否完成」与「勾了多少」是两回事，不要把分母也改成 choose（那会让进度分母凭空缩水）。
 */
/**
 * 收集包勾选键**单源**（v2.99.0 / c179 从 bundleDoneCount 抽出）。
 *
 * `cc:<房间全局下标>:<包下标>:<物品名>` —— 下标必须用 **GameData.bundleRooms 的全局下标**
 * （v2.58 教训：子集重新编号会让老用户已勾的条目静默跑到别的包上）。
 * ⚠ **全项目只有这里能拼 `cc:` 前缀**（v2.100.0 / c182：进度页与 PrefsRepo 的历史迁移表
 *   都改调本函数了 ⇒ `cc:` 字面量只出现在本文件，verify 第 109 节钉死）。
 */
fun bundleCheckKey(roomIdx: Int, bundleIdx: Int, itemName: String): String =
    "cc:$roomIdx:$bundleIdx:$itemName"

fun bundleDoneCount(b: NamedItems, roomIdx: Int, bundleIdx: Int, checks: Set<String>): Int =
    b.items.count { bundleCheckKey(roomIdx, bundleIdx, it.name) in checks }

/**
 * 图鉴条目 → 它出现的收集包（v2.99.0 / c179「你的状态」反查；v2.100.0 / c182 修别名归一）。
 *
 * ⚠ 与 [bundleDoneCount] 用**同一个键构造**（[bundleCheckKey]）—— 反查不许出现
 * 第二套「下标 → 名字」的实现，否则口径漂移（v2.27 教训）。
 *
 * ## v2.100.0 / c182：**两侧都过 [GameData.canonicalName]**（与 [GameData.usagesOf] 同一口径）
 *
 * 病因：`bundles.json` 里 10 个条目的名字是**别名侧**（动物毛 / 大壶牛奶 / 果酒 / 黏液 /
 * 罂粟 / 香料浆果 / 野李子 / 仙人掌果 / 冷冻晶洞…），它们的**规范名**（羊毛 / 大牛奶 /
 * 酒 / 史莱姆泥 / 虞美人 / 香味浆果 / 野梅 / 仙人掌果子 / 冰冻晶洞）**根本不在**
 * bundles.json 里 ⇒ 只按原名精确匹配的话，规范名条目**永远匹配不到收集包**
 * ⇒「🎒 你的状态」里没有「收集包」行，而同屏「🧭 用途速查」却写着「📦 收集包 …」
 * ⇒ **同屏自相矛盾**（图鉴 → 作物 → 虞美人：状态段没有「收集包」，用途速查说有）。
 * 第 2 层给 [GameData.usagesOf] 建了别名归一，第 1 层的反查没建 ⇒ 两处口径分叉。
 *
 * ## ⚠ 拼键用**收集包条目自己的名字**（别名侧，如「罂粟」），不是图鉴条目名（如「虞美人」）
 *
 * 勾选键是用户勾选那一刻按**收集包条目名**写下的（[bundleDoneCount] 用 `it.name`），
 * 拿归一后的名字当键 ⇒ 键会算成 `cc:<ri>:<bi>:虞美人`，而用户勾的键是
 * `cc:<ri>:<bi>:罂粟` ⇒ 老用户已勾的条目**静默变成「未提交」**。
 * 这正是「别让归一后的名字当键」纪律在**反查侧**的镜像。
 */
data class BundleHit(
    val roomIdx: Int,
    val bundleIdx: Int,
    /** 房间名（App 中文）。 */
    val room: String,
    /** 命中的包。 */
    val bundle: NamedItems,
    /**
     * 命中的**收集包条目自己的名字**（别名侧，如「罂粟」）。
     *
     * ⚠ 拼键用它们，**不用图鉴条目名**（见文件头注释）。
     * 同一包里若有多条归一后同名的条目（当前数据没有），全部列出来 ——
     * 「已提交」只要有一条勾上就算（那个包里它们本来就是同一件东西）。
     */
    val itemNames: List<String>,
) {
    /** 这一项在这个包里提交了没有（与 bundleIsFull 同一个键来源）。 */
    fun checked(checks: Set<String>): Boolean =
        itemNames.any { bundleCheckKey(roomIdx, bundleIdx, it) in checks }
}

/**
 * 图鉴条目 → 它出现的收集包（反查）。
 *
 * @param itemName 图鉴条目名（任意一侧的叫法都行：规范名 / 别名 / 带品质前缀）
 * @param rooms 收集包房间列表（全局下标顺序必须与 [GameData.bundleRooms] 一致）
 */
fun bundleHitsOf(itemName: String, rooms: List<BundleRoom>): List<BundleHit> {
    if (itemName.isBlank()) return emptyList()
    val canon = GameData.canonicalName(itemName)
    return rooms.flatMapIndexed { ri, room ->
        room.bundles.mapIndexedNotNull { bi, b ->
            val names = b.items.map { it.name }.filter { GameData.canonicalName(it) == canon }
            if (names.isEmpty()) null else BundleHit(ri, bi, room.room, b, names)
        }
    }
}

fun bundleIsFull(b: NamedItems, roomIdx: Int, bundleIdx: Int, checks: Set<String>): Boolean {
    val done = bundleDoneCount(b, roomIdx, bundleIdx, checks)
    return if (b.choose > 0) done >= b.choose else done == b.items.size
}

fun calcProgress(snap: PrefsRepo.Snapshot): ProgressStats {
    var bundleItemCount = 0
    var fullBundles = 0
    var bundleChecked = 0
    var laterItemCount = 0
    var laterChecked = 0
    // ⚠ 下标必须用 **GameData.bundleRooms 的全局下标**，不能对过滤后的子集重新编号：
    // 勾选键是 `cc:<roomIdx>:<bundleIdx>:<物品名>`，子集编号会让所有老用户已勾的
    // 收集包静默错位（看着像「勾了别的包」）。v2.58 加电影院时踩的就是这个位置。
    GameData.bundleRooms.forEachIndexed { ri, room ->
        room.bundles.forEachIndexed { bi, b ->
            val done = bundleDoneCount(b, ri, bi, snap.checks)
            if (room.optional) {
                laterItemCount += b.items.size
                laterChecked += done
            } else {
                bundleItemCount += b.items.size
                bundleChecked += done
                if (bundleIsFull(b, ri, bi, snap.checks)) fullBundles++
            }
        }
    }
    val museumAll = (GameData.codexGroups.firstOrNull { it.id == "museumMinerals" }?.items ?: emptyList()) +
        (GameData.codexGroups.firstOrNull { it.id == "museumArtifacts" }?.items ?: emptyList())
    val achievements = GameData.codexGroups.firstOrNull { it.id == "achievements" }?.items ?: emptyList()
    val parrotShop = GameData.codexGroups.firstOrNull { it.id == "parrotShop" }?.items ?: emptyList()
    val raccoonShop = GameData.codexGroups.firstOrNull { it.id == "raccoonShop" }?.items ?: emptyList()
    return ProgressStats(
        bundleChecked = bundleChecked,
        bundleTotal = bundleItemCount,
        totalBundles = GameData.bundleRooms.filter { !it.optional }.sumOf { it.bundles.size },
        fullBundles = fullBundles,
        museumDone = museumAll.count { "museum:${it.name}" in snap.checks },
        museumTotal = museumAll.size,
        achDone = achievements.count { "ach:${it.name}" in snap.checks },
        achTotal = achievements.size,
        walnutDone = parrotShop.count { "walnut:${it.name}" in snap.checks },
        walnutTotal = parrotShop.size,
        // 键前缀与 CodexChecks.CHECK_PREFIX 一致；这里写死是既有做法（museum/ach/walnut 同款），
        // verify 会断言「键前缀只允许出现在 CodexChecks.kt 与 calcProgress 里」。
        raccoonDone = raccoonShop.count { "raccoon:${it.name}" in snap.checks },
        raccoonTotal = raccoonShop.size,
        laterDone = laterChecked,
        laterTotal = laterItemCount,
    )
}