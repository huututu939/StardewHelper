package com.stardew.assistant.data

/**
 * v2.32：图鉴打勾口径单源。
 *
 * 起因：进度页的博物馆 / 成就 / 金核桃三段与图鉴分类是**同一批条目、两个勾选入口**，
 * 批次 5 把勾选入口统一收到图鉴。键构造必须只有一个出口，否则三处各写一份必然漂移
 * （v2.27「判定口径散落多处必然漂移」同型）。
 *
 * ## 为什么是混合前缀，而不是统一成 `codex:<分类id>:<名>`
 * 旧 5 组的键已经躺在用户存档的 `checks: Set<String>` 里（PrefsRepo.toggleCheck）。
 * 改名就必须做落盘迁移，而 v2.27 已经踩过一次：**只读映射会让「取消勾选」失效**
 * （toggleCheck 删掉新键、旧键还在，下次读取又映射回新键 → 那个勾永远取消不掉）。
 * 真要迁移就得 `replaceChecks(set.map { REMAP[it] ?: it }.toSet())` + 一次性标记键。
 *
 * 沿用旧前缀 = **零迁移 = 用户已勾的 279 个进度一个不丢**，代价只是键前缀不统一，
 * 而这个代价用下面这张表就吸收了。所以旧 5 组保持历史前缀，新增分类才用 `codex:<分类id>:<名>`。
 *
 * ⚠ 旧键前缀（fish: / museum: / ach: / walnut:）**一个字都不能改**。
 */

/**
 * 可打勾分类 id → 打勾键前缀。这 13 个是白名单。
 *
 * 白名单外的分类（电影院偏好 / 出货图鉴 / 晶洞 / 怪物图鉴 / 节日 / 技能…）不打勾：
 * 实测它们的条目结构不是收集清单——movies.json 是「村民→电影喜好表」、shipping.json 是
 * 7 组聚合（一组含 37 个物品）、monsterDrops.json 是怪物掉落表、geodes.json 只有 4 种晶球。
 */
val CHECK_PREFIX: Map<String, String> = mapOf(
    // ── 旧 5 组：沿用历史前缀，零迁移（改这里会让用户已有勾选全部作废）──
    "fishing" to "fish",
    "museumMinerals" to "museum",
    "museumArtifacts" to "museum",
    "achievements" to "ach",
    "parrotShop" to "walnut",
    // ── 新增 9 组 ──
    "crops" to "codex:crops",
    "tackle" to "codex:tackle",
    "forage" to "codex:forage",
    "secretNotes" to "codex:secretNotes",
    "fruitTrees" to "codex:fruitTrees",
    "recipes" to "codex:recipes",
    "crafting" to "codex:crafting",
    "animals" to "codex:animals",
    // v2.41 浣熊商店：与鹦鹉兑换同形态（交材料换东西），但**不在旧 5 组里**，
    // 所以用新前缀 `raccoon:` —— 没有任何历史键需要迁移，用户从零开始勾。
    "raccoonShop" to "raccoon",
)

/** 该分类能否打勾。 */
fun isCheckable(groupId: String): Boolean = groupId in CHECK_PREFIX

/**
 * 打勾键单源。白名单外的分类返回 null，调用方据此隐藏勾选框（不产生 `":名字"` 这种空前缀键）。
 *
 * **除本文件外，全项目不许出现键前缀字面量**，verify 用否定式断言钉死。
 */
fun codexCheckKey(groupId: String, itemName: String): String? =
    CHECK_PREFIX[groupId]?.let { "$it:$itemName" }

/** 该条目是否已勾。白名单外的分类恒 false（不产生 `":名字"` 这种空前缀键）。 */
fun isItemChecked(groupId: String, itemName: String, checks: Set<String>): Boolean {
    val k = codexCheckKey(groupId, itemName) ?: return false
    return k in checks
}

/** 该分类已勾数（分类行进度 `钓鱼图鉴 12/74` 用）。白名单外返回 0。 */
fun groupCheckedCount(groupId: String, items: List<CodexItem>, checks: Set<String>): Int {
    val p = CHECK_PREFIX[groupId] ?: return 0
    return items.count { "$p:${it.name}" in checks }
}
