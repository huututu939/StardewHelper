package com.stardew.assistant.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * c234：局域网接收会话的**前台服务**（可选，默认关）。
 *
 * ## 它为什么必须存在（c233 轮实测踩到的坑）
 * c232 的常驻自动监听活在 **App 进程**里；c233 给它加了前台服务保活，用户反馈
 * 「常驻通知在，但电脑说没扫描到手机」。实测（魅族 20 / Flyme）查明了机理：
 * - 进程**没被杀**（服务在、通知在、线程还在）；
 * - 但进程**被冻结**了：`dumpsys activity processes` 里 `isFrozen=4`、
 *   `isFreezeExempt=false` —— 线程不被调度，UDP 发现不回应，
 *   连监听端口在 `/proc/net/tcp` 里都整个消失；
 * - 加进**电池优化白名单**后，同一台机、同一 App，只切这一个开关：
 *   退后台 60 秒 → 正常；息屏 90 秒 → 正常；不加 → 退后台 90 秒 → 无回应。
 *
 * ⚠ 所以**前台服务保的是「进程不被杀」，电池优化白名单保的是「进程不被冻结」**——
 *   两把钥匙缺一不可。c233 只有前一把，这就是「通知在但收不到」的完整机理。
 *   白名单的申请在 [BatteryHelper]，UI 引导在设置页「后台常驻」卡。
 *
 * ## c234：会话归**服务**管（不是归 UI 进程）
 * 原来会话由 [LanReceiveController]（UI 进程里的单例）持有，服务只是挂个通知。
 * 现在会话的**拥有者换成这个服务**：
 * - 服务起来 → [LanReceiveController.ensureSessionForService] 建立/接管会话；
 * - 进程被系统杀掉后，服务被重建（START_STICKY）→ 会话**自动重建**，
 *   用户不用回 App 重新点「开始接收」。
 *
 * ⚠ 服务与 UI **同进程**（刻意如此）：DataStore 不支持多进程，
 *   拆成独立进程 (`android:process`) 会让两边的 DataStore 写入互相覆盖。
 *   跨进程的「UI 被杀但服务还在」我们**不做**——那要动存储层，不在这轮的范围内。
 *
 * ## 为什么用 `dataSync` 类型
 * 与 [SteamImportService] 同款语义（等待/传输数据），Android 14+ 必需声明类型。
 *
 * ## 合规
 * **不联网**：本服务只保活 [SaveReceiver] 的局域网监听（只收不传），不发起任何外部请求。
 * 它本身不发网络包——网络行为全在 [SaveReceiver]（只监听本地端口、只收 zip）。
 */
class LanReceiveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LanNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知上的「停止接收」按钮：直接停掉整个接收会话。
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested from notification")
            LanReceiveController.stop("已从通知停止接收")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // ⚠ Android 8+ 必须在 5 秒内 startForeground，否则系统会 ANR 并抛异常。
        //   所以 startForeground 放在最前面，不等任何异步操作。
        val notification = LanNotifications.listeningNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                LanNotifications.ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(LanNotifications.ID_FOREGROUND, notification)
        }

        // c234：会话归服务管。已有会话就接管（UI 进程还在时起的服务走这条），
        // 没有就新建（进程被杀后服务被系统重建时走这条）。
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, true) ?: true
        LanReceiveController.ensureSessionForService(applicationContext, auto)

        // ⚠ START_STICKY：进程被杀后系统会重建这个服务 → 上面的 ensureSession
        //   会把会话重建起来。c233 之所以用 START_NOT_STICKY，是因为那时会话
        //   归 UI 进程管，重建服务只会挂一条「空壳通知」（用户还以为在收）。
        //   现在会话归服务管，重建 = 真的重建，不是空壳。
        return START_STICKY
    }

    /**
     * Android 15（API 35）起，`dataSync` 类型前台服务有**每日 6 小时上限**，
     * 到了上限系统会调这个回调把服务停掉。
     *
     * ⚠ 必须**如实告诉用户**：这时候监听端口已经没了，电脑再推送会扑空。
     *   悄悄死掉的话，用户只会觉得「又不行了」。所以这里明确收摊 + 提示。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.i(TAG, "dataSync foreground timeout reached")
        LanReceiveController.stop("后台常驻已达系统时长上限，接收已停")
        stopSelf(startId)
    }

    companion object {
        private const val TAG = "LanReceiveService"

        /** 通知上「停止接收」按钮的动作。 */
        const val ACTION_STOP = "com.stardew.assistant.action.STOP_LAN_RECEIVE"

        /** 意图 extra：这次要建的是不是「自动会话」（有信任设备的常驻监听）。 */
        const val EXTRA_AUTO = "auto"

        /**
         * 启动服务。
         *
         * ⚠ Android 12+ 启动前台服务必须**在 App 在前台时**调用（后台启动受限）。
         *   我们的调用点在 [LanReceiveController] 起会话的那一刻——App 在前台，合规。
         */
        fun start(ctx: Context, auto: Boolean = true) {
            val intent = Intent(ctx, LanReceiveService::class.java).apply {
                putExtra(EXTRA_AUTO, auto)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "启动前台服务失败：${it.message}")
            }
        }

        /** 停止服务。 */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LanReceiveService::class.java)) }
        }
    }
}
