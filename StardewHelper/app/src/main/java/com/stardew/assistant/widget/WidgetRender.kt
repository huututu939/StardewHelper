package com.stardew.assistant.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.stardew.assistant.MainActivity
import com.stardew.assistant.R
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.Season
import com.stardew.assistant.data.villagerPresent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 小组件共享渲染引擎（v2.6.1）：三个规格（4×1 / 2×2 / 4×2）共用一份内容与配色计算。
 * 像素木框 + 内衬季节背景 + fusion_pixel 字体（布局里声明）+ 文字阴影。
 */
object WidgetRender {

    /** 每个内容块的行数据（标签、正文、点击跳哪个页面） */
    data class Block(val label: String, val body: String, val page: String, val alert: Boolean = false)

    /** 季节配色（与 App SeasonColors 同一套）：ink / inkDim / gold / red。
     *  v2.136.0：bg 字段移除 —— 衬底统一纯白（布局已设），季节只管文字色。 */
    data class Palette(val ink: Int, val inkDim: Int, val gold: Int, val red: Int)

    fun palette(season: Int) = when (season) {
        0 -> Palette(0xFF33421A.toInt(), 0xFF6B7E42.toInt(), 0xFFE86FA4.toInt(), 0xFFC44538.toInt())
        1 -> Palette(0xFF4A2C12.toInt(), 0xFF8A6538.toInt(), 0xFFF0A229.toInt(), 0xFFC44538.toInt())
        2 -> Palette(0xFF3E2210.toInt(), 0xFF8A5A28.toInt(), 0xFFC6412C.toInt(), 0xFFC44538.toInt())
        else -> Palette(0xFF26303C.toInt(), 0xFF5A6B80.toInt(), 0xFF4A7FB5.toInt(), 0xFFC44538.toInt())
    }

    /** 快照：游戏日期 + 各内容块 */
    data class Snap(
        val season: Int, val day: Int, val year: Int,
        val title: String, val date: String,
        val blocks: List<Block>, val progress: Pair<Int, Int>?, // (第 N 天, 28)
        /**
         * v2.89.0：标题右边的「▸ 明天」按钮文案。
         * null = 不显示这个按钮（日期还没跟 App 同步，或已到年份上限）。
         */
        val nextLabel: String? = null,
    )

