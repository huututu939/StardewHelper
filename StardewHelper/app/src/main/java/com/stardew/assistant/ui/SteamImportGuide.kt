package com.stardew.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.SteamDownloadDir
import com.stardew.assistant.data.SteamImportCoordinator
import com.stardew.assistant.data.SteamImportService
import com.stardew.assistant.data.SteamNotifications
import com.stardew.assistant.data.SteamSaveSniffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.86.0：「从 Steam 云获取存档」的引导页。
 *
 * ## 合规红线（这一页是红线的**可见化**，不是形式）
 * ① 打开 Steam 页面只用 **Chrome Custom Tabs**（[CustomTabsIntent]）——
 *    **绝不**自绘 WebView。自绘 WebView 能读登录表单、能注入 JS、能替用户点击，
 *    那三条全是红线。Custom Tabs 把页面交给**用户自己的浏览器**，
 *    App 看不到页面里发生了什么。
 * ② 本页**不收集、不存储、不转发**任何 Steam 账号/密码/令牌。
 * ③ App **不直连** Steam 云接口（那需要密钥与开发者身份）。
 * ④ 「自动」严格限定在「**文件已下载到本地之后**」。
 *    打开网页、登录、点下载 —— 这三步都由用户在 Steam 官网完成。
 *
 * ## ⚠ 各步必须**串行**，不能并行（v2.86.0 修）
 * 一次点击要发起几件事：通知权限 → 前台服务 → 打开 Steam 页。
 * 最初写成「一次点击全发起」⇒ **实测立刻翻车**：
 * Custom Tabs 抢在系统对话框之前弹出来，对话框被顶掉 → 回调拿到 `null` →
 * 用户看到「没拿到下载目录授权」+「没有通知权限」两条**假报错**（用户其实没拒绝任何授权）。
 *
 * 为什么不能并行：系统对话框与 Custom Tabs **都是前台 UI**，同一时刻只能有一个在前台；
 * 后发的那个会把先发的顶掉（或自己被顶掉）。所以必须
 * **等上一步的结果回调，再发起下一步**。
 *
 * ## ⚠ 下载目录只能授权**子目录**（v2.86.0 实测后定的）
 * Android 11+（本 App `targetSdk=35`）**明令禁止**用 `ACTION_OPEN_DOCUMENT_TREE`
 * 授权 **Download 根目录**（官方：您无法再使用 ACTION_OPEN_DOCUMENT_TREE
 * 请求访问…Download 目录）。实测：Download **看得见**但「USE THIS FOLDER」
 * **永远是灰的**。
 * ⇒ 引导页教用户在系统选择器里**当场新建一个子文件夹**（选择器自带
 *   「CREATE NEW FOLDER」按钮）并授权它；再把浏览器的下载位置改成那个文件夹。
 * 详见 [SteamDownloadDir] 的 KDoc（含另外两条路的实测结论）。
 *
 * ## 文案为什么这么写
 * 安全提示卡那一句「不会获取、也不会记录你的 Steam 账号和密码」是
 * **承诺**，所以代码里也必须真的没有那两样东西 —— 承诺跟着实现走。
 */
