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
import com.stardew.assistant.R

/**
 * v2.86.0：Steam 云存档导入的**通知**。
 *
 * ## 两条渠道（必须分开）
 * ① **等待中**（[CHANNEL_WAITING]）：前台服务的常驻通知。
 *    Android 要求前台服务必须挂一条通知，用户才能看见「App 正在等我下载」。
 * ② **结果**（[CHANNEL_RESULT]）：导入成功 / 失败 / 需要选择。
 *    点它能直达 App（带存档深链）。
 *
 * 两条渠道**不能合并**：等待中的通知是「服务还活着」的证据，
 * 结果通知是「事情办完了」—— 合并的话用户分不清现在是哪种状态。
 *
 * ## 为什么结果通知要单独一个渠道
 * 等待中的通知是**低优先级**的常驻通知（用户不该被它打断），
 * 结果通知是**高优先级**的（用户刚下载完，正是要看结果的时候）。
 * 优先级是渠道级的属性，所以必须分渠道才能一个低一个高。
 */
object SteamNotifications {

    /** 等待下载（前台服务常驻通知）。 */
    const val CHANNEL_WAITING = "steam_import_waiting"

    /** 导入结果。 */
    const val CHANNEL_RESULT = "steam_import"

    /** 等待中通知的 id。 */
    const val ID_WAITING = 4101

    /** 结果通知的 id。 */
    const val ID_RESULT = 4102

    /** 前台服务的通知 id（必须与 [ID_WAITING] 不同，否则会互相覆盖）。 */
    const val ID_FOREGROUND = 4103

    /**
     * 建渠道。
     *
     * ⚠ Android 8+ 必须先建渠道才能发通知；渠道建好后**不能改重要性**
     *   （系统会记住首次创建时的值），所以这里一次建对。
     */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // 等待中：低优先级，不打扰
        if (nm.getNotificationChannel(CHANNEL_WAITING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WAITING,
                    "Steam 云存档等待中",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "下载云存档期间显示的常驻提示"
                    setShowBadge(false)
                },
            )
        }
        // 结果：高优先级，用户刚下完，正要看
        if (nm.getNotificationChannel(CHANNEL_RESULT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULT,
                    "Steam 云存档导入结果",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "存档导入完成或失败时的提醒"
                },
            )
        }
    }

    /** 前台服务的常驻通知。 */
    fun waitingNotification(ctx: Context): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_WAITING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在等待 Steam 云存档下载…")
            .setContentText("下载完成后会自动识别并导入")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)          // 常驻，用户划不掉（前台服务要求）
            .setContentIntent(openApp(ctx, null))
            .build()
    }

    /**
     * 导入成功。
     *
     * @param farmName 农场名（null = 未知）
     * @param deepLink 存档位 id（null = 只开 App 首页）
     */
    fun successNotification(ctx: Context, farmName: String?, deepLink: String?): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Steam 云存档已导入")
            .setContentText(if (farmName.isNullOrBlank()) "点击查看" else "$farmName，点击查看")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, deepLink))
            .build()
    }

    /** 识别失败 / 需要用户选择。 */
    fun failureNotification(ctx: Context, why: String, deepLink: String?): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("未识别到有效主存档")
            .setContentText(why.ifBlank { "点此查看帮助" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, deepLink))
            .build()
    }

    /** 需要用户从多个候选里选一个。 */
    fun chooseNotification(ctx: Context, count: Int, deepLink: String?): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("找到 $count 个 Steam 存档")
            .setContentText("点此选择要导入哪一个")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, deepLink))
            .build()
    }

    /**
     * 打开 App 的 PendingIntent。
     *
     * ⚠ **必须带 `FLAG_IMMUTABLE`**（Android 12+ 强制）：通知里的 PendingIntent
     *   是从别的 App 可达的攻击面，不可变才能挡住别人改 extras。
     */
    private fun openApp(ctx: Context, deepLink: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!deepLink.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_STEAM_DEEP_LINK, deepLink)
            }
        }
        return PendingIntent.getActivity(
            ctx,
            if (deepLink.isNullOrBlank()) 0 else deepLink.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 发一条通知。 */
    fun notify(ctx: Context, id: Int, notification: Notification) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(id, notification) }
    }
}
