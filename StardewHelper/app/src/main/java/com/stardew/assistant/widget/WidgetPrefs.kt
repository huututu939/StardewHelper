package com.stardew.assistant.widget

import android.content.Context

/**
 * 小组件专用同步存储：设置项 + 当前存档镜像。
 * 小组件不能异步读 DataStore，App 每次存档变化时把 季节/日/年/待办数 同步写进来（SharedPreferences 同步读）。
 */
object WidgetPrefs {
    /** 内容开关：key → 设置页文案 */
    val CONTENT = listOf(
        "birthday" to "🎂 今天生日",
        "festival" to "🎪 今天节日",
        "week" to "🗓 本周事项",
        "progress" to "🌾 季节进度（14 格条）",
        "todo" to "✅ 待办完成数",
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

    fun isShown(ctx: Context, key: String): Boolean = sp(ctx).getBoolean("show_$key", true)

    fun setShow(ctx: Context, key: String, v: Boolean) {
        sp(ctx).edit().putBoolean("show_$key", v).apply()
    }

    /**
     * App 侧同步当前存档镜像（MainActivity 快照收集时调用）。
     *
     * ## c218：待办完成数**带游戏日期**
     * ⚠ 原来只存 `done` 一个数字，**不带日期** ⇒ 小组件没法判断这份完成数
     *   是「今天」的还是「昨天」的。App 关一天再开，游戏日已经滚到第 2 天，
     *   盘上那份 `done` 仍是昨天勾的数 ⇒ 小组件显示「✅ 3 / 6 待办完成」，
     *   而今天其实一条都没勾。
     *   （待办的勾选键本身**已经**按日隔离：`todo:年:季:日:文本` ——
     *   所以 App 里的数字永远是对的，**只有小组件**因为缺日期而显示错。）
     *
     * 现在同步时记下**这份完成数属于哪个游戏日**（[dateKeyOf]），
     * 渲染时与当前游戏日比对，**对不上就不显示完成数**（宁可空着，
     * 也不能拿昨天的数冒充今天的）。
     */
    fun sync(ctx: Context, season: Int, day: Int, year: Int, done: Int, total: Int) {
        sp(ctx).edit()
            .putInt("season", season).putInt("day", day).putInt("year", year)
            .putInt("done", done).putInt("total", total)
            .putString(DONE_DATE, dateKeyOf(year, season, day))
            .apply()
    }

    /**
     * 游戏日 → 可比较的字符串（`年|季|日`）。
     *
     * ⚠ 用 `|` 分隔而不是别的：与 [PrefsRepo] 的日期键同款，不会与别的字段撞。
     */
    fun dateKeyOf(year: Int, season: Int, day: Int): String = "$year|$season|$day"

    /**
     * 盘上这份待办完成数属于哪个游戏日。空串 = **从没同步过**（老版本留下的盘）。
     *
     * ⚠ 空串不是「今天」：老用户升级后盘上只有 `done` 没有 `done_date`，
     *   那一版的完成数属于**未知日期** ⇒ 按「不是今天」处理（不显示完成数），
     *   等 App 下次同步自然就准了。
     */
    fun doneDateOf(ctx: Context): String = sp(ctx).getString(DONE_DATE, "") ?: ""

    /**
     * 盘上这份待办完成数**是不是当前游戏日**的。
     *
     * @param year 当前游戏年 / season 当前游戏季 / day 当前游戏日
     * @return true = 可以显示这个完成数；false = 这是别的日期的数（跨日残留）
     */
    fun doneIsCurrent(ctx: Context, year: Int, season: Int, day: Int): Boolean =
        doneDateOf(ctx) == dateKeyOf(year, season, day)

    /**
     * 游戏日推进后把待办完成数**归零**，并把归属日期设成**新**的游戏日。
     *
     * ⚠ 为什么归零而不是只靠日期比对：日期比对能挡住「显示错数」，
     *   但盘上那份 `done` 还留着昨天的数。游戏日推进时那份数已经**没有意义**
     *   （今天的待办一条都没勾），留着它只会让下一次同步前的窗口期显示旧值。
     *
     * ⚠ 归零后**仍要带上新日期**（而不是把日期清空）：新的一天确实是「0 / N」
     *   —— 这是**如实**的，不是「还没统计过」。清空日期会让整块待办消失，
     *   而用户确实有 N 条待办要办。
     */
    fun markDoneForNewDay(ctx: Context, year: Int, season: Int, day: Int) {
        sp(ctx).edit()
            .putInt("done", 0)
            .putString(DONE_DATE, dateKeyOf(year, season, day))
            .apply()
    }

    /** 待办完成数所属的游戏日（`年|季|日`）。空串 = 没有有效的完成数。 */
    private const val DONE_DATE = "done_date"
}
