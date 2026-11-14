package com.stardew.assistant.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2.86.0：Steam 云存档的**导入协调器**。
 *
 * ## 它做什么
 * 把「目录里有文件」变成「存档已导入」，中间管三件事：
 * ① **完整性**：文件是不是写完了（[SteamSaveWatcher]）；
 * ② **去重**：同一个文件不重复导入、不重复通知（[STEAM_FP_KEY]）；
 * ③ **导入**：复制进 App 私有目录 → 走 [SaveImport] 解析 → 落盘。
 *
 * ## 状态机（对外暴露为 [StateFlow]，UI 订阅它）
 * ```
 * Idle ──扫描──▶ Scanning ──找到候选──▶ Parsing ──▶ Done / Failed / NeedChoice
 * ```
 * UI 用 [state] 决定弹什么卡；前台服务用它决定什么时候停。
 *
 * ## 「自动」的边界
 * 自动的是「**文件已经下载到本地之后**」的识别与导入。
 * 打开网页、登录、点下载 —— 那三步**全部由用户在 Steam 官网完成**，
 * 本文件一行网络代码都没有。
 */
object SteamImportCoordinator {

    private const val TAG = "SteamImport"

    /** 已处理文件的指纹集合（持久化，防重复导入/重复通知）。 */
    private const val PREFS = "steam_import_prefs"
    private const val STEAM_FP_KEY = "processed_fps"

    /** 导入结果事件。 */
    sealed interface State {
        /** 空闲。 */
        object Idle : State

        /** 正在扫描/等待。 */
        data class Scanning(val found: Int = 0) : State

        /** 正在解析（文件已复制进缓存）。 */
        data class Parsing(val farmName: String) : State

        /** 导入成功。 */
        data class Done(
            val farmName: String,
            val profileId: String?,
            val importedCount: Int,
            val reportReady: Boolean,
        ) : State

        /** 需要用户从多个候选里选。 */
        data class NeedChoice(val candidates: List<SteamSaveSniffer.Candidate>) : State

        /** 失败。 */
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** 状态流。UI 与前台服务都订阅它。 */
    val state: StateFlow<State> = _state

    /** 最近一次成功导入的存档位 id（供深链使用）。 */
    @Volatile
    var lastProfileId: String? = null
        private set

    /**
     * 扫描下载目录并处理。
     *
     * @param scope 协程作用域
     * @param force true = 忽略去重记录，强制再处理一次（用户手动点「重新扫描」时用）
     */
    fun scanAndImport(ctx: Context, scope: CoroutineScope, force: Boolean = false) {
        scope.launch {
            _state.value = State.Scanning()
            val candidates = withContext(Dispatchers.IO) {
                SteamDownloadDir.scan(ctx, onlyMain = true)
            }
            if (candidates.isEmpty()) {
                // 没有主存档：不算失败（用户可能还没下完），保持 Scanning 等下一轮。
                _state.value = State.Scanning(0)
                return@launch
            }
            handleCandidates(ctx, scope, candidates, force)
        }
    }

    /**
     * 处理候选列表。
     *
     * 只有一个 → 直接导入；多个 → 让用户选（**不静默导入**）。
     */
    private fun handleCandidates(
        ctx: Context,
        scope: CoroutineScope,
        candidates: List<SteamSaveSniffer.Candidate>,
        force: Boolean,
    ) {
        val fresh = if (force) candidates else candidates.filter { !isProcessed(ctx, it) }
        if (fresh.isEmpty()) {
            _state.value = State.Scanning(candidates.size)
            return
        }
        if (fresh.size > 1) {
            // ⚠ 多个候选**不静默导入** —— 拿哪个存档是用户的选择。
            _state.value = State.NeedChoice(fresh)
            return
        }
        importOne(ctx, scope, fresh[0])
    }

    /**
     * 导入一个候选。
     *
     * ⚠ **先复制再解析** —— 下载目录里的文件随时可能被浏览器删掉
     *   （下载完成后浏览器会清理「已下载」列表，但文件本身还在；
     *   更常见的是用户手动删了）。复制进缓存后解析，源文件被删也不影响。
     */
    fun importOne(
        ctx: Context,
        scope: CoroutineScope,
        candidate: SteamSaveSniffer.Candidate,
    ) {
        scope.launch {
            _state.value = State.Parsing(candidate.farmName)
            val ok = withContext(Dispatchers.IO) { doImport(ctx, candidate) }
            if (!ok) {
                _state.value = State.Failed("存档识别失败，请确认下载的是与农场同名的主文件")
            }
        }
    }

    /**
     * 真正的导入（**在后台线程**）。
     *
     * ⚠ suspend：落盘那几个方法（`current` / `autoBackup` / `replaceChecks` /
     *   `setReport`）都是 suspend 的，必须在协程里调。
     *
     * @return 是否成功
     */
    private suspend fun doImport(ctx: Context, candidate: SteamSaveSniffer.Candidate): Boolean {
        val uri = runCatching { Uri.parse(candidate.uri) }.getOrNull()
            ?: return false
        // ① 复制进 App 私有目录（只读源文件，不动用户的下载目录）
        val cached: File = SteamDownloadDir.copyToCache(ctx, uri, candidate.name)
            ?: return false
        try {
            // ② 走既有的存档解析链路（裸 XML 分支），**只读**
            val result = SaveImport.parseFile(ctx, cached)
            if (result !is SaveImport.Result.Ok) {
                Log.w(TAG, "解析失败：${(result as SaveImport.Result.Err).msg}")
                return false
            }
            // ③ 落盘：只补不删（并集），与现有导入语义一致
            val repo = PrefsRepo(ctx)
            val snap = repo.current()
            repo.autoBackup()
            repo.replaceChecks(snap.checks + result.keys)
            repo.setReport(snap.activeProfile, result.report)
            // ④ 记指纹，防重复
            markProcessed(ctx, candidate)
            lastProfileId = snap.activeProfile
            _state.value = State.Done(
                farmName = result.farmerName ?: candidate.farmName,
                profileId = snap.activeProfile,
                importedCount = result.keys.size,
                reportReady = result.report != null,
            )
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "导入异常：${t.message}")
            return false
        } finally {
            runCatching { cached.delete() }
        }
    }

    /**
     * 手动选一个候选（用户从多个里挑）。
     */
    fun choose(ctx: Context, scope: CoroutineScope, candidate: SteamSaveSniffer.Candidate) {
        importOne(ctx, scope, candidate)
    }

    /** 复位状态（UI 消费完 Done/Failed 后调用）。 */
    fun reset() {
        _state.value = State.Idle
    }

    // ── 去重记录 ──────────────────────────────────────────────

    /** 这个文件是不是已经处理过。 */
    fun isProcessed(ctx: Context, c: SteamSaveSniffer.Candidate): Boolean {
        val fp = SteamSaveWatcher.fingerprint(c.name, c.size, c.lastModified)
        return processedSet(ctx).contains(fp)
    }

    /** 记下「这个文件处理过了」。 */
    fun markProcessed(ctx: Context, c: SteamSaveSniffer.Candidate) {
        val fp = SteamSaveWatcher.fingerprint(c.name, c.size, c.lastModified)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 只留最近 50 条 —— 免得无限增长
        val next = (processedSet(ctx) + fp).toList().takeLast(50).toSet()
        sp.edit().putStringSet(STEAM_FP_KEY, next).apply()
    }

    private fun processedSet(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(STEAM_FP_KEY, emptySet()) ?: emptySet()

    /** 已处理记录（测试/调试用）。 */
    fun processedFingerprints(ctx: Context): Set<String> = processedSet(ctx)
}
