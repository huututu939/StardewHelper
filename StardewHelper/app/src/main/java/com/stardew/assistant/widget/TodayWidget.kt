package com.stardew.assistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.stardew.assistant.R
import com.stardew.assistant.data.PrefsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 今日小组件 v2.6.1（4×1 细条）：与 2×2/4×2 三个规格共用 WidgetRender 渲染引擎。
 * 像素木框 + 季节配色 + fusion_pixel 字体；点组件打开 app（生日区直达村民页）。
 *
 * v2.89.0：标题右边多了「▸ 明天」按钮 —— 点一下把游戏日期往后推一天。
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snap = WidgetRender.buildSnapshot(context)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            WidgetRender.fillLines(context, views, snap)
            manager.updateAppWidget(id, views)
        }
    }

    override fun onEnabled(context: Context) {
        scheduleNextDay(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // ⚠ 「明天」要先把日期推完再刷，否则刷出来还是今天。
        if (intent.action == Companion.ACTION_NEXT_DAY) {
            advanceToNextDay(context)
            return
        }
        WidgetUpdater.refreshAll(context)
        if (intent.action == Companion.ACTION_MIDNIGHT) scheduleNextDay(context)
    }

    /**
     * v2.89.0：把游戏日期往后推一天。
     *
     * ## 推的是 App 里的日期，不只是小组件自己的副本
     * 小组件显示的日期来自 `widget_prefs`（App 同步进去的镜像）。
     * 只改 `widget_prefs` 的话，小组件写着「明天」而 App 还停在今天，两边对不上；
     * 所以这里先 [PrefsRepo.setDate] 改**真正的**游戏日期，再把小组件的镜像跟上。
     *
     * ## 为什么在协程里
     * 广播的 `onReceive` 是同步的，而读 DataStore（[PrefsRepo.current]）是 suspend。
     * `goAsync()` 告诉系统「这条广播我还没处理完」，等协程跑完再 `finish()`。
     */
    private fun advanceToNextDay(context: Context) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = PrefsRepo(app)
                val snap = repo.current()
                val next = WidgetRender.nextDay(snap.year, snap.season, snap.day)
                if (next == null) {
                    Log.w(TAG, "已经到年份上限，不推进")
                } else {
                    val (y, s, d) = next
                    repo.setDate(y, s, d)
                    // 小组件自己的镜像同步跟上（App 下次启动也会再同步一次）
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt("season", s).putInt("day", d).putInt("year", y)
                        .apply()
                    // ⚠ **c218：游戏日推进 ⇒ 待办完成数归零（并改挂到新日期）**
                    //   盘上那份 done 是**上一个游戏日**勾出来的数（待办勾选键按日隔离：
                    //   todo:年:季:日:文本）。日期推进后它已经失效，
                    //   不清就会在小组件上显示「昨天的完成数」冒充今天的。
                    //   归零后仍挂新日期：新的一天确实是「0 / N」——这是**如实**的。
                    //   渲染层还有一道日期比对兜底（WidgetRender 里 doneIsCurrent）。
                    WidgetPrefs.markDoneForNewDay(app, y, s, d)
                    WidgetUpdater.refreshAll(app)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "切到下一天失败：${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WidgetUpdater"

        /** 小组件与 App 共用的镜像存储名（见 [WidgetPrefs]）。 */
        private const val PREFS = "widget_prefs"

        const val ACTION_MIDNIGHT = "com.stardew.assistant.WIDGET_MIDNIGHT"

        /** v2.89.0：点「▸ 明天」。 */
        const val ACTION_NEXT_DAY = "com.stardew.assistant.WIDGET_NEXT_DAY"

        /** 每天真实 06:00 刷新一次（游戏日切点） */
        fun scheduleNextDay(context: Context) = WidgetUpdater.scheduleNextDay(context)
    }
}