@Composable
fun SteamImportGuide(
    visible: Boolean,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    /** 用户点了「我已下载，手动选择文件」→ 交给设置页现有的选文件入口。 */
    onManualPick: () -> Unit,
    /** 打开 Steam 官网失败时给用户看的原因。 */
    onOpenFailed: (String) -> Unit,
    /** 「查看农场报告」→ 跳到设置页（报告卡在那里）。 */
    onOpenReport: (String?) -> Unit,
) {
    if (!visible) return
    val c = LocalPixel.current
    // ⚠ LocalContext 必须在 @Composable 里取，不能在 lambda 里取
    //   （在 onClick lambda 里调 LocalContext.current 读的是**回调时**的组合上下文，
    //   Android 上会抛「@Composable invocations can only happen from a @Composable context」）。
    val ctx = LocalContext.current
    val state by SteamImportCoordinator.state.collectAsState()

    // ── 「所有文件访问」的状态（v2.91.0 补回）──────────────
    //
    // ⚠ 用户点「去开启所有文件访问」会**跳出 App** 去系统设置，
    //   回来时这个 Composable 不会自动重组 → 权限状态会停在「未开」。
    //   所以监听「回到前台」这一刻，重新读一次。
    //
    // ⚠ 为什么用 framework 的 ActivityLifecycleCallbacks 而不是 androidx.lifecycle：
    //   本项目**没有**引 lifecycle-runtime（那要为一个监听多拉一个依赖），
    //   而 Application.ActivityLifecycleCallbacks 是 Android 自带的，零依赖。
    var allFilesGranted by remember { mutableStateOf(SteamDownloadDir.hasAllFilesAccess(ctx)) }
    val app = ctx.applicationContext as? android.app.Application
    DisposableEffect(app) {
        val cb = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(act: Activity) {
                allFilesGranted = SteamDownloadDir.hasAllFilesAccess(ctx)
            }

            override fun onActivityCreated(act: Activity, b: android.os.Bundle?) = Unit
            override fun onActivityStarted(act: Activity) = Unit
            override fun onActivityPaused(act: Activity) = Unit
            override fun onActivityStopped(act: Activity) = Unit
            override fun onActivitySaveInstanceState(act: Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(act: Activity) = Unit
        }
        app?.registerActivityLifecycleCallbacks(cb)
        onDispose { app?.unregisterActivityLifecycleCallbacks(cb) }
    }

    // 扫描摘要：两条通道各自的状态 + 扫到几个文件。
    // ⚠ 扫描要读文件头（IO），**不能**在 Composable 里同步做 → 丢到 IO 线程。
    var scanSummary by remember { mutableStateOf("（还没扫过）") }
    suspend fun refreshSummary() {
        val text = withContext(Dispatchers.IO) { SteamDownloadDir.scanSummary(ctx) }
        scanSummary = text
    }
    LaunchedEffect(Unit) { refreshSummary() }

    // ── 底部四个小节的展开状态 ─────────────────────────────
    //
    // ⚠ v2.91.0：改成**点标题展开详情**（与远端 2.90.0 一致）。
    //   原来三张卡常驻着，页面被拉得很长；展开式一屏能看到全部入口。
    var expanded by remember { mutableStateOf<String?>(null) }

    // ── 串行流程的当前步骤 ─────────────────────────────────
    //
    // 每一步都**等上一步的回调**才走下一步（见 KDoc 里的实测翻车记录）。
    //
    // ⚠ 为什么用 `step` 状态 + `LaunchedEffect` 驱动，而不是 lambda 互相调用：
    //   Kotlin 的局部 lambda 不能前向引用，而把流程写成「回调里直接调下一个回调」
    //   会形成环状依赖、读不懂。状态机是最直白的写法：
    //   **回调只改状态，状态变化才推进下一步**。
    var step by remember { mutableStateOf(SteamStep.IDLE) }

    // ② 通知权限 —— 拒绝**不影响导入**（结果仍在 App 里显示）
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                ctx,
                "没给通知权限，导入完成时不会弹通知（结果仍会在 App 里显示）",
                Toast.LENGTH_LONG,
            ).show()
        }
        // 无论给不给，都往下走
        step = SteamStep.OPEN_PAGE
    }

    // ① SAF 目录授权（**自动识别的关键**）
    //
    // ⚠ 用户在系统选择器里要选一个**子目录**（Download 根目录被系统禁止）。
    //   选择器自带「CREATE NEW FOLDER」按钮，当场就能建 —— 所以文案教他这么做。
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            // 用户取消/拒绝：**不强制**（还有「我已下载，手动选择文件」那条退路）。
            Toast.makeText(
                ctx,
                "没拿到文件夹授权。下完可以用「我已下载，手动选择文件」导入。",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            val ok = SteamDownloadDir.persistPermission(ctx, uri)
            SteamDownloadDir.setTreeUri(ctx, if (ok) uri else null)
            if (ok) {
                Toast.makeText(
                    ctx, "已记住这个文件夹，下载完会自动识别", Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(
                    ctx, "文件夹授权没保存成功，请再试一次", Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // 串行推进：每一步只做一件事，等它的回调把 step 推到下一步。
    LaunchedEffect(step) {
        when (step) {
            SteamStep.IDLE -> Unit
            SteamStep.REQUEST_TREE -> {
                // 已经授权过就别再问一遍（用户第二次点按钮时）
                if (SteamDownloadDir.hasTreePermission(ctx)) {
                    Toast.makeText(
                        ctx, "已经授权过文件夹了，直接去下载就行", Toast.LENGTH_SHORT,
                    ).show()
                    step = SteamStep.IDLE
                } else {
                    treeLauncher.launch(null)
                }
            }
            SteamStep.REQUEST_NOTIFY -> {
                // Android 13+ 才要通知权限；以下版本直接跳到打开页面
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notifyLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    step = SteamStep.OPEN_PAGE
                }
            }
            SteamStep.OPEN_PAGE -> {
                // ③ 先起前台服务：用户去浏览器下载那段「人不在 App」的时段靠它活着
                SteamImportService.start(ctx)
                // ④ 打开 Steam 官网
                openSteamCloud(ctx, onOpenFailed)
                step = SteamStep.IDLE
            }
        }
    }

    // 导入状态 → 页面上的实时反馈。
    //
    // ⚠ 结果**同时**走两条路（通知 + 页面内的卡）：
    //   用户在引导页前面 → 看得见卡；
    //   用户在浏览器/别的 App → 看得见通知。
    LaunchedEffect(state) {
        when (val s = state) {
            is SteamImportCoordinator.State.Done -> {
                SteamNotifications.notify(
                    ctx,
                    SteamNotifications.ID_RESULT,
                    SteamNotifications.successNotification(
                        ctx,
                        s.farmName,
                        s.profileId,
                    ),
                )
            }
            is SteamImportCoordinator.State.Failed -> {
                SteamNotifications.notify(
                    ctx,
                    SteamNotifications.ID_RESULT,
                    SteamNotifications.failureNotification(
                        ctx,
                        s.reason,
                        null,
                    ),
                )
            }
            else -> Unit
        }
    }

    DetailDrawer(
        visible = visible,
        title = "☁️ 从 Steam 云获取存档",
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 非官方声明（v2.90.0 提到顶部）─────────────────────
            //  这一页会带用户去 Steam 官网，**在动手之前**就该把「非官方」说清楚，
            //  不然只在页尾一行小字，用户早就点出去了。
            PixelCard(title = "ℹ️ 非官方声明", accent = c.gold) {
                PixelText(NON_OFFICIAL_NOTICE, color = c.inkDim)
            }

            // ── 安全提示卡 ─────────────────────────────────────
            PixelCard(title = "🔒 安全提示", accent = c.green) {
                PixelText(SAFETY_NOTICE, color = c.ink)
                Spacer(Modifier.height(5.dp))
                // v2.91.0：补回远端 2.90.0 的原文 —— 上一段只说了「不碰账号」，
                //   这一段把**存档本身**的处置也写清楚（只在本机读、不上传、不再分发）。
                PixelText(STEAM_CLOUD_ATTRIBUTION, style = Px.caption, color = c.inkDim)
            }

            // ── 实时状态 ───────────────────────────────────────
            when (val s = state) {
                is SteamImportCoordinator.State.Scanning ->
                    if (s.found > 0) {
                        PixelText("在下载文件夹里找到 ${s.found} 个存档…", color = c.gold)
                    }
                is SteamImportCoordinator.State.Parsing -> {
                    // v2.92.0：补回远端 2.90.0 的原文 —— 存档几十兆，解析要等一会儿，
                    //   不说清楚用户会以为 App 卡死了（这是当初写这段的用意）。
                    PixelText("正在读取「${s.farmName}」…", color = c.gold)
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        "存档几十兆，要读一会儿，完了会弹结果。",
                        style = Px.caption, color = c.inkDim,
                    )
                }
                is SteamImportCoordinator.State.Done ->
                    // 成功卡：结果 + 两个动作（看报告 / 关掉）
                    PixelCard(title = "✅ 导入完成", accent = c.green) {
                        PixelText(
                            "「${s.farmName}」已导入，勾上 ${s.importedCount} 条进度" +
                                if (s.reportReady) "，农场报告已就绪" else "",
                            color = c.ink,
                        )
                        // ⚠ 报告没生成出来时说清楚**为什么**，否则用户以为导入失败了。
                        if (!s.reportReady) {
                            Spacer(Modifier.height(4.dp))
                            PixelText(
                                "这份存档里没有能生成报告的数据，进度已经勾上了。",
                                style = Px.caption,
                                color = c.inkDim,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // v2.87.0：只有真的解析出报告才给这个按钮 ——
                            // 没有报告就点开只能看到一张空图，不如不给。
                            if (s.reportReady) {
                                PixelButton(
                                    "查看农场报告",
                                    active = true,
                                    onClick = {
                                        SteamImportCoordinator.reset()
                                        onOpenReport(s.profileId)
                                    },
                                )
                            }
                            PixelButton("完成", onClick = {
                                SteamImportCoordinator.reset()
                            })
                        }
                    }
                is SteamImportCoordinator.State.Failed ->
                    PixelCard(title = "✗ 导入失败", accent = c.red) {
                        PixelText(s.reason, color = c.ink)
                        Spacer(Modifier.height(4.dp))
                        PixelText(
                            "请确认下载的是与农场同名、体积最大的主存档文件（不要下 SaveGameInfo 和 _old）。",
                            style = Px.caption,
                            color = c.inkDim,
                        )
                        Spacer(Modifier.height(6.dp))
                        // v2.92.0：补回远端 2.90.0 的「知道了」按钮 —— 原先失败卡
                        //   没法关掉，用户想重试只能把整个抽屉划走。
                        PixelButton(
                            text = "知道了",
                            active = true,
                            onClick = { SteamImportCoordinator.reset() },
                        )
                    }
                is SteamImportCoordinator.State.NeedChoice ->
                    PixelText(
                        "找到 ${s.candidates.size} 个存档，请选一个：",
                        color = c.gold,
                    )
                else -> Unit
            }

            // ── 多候选时列出 ───────────────────────────────────
            val choice = state as? SteamImportCoordinator.State.NeedChoice
            if (choice != null) {
                choice.candidates.forEach { cand ->
                    PixelCard {
                        Row {
                            Column(Modifier.weight(1f)) {
                                PixelText(cand.farmName, color = c.ink)
                                PixelText(
                                    String.format("%.1f MB", cand.size / 1024.0 / 1024.0) +
                                        " · " + formatModifiedAt(cand.lastModified),
                                    style = Px.caption,
                                    color = c.inkDim,
                                )
                            }
                            Spacer(Modifier.padding(horizontal = 6.dp))
                            PixelButton(
                                "导入",
                                active = true,
                                onClick = {
                                    SteamImportCoordinator.choose(ctx, scope, cand)
                                },
                            )
                        }
                    }
                }
            }

            // ── 重新扫描 + 步骤图文（v2.91.0 补回，与远端 2.90.0 对齐）──
            PixelButton(
                text = "重新扫描下载目录",
                active = true,
                onClick = {
                    scope.launch { refreshSummary() }
                    // force = true：忽略「这个文件处理过了」的去重记录，
                    //   用户手动点的重扫就该真的再处理一次。
                    SteamImportCoordinator.scanAndImport(ctx, scope, force = true)
                },
            )
            Spacer(Modifier.height(4.dp))
            STEPS.forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    PixelText("${i + 1}.", color = c.gold)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    PixelText(step, color = c.ink)
                }
            }
            Spacer(Modifier.height(4.dp))
            // 扫描状态：两条通道各自的状态 + 扫到几个文件
            PixelText(scanSummary, style = Px.caption, color = c.inkDim)

            // ── 两种办法（v2.91.0 补回「所有文件访问」那条）────
            //  ⚠ 办法一是**敏感权限**（系统设置里手动开），但 App **不申请**它，
            //    用户不点那条路就完全不影响使用 —— 办法二仍然是零权限的退路。
            PixelCard {
                PixelText(
                    // c226 瘦身：星号是写给 Markdown 的，Compose 的 Text 不解析、会裸显两颗星。
                    "有两种办法，选一个就行。开了之后，下载完的存档 App 自己就认出来了。",
                    color = c.ink,
                )
                Spacer(Modifier.height(8.dp))

                PixelText("办法一：开启「所有文件访问」（推荐，最省事）", color = c.gold)
                Spacer(Modifier.height(2.dp))
                PixelText(
                    // c226 瘦身：末句与卡头「选一个就行」同一句话说两遍，删。
                    "点下面按钮跳到系统设置，在「星露小助手」那页打开「所有文件访问」。" +
                        "开了就不用改浏览器的下载位置。",
                    style = Px.caption,
                    color = c.inkDim,
                )
                Spacer(Modifier.height(4.dp))
                PixelButton(
                    text = "去开启所有文件访问",
                    active = true,
                    onClick = { openAllFilesAccessSettings(ctx) },
                )
                if (allFilesGranted) {
                    Spacer(Modifier.height(4.dp))
                    PixelText("✓ 已开启", color = c.green)
                }

                Spacer(Modifier.height(10.dp))

                PixelText("办法二：只授权一个文件夹（不需要任何权限）", color = c.gold)
                Spacer(Modifier.height(2.dp))
                PixelText(
                    "系统不允许直接授权「下载」这个大文件夹，得在里面建一个子文件夹。" +
                        "点下面按钮后，在弹出的系统窗口里：进「Download」→ " +
                        "点右下角「新建文件夹」→ 起个名（比如「星露存档」）→ " +
                        "选中它 →点「使用此文件夹」→ 点「允许」。" +
                        "之后把浏览器的下载位置改成那个文件夹就行。",
                    style = Px.caption,
                    color = c.inkDim,
                )
                Spacer(Modifier.height(4.dp))
                PixelButton(
                    text = "授权文件夹",
                    active = true,
                    onClick = { step = SteamStep.REQUEST_TREE },
                )
                if (SteamDownloadDir.hasTreePermission(ctx)) {
                    Spacer(Modifier.height(4.dp))
                    PixelText("✓ 已授权", color = c.green)
                }
            }

            // ── ② 改浏览器下载位置（只有两种办法都没开才需要）────
            //  ⚠ 条件同远端 2.90.0：所有文件访问开了、或已授权文件夹，
            //    就**不用**改下载位置了 —— 这时还提示这条反而是干扰。
            if (!allFilesGranted && !SteamDownloadDir.hasTreePermission(ctx)) {
                PixelButton(
                    text = "② 把浏览器的下载位置改到那个文件夹",
                    onClick = { expanded = if (expanded == "dl") null else "dl" },
                )
                if (expanded == "dl") {
                    PixelCard {
                        // c226 瘦身：末句与办法二步骤、STEPS 第 2 条三处重复，删。
                        PixelText(
                            "浏览器设置 → 下载 → 下载位置 → 选刚才那个文件夹。",
                            color = c.ink,
                        )
                    }
                }
            }

            // ── 去 Steam 官网下载 ───────────────────────────────
            PixelButton(
                text = "去 Steam 官网下载",
                onClick = { expanded = if (expanded == "steam") null else "steam" },
            )
            if (expanded == "steam") {
                PixelCard {
                    PixelText("在 Steam 官方页面登录你的账号：", color = c.ink)
                    Spacer(Modifier.height(2.dp))
                    PixelText(
                        "· 找到星露谷物语云文件，点「显示文件/Show Files」；",
                        style = Px.caption,
                        color = c.inkDim,
                    )
                    PixelText(
                        "· 下载「与你农场同名、体积最大」的主存档文件" +
                            "（不要下 SaveGameInfo 和 _old）；",
                        style = Px.caption,
                        color = c.inkDim,
                    )
                    PixelText(
                        "· 下载完成后回到 App，会自动识别并导入。",
                        style = Px.caption,
                        color = c.inkDim,
                    )
                    Spacer(Modifier.height(6.dp))
                    PixelButton(
                        text = "打开 Steam 云存档页",
                        active = true,
                        onClick = {
                            // ⚠ 这里**只设状态**，不直接发起任何系统 UI。
                            //   各步（通知权限 → 前台服务 → 打开网页）由上面的
                            //   `LaunchedEffect(step)` 串行推进 —— 一次全发起会让
                            //   Custom Tabs 顶掉系统对话框，用户看到假报错。
                            step = SteamStep.REQUEST_NOTIFY
                        },
                    )
                }
            }

            // ── 没自动弹？手动扫一次 ───────────────────────────
            PixelButton(
                text = "没自动弹？扫一次看看",
                onClick = { expanded = if (expanded == "rescan") null else "rescan" },
            )
            if (expanded == "rescan") {
                // c226 瘦身：页顶已有常驻的「重新扫描下载目录」按钮，这里不再放第二份，
                // 说明句就地指路（verify 钉的是折叠入口标题与按钮 ≥1 处，均满足）。
                PixelCard {
                    PixelText(
                        "下载完回 App 还没弹的话，用上面的「重新扫描下载目录」再扫一次。",
                        color = c.ink,
                    )
                }
            }

            // ── 退路：手动选文件 ─────────────────────────────
            PixelButton(
                text = "不想改下载位置？",
                onClick = { expanded = if (expanded == "manual") null else "manual" },
            )
            if (expanded == "manual") {
                PixelCard {
                    PixelText(
                        "下完直接回 App 点下面的按钮，在系统文件窗口里选那个存档文件就行。",
                        color = c.ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    PixelButton(
                        text = "我已下载，手动选择文件",
                        onClick = {
                            SteamImportService.stop(ctx)
                            onManualPick()
                        },
                    )
                }
            }

            // ── 页脚：非官方声明（顶部已有一份，这里当提醒）──────────
            Spacer(Modifier.height(2.dp))
            PixelText(
                NON_OFFICIAL_NOTICE,
                style = Px.caption,
                color = c.inkDim,
            )
        }
    }
}

