package com.stardew.assistant.ui.today

import com.stardew.assistant.data.Calc
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.Season
import com.stardew.assistant.data.villagerPresent

/**
 * v2.25：今日事实的**单源**计算。
 *
 * 今日页与「今日概览卡」都要说「今天谁生日 / 下个节日几天后 / 哪个作物该种了」。
 * 两处各算一遍必然漂移（分享出去的图和 App 里显示不一样是最难解释的 bug），故抽这里。
 *
 * 只取**确定性事实**：日期、节日、生日、播种死线。
 * 天气 / 今日钓什么鱼 / 今日幸运一概没有——游戏内随机，App 不知道，编不得。
 */
data class TodayFacts(
    val seasonName: String,
    val day: Int,
    val year: Int,
    val todayBirthdays: List<String>,
    val nextFestivalName: String?,
    val nextFestivalDate: String?,
    val nextFestivalDays: Int,
    val nearestDeadCrop: String?,
    val nearestDeadDays: Int,
    val todoDone: Int,
    val todoTotal: Int,
)

fun todayFacts(snap: PrefsRepo.Snapshot): TodayFacts {
    val seasonName = Season.names[snap.season]

    val si = Season.names.indexOf(seasonName)
    // v2.38：肯特第一年在部队服役，第二年春才回村——生日日期（春 4）本身没错，
    // 错的是「第一年春天就提醒他 3 天后过生日」。在场判定单源在 VillagerArrival.kt。
    val birthdays = GameData.gifts
        .filter { it.birthdaySeason == seasonName && it.birthdayDay == snap.day }
        .filter { villagerPresent(it.name, snap.year, si, snap.day) }
        .map { it.name }

    // 下一个节日：季节循环制倒计时（Calc.daysUntil 跨季正确），窗口放到 60 天
    val next = GameData.festivals
        .mapNotNull { f ->
            val sd = f.startDay ?: return@mapNotNull null
            f to Calc.daysUntil(seasonName, snap.day, f.season ?: "春", sd)
        }
        .filter { it.second in 0..60 }
        .minByOrNull { it.second }

    // 最近的一个「再不种就来不及」
    val dead = GameData.crops
        .filter { Calc.deadline(it, seasonName, snap.day, snap.prof10) == Calc.Deadline.MUST_PLANT }
        .minByOrNull { Calc.lastPlantDay(it, seasonName, snap.prof10) }

    return TodayFacts(
        seasonName = seasonName,
        day = snap.day,
        year = snap.year,
        todayBirthdays = birthdays,
        nextFestivalName = next?.first?.name,
        nextFestivalDate = next?.first?.date,
        nextFestivalDays = next?.second ?: 999,
        nearestDeadCrop = dead?.name,
        nearestDeadDays = if (dead == null) 0 else snap.day.let { Calc.lastPlantDay(dead, seasonName, snap.prof10) - it },
        todoDone = todoDoneCount(snap),
        todoTotal = snap.todoList.size,
    )
}