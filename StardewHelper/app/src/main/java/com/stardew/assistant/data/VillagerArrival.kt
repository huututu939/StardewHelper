package com.stardew.assistant.data

/**
 * v2.38：村民「到场时间」规则——生日能不能过的**时序**判定，单源。
 *
 * ## 要分清两件事
 *
 * **生日日期是静态属性**：肯特确实是「春 4」生日，维基 Infobox 三个历史版本都这么写。
 * 所以资料页、攻略页、礼物页照写「春 4」**一字不改**。
 *
 * **「这个生日此刻能不能过」是时序事实**：肯特第一年在军队服役，**第二年春天**才回
 * 鹈鹕镇。第一年春天给他显示「3 天后 · 肯特（春 4）」是错的——那时候他人还在部队，
 * 你连他面都见不着，更谈不上送礼吃 8 倍好感。
 *
 * 本文件只回答第二个问题。第一个问题归数据（`gifts.json`）。
 *
 * ## 为什么要单源
 *
 * 「今天谁生日 / 接下来 7 天谁生日 / 年历里列哪些生日 / 桌面小组件报谁生日」
 * 是**四个消费点**，四份判定逻辑散开写就是 v2.27「判定逻辑散落多处必然漂移」的复刻。
 * 本文件是唯一出口，四处都必须调 `villagerPresent()`。
 *
 * ## 数据依据
 *
 * zh.stardewvalleywiki.com 各村民页的「生日 / 居住于」栏。肯特页原文：
 * 「肯特是一位住在鹈鹕镇的居民。**第一年他在军队服役，第二年春天才会回到鹈鹕镇**
 * 柳巷1号居住。」
 */
data class VillagerArrival(val year: Int, val seasonIdx: Int, val day: Int)

/** 默认：第一年春季 1 日开局时已在镇上 */
val DEFAULT_ARRIVAL = VillagerArrival(1, 0, 1)

/**
 * 只有登记在册的村民才有时序限制；其余一律按 [DEFAULT_ARRIVAL] 处理。
 *
 * - 肯特：第一年服役，第二年春 1 起在柳巷 1 号。
 * - 其余 31 位有生日的村民开局第一年春季均已在镇（含利奥——他生来就在姜岛，
 *   玩家从第一年春 11 日起可登岛，而他的生日在夏 26，原本就在能见到他之后）。
 */
private val ARRIVAL: Map<String, VillagerArrival> = mapOf(
    "肯特" to VillagerArrival(2, 0, 1),
)

fun villagerArrival(name: String): VillagerArrival = ARRIVAL[name] ?: DEFAULT_ARRIVAL

/**
 * 该村民在 [year] 年 [seasonIdx] 季 [day] 日是否已在农场。
 *
 * 跨年到达时以「到达当年的同一时刻」为界：肯特第 2 年春 1 到场，
 * 所以第 2 年春 1 起的每一天都在，第 1 年一天都不在。
 */
fun villagerPresent(name: String, year: Int, seasonIdx: Int, day: Int): Boolean {
    val a = villagerArrival(name)
    if (year != a.year) return year > a.year
    return seasonIdx > a.seasonIdx || (seasonIdx == a.seasonIdx && day >= a.day)
}

/**
 * 该村民「生日落在 [year] 年 [seasonIdx] 季 [day] 日」这一天是否可观测。
 *
 * 与 [villagerPresent] 同义，只是把调用点的意图写清楚——生日发生在某人身上，
 * 前提是那天他在场。
 */
fun birthdayObservable(name: String, year: Int, seasonIdx: Int, day: Int): Boolean =
    villagerPresent(name, year, seasonIdx, day)

/** 生日季名 → 季下标；解析不出返回 -1（调用方负责跳过） */
fun birthdaySeasonIdx(seasonName: String?): Int {
    if (seasonName == null) return -1
    return Season.names.indexOf(seasonName)
}