    // ── v2.17 E2：冷加载不再堵主线程 ─────────────────────────────
    // 小组件 onUpdate/onReceive 跑在主线程，而 assets/data 合计约 530KB JSON，
    // 进程未开过 App 时首个 06:00 闹钟刷新会同步解析几百毫秒。改为后台单线程加载，
    // 未就绪时先渲染占位，加载完自动再刷一轮（loadAttempted 保证只试一次不循环）。
    private val loadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "widget-data-load").apply { isDaemon = true }
    }
    @Volatile private var loading = false
    @Volatile private var loadAttempted = false

    private fun ensureLoadedAsync(ctx: Context) {
        if (GameData.ready || loading || loadAttempted) return
        loadAttempted = true
        loading = true
        val app = ctx.applicationContext
        loadExecutor.execute {
            runCatching { GameData.load(app) }
            loading = false
            runCatching { WidgetUpdater.refreshAll(app) }
        }
    }

    fun buildSnapshot(ctx: Context): Snap {
        if (!GameData.ready) {
            ensureLoadedAsync(ctx)
            return Snap(
                0, 1, 1, "🌱 星露小助手", "数据加载中…",
                listOf(Block("⏳", "正在加载游戏数据，稍后自动刷新…", "today")),
                null,
            )
        }
        val sp = ctx.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val season = sp.getInt("season", -1)
        val day = sp.getInt("day", 1)
        val year = sp.getInt("year", 1)
        // 尚未从 App 同步过：按真实月份近似季节兜底
        val fallBack = season !in 0..3
        val (effSeason, effDay, effYear) = if (fallBack) {
            val cal = Calendar.getInstance()
            val month = cal.get(Calendar.MONTH) + 1
            val s = when (month) { in 3..5 -> 0; in 6..8 -> 1; in 9..11 -> 2; else -> 3 }
            Triple(s, ((cal.get(Calendar.DAY_OF_YEAR) - 1).mod(28)) + 1, 1)
        } else Triple(season, day, year)

        val seasonName = Season.names[effSeason]
        val todayReal = SimpleDateFormat("M月d日", Locale.CHINESE).format(Calendar.getInstance().time)
        val seasonIcon = listOf("🌱", "☀️", "🍂", "❄️")[effSeason]

        // v2.38：生日要判「当天他在不在农场」——肯特第 1 年在部队服役，
        // 第 1 年春 4 不该报他生日（fallBack 分支的 effYear 恒为 1，同理）。
        val birthdays = GameData.gifts
            .filter { it.birthdaySeason == seasonName && it.birthdayDay == effDay }
            .filter { villagerPresent(it.name, effYear, effSeason, effDay) }
        // v2.17 A12：节日按 start..end 区间判定（旧实现只比首日 → 多日节日第 2 天起小组件漏报，每年漏 6 天）
        val festivals = GameData.festivals.filter { f ->
            f.season == seasonName && f.startDay != null && effDay in f.startDay!!..(f.endDay ?: f.startDay!!)
        }
        val dow = (effDay - 1) % 7
        val weekDay = GameData.weekLoop.getOrNull(dow)
        val done = sp.getInt("done", -1)
        val total = sp.getInt("total", 6)
        // ⚠ **c218：完成数必须带日期，否则会显示昨天的数**
        //   待办的勾选键本身按日隔离（todo:年:季:日:文本），所以 App 里的数永远对；
        //   小组件读的却是盘上那份镜像，**不带日期** ⇒ 游戏日滚过去之后，
        //   盘上那份 done 还是昨天勾的数（App 关一天再开就复现）。
        //   判定：这份完成数属于哪个游戏日（WidgetPrefs.doneDateOf）？
        //   与当前游戏日对不上 ⇒ **不显示完成数**（宁可空着，也不拿昨天的数冒充今天）。
        val doneCurrent = WidgetPrefs.doneIsCurrent(ctx, effYear, effSeason, effDay)
        val doneToday = if (doneCurrent) done else -1

        val blocks = mutableListOf<Block>()
        if (WidgetPrefs.isShown(ctx, "festival") && festivals.isNotEmpty()) {
            blocks += Block("🎪", festivals.joinToString("、") { it.name }, "today", alert = true)
        }
        if (WidgetPrefs.isShown(ctx, "birthday") && birthdays.isNotEmpty()) {
            blocks += Block("🎂", birthdays.joinToString("、") { it.name }, "villagers", alert = true)
        }
        if (WidgetPrefs.isShown(ctx, "week") && weekDay != null) {
            // v2.17 A2：按结构化 closedDays 判定（旧 when(dow) 硬编码错位，与今日页同修）
            val closed = GameData.shopHours.filter { it.closedDays.contains(weekDay.day) }
            var body = "${weekDay.day} · " + weekDay.items.take(2).joinToString("、")
            if (closed.isNotEmpty()) body += "｜闭店：" + closed.joinToString("、") { it.name }
            blocks += Block("🗓", body, "today", alert = closed.isNotEmpty())
        }
        if (WidgetPrefs.isShown(ctx, "todo")) {
            // doneToday = -1 = 这份完成数不是今天的（或从没同步过）⇒ 整块不出现。
            // ⚠ 原来只在 `done >= 0` 时显示，内容恒为「done / total」。
            //   现在跨日残留时**连块都不出现**（不是显示「0 / 6」——
            //   「0」在说「你今天一条都没勾」，而真相是「今天还没统计过」，两回事）。
            if (doneToday >= 0) {
                blocks += Block("✅", "$doneToday / $total 待办完成", "today")
            }
        }
        if (blocks.isEmpty()) blocks += Block("🌱", "今天没有生日和节日，点开看详情", "today")

        val progress = if (WidgetPrefs.isShown(ctx, "progress")) effDay to 28 else null
        val dateLine = if (fallBack) "$todayReal · 打开 App 同步存档日期" else "现实 $todayReal"
        // v2.89.0：日期跟 App 同步过才给「明天」按钮（没同步时推进的是假日期，推了也白推）。
        val nextLabel = if (fallBack) null else nextDayLabel(effYear, effSeason, effDay)
        return Snap(
            effSeason, effDay, effYear, "$seasonIcon $seasonName $effDay · 第 $effYear 年",
            dateLine, blocks, progress, nextLabel,
        )
    }

    /**
     * v2.89.0：游戏日期的**下一天**。
     *
     * 一年四季各 28 天：冬天第 28 天之后是**下一年春天第 1 天**。
     * 到第 10 年（游戏里的年份上限）就不再往后推了 —— 返回 null。
     *
     * @return (年, 季, 日)，下标与 [Season.names] 一致（0 春 1 夏 2 秋 3 冬）
     */
    fun nextDay(year: Int, season: Int, day: Int): Triple<Int, Int, Int>? {
        if (day < 28) return Triple(year, season, day + 1)
        if (season < 3) return Triple(year, season + 1, 1)
        if (year >= MAX_YEAR) return null
        return Triple(year + 1, 0, 1)
    }

    /** 游戏里的年份上限：第 10 年之后不再推进。 */
    const val MAX_YEAR = 10

    /** 「明天」按钮的文案。 */
    private fun nextDayLabel(year: Int, season: Int, day: Int): String? =
        if (nextDay(year, season, day) != null) "▸ 明天" else null

    fun pageIntent(ctx: Context, page: String): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).putExtra("open_page", page)
        val code = if (page == "villagers") 2 else 1
        return PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressBar(day: Int, total: Int): String {
        val filled = ((day - 1) * 14 / total) + 1
        val sb = StringBuilder()
        repeat(14) { i -> sb.append(if (i < filled) "▰" else "▱") }
        return sb.toString()
    }

    /** 公共头部：衬底纯白（v2.136.0 起，代码不再按季染色）+ 标题 + 日期 + 点击兜底 */
    fun fillHeader(ctx: Context, views: RemoteViews, snap: Snap) {
        val p = palette(snap.season)
        views.setTextColor(R.id.w_title, p.ink)
        views.setTextColor(R.id.w_date, p.inkDim)
        views.setTextViewText(R.id.w_title, snap.title)
        views.setTextViewText(R.id.w_date, snap.date)
        views.setOnClickPendingIntent(R.id.w_root, pageIntent(ctx, "today"))
        fillNextButton(ctx, views, snap, p)
    }

    /**
     * v2.89.0：标题右边的「▸ 明天」按钮。
     *
     * 走 **广播**（不是 Activity）：小组件上点一下就把日期往后推一天，
     * 不用先打开 App。广播由 [TodayWidget] 接住。
     */
    private fun fillNextButton(ctx: Context, views: RemoteViews, snap: Snap, p: Palette) {
        val label = snap.nextLabel
        if (label == null) {
            views.setViewVisibility(R.id.w_next, View.GONE)
            return
        }
        views.setViewVisibility(R.id.w_next, View.VISIBLE)
        views.setTextViewText(R.id.w_next, label)
        views.setTextColor(R.id.w_next, p.ink)
        val intent = Intent(ctx, TodayWidget::class.java).setAction(TodayWidget.ACTION_NEXT_DAY)
        views.setOnClickPendingIntent(
            R.id.w_next,
            PendingIntent.getBroadcast(
                ctx, 3, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /** 4×1 细条 / 4×2 宽条：标题 + 日期 + 最多 3 行内容（+ 可选进度条行） */
    fun fillLines(ctx: Context, views: RemoteViews, snap: Snap) {
        fillHeader(ctx, views, snap)
        val p = palette(snap.season)
        val slots = listOf(R.id.w_line1, R.id.w_line2, R.id.w_line3)
        slots.forEach { views.setViewVisibility(it, View.GONE) }
        snap.blocks.take(3).forEachIndexed { i, b ->
            views.setViewVisibility(slots[i], View.VISIBLE)
            views.setTextViewText(slots[i], "${b.label} ${b.body}")
            views.setTextColor(slots[i], if (b.alert) p.red else p.ink)
            views.setOnClickPendingIntent(slots[i], pageIntent(ctx, b.page))
        }
        if (snap.blocks.size > 3) {
            views.setViewVisibility(slots[2], View.VISIBLE)
            views.setTextViewText(slots[2], "＋还有 ${snap.blocks.size - 3} 条，点开查看")
            views.setTextColor(slots[2], p.inkDim)
            views.setOnClickPendingIntent(slots[2], pageIntent(ctx, "today"))
        }
        fillProgressRow(ctx, views, snap)
    }

    /** 4×1/4×2 的进度条行（若有） */
    fun fillProgressRow(ctx: Context, views: RemoteViews, snap: Snap) {
        val p = palette(snap.season)
        snap.progress?.let { (d, total) ->
            views.setViewVisibility(R.id.w_progress_row, View.VISIBLE)
            views.setTextViewText(R.id.w_progress_bar, progressBar(d, total))
            views.setTextColor(R.id.w_progress_bar, p.gold)
            views.setTextViewText(R.id.w_progress_label, "第 $d / $total 天")
            views.setTextColor(R.id.w_progress_label, p.inkDim)
            views.setOnClickPendingIntent(R.id.w_progress_row, pageIntent(ctx, "today"))
        } ?: views.setViewVisibility(R.id.w_progress_row, View.GONE)
    }

    /** 2×2 方卡：标题 + 日期 + 1 行内容 + 大格进度条 */
    fun fillCard(ctx: Context, views: RemoteViews, snap: Snap) {
        fillHeader(ctx, views, snap)
        val p = palette(snap.season)
        val b = snap.blocks.first()
        views.setTextViewText(R.id.w_line1, "${b.label} ${b.body}")
        views.setTextColor(R.id.w_line1, if (b.alert) p.red else p.ink)
        views.setOnClickPendingIntent(R.id.w_line1, pageIntent(ctx, b.page))
        snap.progress?.let { (d, total) ->
            views.setViewVisibility(R.id.w_progress_row, View.VISIBLE)
            views.setTextViewText(R.id.w_progress_bar, progressBar(d, total))
            views.setTextColor(R.id.w_progress_bar, p.gold)
            views.setTextViewText(R.id.w_progress_label, "第 $d / $total 天")
            views.setTextColor(R.id.w_progress_label, p.inkDim)
        } ?: views.setViewVisibility(R.id.w_progress_row, View.GONE)
    }
}
