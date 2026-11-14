package com.stardew.assistant.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.stardew.assistant.MainActivity

/**
 * c233：局域网接收会话的**通知**。
 *
 * ## 为什么单独一套渠道（不蹭 Steam 那套）
 * Steam 的两条渠道是「等待下载」与「导入结果」，语义都挂在 Steam 云存档上；
 * 接收会话的常驻通知说的是另一件事（「我在等电脑推存档」）。
 * 两条渠道混在一起，用户在通知栏里看到「正在等待 Steam 云存档下载」，
 * 却是我们的局域网接收——那是**骗人**。渠道名是用户看得见的文字，必须说实话。
 *
 * ## 一条就够
 * 常驻通知只说明「服务活着、正在收」，不做结果播报（结果走 Toast 与 App 内状态）。
 * 所以这里只有一条渠道、一条常驻通知。
 */
object LanNotifications {

    /** 接收会话常驻通知的渠道。 */
    const val CHANNEL_LISTENING = "lan_receive_listening"

    /** 前台服务的通知 id（与 Steam 的 4103 不重复，避免互相覆盖）。 */
    const val ID_FOREGROUND = 4201

    /**
     * 建渠道。
     *
     * ⚠ Android 8+ 必须先建渠道才能发通知；渠道建好后**不能改重要性**
     *   （系统会记住首次创建时的值），所以这里一次建对。
     */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_LISTENING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LISTENING,
                    "局域网接收存档",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "开启后台常驻接收后显示的常驻提示"
                    setShowBadge(false)
                },
            )
        }
    }

    /** 前台服务的常驻通知。 */
    fun listeningNotification(ctx: Context): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_LISTENING)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("正在等电脑推送存档…")
            .setContentText("同一个 WiFi 下，电脑保存后会自动推过来")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)          // 常驻，用户划不掉（前台服务要求）
            .setContentIntent(openApp(ctx))
            // 通知上给一个「停止接收」的出口：常驻通知挡着通知栏，
            // 用户想关掉这个开关时，不该非得先回 App 进设置页。
            .addAction(0, "停止接收", stopPendingIntent(ctx))
            .build()
    }

    /**
     * 通知上的「停止接收」按钮。
     *
     * ⚠ **必须带 `FLAG_IMMUTABLE`**（Android 12+ 强制）。
     *   动作是 Service 的 Intent（不是 Activity），同样从别的 App 可达。
     */
    private fun stopPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, LanReceiveService::class.java).apply {
            action = LanReceiveService.ACTION_STOP
        }
        return PendingIntent.getService(
            ctx,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 打开 App 的 PendingIntent。
     *
     * ⚠ **必须带 `FLAG_IMMUTABLE`**（Android 12+ 强制）：通知里的 PendingIntent
     *   是从别的 App 可达的攻击面，不可变才能挡住别人改 extras。
     */
    private fun openApp(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