/**
 * 「打开 Steam 云存档页」这条流程的**串行步骤**。
 *
 * ⚠ 为什么要有这个枚举：一次点击要发起几件事（通知权限 / 前台服务 / 打开网页），
 *   而它们**不能并行**——系统对话框与 Custom Tabs 都是前台 UI，
 *   同一时刻只能有一个在前台，后发的会把先发的顶掉。
 *   实测并行发起的后果：Custom Tabs 抢在对话框之前弹出，回调拿到 `null`，
 *   用户同时看到「没拿到下载目录授权」和「没有通知权限」两条**假报错**。
 *
 * 所以流程必须**一步一步等回调**：回调只改 [step]，由 `LaunchedEffect(step)` 推进。
 */
private enum class SteamStep {
    /** 空闲（不在流程中）。 */
    IDLE,

    /** ① 要 SAF 目录授权（子目录）。 */
    REQUEST_TREE,

    /** ② 要通知权限。 */
    REQUEST_NOTIFY,

    /** ③ 起前台服务 + ④ 打开 Steam 官网。 */
    OPEN_PAGE,
}

/**
 * 安全提示文案见 [SAFETY_NOTICE]（与协议页共用一份，见 SteamNotices.kt）。
 */

/**
 * 步骤要点。
 *
 * v2.91.0 改成与远端 2.90.0 一致的四条：第①条现在提**两条**认路办法
 * （开「所有文件访问」或授权子文件夹），不再只说授权子文件夹。
 */
