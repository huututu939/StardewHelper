package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.94.0：NPC 行程表的验收测试。
 *
 * ## 为什么要有这个
 *
 * 行程表的**判定**（今天适用哪张表、跨午夜怎么算、搜行程）在真机上才暴露问题：
 * 第一版装上去点几下就发现「选晴却满屏显示雨天」「平时也显示沙漠节」这类**答错**
 * 的毛病 —— 而当时 verify 的静态断言**全绿**（它们钉的是「结构」，不是「判定结果」）。
 *
 * 所以这里用**真数据**（assets 里那份 JSON）+ **真代码**（同一份 `NpcSchedules.kt`）
 * 在 JVM 上跑：`org.json` 在测试源集有真实现（见 build.gradle 的 `testImplementation(libs.orgjson)`），
 * 解析入口 `parseSchedules(text)` 刻意不依赖 Android 的 Context。
 *
 * ## 这些断言钉的是什么
 *
 * ① **天气是开关**：选晴就不许出现雨天的表（第一版的头号 bug）；
 * ② **每个被选中的表都满足它自己的约束**（星期/日期）——「无锚点的表天天成立」那类错
 *    一律会被这条抓住（第二版补的锚点规则）；
 * ③ **跨午夜**（`12:00 AM`）按「当日深夜延续」；
 * ④ **奇偶年**是唯一能被 App 确定的分支条件；
 * ⑤ **搜索**能按行为原文命中，且容器（分组名）不参与。
 */
class ScheduleTest {

