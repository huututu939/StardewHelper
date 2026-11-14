package com.stardew.assistant.data

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * c232：局域网接收的验证码弹窗数据（原 SettingsPage 私有类，收进控制台供全局宿主渲染）。
 *
 * @param attemptsLeft 本窗口还剩几次错误机会（全局计数，不是每连接各算）。
 * @param wrong 上一次是不是输错了——用来显示行内提示。
 * @param submit 把用户的回答交回服务端（那边才知道正确答案）。
 */
data class CodePrompt(
    val attemptsLeft: Int,
    val wrong: Boolean,
    val submit: (SaveReceiver.CodeReply) -> Unit,
)

/**
 * c240：自动会话连续重建失败多少次后**放弃**自动重建。
 *
 * ⚠ 为什么要有上限：自愈是「会话停了 → 重建」，但如果**重建本身就失败**
 *   （端口一直被占、网卡还没起来），就会变成「起 → 停 → 起 → 停」的死循环，
 *   白耗电、白刷通知。⇒ 连续失败超过这个次数就**不再自动重建**，
 *   改为如实告诉用户「接收没能起来，请打开 App」。
 */
private const val MAX_AUTO_REBUILD = 3

/**
 * c232（v2.142.0）：局域网接收的**进程级**控制台。
 *
 * ## 为什么要它
 * 「自动同步」的最后一环：只要配过对（存在信任设备），**App 一启动就该能收**，
 * 不能要求用户每次先去设置页点「开始接收」——那个窗口还有 5 分钟空闲自杀，
 * 用户睡个午觉回来端口就没了（c231 轮实测就是栽在这）。所以把原来长在
 * SettingsPage 里的服务接线（状态/码弹窗/收包/自动导入）整体上收成单例：
 * - **自动会话**：App 启动（MainActivity.onCreate → [ensureAuto]）时，存在信任
 *   设备就常驻监听——**无空闲超时**、关抽屉不停、任何页面都能弹码框；
 * - **手动会话**：抽屉里点「开始接收」起的会话保持旧行为（5 分钟空闲自杀、
 *   关抽屉即停、bat 路径走确认抽屉）——没配过对的用户世界一点不变；
 * - 状态、码弹窗、待确认预览都从这里发（StateFlow），抽屉与全局宿主只管渲染。
 *
 * ## 两种会话下「码路径」的分流（SaveReceiver.autoMode）
 * - 手动：bat 推包 → 输码 → 异步解析 → **确认抽屉**（v2.75 起的老流程，不动）；
 * - 自动：bat 推包 → 输码 → **连接内同步导入 + Toast**。理由：自动会话的前提是
 *   用户已配对（选定了自动化）；输码本身已经是这次传输的人工授权，不再拦一道
 *   确认抽屉。文案走 Toast（c220：行为与文案同轮改）。
 *
 * ## 线程
 * 状态流转用 Main immediate 的常驻 scope；导入/解析在 IO（[syncImport] 连接线程
 * runBlocking 是 SaveReceiver 的合同，别在主线程调它暴露的入口）。
 */
