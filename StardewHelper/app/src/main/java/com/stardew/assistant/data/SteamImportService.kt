package com.stardew.assistant.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v2.86.0：等待 Steam 云存档下载的**前台服务**。
 *
 * ## 为什么需要它
 * 用户点了「打开 Steam 云存档页」之后，人就跑到浏览器里去了 —— 这时 App 在后台，
 * 页面上的 observer 还在，但**进程随时可能被系统杀掉**（尤其是息屏一段时间后）。
 * 进程一死，observer 就没了，用户下完存档回到 App 时只能手动选文件 ——
 * 那就违背了「点完下载就全自动」的初衷。
 *
 * 前台服务让 App 在这段「用户不在」的时段里**活着**（有常驻通知，用户看得见）。
 *
 * ## 三条退出路径
 * ① **导入完成** → 停；
 * ② **超时**（[TIMEOUT_MS]，默认 10 分钟）→ 停，并通知用户；
 * ③ **用户手动停止**（通知上的「停止」按钮 / 引导页的按钮）→ 停。
 *
 * ## 为什么用 `dataSync` 类型
 * Android 14（API 34）起前台服务必须声明 `foregroundServiceType`。
 * 「等一个文件下载完并导入」正是 `dataSync` 的语义。
 * ⚠ 若商店审核对该类型有限制，可改 `specialUse` 并在清单里写明用途
 *   —— 但 `dataSync` 的语义匹配度更高，优先用它。
 *
 * ## 合规
 * 本服务**不联网**。它只做两件事：盯着下载目录、文件到了就复制进 App 私有目录。
 * Steam 账号环节全部在官网里由用户本人完成。
 */
class SteamImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observers: List<ContentObserver> = emptyList()
    private var watchJob: Job? = null
    private var timeoutJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SteamNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠ Android 8+ 必须在 5 秒内 startForeground，否则系统会 ANR 并抛异常。
        //   所以 startForeground 放在最前面，不等任何异步操作。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SteamNotifications.ID_FOREGROUND,
                SteamNotifications.waitingNotification(this),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(
                SteamNotifications.ID_FOREGROUND,
                SteamNotifications.waitingNotification(this),
            )
        }

        // 已经在跑了就不重复起 observer
        if (watchJob?.isActive == true) return START_STICKY

        startWatching()
        return START_STICKY
    }

    /**
     * 开始盯下载目录。
     *
     * ① 注册 observer（文件一变就扫）
     * ② 同时**立即扫一次**（用户可能在下 App 之前就下完了）
     * ③ 定期回扫（observer 可能漏报 —— 尤其跨进程时）
     */
    private fun startWatching() {
        // ① observer
        observers = SteamDownloadDir.observe(this) { triggerScan() }

        // ② 立即扫一次
        triggerScan()

        // ③ 定期回扫（30 秒一次）。observer 是「优化」，回扫才是「保证」。
        watchJob = scope.launch {
            while (true) {
                delay(SCAN_INTERVAL_MS)
                triggerScan()
            }
        }

        // 超时兜底
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            Log.i(TAG, "等待超时，停止服务")
            SteamNotifications.notify(
                this@SteamImportService,
                SteamNotifications.ID_RESULT,
                SteamNotifications.failureNotification(
                    this@SteamImportService,
                    "等待下载超时了。如果已经下载完，回 App 手动选一下文件也行。",
                    null,
                ),
            )
            stopSelf()
        }
    }

    /**
     * 扫一次下载目录。
     *
     * ⚠ 只处理「**已处理记录里没有**」的文件 —— 否则每 30 秒的定期回扫
     *   会把同一个文件反复导入一遍、反复弹通知。
     */
    private fun triggerScan() {
        val candidates = SteamDownloadDir.scan(this, onlyMain = true)
        if (candidates.isEmpty()) return
        val fresh = candidates.filter { !SteamImportCoordinator.isProcessed(this, it) }
        if (fresh.isEmpty()) return

        if (fresh.size > 1) {
            // 多个候选：通知用户选，不静默导入。
            SteamNotifications.notify(
                this,
                SteamNotifications.ID_RESULT,
                SteamNotifications.chooseNotification(this, fresh.size, null),
            )
            stopSelf()
            return
        }

        // 唯一候选：直接导入。
        val candidate = fresh[0]
        SteamImportCoordinator.importOne(this, scope, candidate)

        // 导入是异步的；这里等状态落定再停服务。
        scope.launch {
            val deadline = System.currentTimeMillis() + IMPORT_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(300)
                val st = SteamImportCoordinator.state.value
                if (st is SteamImportCoordinator.State.Done) {
                    SteamNotifications.notify(
                        this@SteamImportService,
                        SteamNotifications.ID_RESULT,
                        SteamNotifications.successNotification(
                            this@SteamImportService,
                            st.farmName,
                            st.profileId,
                        ),
                    )
                    stopSelf()
                    return@launch
                }
                if (st is SteamImportCoordinator.State.Failed) {
                    SteamNotifications.notify(
                        this@SteamImportService,
                        SteamNotifications.ID_RESULT,
                        SteamNotifications.failureNotification(
                            this@SteamImportService,
                            st.reason,
                            null,
                        ),
                    )
                    stopSelf()
                    return@launch
                }
            }
            // 超时没结果：交给用户手动（回 App 选文件）
            Log.w(TAG, "导入等待超时")
            stopSelf()
        }
    }

    override fun onDestroy() {
        SteamDownloadDir.unobserve(this, observers)
        observers = emptyList()
        watchJob?.cancel()
        timeoutJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SteamImportService"

        /** 等待总超时（毫秒）—— 10 分钟。 */
        const val TIMEOUT_MS = 10 * 60 * 1000L

        /** 定期回扫间隔（毫秒）。 */
        const val SCAN_INTERVAL_MS = 30 * 1000L

        /** 等导入结果的最长时间（毫秒）。 */
        const val IMPORT_WAIT_MS = 120 * 1000L

        /**
         * 启动服务。
         *
         * ⚠ Android 12+ 启动前台服务必须**在 App 在前台时**调用（后台启动受限）。
         *   我们的调用点就在用户点按钮的那一刻 —— App 在前台，合规。
         */
        fun start(ctx: Context) {
            val intent = Intent(ctx, SteamImportService::class.java)
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
            runCatching { ctx.stopService(Intent(ctx, SteamImportService::class.java)) }
        }
    }
}
