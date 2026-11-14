package com.stardew.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.stardew.assistant.R
import java.util.Calendar

/** 刷新/闹钟统一入口：三个规格的小组件一起刷 */
object WidgetUpdater {

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val snap = WidgetRender.buildSnapshot(context)

        TodayWidget::class.java.let { cls ->
            manager.getAppWidgetIds(ComponentName(context, cls)).takeIf { it.isNotEmpty() }?.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_today)
                WidgetRender.fillLines(context, views, snap)
                manager.updateAppWidget(id, views)
            }
        }
        CardWidget::class.java.let { cls ->
            manager.getAppWidgetIds(ComponentName(context, cls)).takeIf { it.isNotEmpty() }?.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_card)
                WidgetRender.fillCard(context, views, snap)
                manager.updateAppWidget(id, views)
            }
        }
        WideWidget::class.java.let { cls ->
            manager.getAppWidgetIds(ComponentName(context, cls)).takeIf { it.isNotEmpty() }?.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_wide)
                WidgetRender.fillLines(context, views, snap)
                manager.updateAppWidget(id, views)
            }
        }
    }

    /** 兼容旧入口（MainActivity 调用） */
    fun refresh(context: Context) = refreshAll(context)

    /** 每天真实 06:00 刷新一次（游戏日切点） */
    fun scheduleNextDay(context: Context) {
        val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        am.setAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis,
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, TodayWidget::class.java).setAction(TodayWidget.ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }
}

/** 2×2 方卡：标题 + 日期 + 首条内容 + 大格进度条 */
class CardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snap = WidgetRender.buildSnapshot(context)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_card)
            WidgetRender.fillCard(context, views, snap)
            manager.updateAppWidget(id, views)
        }
    }
}

/** 4×2 宽条：标题 + 日期 + 3 行内容 */
class WideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snap = WidgetRender.buildSnapshot(context)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_wide)
            WidgetRender.fillLines(context, views, snap)
            manager.updateAppWidget(id, views)
        }
    }
}
