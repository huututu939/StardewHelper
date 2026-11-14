package com.stardew.assistant.ui.guide

import com.stardew.assistant.Screen
import com.stardew.assistant.ui.nav.NavRequest

/**
 * v2.31：攻略册的**跳转目标表**（v2.35 起九本，钓鱼地图已移出，见下）。
 *
 * ## 为什么要有这张表
 * 批次 4 的「跳到…」面板要列出全部攻略入口。此前这些目标只存在于两处：
 * `GuideHomeBody` 的 `onOpenXxx` 回调，和 `MainActivity` 的 `guideBody` lambda
 * 里的 `stack.add(...)`。再写第三份就是三源。
 *
 * ## 表里只放跳转三元组，不放 desc / badge
 * `GuideHomeBody` 的卡片描述与徽章是**实时算出来的**（核桃总数、节日数……）。
 * 把它们抄进表里就是 v2.17 D9 那个坑的复刻：
 * 「钓点数不再写死」之后旧文案与 badge 双源，数据一扩充即说谎。
 * 徽章仍然只在 `GuideHomeBody` 里现算，这里只管「点哪去」。
 *
 * ## v2.35：钓鱼地图整本移出
 * 它从来不是一本册子，而是一条「跳到图鉴钓鱼分类并展开钓点筛选」的指令
 * （[NavRequest.Codex]，不是 [NavRequest.Guide]，因此也不在 [GUIDE_SCREENS] 里）。
 * 钓点 / 经验 / 四档售价在图鉴「钓鱼图鉴」的条目详情里本来就有
 * （`CodexPage` 的「🗺 钓点」卡 + 分类级钓点筛选），攻略页那一张只是入口重复，
 * 用户反馈「在数据页面就能直接查看」后移除。
 *
 * 若日后再加同类入口（非压栈、跳去某个页面的某个位置），照此处理：
 * 用 [NavRequest.Codex] 这样的**对应指令类型**，不要为了进这本表硬造一个 [Screen]。
 */
data class GuideBook(
    val key: String,
    val emoji: String,
    val title: String,
    val nav: NavRequest,
)

val GUIDE_BOOKS = listOf(
    GuideBook("island", "🏝", "姜岛攻略", NavRequest.Guide(Screen.GuideIsland)),
    GuideBook("marriage", "💍", "结婚攻略", NavRequest.Guide(Screen.GuideMarriage)),
    GuideBook("weapons", "⚔", "武器收集", NavRequest.Guide(Screen.GuideWeapons)),
    GuideBook("skills", "⭐", "技能升级", NavRequest.Guide(Screen.GuideSkills)),
    GuideBook("foraging", "🍄", "采集攻略", NavRequest.Guide(Screen.GuideForaging)),
    GuideBook("pets", "🐱", "宠物攻略", NavRequest.Guide(Screen.GuidePets)),
    GuideBook("weather", "🌦", "天气与幸运", NavRequest.Guide(Screen.GuideWeather)),
    GuideBook("combat", "⚔", "刷级路线", NavRequest.Guide(Screen.GuideCombat)),
    GuideBook("calendar", "📅", "年历", NavRequest.Guide(Screen.Calendar)),
    GuideBook("shops", "🛒", "商店目录", NavRequest.Guide(Screen.GuideShops)),
)

val GUIDE_BOOK_BY_KEY: Map<String, GuideBook> = GUIDE_BOOKS.associateBy { it.key }

/** 需要开正文词典链接的二级页 = [GUIDE_BOOKS] 里全部压栈目标（8 册攻略 + 年历）。
 *  v2.35 起这张表与 `GUIDE_BOOKS` 的 `NavRequest.Guide` 项一一对应，
 *  非压栈入口（v2.35 前是钓鱼地图）本就不该出现在这里。 */
val GUIDE_SCREENS: Set<Screen> = GUIDE_BOOKS.mapNotNull { (it.nav as? NavRequest.Guide)?.screen }.toSet()