object LanReceiveController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<SaveReceiver.State>(SaveReceiver.State.Idle)
    val state: StateFlow<SaveReceiver.State> = _state

    private val _codePrompt = MutableStateFlow<CodePrompt?>(null)
    val codePrompt: StateFlow<CodePrompt?> = _codePrompt

    /** 待确认的导入预览（手动路径收包后填；SAF 选文件导入也共用这一份）。 */
    private val _preview = MutableStateFlow<SaveImport.Result.Ok?>(null)
    val preview: StateFlow<SaveImport.Result.Ok?> = _preview

    private val _parsing = MutableStateFlow(false)
    val parsing: StateFlow<Boolean> = _parsing

    /**
     * c234：当前是否已在**电池优化白名单**里（前台服务保进程不被杀，
     * 白名单保进程不被冻结——两把钥匙缺一不可，实测见 [BatteryHelper]）。
     *
     * ⚠ 白名单状态是**系统设置**，用户去系统页改完回来时这个值会变，
     *   所以 UI 每次回到前台都要重查（不能只在启动时查一次）。
     */
    private val _whitelisted = MutableStateFlow(false)
    val whitelisted: StateFlow<Boolean> = _whitelisted

    /** 重查白名单状态（UI 回到前台时调）。 */
    fun refreshWhitelist(ctx: Context) {
        scope.launch {
            _whitelisted.value = runCatching {
                BatteryHelper.isIgnoringBatteryOptimizations(ctx)
            }.getOrDefault(false)
        }
    }

    /** 当前会话是否「自动监听」。决定关抽屉要不要停、有没有空闲自杀。 */
    @Volatile
    var autoSession = false
        private set

    /**
     * 当前会话的 Application 上下文（c233：停前台服务要用）。
     *
     * ⚠ 存的是 **applicationContext**，不是 Activity 上下文——存 Activity 会泄漏它。
     */
    @Volatile
    private var sessionCtx: Context? = null

    @Volatile
    private var lastListen: SaveReceiver.State.Listening? = null

    /**
     * c240：自动会话的**重建次数**（连续失败计数）。
     *
     * ⚠ 为什么要有这个上限：自愈是「会话停了 → 重建」，但如果**重建本身就失败**
     *   （端口一直被占、网卡还没起来），就会变成「起 → 停 → 起 → 停」的死循环，
     *   白耗电、白刷通知。⇒ 连续失败超过 [MAX_AUTO_REBUILD] 次就**不再自动重建**，
     *   改为如实告诉用户「接收没能起来，请打开 App」。
     *
     * ⚠ 计数在**成功**时清零：中间失败几次、后来成功一次，那就不算连续失败。
     */
    private var autoRebuildAttempts = 0

    /**
     * App 启动即调用（MainActivity.onCreate）：有信任设备 → 常驻自动监听；
     * 没有 → 什么都不发生（手动流程不变）。幂等，已在跑就不动。
     */
    fun ensureAuto(appCtx: Context) {
        if (SaveReceiver.isRunning()) return
        scope.launch {
            val has = runCatching { TrustedDevices.all(appCtx).isNotEmpty() }.getOrDefault(false)
            if (has && !SaveReceiver.isRunning()) start(appCtx, auto = true)
        }
    }

    /** 抽屉里点「开始接收」：手动窗口（旧行为，含确认抽屉）。已在跑就不重复起。 */
    fun startManual(ctx: Context) {
        if (SaveReceiver.isRunning()) return
        start(ctx, auto = false)
    }

    /**
     * c234：前台服务用它来**接管 / 重建**会话。
     *
     * ## 为什么需要它
     * c234 之前会话归 UI 进程管，服务只挂通知；进程被系统杀掉后，
     * 服务重建（START_STICKY）也接不回会话 —— 端口就没了，用户还得回 App
     * 重新点「开始接收」。现在会话归服务管，服务一起来就把会话建好。
     *
     * ## 幂等
     * 已经在跑就什么都不做（UI 进程先起会话、后起服务的情形走这条）。
     */
    fun ensureSessionForService(appCtx: Context, auto: Boolean) {
        if (SaveReceiver.isRunning()) return
        scope.launch {
            // 自动会话要**有信任设备**才起（跟 ensureAuto 同一条规矩：
            // 没有信任设备就别在后台白占一个端口）。
            if (auto) {
                val has = runCatching { TrustedDevices.all(appCtx).isNotEmpty() }
                    .getOrDefault(false)
                if (!has) {
                    android.util.Log.i(
                        "LanReceiveController",
                        "没有信任设备：自动会话不建（等用户手动开窗）",
                    )
                    return@launch
                }
            }
            if (!SaveReceiver.isRunning()) start(appCtx, auto = auto)
        }
    }

    /**
     * c233：开关改了之后调用（设置页改「后台常驻」）。
     *
     * 开 → 若正在接收，给会话补一个前台服务保活；
     * 关 → 停掉前台服务（**接收会话不停**，只是不再保活，回到 c232 的进程内行为）。
     */
    fun onForegroundPreferenceChanged(appCtx: Context, enabled: Boolean) {
        scope.launch {
            if (!SaveReceiver.isRunning()) return@launch
            if (enabled) {
                LanReceiveService.start(appCtx, autoSession)
            } else {
                LanReceiveService.stop(appCtx)
            }
        }
    }

    /** 撤销信任后调用：信任设备清零就把自动会话收摊（撤销要能被感知）。 */
    fun onTrustChanged(appCtx: Context) {
        scope.launch {
            val has = runCatching { TrustedDevices.all(appCtx).isNotEmpty() }.getOrDefault(false)
            if (!has && autoSession) stop("信任设备已清空，自动接收停止")
        }
    }

    /**
     * 抽屉关闭/销毁：只停**手动**会话。⚠ 自动会话是常驻的——关个抽屉把自动
     * 接收误杀，就退回了「每次都要手动开窗」的老世界。
     */
    fun onDrawerDisposed() {
        if (SaveReceiver.isRunning() && !autoSession) {
            stop("已停止接收")
        }
    }

    /** 停服务并回 Idle（信任清空/显式停止）。 */
    fun stop(reason: String = "已停止接收") {
        SaveReceiver.stop(reason)
        autoSession = false
        // c240：用户主动停 ⇒ 连续失败计数也清零（下次自动会话从头算）。
        autoRebuildAttempts = 0
        resetToIdle()
        // 会话没了，前台服务也没存在的理由（留着就是个空壳通知）。
        sessionCtx?.let { LanReceiveService.stop(it) }
        sessionCtx = null
    }

    /** 全局码弹窗提交/取消后收掉。 */
    fun clearCodePrompt() {
        _codePrompt.value = null
    }

    /** 确认抽屉取消：连带丢掉 pending 的农场解析（解析与写入分离的老规矩）。 */
    fun discardPreview() {
        _preview.value = null
        FarmTodayCache.discardPending()
    }

    /** 确认导入完成 → 收掉预览（导入方已 commit，pending 已被取走）。 */
    fun consumePreview() {
        _preview.value = null
    }

    /** SAF 选文件导入也要弹同一个确认抽屉（c232 起预览状态归控制台统一持有）。 */
    fun setPreview(r: SaveImport.Result.Ok?) {
        _preview.value = r
    }

    private fun resetToIdle() {
        lastListen = null
        _state.value = SaveReceiver.State.Idle
        _codePrompt.value = null
    }

    private fun start(ctx: Context, auto: Boolean) {
        autoSession = auto
        val appCtx = ctx.applicationContext
        sessionCtx = appCtx
        val repo = PrefsRepo(appCtx)
        SaveReceiver.start(
            ctx = appCtx,
            autoMode = auto,
            onChanged = { s ->
                scope.launch {
                    if (s is SaveReceiver.State.Listening) {
                        lastListen = s
                        _codePrompt.value = null
                        // c240：会话起来了 ⇒ 连续失败计数清零（中间失败几次不算连续）。
                        autoRebuildAttempts = 0
                        // c233：开关开着 → 给会话补一个前台服务保活（息屏/切后台也活着）。
                        // ⚠ 只在**真的听到端口**之后起：SaveReceiver.start 失败（端口被占）
                        //   时会报 Failed 而不是 Listening，那时起服务只会挂一条空通知。
                        // c234：服务会回头调 ensureSessionForService —— 那时会话已在跑，
                        //   它会直接返回（幂等），不会递归。
                        if (runCatching { repo.current().lanForeground }.getOrDefault(false)) {
                            LanReceiveService.start(appCtx, auto)
                        }
                    }
                    // c240：**自动会话掉了要自愈**。
                    //   实测（2026-11-09，魅族 20）：serve() 因 accept() 抛异常退出时，
                    //   进程活着、前台服务活着、常驻通知还在，但 TCP 端口没了 ——
                    //   电脑侧看到的就是「没扫描到手机」，而通知栏明明挂着。
                    //   以前这个状态**没人管**：ensureAuto 只在 App 冷启动调一次，
                    //   ensureSessionForService 只在服务 onStartCommand 调，
                    //   「会话自己没了」不会触发其中任何一个 ⇒ 不重启 App 就一直收不到。
                    //   这里补上：自动会话意外停了 → 延迟一小会儿重建。
                    //   ⚠ 只对**自动会话**自愈：手动会话停了就是用户的意思
                    //   （他点了「开始接收」又关掉、或超时自动关），不能偷偷重开。
                    //   ⚠ 延迟一点再建：刚 stop() 时状态还没稳，立刻重开会与
                    //   用户「刚点停止」的动作打架（stop 会把 autoSession 置 false，
                    //   这里读到的就是 false，不会误重开 —— 但端口释放需要时间）。
                    if (s is SaveReceiver.State.Stopped && autoSession) {
                        val ctxNow = sessionCtx
                        if (ctxNow != null) {
                            autoRebuildAttempts++
                            if (autoRebuildAttempts <= MAX_AUTO_REBUILD) {
                                android.util.Log.i(
                                    "LanReceiveController",
                                    "自动会话意外停了，准备重建（第 ${autoRebuildAttempts} 次，原因：${s.reason}）",
                                )
                                scope.launch {
                                    delay(1500)
                                    // 重建前再确认一次：期间用户可能已经手动接管了
                                    if (autoSession && !SaveReceiver.isRunning()) {
                                        start(ctxNow, auto = true)
                                    }
                                }
                            } else {
                                // ⚠ 重建一直失败 ⇒ 停下来，如实告诉用户，别白耗电。
                                android.util.Log.w(
                                    "LanReceiveController",
                                    "自动会话连续重建 $MAX_AUTO_REBUILD 次仍失败，放弃自动重建",
                                )
                                _state.value = SaveReceiver.State.Failed(
                                    "接收没能起来（连续重试 $MAX_AUTO_REBUILD 次），请打开 App 重试",
                                )
                            }
                        }
                    }
                    _state.value = s
                }
            },
            onCodeRequest = { _, left, submit ->
                scope.launch {
                    _codePrompt.value = CodePrompt(
                        attemptsLeft = left,
                        wrong = left < SaveReceiver.MAX_CODE_ATTEMPTS,
                        submit = submit,
                    )
                }
            },
            onReceived = { file ->
                // 只剩**手动会话的旧式请求**（bat / 手动码）走到这里：
                // 异步解析 → 确认抽屉，v2.75 起的老流程逐字保留。
                scope.launch {
                    _parsing.value = true
                    val r = withContext(Dispatchers.IO) {
                        val snapNow = repo.current()
                        SaveImport.parseFile(appCtx, file) { src ->
                            FarmTodayCache.parse(appCtx, src, file.name, snapNow.activeProfile)
                        }
                    }
                    _parsing.value = false
                    when (r) {
                        is SaveImport.Result.Ok -> {
                            // 窗口还开着 → 回到监听态（v2.75.0 起「收一个不关窗」）
                            _state.value = lastListen ?: SaveReceiver.State.Idle
                            if (r.keys.isEmpty()) {
                                toast(appCtx, "这份存档里没有可自动勾选的进度")
                            } else {
                                _preview.value = r
                            }
                        }
                        is SaveImport.Result.Err -> {
                            _state.value = SaveReceiver.State.Failed(r.msg)
                            toast(appCtx, r.msg)
                        }
                    }
                }
            },
            onAutoImport = { file -> syncImport(appCtx, repo, file) },
            onAutoImported = { _, msg ->
                scope.launch {
                    toast(appCtx, msg)
                    // 成败都回「还在接收」：那条连接结束了但窗口活着，停在
                    // 「等待验证码确认…」是假状态（v2.67.3 的坑，不许再犯）。
                    _state.value = lastListen ?: SaveReceiver.State.Idle
                }
            },
        )
    }

    /**
     * 信任路径的**连接内同步导入**：口径与确认抽屉逐行一致
     * （备份 → 并集只补不删 → 报告 → 农场缓存；无可勾项不动库）。
     */
    private fun syncImport(ctx: Context, repo: PrefsRepo, file: File): SaveImport.Result =
        kotlinx.coroutines.runBlocking {
            val snapNow = repo.current()
            val r = withContext(Dispatchers.IO) {
                SaveImport.parseFile(ctx, file) { src ->
                    FarmTodayCache.parse(ctx, src, file.name, snapNow.activeProfile)
                }
            }
            if (r is SaveImport.Result.Ok && r.keys.isNotEmpty()) {
                repo.autoBackup()
                repo.replaceChecks(snapNow.checks + r.keys)
                repo.setReport(snapNow.activeProfile, r.report)
                val pend = FarmTodayCache.takePending()
                FarmTodayCache.commit(ctx, snapNow.activeProfile, pend)
            }
            r
        }

    private fun toast(ctx: Context, msg: String) {
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }
}
