package com.stardew.assistant.ui.calendar

import com.stardew.assistant.data.Calc
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.Season
import com.stardew.assistant.data.birthdayObservable

/**
 * v2.25 年历：游戏内历法（4 季 × 28 天）的全年确定性事件索引。
 *
 * 只收录**日期确定**的事件：节日、NPC 生日、作物最晚下种日。
 * 刻意不收录天气 / 当天钓什么鱼 / 今日幸运——那些在游戏里是随机的，
 * App 无从知晓，写上去就是编数据。
 */

enum class CalEventKind { FESTIVAL, BIRTHDAY, SOW_DEADLINE, UNLOCK }

data class CalEvent(
    val kind: CalEventKind,
    val seasonIdx: Int,   // 0..3
    val day: Int,         // 1..28
    val title: String,
    val detail: String,
    val iconName: String,
)

data class CalendarData(
    val events: Map<Pair<Int, Int>, List<CalEvent>>,
    val festivalCount: Int,
    val birthdayCount: Int,
) {
    fun of(seasonIdx: Int, day: Int): List<CalEvent> = events[seasonIdx to day] ?: emptyList()

    /** 该季有事件的天数（用于页面脚注） */
    fun seasonDays(seasonIdx: Int): Int = events.count { it.key.first == seasonIdx }

    val badge: String get() = "$festivalCount 节日 · $birthdayCount 生日"
}

/**
 * festivals.json 的 date 字段格式实测 12/12 均为「季节+数字」或「季节+起-止」
 * （春13 / 春15-17 / 冬25 …），这里严格匹配；匹配不上就返回 null 而不是猜一个值。
 */
private val FESTIVAL_DATE_RE = Regex("^(春|夏|秋|冬)(\\d+)(?:-(\\d+))?$")

/** 返回 (seasonIdx, startDay, endDay)；无法解析返回 null */
fun parseFestivalDate(raw: String): Triple<Int, Int, Int>? {
    val m = FESTIVAL_DATE_RE.find(raw.trim()) ?: return null
    val sIdx = Season.names.indexOf(m.groupValues[1])
    val start = m.groupValues[2].toIntOrNull() ?: return null
    val end = m.groupValues[3].ifEmpty { "" }.toIntOrNull() ?: start
    if (sIdx !in 0..3 || start !in 1..28 || end !in 1..28) return null
    return Triple(sIdx, start, maxOf(start, end))
}

/**
 * 构建全年索引。prof10 传入存档的十级职业（影响末种日：农业学家 ×0.9）。
 * 末种日一律走 Calc.lastPlantDay，不自己写 28 - growth——那会丢掉
 * 「农业学家加速」与「跨季作物」两条既有口径。
 *
 * v2.38：新增 [year]。生日事件要按「那年那位村民在不在农场」过滤——肯特第 1 年
 * 在部队服役，年历不该把「春 4 肯特生日」印在第 1 年那一格里。
 * 节日与末种日与年份无关，照旧每年重复。
 */
fun buildCalendar(prof10: String?, year: Int): CalendarData {
    val map = LinkedHashMap<Pair<Int, Int>, MutableList<CalEvent>>()
    fun put(e: CalEvent) {
        map.getOrPut(e.seasonIdx to e.day) { mutableListOf() }.add(e)
    }

    var festCount = 0
    for (f in GameData.festivals) {
        val parsed = parseFestivalDate(f.date) ?: continue
        val si = parsed.first
        val s = parsed.second
        val e = parsed.third
        val span = if (e > s) "${f.date} 进行中" else f.date
        val loc = f.location.takeIf { it.isNotBlank() } ?: ""
        for (d in s..e) {
            put(CalEvent(CalEventKind.FESTIVAL, si, d, f.name, listOf(span, loc).filter { it.isNotEmpty() }.joinToString(" · "), f.name))
        }
        festCount++
    }

    var bdCount = 0
    for (g in GameData.gifts) {
        val bs = g.birthdaySeason ?: continue
        val bd = g.birthdayDay ?: continue
        val si = Season.names.indexOf(bs)
        if (si !in 0..3 || bd !in 1..28) continue
        // v2.38：生日日当天他在不在农场。日期本身是静态事实（肯特确实春 4 生日），
        // 但「第 1 年春 4 他还在部队」——那一格不该出现这个生日。
        if (!birthdayObservable(g.name, year, si, bd)) continue
        put(CalEvent(CalEventKind.BIRTHDAY, si, bd, "${g.name} 生日", "生日当天送礼效果 ×8，提前备最爱礼物", g.name))
        bdCount++
    }

    for (c in GameData.crops) {
        for (sn in c.seasons) {
            val si = Season.names.indexOf(sn)
            if (si !in 0..3) continue
            val lp = Calc.lastPlantDay(c, sn, prof10)
            if (lp !in 1..28) continue
            // 28 = 跨季作物（survivesCross），种在季末也能过冬/过夏
            val detail = if (lp == 28) "季末种下也能跨季成熟" else "${c.growth ?: 0} 天成熟，错过再等一年"
            put(CalEvent(CalEventKind.SOW_DEADLINE, si, lp, "${c.name} 末种日", detail, c.name))
        }
    }

    val frozen = map.mapValues { (_, v) -> v.toList() }
    return CalendarData(frozen, festCount, bdCount)
}