private val STEPS = listOf(
    "先让 App 认得出下载目录：开「所有文件访问」，或授权一个子文件夹（两种都行）。",
    "如果选了子文件夹办法，把浏览器的下载位置改成那个文件夹。",
    "去 Steam 官网登录，把主存档下载进去。",
    "回到 App，自动识别并导入；点通知就能看结果。",
)

/**
 * 跳到系统设置里的「所有文件访问」开关页。
 *
 * ⚠ 这个权限**不会在安装时弹窗** —— 它在系统设置 → 应用 → 特殊权限里，
 *   用户自己去勾。App 只负责把用户**送到**那一页，勾不勾永远是他自己的决定，
 *   不开也不影响使用（还有 SAF 子目录那条路）。
 *
 * ⚠ 个别定制系统会去掉这个页面 → 退回「所有文件访问」的总列表页；
 *   都没有就明确告诉用户走办法二，别让他对着没反应的按钮发呆。
 */
private fun openAllFilesAccessSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        Toast.makeText(
            ctx, "这个系统没有「所有文件访问」开关，请用下面的办法二。", Toast.LENGTH_LONG,
        ).show()
        return
    }
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${ctx.packageName}")),
        )
    }.onFailure {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.onFailure {
            Toast.makeText(
                ctx, "打不开这个系统设置页，请用下面的办法二。", Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * 候选存档的最后修改时间，格式化成 `MM-dd HH:mm`。
 *
 * ⚠ 之前这里直接拼毫秒时间戳（`修改于 1729838400000`）—— 用户看不懂，
 *   而且有的来源根本给不出时间（SAF 的 COLUMN_LAST_MODIFIED 可能是 -1）。
 *   拿不到时间就说「时间未知」，别显示一个假的时间。
 */
private fun formatModifiedAt(millis: Long): String {
    if (millis <= 0L) return "时间未知"
    return runCatching {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }.getOrElse { "时间未知" }
}

/**
 * 打开 Steam 官方云存档直达页。
 *
 * ## 为什么是 Custom Tabs 而不是 WebView
 * Custom Tabs 把 URL 交给**系统默认浏览器**（Chrome / 用户装的那个），
 * 在一个受限的标签页里打开：App 拿不到页面 DOM、拿不到表单、也注入不了 JS。
 * 这是「不碰账号」这件事在**机制上**成立，而不只是一句承诺。
 *
 * ## 降级
 * 没有支持 Custom Tabs 的浏览器时（很罕见），退回 `ACTION_VIEW` 让系统挑浏览器；
 * 连浏览器都没有就报错给用户。
 *
 * @param onFailed 失败时把原因给出去（人话）
 */
private fun openSteamCloud(ctx: Context, onFailed: (String) -> Unit) {
    val url = STEAM_CLOUD_URL
    runCatching {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(ctx, Uri.parse(url))
    }.onFailure {
        Log.w("SteamImport", "Custom Tabs 打开失败：${it.message}")
        // 降级：系统浏览器
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            onFailed("打不开 Steam 页面。请先开启加速器，或用「我已下载，手动选择文件」。")
        }
    }
}

/** 星露谷物语 Steam AppID = 413150。 */
private const val STEAM_APP_ID = 413150

/** Steam 官方云存档直达页。 */
private const val STEAM_CLOUD_URL =
    "https://store.steampowered.com/account/remotestorageapp/?appid=$STEAM_APP_ID"