    private val npcs: List<NpcSchedule> by lazy {
        // ⚠ JavaExec 的工作目录是**模块目录**（app/），不是工程根 ⇒ 相对路径要向上找。
        val explicit = System.getProperty("schedule.json")
        val candidates = buildList {
            if (!explicit.isNullOrBlank()) add(File(explicit))
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                add(File(dir, "app/src/main/assets/data/npcSchedules.json"))
                add(File(dir, "src/main/assets/data/npcSchedules.json"))
                dir = dir.parentFile
            }
        }
        val f = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到行程表 JSON，试过：" + candidates.joinToString(" | ") { it.absolutePath },
            f != null,
        )
        parseSchedules(f!!.readText(Charsets.UTF_8))
    }

    private fun npc(name: String): NpcSchedule =
        npcs.firstOrNull { it.name == name } ?: error("没有这位 NPC：$name")

    /** 春 13 日 = 周六（游戏第 1 天是周一 ⇒ (day-1)%7+1）。 */
    private fun ctx(
        seasonIdx: Int = 0,
        day: Int = 13,
        weather: String = ScheduleWeather.CLEAR,
        year: Int = 1,
        married: Boolean = false,
    ) = ScheduleContext(year, seasonIdx, day, weather, married)

    /* ─────────────── ① 数据自洽 ─────────────── */

    @Test
    fun parsesAll() {
        assertEquals("34 位原版 NPC", 34, npcs.size)
        assertEquals(
            "34 位 NPC 的名字与顺序",
            listOf(
                "亚历克斯", "艾利欧特", "哈维", "山姆", "塞巴斯蒂安", "谢恩",
                "阿比盖尔", "艾米丽", "海莉", "莉亚", "玛鲁", "潘妮",
                "卡洛琳", "克林特", "德米特里厄斯", "矮人", "艾芙琳", "乔治", "格斯",
                "贾斯", "乔迪", "肯特", "科罗布斯", "雷欧", "刘易斯", "莱纳斯", "玛妮",
                "齐先生", "潘姆", "皮埃尔", "罗宾", "桑迪", "威利", "法师",
            ),
            npcs.map { it.name },
        )
        val ids = npcs.flatMap { it.variants.map { v -> v.id } }
        assertEquals("变体 id 全局唯一", ids.size, ids.toSet().size)
        assertTrue("id 不得为空", ids.none { it.isBlank() })
    }

    @Test
    fun everySpecialWithRowsHasAnchor() {
        // 「有表但既无日期也无星期」= 每天都成立 ⇒ 平日常年显示节日行程。
        val bad = npcs.flatMap { n ->
            n.variants.filter {
                !it.container && it.kind == ScheduleKind.SPECIAL &&
                    it.rows.isNotEmpty() && it.dates.isEmpty() && it.weekdays.isEmpty()
            }.map { "${n.name}/${it.id}" }
        }
        assertTrue("无锚点的 special 表：$bad", bad.isEmpty())
    }

    @Test
    fun containersHaveNoRows() {
        val bad = npcs.flatMap { n ->
            n.variants.filter { it.container && it.rows.isNotEmpty() }.map { "${n.name}/${it.id}" }
        }
        assertTrue("容器（分组名）不该带时段：$bad", bad.isEmpty())
    }

    /* ─────────────── ② 天气是开关（第一版的头号 bug） ─────────────── */

    @Test
    fun clearDayNeverPicksRainOrGreen() {
        for (n in npcs) {
            for (seasonIdx in 0..3) {
                for (day in 1..28) {
                    val pick = n.applicable(ctx(seasonIdx, day, ScheduleWeather.CLEAR))
                    for (v in pick.primary) {
                        assertFalse(
                            "${n.name} 春/夏/秋/冬 第 $day 日选晴却挑中「${v.label}」",
                            v.kind == ScheduleKind.RAIN || v.kind == ScheduleKind.GREEN,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun clearDayPicksClearOrSpecial() {
        val alex = npc("亚历克斯")
        val pick = alex.applicable(ctx())
        assertTrue("春 13 日晴天，亚历克斯应有今天的表", pick.primary.isNotEmpty())
        assertEquals("亚历克斯春 13（周六）晴天 ⇒ 晴天（常规）", "晴天（常规）", pick.primary[0].label)
        assertEquals(6, pick.primary[0].rows.size)
        // 哈维春 13 有专属周六表 ⇒ 应该挑「周六」而不是晴天常规
        assertEquals("周六", npc("哈维").applicable(ctx()).primary[0].label)
    }

    @Test
    fun rainDayPicksRain() {
        val pick = npc("亚历克斯").applicable(ctx(weather = ScheduleWeather.RAIN))
        assertEquals("雨天 ⇒ 雨天的表", "雨天", pick.primary[0].label)
    }

    @Test
    fun rainTableWithWeekdayLimitNotPickedOnOtherDays() {
        // 玛妮：「雨天（且为周四）」—— 只有周四的雨天表，
        // 别的日子（春 13 = 周六）不该拿它当今天的表。
        val marnie = npc("玛妮")
        val saturday = marnie.applicable(ctx(day = 13, weather = ScheduleWeather.RAIN))
        for (v in saturday.primary) {
            assertFalse(
                "玛妮周六下雨不该挑中周四限定的雨天表",
                v.kind == ScheduleKind.RAIN && v.weekdays.isNotEmpty() && 6 !in v.weekdays,
            )
        }
        // 同一个雨天表在周四（春 4 / 11 / 18 / 25）应当被挑中
        val thursday = marnie.applicable(ctx(day = 11, weather = ScheduleWeather.RAIN))
        assertTrue(
            "玛妮周四下雨应挑中雨天表",
            thursday.primary.any { it.kind == ScheduleKind.RAIN },
        )
    }

    /* ─────────────── ③ 被挑中的表必须满足它自己的约束 ─────────────── */

    @Test
    fun everyPickSatisfiesItsOwnConstraint() {
        for (n in npcs) {
            for (seasonIdx in 0..3) {
                for (day in 1..28) {
                    for (weather in listOf(
                        ScheduleWeather.CLEAR, ScheduleWeather.RAIN, ScheduleWeather.GREEN,
                    )) {
                        val c = ctx(seasonIdx, day, weather)
                        val pick = n.applicable(c)
                        for (v in pick.primary) {
                            if (v.weekdays.isNotEmpty() && c.weekday !in v.weekdays) {
                                // 唯一允许「星期对不上还选它」的情形：
                                // 常规表限定了星期而今天不在范围内 —— 这时必须**带说明**。
                                assertTrue(
                                    "${n.name} 季$seasonIdx 第 $day 日挑中「${v.label}」但星期对不上，且没有说明",
                                    pick.fallbackNote.isNotEmpty(),
                                )
                            }
                            if (v.dates.isNotEmpty()) {
                                assertTrue(
                                    "${n.name} 季$seasonIdx 第 $day 日挑中带日期的「${v.label}」但日期对不上",
                                    v.dates.any { d ->
                                        ScheduleSeasons.idxOf(d.season) == seasonIdx &&
                                            day >= d.day && day <= d.day2
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /* ─────────────── ④ 特殊日期与奇偶年 ─────────────── */

    @Test
    fun specialDateBeatsRegular() {
        val pick = npc("卡洛琳").applicable(ctx(day = 15))
        assertTrue(
            "春 15 沙漠节当天应挑中沙漠节",
            pick.primary.any { it.label.contains("沙漠节") },
        )
        val other = npc("卡洛琳").applicable(ctx(day = 13))
        assertFalse(
            "春 13 不是沙漠节，不该挑中沙漠节",
            other.primary.any { it.label.contains("沙漠节") },
        )
    }

    @Test
    fun parityYearPicksOnlyMatchingBranch() {
        // 卡洛琳：沙漠节分「偶**数**年作为摊主」/「奇**数**年作为游客」两条。
        // ⚠ 源稿写的是「偶数年」（三个字），按「偶年」去 contains 永远匹配不上 ——
        //   这个坑就是这条测试抓出来的。
        val odd = npc("卡洛琳").applicable(ctx(day = 15, year = 1)).primary
        assertTrue("第 1 年（奇）应挑中奇数年那条", odd.any { it.cond.contains("奇数") })
        assertFalse("第 1 年不该挑中偶数年那条", odd.any { it.cond.contains("偶数") })
        val even = npc("卡洛琳").applicable(ctx(day = 15, year = 2)).primary
        assertTrue("第 2 年（偶）应挑中偶数年那条", even.any { it.cond.contains("偶数") })
        assertFalse("第 2 年不该挑中奇数年那条", even.any { it.cond.contains("奇数") })
    }

    @Test
    fun monthlyDayOnlyOnThatDay() {
        // 艾芙琳「每月2号（自己的体检日）」= 每个季的第 2 天。
        val on2 = npc("艾芙琳").applicable(ctx(day = 2)).primary
        assertTrue("春 2 应挑中每月2号", on2.any { it.label.contains("每月2号") })
        val on13 = npc("艾芙琳").applicable(ctx(day = 13)).primary
        assertFalse("春 13 不该挑中每月2号", on13.any { it.label.contains("每月2号") })
    }

    /* ─────────────── ⑤ 跨午夜 ─────────────── */

    @Test
    fun crossMidnightIsEndOfDay() {
        // 艾米丽的表最后一行是 `12:30 AM` —— 属**当日深夜延续**，排在末尾。
        val emily = npc("艾米丽")
        val v = emily.variants.firstOrNull { it.rows.any { r -> r.t == "12:30 AM" } }
        assertNotNull("艾米丽应有含 12:30 AM 的表", v)
        val last = v!!.rows.last()
        assertEquals("12:30 AM 应是当天最后一行", "12:30 AM", last.t)
        // 00:30 时：当天首行还没到 ⇒ 承接「前一晚」⇒ 命中最后一行
        assertEquals("00:30 应命中 12:30 AM 那行", last.t, v.rowAt(30)?.t)
        // 23:00 时：还没到午夜 ⇒ 命中前一行
        val prev = v.rows[v.rows.size - 2]
        assertEquals("23:00 应命中倒数第二行", prev.t, v.rowAt(23 * 60)?.t)
    }

    @Test
    fun scheduleMinuteHandlesTwelveHourClock() {
        assertEquals(0, scheduleMinute("12:00 AM"))
        assertEquals(50, scheduleMinute("12:50 AM"))
        assertEquals(720, scheduleMinute("12:00 PM"))
        assertEquals(480, scheduleMinute("8:00 AM"))
        assertEquals(780, scheduleMinute("1:00 PM"))
        assertEquals(-1, scheduleMinute("全天"))
        assertEquals(-1, scheduleMinute("白天"))
        assertEquals(-1, scheduleMinute("夜间"))
    }

    /* ─────────────── ⑥ 搜索 ─────────────── */

    @Test
    fun searchFindsByActionText() {
        val hits = searchSchedules(npcs, "皮埃尔")
        assertTrue("搜「皮埃尔」应命中若干条", hits.isNotEmpty())
        val names = npcs.map { it.name }.toSet()
        assertTrue("命中项的 NPC 名必须在名单内", hits.all { it.npc in names })
        assertTrue(
            "命中项应确实含关键词",
            hits.all { h ->
                (h.row?.a?.contains("皮埃尔") == true) ||
                    h.variant.label.contains("皮埃尔") || h.variant.cond.contains("皮埃尔")
            },
        )
    }

    @Test
    fun searchFindsFestivalByLabel() {
        val hits = searchSchedules(npcs, "沙漠节")
        assertTrue("搜「沙漠节」应命中若干条", hits.isNotEmpty())
        assertTrue("容器（分组名）不参与搜索", hits.all { !it.variant.container })
    }

    @Test
    fun searchEmptyQueryReturnsNothing() {
        assertTrue(searchSchedules(npcs, "").isEmpty())
        assertTrue(searchSchedules(npcs, "   ").isEmpty())
        assertTrue(searchSchedules(npcs, "这个关键词一定搜不到zzz").isEmpty())
    }

    /* ─────────────── ⑦ 婚后 ─────────────── */

    @Test
    fun marriedVariantsExistOnlyForRomanceable() {
        val married = npcs.map { it.name to it.marriedVariants().size }.filter { it.second > 0 }
        assertEquals("12 位可结婚对象各有婚后行程", 12, married.size)
        assertTrue(
            "婚后变体只属于可结婚对象",
            married.all { (name, _) -> name in listOf(
                "亚历克斯", "艾利欧特", "哈维", "山姆", "塞巴斯蒂安", "谢恩",
                "阿比盖尔", "艾米丽", "海莉", "莉亚", "玛鲁", "潘妮",
            ) },
        )
    }
}
