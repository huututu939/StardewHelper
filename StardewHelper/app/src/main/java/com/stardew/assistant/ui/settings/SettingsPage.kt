package com.stardew.assistant.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stardew.assistant.BuildConfig
import com.stardew.assistant.R
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.SaveReceiver
import com.stardew.assistant.data.UpdateRepo
import com.stardew.assistant.ui.AppFont
import com.stardew.assistant.ui.CollapsibleCard
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.FontScale
import com.stardew.assistant.ui.IconCache
import com.stardew.assistant.ui.ThemeMode
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelConfirmDialog
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.STEAM_CLOUD_ATTRIBUTION
import com.stardew.assistant.ui.UiIcon
import com.stardew.assistant.ui.UpdateNotes
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * c227：设置页三栏（外观 / 存档 / 其他）。
 * 栏序 = 卡片归属分工；改栏名或挪卡时同步 verify 144 节。
 */
private val SETTINGS_TABS = listOf("外观", "存档", "其他")

/**
 * 设置页。
 *
 * [onOpenGuide] 是 v2.99.0 加的：打开使用指引页。
 * 给成**带默认值的参数**而不是必填，是为了不打断别的调用方
 * （本页目前只有 `MainActivity` 一处调用，但它自带退栈回调）。
 */
@Composable
fun SettingsPage(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    onBack: () -> Unit,
    onOpenGuide: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val c = LocalPixel.current
    // ⚠ 2026-11-10：原来的 showCredits / showSponsors（致谢名单 / 赞助名单两个抽屉）
    //   随名单一起删了 —— 开源后公开群友 ID 是侵犯别人的信息（用户口径）。
    // v2.60.0 开源与数据来源（wiki 署名 / 游戏素材声明 / 字体署名 / 协议全文）
    val showLicense = remember { mutableStateOf(false) }
    var importConfirm by remember { mutableStateOf<ImportConfirm?>(null) }
    // v2.55.0 在线更新状态（remember 即可：离开设置页就丢弃，不做跨页持久）
    val checkingUpdate = remember { mutableStateOf(false) }
    val updateState = remember {
        mutableStateOf<com.stardew.assistant.data.UpdateRepo.Check?>(null)
    }
    // v2.95.0 自定义字体：删除前要确认（删了要重新导入，得让用户知道）
    val confirmDeleteFont = remember { mutableStateOf(false) }
    // c214 图鉴勾选一键清空（当前存档）：清空不可逆，先确认
    val confirmClearChecks = remember { mutableStateOf(false) }
    // c227：设置页顶部三栏（外观 / 存档 / 其他）。默认「外观」（最常用）；
    // 不落盘，离开设置页重置——栏位是导航状态，不是用户偏好。
    var settingsTab by remember { mutableStateOf(0) }

    // v2.95.0：当前导入的自定义字体（id = 内容哈希）。空串 = 没导入过。
    val customId = snap.fontCustomId
    // 元数据从磁盘读（几十字节的 JSON，读取很快，不值得为它建缓存）
    val customMeta = remember(customId) {
        if (customId.isBlank()) com.stardew.assistant.data.CustomFont.Meta()
        else com.stardew.assistant.data.CustomFont.meta(ctx, customId)
    }

    // v2.95.0：SAF 选字体文件。**不申请任何权限**（ACTION_OPEN_DOCUMENT 走系统授权）。
    // mime 给一串常见的；系统若认不出，仍会退回「所有文件」界面，用户自己挑即可。
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // ⚠ **拷贝 + 解析 + 建 Typeface 全部离开主线程**：
            //   7 MB 字体的 Typeface.createFromFile 要几百毫秒，主线程上做会掉帧。
            //   后台做完才落盘切档 ⇒ 用户点完直接看到新字体，中间不卡。
            val r = withContext(Dispatchers.IO) {
                com.stardew.assistant.data.CustomFont.importFont(ctx, uri)
            }
            when (r) {
                is com.stardew.assistant.data.CustomFont.Result.Ok -> {
                    // 先记 id，再切档 —— setFontFamily 的 custom 分支要校验「文件在不在」
                    repo.setFontCustomId(r.id)
                    repo.setFontFamily(AppFont.CUSTOM)
                }
                is com.stardew.assistant.data.CustomFont.Result.Err -> {
                    android.widget.Toast.makeText(ctx, r.msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(repo.exportJson().toByteArray()) }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                android.widget.Toast.makeText(ctx, "读取失败：文件为空", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val preview = runCatching { parseImportPreview(text, snap) }.getOrNull()
            if (preview == null) {
                android.widget.Toast.makeText(ctx, "解析失败：不是有效的备份 JSON", android.widget.Toast.LENGTH_LONG).show()
            } else {
                importConfirm = preview
            }
        }
    }

    // v2.86.0：Steam 云导入引导页的开合状态。
    // ⚠ 只管「开合」——SAF 授权与通知权限的 launcher **在引导页自己**，
    //   因为那几步必须**串行**（并行会被 Custom Tabs 顶掉 SAF 选择器，
    //   用户看到两条假报错）。详见 SteamImportGuide 的 KDoc。
    val steamGuideOpen = remember { mutableStateOf(false) }

    // v2.87.0：导入完成后直接看报告长图（不用跳到报告卡所在的地方）。
    var reportPreviewOpen by remember { mutableStateOf(false) }


    // v2.65 导入游戏存档：解析 34MB 单行 XML 是秒级操作，必须离开主线程。
    // 状态只有一个 Ok 结果——失败当场 Toast，只有成功才开确认抽屉，
    // 保证「解析与写入分离」：一个勾都不会在用户确认前落盘。
    var saveParsing by remember { mutableStateOf(false) }
    val saveImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saveParsing = true
        scope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                // v2.81.0：导入成功后**顺手**把同一份主存档交给世界状态解析器。
                // ⚠ 回调在 parse 内部同步触发（那时 zip 还没关），所以它跑在同一个
                //   后台线程里 —— 35MB 的解析不会卡 UI，只是导入多等几秒。
                //   世界状态失败不影响导入结果（勾选进度照旧）。
                //
                // c217：**只解析不落盘**。原来这里直接 refresh（写盘），于是用户点
                //   「取消」也把当前存档的农场换掉了 —— 勾选还是旧存档、农场却是新存档，
                //   同一屏自相矛盾。现在结果只进内存 pending，确认后才 commit。
                com.stardew.assistant.data.SaveImport.parse(ctx, uri) { src ->
                    com.stardew.assistant.data.FarmTodayCache.parse(
                        ctx, src, uri.lastPathSegment.orEmpty(), snap.activeProfile,
                    )
                }
            }
            saveParsing = false
            when (r) {
                is com.stardew.assistant.data.SaveImport.Result.Ok -> {
                    if (r.keys.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "这份存档里没有可自动勾选的进度", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        com.stardew.assistant.data.LanReceiveController.setPreview(r)
                    }
                }
                is com.stardew.assistant.data.SaveImport.Result.Err ->
                    android.widget.Toast.makeText(ctx, r.msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // v2.86.0：Steam「我已下载，手动选择文件」的 launcher。
    //
    // ⚠ **与「选择存档文件」分开**：那个按钮的 mime 是 zip 优先，而 Steam 云存档下载下来
    //   是**无扩展名的裸 XML**（文件名就是「农场名_数字ID」）。用同一个 launcher 会让
    //   用户在系统窗口里找不到那个文件（无扩展名的东西有时被归到「其他」类）。
    //   这里用 `OpenDocument()` 不带 mime 过滤 ⇒ 系统窗口里什么都能选。
    //
    // 选中后**先嗅探**（是不是主存档），再走**既有的**解析链路 ——
    // 与「从 Steam 云自动识别」那条路共用同一个 [com.stardew.assistant.data.SteamSaveSniffer]
    // 判据，所以两条路认的是同一批文件。
    val steamManualLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saveParsing = true
        scope.launch {
            // ① 先嗅探：确认这是主存档（根 <SaveGame> + <player>），
            //    顺带把 SaveGameInfo / _old 挡在外面。
            val name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
            val sniff = kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.stardew.assistant.data.SteamDownloadDir.sniffUri(ctx, uri, name)
            }
            if (!sniff.isMainSave) {
                saveParsing = false
                android.widget.Toast.makeText(
                    ctx,
                    "这不是主存档（${sniff.reason}）。请选与农场同名、最大的那个文件，" +
                        "不要选 SaveGameInfo 或 _old。",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            // ② 是主存档 → 走既有解析链路（zip / 裸 XML 自动分流）
            // c217：农场解析**只进内存**，用户点「确定导入」才落盘（与选文件那条同口径）。
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.stardew.assistant.data.SaveImport.parse(ctx, uri) { src ->
                    com.stardew.assistant.data.FarmTodayCache.parse(
                        ctx, src, uri.lastPathSegment.orEmpty(), snap.activeProfile,
                    )
                }
            }
            saveParsing = false
            when (r) {
                is com.stardew.assistant.data.SaveImport.Result.Ok -> {
                    if (r.keys.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "这份存档里没有可自动勾选的进度", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        com.stardew.assistant.data.LanReceiveController.setPreview(r)
                    }
                }
                is com.stardew.assistant.data.SaveImport.Result.Err ->
                    android.widget.Toast.makeText(ctx, r.msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // v2.67.0 局域网接收：手机开一个窗口等 PC 推包，省掉「压缩 → 传文件 → 找文件」。
    // 只监听、只收：GET / 只回一句状态文本，没有任何路径能读走手机上的东西；
    // 收满一个包服务端自动关闭（单次即焚），另有 2 分钟空闲超时兜底。
    val receiveOpen = remember { mutableStateOf(false) }
    // v2.68.1 脚本使用教程卡的展开状态（默认折叠，理由见卡片处注释）
    var tutorialOpen by remember { mutableStateOf(false) }
    // c238：「其它拿法」（PowerShell 一行命令 / 直链）的展开状态。默认折叠 ——
    //   这两条是给「只用电脑、不装这个 App」的人的兜底路径，平时用不上，
    //   摊在主流程里只是噪声。
    var altOpen by remember { mutableStateOf(false) }
    // c232：接收服务的状态/码弹窗/待确认预览收进进程级控制台——自动监听起来后
    // 配对请求可能在任何页面弹，这些状态不能只活在设置页里。
    val recvState by com.stardew.assistant.data.LanReceiveController.state.collectAsState()
    val lanParsing by com.stardew.assistant.data.LanReceiveController.parsing.collectAsState()
    // c234：电池优化白名单状态（用户在系统页改完会回到 App，状态要跟着变）
    val lanWhitelisted by com.stardew.assistant.data.LanReceiveController.whitelisted.collectAsState()
    val savePreview = com.stardew.assistant.data.LanReceiveController.preview.collectAsState().value
    // 信任设备名单（进抽屉时拉；配对/撤销后随接收状态刷新）
    var trustedDevices by remember {
        mutableStateOf<List<com.stardew.assistant.data.TrustedDevices.Device>>(emptyList())
    }
    var trustRevoking by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(receiveOpen.value, recvState) {
        if (receiveOpen.value) {
            trustedDevices = com.stardew.assistant.data.TrustedDevices.all(ctx)
            // c234：白名单状态是系统设置，用户去系统页授权后回到 App 就要看到「✓」
            com.stardew.assistant.data.LanReceiveController.refreshWhitelist(ctx)
        }
    }
    // 关抽屉只停**手动**会话（自动会话常驻，见 onDrawerDisposed）
    DisposableEffect(Unit) {
        onDispose { com.stardew.assistant.data.LanReceiveController.onDrawerDisposed() }
    }
    // ⚠ 必须是 **lambda 变量**而不是 `fun`：@Composable 的 body 里只允许 UI 语句，
    //   普通函数声明是非法的（编译期在下一行报「Expecting a top level declaration」，
    //   报错位置离病因很远——这是本文件刚踩过的坑）。
    // c232：起服务收进控制台（startManual = 手动会话，旧行为含确认抽屉）；自动会话
    // 由 MainActivity.onCreate 的 ensureAuto 负责，与抽屉无关。
    val startReceiving: () -> Unit = {
        com.stardew.assistant.data.LanReceiveController.startManual(ctx)
    }

    // v2.68.0 电脑脚本下载：那个 send_save.bat 原先只发在 QQ 群，不在群里的用户压根拿不到。
    // 现在从发布仓直接拉，落到手机的「下载」目录——**文件名必须与电脑上双击的那个一字不差**，
    // 浏览器下载有把 .bat 改名成 .txt 的先例，改了名用户就双击不动了。
    var scriptState by remember { mutableStateOf<UpdateRepo.Script?>(null) }
    var scriptBusy by remember { mutableStateOf(false) }

    val fetchScript: () -> Unit = {
        scriptBusy = true
        scriptState = null          // 上一次的结果先清掉，别让新一次下载期间显示着旧的「✓」
        scope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                UpdateRepo.downloadScript(ctx)
            }
            scriptBusy = false
            scriptState = r
            val msg = when (r) {
                is UpdateRepo.Script.Ok -> "✓ 已存到${r.where}，传到电脑上双击即可"
                is UpdateRepo.Script.Failed -> r.why
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // c237：电脑端模组包下载。跟脚本同一条路（读安装包里的 assets，不联网），
    //   但**状态是分开的**：两者的失败原因、落地文件名、后续步骤都不同，
    //   共用一个状态变量会出现「显示脚本的失败文案、说成模组的问题」。
    var modState by remember { mutableStateOf<UpdateRepo.Script?>(null) }
    var modBusy by remember { mutableStateOf(false) }

    val fetchMod: () -> Unit = {
        modBusy = true
        modState = null            // 同 fetchScript：下载期间别显示着上一次的「✓」
        scope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                UpdateRepo.downloadMod(ctx)
            }
            modBusy = false
            modState = r
            val msg = when (r) {
                is UpdateRepo.Script.Ok -> "✓ 已存到${r.where}，传到电脑上解压即可"
                is UpdateRepo.Script.Failed -> r.why
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ⚠ 只有 Android 8/9 要问权限（走公共目录旧路径），10+ 走 MediaStore 一个权限都不用。
    //   在所有版本上都弹这个框，等于让绝大多数用户白挨一次系统询问却什么也没换来。
    //
    // ⚠ c237：权限回调要记得**是哪一次**在等权限 —— 脚本与模组共用这一个
    //   launcher（它们都要写同一个「下载」目录），不记的话会给模组弹出
    //   脚本的失败文案（「脚本存不进下载目录」），用户会一脸茫然。
    var pendingLegacyTarget by remember { mutableStateOf<LegacyTarget?>(null) }
    val legacyWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingLegacyTarget
        pendingLegacyTarget = null
        if (granted) {
            when (target) {
                LegacyTarget.Mod -> fetchMod()
                else -> fetchScript()
            }
        } else {
            when (target) {
                LegacyTarget.Mod -> {
                    modBusy = false
                    modState = UpdateRepo.Script.Failed(
                        "没拿到存储权限（Android 9 及以下才需要），模组包存不进「下载」目录"
                    )
                }
                else -> {
                    scriptBusy = false
                    scriptState = UpdateRepo.Script.Failed(
                        "没拿到存储权限（Android 9 及以下才需要），脚本存不进「下载」目录"
                    )
                }
            }
        }
    }

    val onDownloadScript: () -> Unit = {
        val needPerm = UpdateRepo.needsStoragePermission() &&
            ContextCompat.checkSelfPermission(ctx, UpdateRepo.LEGACY_WRITE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        when {
            scriptBusy -> Unit                    // 下载中再点只会并发两次写同一个文件名
            needPerm -> {
                pendingLegacyTarget = LegacyTarget.Script
                legacyWriteLauncher.launch(UpdateRepo.LEGACY_WRITE_PERMISSION)
            }
            else -> fetchScript()
        }
    }

    val onDownloadMod: () -> Unit = {
        val needPerm = UpdateRepo.needsStoragePermission() &&
            ContextCompat.checkSelfPermission(ctx, UpdateRepo.LEGACY_WRITE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        when {
            modBusy -> Unit
            needPerm -> {
                pendingLegacyTarget = LegacyTarget.Mod
                legacyWriteLauncher.launch(UpdateRepo.LEGACY_WRITE_PERMISSION)
            }
            else -> fetchMod()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // c227：设置页顶部三栏（外观 / 存档 / 其他）+ 使用指引常驻入口。
        // 使用指引原来占第一张卡（v2.99.0「最显眼」）；tab 化后藏进某一栏反而
        // 违背原意 ⇒ 挪到 BackBar 右侧，任何栏一键直达、不占卡片位。
        // 「重新看引导」一并挪到使用指引页页头（帮助语境里的次级入口）。
        BackBar("设置", onBack, right = {
            Box(
                Modifier
                    .background(c.slot)
                    .slotBevel(inset = 2.dp, width = 1.dp)
                    .border(2.dp, c.slotEdge)
                    .clickable { onOpenGuide() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) { PixelText("📖 指引", color = c.ink) }
        })
        PixelSegmented(
            options = SETTINGS_TABS,
            selected = settingsTab,
            onSelect = { settingsTab = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        // key(settingsTab)：切栏重建 LazyColumn ⇒ 滚动位置回到顶
        // （不重建的话，从「其他」滚到底切到「外观」会停在半截）。
        key(settingsTab) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (settingsTab) {
                    // c230：三栏卡片体逐字搬进三个页签文件（见各文件头注释）；
                    // 本文件只留状态、launcher 与调度。
                    0 -> settingsAppearanceTab(
                        snap, repo, scope, c, ctx,
                        fontPicker, customMeta, customId, confirmDeleteFont,
                    )
                    1 -> settingsSavesTab(
                        c, snap, repo,
                        exportLauncher, importLauncher, saveImportLauncher,
                        saveParsing, confirmClearChecks, receiveOpen, steamGuideOpen,
                    )
                    else -> settingsAboutTab(
                        snap, repo, scope, c, ctx,
                        checkingUpdate, updateState,
                        showLicense,
                    )
                }
            }
        }
    }

    // v2.64.0：「更新日志」抽屉随入口一并移除（应用户要求）。
    // CHANGELOG 数据保留在文件底部，仍作 publish_gitee.py 的 notes 单源。

    // v2.65 导入游戏存档的确认抽屉。与备份导入分开：备份是「覆盖」，这里是「只补不删」，
    // 语义不同就不能共用同一个确认框——文案必须一字不差地说明不会删任何已有勾选。
    // c217：抽屉关掉（取消 / 点外面 / 返回）⇒ 丢掉待落盘的农场数据。
    //   不丢的话 pending 一直挂着，下次导入别的存档时可能被误当成这次的。
    //   （pending 是**进程级单例**，跨导入轮次共用一个槽位。）
    val dismissSavePreview: () -> Unit = {
        com.stardew.assistant.data.LanReceiveController.discardPreview()
    }
    DetailDrawer(visible = savePreview != null, title = "导入游戏存档", onDismiss = dismissSavePreview) {
        val pv = savePreview
        if (pv != null) {
            val sounds = com.stardew.assistant.ui.rememberSounds()
            // v2.96：正文必须套 PixelCard。
            //   抽屉底是**深木色**（DetailDrawer 用 c.wood），而 ink / inkDim 是给浅色
            //   羊皮纸设计的深棕——直接压在木色上对比度极低，正文几乎看不清
            //   （用户 2026-10-26 反馈「进度和背景的字体对比度有点低」）。
            //   其余抽屉（导入备份 / 删除字体 / 致谢名单 / 数据来源…）**一律套卡片**，
            //   这一处是唯一漏网的，行为不一致本身就是 bug。
            //   顺带补 verticalScroll：勾选条目可能几十行，卡片超出会被抽屉裁掉看不见。
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PixelCard {
                    pv.farmerName?.let { PixelText("角色：$it", color = c.ink) }
                    Spacer(Modifier.height(2.dp))
                    PixelText("将从这份存档勾上 ${pv.keys.size} 条：", color = c.ink)
                    pv.groups.forEach { PixelText("· ${it.label}", color = c.inkDim) }
                    if (pv.museumFellBack) {
                        Spacer(Modifier.height(2.dp))
                        // 正常口径是「已捐赠」；这份存档读不到博物馆的捐赠记录，
                        // 已退回按「捡到过」算——数字偏大但不会勾错，必须让用户知道。
                        PixelText(
                            "这份存档没有博物馆捐赠记录，博物馆两项按「捡到过」计算，可能偏多。",
                            color = c.inkDim,
                        )
                    }
                    if (pv.unmapped > 0) {
                        Spacer(Modifier.height(2.dp))
                        PixelText(
                            "另有 ${pv.unmapped} 条存档里有、但 App 图鉴无法对应，已跳过（不是错误）。",
                            color = c.inkDim,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        "只补不删：你已有的勾选一条都不会动。可以导入多次，结果是累加。",
                        color = c.ink,
                    )
                    PixelText("写入前会自动备份当前进度到 APP 私有目录。", color = c.inkDim)
                    PixelText("不会修改你的游戏存档文件。", color = c.inkDim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("取消", onClick = dismissSavePreview)
                    PixelButton("确定导入", onClick = {
                        scope.launch {
                            val data = savePreview
                            val backup = repo.autoBackup()
                            com.stardew.assistant.data.LanReceiveController.consumePreview()
                            if (data == null) return@launch
                            // 只补不删：并集。手动勾的条目一条不丢，误导入也能靠备份救回。
                            repo.replaceChecks(snap.checks + data.keys)
                            // v2.74.0：农场报告统计也存到**当前存档位**（跟打勾同一份存档）。
                            //   与打勾一样走「用户确认后才落盘」：确认之前一个字节都没写。
                            repo.setReport(snap.activeProfile, data.report)
                            // c217：农场与图鉴状态也在这里落盘（**确认之后**，不再更早）。
                            //   落进**当前存档**名下 —— 每个存档一份自己的缓存，切档不串。
                            val pend = com.stardew.assistant.data.FarmTodayCache.takePending()
                            val farmOk = com.stardew.assistant.data.FarmTodayCache.commit(
                                ctx, snap.activeProfile, pend,
                            )
                            sounds.complete()
                            val reportTip = if (data.report != null) "，农场报告已就绪" else ""
                            val farmTip = if (farmOk) "，农场数据已就绪" else ""
                            android.widget.Toast.makeText(
                                ctx,
                                if (backup != null) "✓ 已勾上 ${data.keys.size} 条$reportTip$farmTip（备份在 $backup）"
                                else "✓ 已勾上 ${data.keys.size} 条$reportTip$farmTip（备份未创建）",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    })
                }
            }
        }
    }

    // v2.86.0 从 Steam 云获取存档（无需电脑）。
    //
    // ⚠ 关抽屉**不**停前台服务 —— 用户可能是「打开 Steam 页 → 关掉抽屉 → 去浏览器下载」，
    //   服务得继续活着等他下完。服务自己会在导入完成/超时时停。
    com.stardew.assistant.ui.SteamImportGuide(
        visible = steamGuideOpen.value,
        scope = scope,
        onDismiss = { steamGuideOpen.value = false },
        // 「我已下载，手动选择文件」→ 走**专用**的 launcher（不带 mime 过滤，
        //   否则无扩展名的裸存档在系统窗口里找不到），选中后先嗅探再解析。
        onManualPick = {
            steamGuideOpen.value = false
            steamManualLauncher.launch(arrayOf("*/*"))
        },
        onOpenFailed = { why ->
            android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
        },
        // 「查看农场报告」→ v2.87.0：**直接看长图**，不再把人支到别处。
        // 报告数据是导入时按存档位落好的（跟打勾同一份存档），所以这里拿当前存档位的那份。
        onOpenReport = { profileId ->
            steamGuideOpen.value = false
            com.stardew.assistant.data.SteamImportCoordinator.reset()
            if (snap.report != null) {
                reportPreviewOpen = true
            } else {
                android.widget.Toast.makeText(
                    ctx, "这份存档还没有报告数据", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )

    // v2.87.0：报告长图的预览窗。报告卡本体在今日页「我的农场」，但**预览窗在这里也能起**
    // —— 导入完成的提示里点「查看农场报告」不必先跳走。
    if (reportPreviewOpen && snap.report != null) {
        com.stardew.assistant.ui.report.ReportPreviewDialog(
            report = snap.report,
            onDismiss = { reportPreviewOpen = false },
        )
    }

    // v2.67.0 从电脑接收。把「怎么压包、怎么传、怎么找」三步压成电脑上一个脚本。
    DetailDrawer(visible = receiveOpen.value, title = "从电脑接收存档", onDismiss = {
        receiveOpen.value = false
        // c232：只停**手动**会话（自动会话常驻——关个抽屉不能把自动接收关了）
        com.stardew.assistant.data.LanReceiveController.onDrawerDisposed()
    }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PixelCard {
                // c238：**精简**。c237 把模组说明写了两遍（首屏卡 + 下载卡各一遍
                //   安装步骤），加上脚本卡里的 PowerShell 命令和长 URL，整个抽屉
                //   长得像说明书而不像个设置页。用户原话：「这个页面咱们写得太啰嗦了，
                //   你精简一下，写得官方一点」。
                //   口径：**每件事只说一次，说完就收**。
                //   - 模组的好处 + 怎么装 → 合并成下面这块（下载卡只留按钮和状态）；
                //   - 脚本的 PowerShell 命令 / 长 URL → 收进折叠区，默认不摊开；
                //   - 「只收不传」这类安全承诺 → 保留，但压成一行。
                PixelText("推荐：装电脑端模组", color = c.green)
                Spacer(Modifier.height(4.dp))
                PixelText(
                    "装上之后，游戏里一保存就自动把存档推到这台手机，" +
                        "不用开电脑上的窗口、不用每次输验证码。",
                    color = c.ink,
                )
                Spacer(Modifier.height(4.dp))
                PixelText(
                    "① 下载下面那个压缩包；② 电脑上解压，把 StardewAutoPush 文件夹" +
                        "放进《星露谷物语》的 Mods 目录；③ 游戏里按 F5 推一次。",
                    color = c.inkDim,
                )
                PixelText(
                    "首次要敲一次 6 位验证码，之后自动。需要先装 SMAPI。",
                    color = c.inkDim,
                )
            }
            PixelCard {
                when (val s = recvState) {
                    is SaveReceiver.State.Listening -> {
                        PixelText("正在等电脑推送…", color = c.green)
                        if (com.stardew.assistant.data.LanReceiveController.autoSession) {
                            // c232：这是启动即常驻的自动会话——把这件事告诉用户，
                            // 他才能明白为什么不用点「开始接收」也一直在收。
                            PixelText("自动接收中：App 打开期间常驻，无需手动开窗。", color = c.inkDim)
                        }
                        Spacer(Modifier.height(4.dp))
                        if (s.addresses.isEmpty()) {
                            PixelText(
                                "没找到局域网地址：请确认手机已连 WiFi（或开着热点）。",
                                color = c.inkDim,
                            )
                        } else {
                            // ⚠ v2.75.0：电脑端会**广播自动找这台手机**，
                            //   所以这里显示的 IP 只是**兜底**（广播被挡时才要手输）。
                            //   端口不用抄——电脑自己扫 38217~38236 那一段。
                            PixelText("手机上的 IP（一般不用填，电脑端会自己找到你）：", color = c.inkDim)
                            s.addresses.forEach { PixelText(it, color = c.ink) }
                        }
                        if (!s.autoFind) {
                            // ⚠ 自动发现服务没起来 → 电脑端一定找不到这台手机。
                            //   不说的话，用户只会看到「没找到」，不知道该手输 IP。
                            Spacer(Modifier.height(2.dp))
                            PixelText(
                                "⚠ 自动发现没起来（UDP 端口被占），" +
                                    "电脑上找不到这台手机——请手动把上面的 IP 输进去。",
                                color = c.red,
                            )
                        }
                        SaveReceiver.wifiName(ctx)?.let {
                            Spacer(Modifier.height(2.dp))
                            PixelText("当前 WiFi：$it（电脑要连同一个）", color = c.inkDim)
                        }
                    }
                    // v2.75.0：有连接在等验证码。主 UI 要同步说清楚，
                    //   否则用户看到「正在等电脑推送…」却有个弹窗，会以为服务卡住了。
                    is SaveReceiver.State.AwaitingCode -> {
                        PixelText("等待验证码确认…", color = c.gold)
                        Spacer(Modifier.height(4.dp))
                        PixelText(
                            "电脑上那行「本次验证码：xxxxxx」，照着敲进弹出来的输入框。",
                            color = c.inkDim,
                        )
                        PixelText(
                            "这个码只应该出现在你自己电脑的窗口里——别人发给你的码，别输。",
                            color = c.inkDim,
                        )
                    }
                    is SaveReceiver.State.Failed ->
                        PixelText("✗ ${s.msg}", color = c.inkDim)
                    is SaveReceiver.State.Received ->
                        PixelText("已收到，正在解析…", color = c.inkDim)
                    // v2.67.3：服务自己结束了（超时/收完包），UI 必须说实话——
                    //   停在「正在等电脑推送…」会让人对着已关的端口白忙。
                    is SaveReceiver.State.Stopped -> {
                        PixelText("⏹ ${s.reason}", color = c.inkDim)
                        PixelText("要再收一个包，点下面「开始接收」重新打开。", color = c.inkDim)
                    }
                    SaveReceiver.State.Idle -> {
                        PixelText(
                            "装好模组就不用管：手机和电脑连同一个 WiFi，" +
                                "游戏里一保存，存档就自己过来了。",
                            color = c.inkDim,
                        )
                    }
                }
                // c232：信任设备管理。撤销立即生效（每次请求现读名单）；
                // 全部撤销后自动接收也停（onTrustChanged）。
                PixelCard {
                    PixelText("已信任的电脑", color = c.ink)
                    if (trustedDevices.isEmpty()) {
                        PixelText("还没有。电脑端推送一次、输一次码，就会记在这里。", color = c.inkDim)
                    } else {
                        trustedDevices.forEach { d ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    PixelText(d.name, color = c.ink)
                                    PixelText(
                                        "配对于 " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                                            .format(java.util.Date(d.pairedAt)),
                                        color = c.inkDim,
                                    )
                                }
                                PixelButton(
                                    if (trustRevoking == d.id) "撤销中…" else "撤销",
                                    onClick = {
                                        if (trustRevoking != null) return@PixelButton
                                        trustRevoking = d.id
                                        scope.launch {
                                            val had = com.stardew.assistant.data.TrustedDevices.revoke(ctx, d.id)
                                            com.stardew.assistant.data.LanReceiveController.onTrustChanged(ctx)
                                            trustedDevices = com.stardew.assistant.data.TrustedDevices.all(ctx)
                                            trustRevoking = null
                                            android.widget.Toast.makeText(
                                                ctx,
                                                if (had) "已撤销「${d.name}」的信任，它下次推送要重新配对"
                                                else "撤销失败，请再试一次",
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                // c233：后台常驻（可选，默认关）。放这里而不是设置页根部——
                //   它是「从电脑接收」的一部分，摆在根部只会给设置页添噪音。
                // ⚠ 默认关 = c232 行为一字不变（无常驻通知、不多占资源）。
                PixelCard(title = "🌙 后台常驻") {
                    PixelSegmented(
                        options = listOf("关", "开"),
                        selected = if (snap.lanForeground) 1 else 0,
                        onSelect = { i ->
                            val on = i == 1
                            scope.launch {
                                repo.setLanForeground(on)
                                // 正在接收的话，立刻让会话切到新状态（开→补服务；关→撤服务）
                                com.stardew.assistant.data.LanReceiveController
                                    .onForegroundPreferenceChanged(ctx, on)
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (on) "已开启后台常驻：息屏切后台也能收存档"
                                    else "已关闭后台常驻：接收只在 App 打开时有效",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                    Spacer(Modifier.height(5.dp))
                    PixelText(
                        "开启后接收挂在一条常驻通知里，息屏、切后台也能收到。",
                        color = c.inkDim,
                    )
                    PixelText(
                        "系统每天最多允许它连续运行约 6 小时，到顶会停掉并提示。",
                        color = c.inkDim,
                    )
                    // c234：白名单引导。⚠ 这一步**不能省**——实测（魅族 20）：
                    //   只开常驻通知而不进白名单，手机退到后台 90 秒后系统会把
                    //   App 冻结（进程在、通知在，但 UDP 不回应、监听端口整个消失），
                    //   电脑就会说「没扫描到手机」。进了白名单才真的收得到。
                    if (snap.lanForeground) {
                        Spacer(Modifier.height(6.dp))
                        if (lanWhitelisted) {
                            PixelText("✓ 已进电池优化白名单，息屏也能收。", color = c.green)
                        } else {
                            PixelText(
                                "⚠ 还差一步：系统会把 App 冻结，息屏就收不到了。点下面按钮授权。",
                                color = c.gold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PixelButton("去授权", onClick = {
                                    // 优先走系统对话框（一步到位）；失败退到列表页。
                                    val ok = com.stardew.assistant.data.BatteryHelper
                                        .requestIgnoreBatteryOptimizations(ctx)
                                    if (!ok) {
                                        com.stardew.assistant.data.BatteryHelper.openBatterySettings(ctx)
                                    }
                                })
                                PixelButton("自启动设置", onClick = {
                                    // 魅族/OPPO/vivo 的第二道锁：系统对话框管不了，得跳 ROM 自己的页。
                                    val ok = com.stardew.assistant.data.BatteryHelper
                                        .openAutostartSettings(ctx)
                                    if (!ok) {
                                        android.widget.Toast.makeText(
                                            ctx,
                                            "这个手机没有自启动设置页，去「设置 → 应用」里允许后台运行",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                })
                            }
                            Spacer(Modifier.height(4.dp))
                            PixelText(
                                "授权后回到 App，这行会变成「✓ 已进白名单」。",
                                color = c.inkDim,
                            )
                        }
                    }
                    if (snap.lanForeground &&
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            ctx, android.Manifest.permission.POST_NOTIFICATIONS,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        PixelText(
                            "⚠ 没通知权限：常驻通知可能不显示，但接收功能不受影响。",
                            color = c.gold,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recvState is SaveReceiver.State.Listening) {
                        PixelButton("停止接收", onClick = {
                            // c232：经控制台停——自动会话被显式停掉也回 Idle
                            com.stardew.assistant.data.LanReceiveController.stop()
                        })
                    } else {
                        PixelButton(if (saveParsing || lanParsing) "解析中…" else "开始接收", onClick = {
                            if (saveParsing || lanParsing) return@PixelButton
                            startReceiving()
                        })
                    }
                }
                Spacer(Modifier.height(6.dp))
                PixelText(
                    // ⚠ 别写 Markdown 星号：App 内不渲染，会原样显示两个星号。
                    // c238：压成一行 —— 安全承诺要看得见，但不用展开成一段。
                    "只收不传：手机上任何文件都读不出去；存档只读不改。",
                    color = c.inkDim,
                )
                // c238：⚠ **修正一句不准确的话**。原来写的是「5 分钟没人连会自动关」
                //   （对**手动**窗口成立），但自动会话（c232 起 App 开着就有）
                //   **没有空闲超时** —— SaveReceiver.serve 里 `sock.soTimeout =
                //   if (autoMode) 0 else IDLE_TIMEOUT_MS`，自动会话是 0（永不超时）。
                //   照抄那句话 = 在自动会话下**说谎**（用户会以为窗口会自己关，
                //   睡一觉回来发现还在，又以为出 bug）。按会话类型分开说。
                if (com.stardew.assistant.data.LanReceiveController.autoSession) {
                    PixelText(
                        "自动接收会一直开着，直到你关掉 App。",
                        color = c.inkDim,
                    )
                } else {
                    PixelText(
                        "手动接收 5 分钟没人连会自动关，关了点「开始接收」重开。",
                        color = c.inkDim,
                    )
                }
            }
            // c238：模组下载卡 —— **只留按钮和状态**。
            //   装法已经写在首屏那张推荐卡里了（c237 时这里又写了一遍，
            //   同一个抽屉里说两遍同一件事 = 啰嗦）。这里只负责「拿到文件」。
            // c239：加一条「**电脑上直接下载**」的路（复制链接）。
            //   用户原话：「目前先下载到手机上，再通过手机传到电脑上也是够麻烦的。
            //   我们可以把这个 mod 也上传到咱们的仓库里，在手机上直接显示链接，
            //   或者让他们从手机端下载。咱们做一个可选的方案」。
            //   ⇒ **两条路并存**：手机下载（读安装包，不联网）/ 电脑直链（复制链接）。
            PixelCard(title = "⬇ 电脑端模组（推荐）") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton(
                        if (modBusy) "处理中…" else "下载到手机",
                        onClick = onDownloadMod,
                    )
                }
                when (val s = modState) {
                    is UpdateRepo.Script.Ok -> {
                        Spacer(Modifier.padding(2.dp))
                        PixelText("✓ ${s.where}（${s.bytes / 1024} KB）", color = c.green)
                    }
                    is UpdateRepo.Script.Failed -> {
                        Spacer(Modifier.padding(2.dp))
                        PixelText("✗ ${s.why}", color = c.inkDim)
                    }
                    // null = 还没点过。别在没点的时候先摆一句「✓」或「✗」，那是假状态。
                    null -> Unit
                }
                Spacer(Modifier.height(5.dp))
                // c239：**两条路并存** —— 手机下载（上面的按钮，读安装包里已有的文件，
                //   不联网）/ 电脑直链（下面这行，点一下复制，粘到电脑浏览器里直接下）。
                //   用户原话：「先下载到手机上，再通过手机传到电脑上也是够麻烦的」。
                // ⚠ 措辞避开「嫌」「烦」两个字 —— 它们**不在字体子集里**，
                //   写进 UI 会让 verify 41.1 当场变红（子集只增不减是既定口径）。
                PixelText("想省掉传文件这一步？复制下面这行，粘到电脑浏览器里直接下：", color = c.inkDim)
                // ⚠ 走 CopyLinkRow（协议链接那套）：点按复制、不跳浏览器，
                //   保持「不主动打开外部页面」的口径一致。
                CopyLinkRow(UpdateRepo.MOD_URL, UpdateRepo.MOD_URL, ctx)
                PixelText(
                    "浏览器会直接存成 zip（这个链接是压缩包，不是网页）。",
                    color = c.inkDim,
                )
            }
            // c237：脚本下载 —— **降为低优先级备选**。
            //   原来这个抽屉一上来就让用户去双击 send_save.bat，那是 v2.67.0
            //   唯一路径时代的说法。模组（c106 起一路做到 c235）才是推荐做法。
            //   ⚠ **脚本不许删**：没装模组的人（或者不想装模组的人）仍然需要
            //   一条「电脑上什么都不用装」的路 —— 那是回归兼容的底线
            //   （任务书验收标准第 7 条：旧 send_save.bat 路径必须照常工作）。
            //   所以这里是「降级 + 说清适用场景」，不是「拿掉」。
            PixelCard(title = "⬇ 电脑端脚本（备选）") {
                PixelText(
                    "不想装模组就用这个：电脑上双击 send_save.bat，手动推存档。" +
                        "每次都要开窗口、每次都要输验证码。",
                    color = c.inkDim,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton(
                        if (scriptBusy) "下载中…" else "下载 send_save.bat",
                        onClick = onDownloadScript,
                    )
                }
                when (val s = scriptState) {
                    is UpdateRepo.Script.Ok -> {
                        Spacer(Modifier.padding(2.dp))
                        PixelText("✓ ${s.where}（${s.bytes / 1024} KB）", color = c.green)
                    }
                    is UpdateRepo.Script.Failed -> {
                        Spacer(Modifier.padding(2.dp))
                        PixelText("✗ ${s.why}", color = c.inkDim)
                    }
                    // null = 还没点过。别在没点的时候先摆一句「✓」或「✗」，那是假状态。
                    null -> Unit
                }
                // c238：PowerShell 命令 + 长 URL **收进折叠区**。
                //   这两条是给「只用电脑、不装这个 App」的人的兜底路径，
                //   平时用不上，摊在主流程里只是噪声（还要占三四行 + 一大段换行的 URL）。
                //   ⚠ 保留原样（v2.82.0 改成 irm -OutFile 是因为 gitee raw 对 .bat
                //   回 text/plain + inline，浏览器不落盘）——只是换个位置，不是删。
                CollapsibleCard(
                    title = "🔧 其它拿法",
                    expanded = altOpen,
                    onToggle = { altOpen = !altOpen },
                    collapsedHint = "PowerShell 一行命令 / 直链",
                ) {
                    PixelText("只用电脑、不装这个 App？复制这行到 PowerShell 里跑一下：", color = c.inkDim)
                    // ⚠ 点按复制，不跳浏览器：一是「不主动打开外部页面」的口径要一致
                    //   （与协议链接同一个惯例，见 CopyLinkRow），二是手机上没人想手打
                    //   六十多个字符的 URL——手打必错，错了只会以为是地址坏了。
                    CopyLinkRow(UpdateRepo.SCRIPT_PS1_CMD, UpdateRepo.SCRIPT_PS1_CMD, ctx, "已复制命令")
                    PixelText("点一下就复制，不用手打。跑完就在你当前那个文件夹里。", color = c.inkDim)
                    Spacer(Modifier.padding(2.dp))
                    PixelText("只想复制地址的话：", color = c.inkDim)
                    CopyLinkRow(UpdateRepo.SCRIPT_URL, UpdateRepo.SCRIPT_URL, ctx)
                    PixelText("在浏览器里打开只会显示文本，不会自动下载。", color = c.inkDim)
                }
            }
            // v2.68.1 脚本使用教程。默认折叠：这个抽屉的主操作是「开始接收」，
            //   教程摊开会把按钮顶出首屏；但它在「第一次用」时是刚需，所以标题要写清楚。
            // ⚠ c237：这是**脚本**的教程（模组的三步在上面的推荐卡里）。
            //   标题要写明是备选路，否则用户以为「电脑上怎么用」就等于这条六步，
            //   会跳过上面推荐的模组。
            CollapsibleCard(
                title = "📖 不用模组时：电脑上怎么用（六步）",
                expanded = tutorialOpen,
                onToggle = { tutorialOpen = !tutorialOpen },
                collapsedHint = "双击 → 选农场 → 输验证码 → 确认导入",
            ) {
                val steps = listOf(
                    "把 send_save.bat 传到电脑上（QQ / 微信 / 数据线 / 网盘都行）。" +
                        "只要这一个文件，不用装任何东西。",
                    "双击它。第一次运行 Windows 可能弹防火墙提示，点「允许访问」。",
                    "窗口会列出电脑上的存档（农场），输入序号后回车；直接回车就是第一个。",
                    "脚本会广播一次自己找到这台手机；万一被防火墙挡了，再把手机上显示的 " +
                        "IP 输进去，端口不用填。",
                    "窗口会显示一个 6 位验证码，敲进手机弹出来的输入框。" +
                        "这个码只应该出现在你自己的电脑上——别人发给你的码别输。",
                    "回到手机：核对数字后点「确定导入」。存档不会被改动。",
                )
                steps.forEachIndexed { i, s ->
                    PixelText("${i + 1}. $s", color = c.ink, modifier = Modifier.padding(vertical = 3.dp))
                }
                Spacer(Modifier.height(4.dp))
                PixelText(
                    "这条是备选路。装了模组就不用每次开窗口、每次输验证码。",
                    color = c.inkDim,
                )
                Spacer(Modifier.height(4.dp))
                PixelText("连不上 / 收不到，通常是这三个原因：", color = c.inkDim)
                val fixes = listOf(
                    "手机和电脑不在同一个 WiFi。电脑走网线、手机走 WiFi 时，路由器可能把它们" +
                        "分到两个网段——让电脑也连上手机那个 WiFi 最稳。",
                    "手机上那个窗口已经关了：手动接收 5 分钟没人连会自动关，" +
                        "重新点一次「开始接收」再发。",
                    "地址抄错了或手机换了 IP。带端口的地址就在手机上那个窗口里，照着重新输一遍。",
                )
                fixes.forEach { s ->
                    PixelText("· $s", color = c.inkDim, modifier = Modifier.padding(vertical = 2.dp))
                }
                Spacer(Modifier.height(4.dp))
                PixelText(
                    "电脑上也可以免输入：send_save.bat --host 手机IP --farm 农场名",
                    color = c.inkDim,
                )
            }
        }
    }

    // c232：验证码弹窗上收到全局宿主（MainActivity）——自动监听的配对请求
    // 可能在任何页面弹出，码框不能只挂在设置页里。

    DetailDrawer(visible = importConfirm != null, title = "导入备份", onDismiss = { importConfirm = null }) {
        val data = importConfirm
        if (data != null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PixelCard {
                    PixelText(data.title)
                    Spacer(Modifier.height(6.dp))
                    data.summary.forEach {
                        PixelText("· $it", color = c.ink, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                val sounds = com.stardew.assistant.ui.rememberSounds()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("取消", onClick = { importConfirm = null })
                    PixelButton("确定导入", onClick = {
                        scope.launch {
                            val backup = repo.autoBackup()
                            importConfirm = null
                            val res = runCatching { repo.importJson(data.jsonText) }
                            if (res.isSuccess) {
                                sounds.complete()
                                val detail = res.getOrNull()?.let { "：$it" } ?: ""
                                if (backup != null) android.widget.Toast.makeText(ctx, "✓ 已导入$detail（备份在 $backup）", android.widget.Toast.LENGTH_LONG).show()
                                else android.widget.Toast.makeText(ctx, "✓ 已导入$detail（备份失败：未创建）", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                sounds.error()
                                android.widget.Toast.makeText(ctx, "导入失败：${res.exceptionOrNull()?.message ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    })
                }
            }
        }
    }

    // v2.95.0：删除自定义字体前的确认。
    // 删了要重新导入（得再挑一次文件），所以先问一句 —— 不确认就直接删是找不回来的操作。
    // c202：由底部抽屉改成**居中确认框**：确认只有两行字，抽屉太占地方
    //（与 ModsPage 的「删除扩展包」同一范式）。
    PixelConfirmDialog(
        visible = confirmDeleteFont.value,
        title = "删除字体",
        message = "确定删除这个字体吗？会删掉手机里存的那份字体文件，并切回像素字体。" +
            "以后想再用，得重新挑一次字体文件。",
        onConfirm = {
            scope.launch {
                // 先把档位切回像素，再删文件 —— 顺序反了的话，
                // 中间那一瞬间会停在一个「选中了一个不存在的字体」的状态上。
                repo.setFontFamily(AppFont.PIXEL)
                com.stardew.assistant.data.CustomFont.delete(ctx, customId)
                repo.setFontCustomId("")
            }
        },
        onDismiss = { confirmDeleteFont.value = false },
        confirmText = "确定删除",
    )

    PixelConfirmDialog(
        visible = confirmClearChecks.value,
        title = "清空图鉴勾选",
        message = "确定清空当前存档的图鉴打勾吗？" +
            "图鉴/收集包/进度的所有已勾记录都会被清掉（进度页计数归零）。" +
            "其他存档不受影响，但现在这条也清不回来——建议先导出备份。",
        onConfirm = {
            scope.launch { repo.replaceChecks(emptySet()) }
            confirmClearChecks.value = false
        },
        onDismiss = { confirmClearChecks.value = false },
        confirmText = "确定清空",
    )

    // c230：协议抽屉搬进 SettingsInfoDrawers.kt。
    //   ⚠ 2026-11-10：致谢 / 赞助两个抽屉已随名单删除（开源后不公开群友 ID）。
    SettingsInfoDrawers(
        ctx,
        showLicenseState = showLicense,
    )

}



private data class ImportConfirm(
    val jsonText: String,
    val version: String,
    val title: String,
    val summary: List<String>,
)

/** v2 全量 / v1 单存档：解析备份 JSON 头，生成给用户确认的预览文案 */
private fun parseImportPreview(text: String, snap: PrefsRepo.Snapshot): ImportConfirm {
    val o = JSONObject(text)
    return if (o.has("profiles")) {
        val names = o.optJSONArray("profile_names")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val active = o.optString("active", names.firstOrNull() ?: "")
        // v2.17 C1：导入已改为「同名覆盖、其余保留」的合并语义，预览必须如实说明
        val same = names.count { it in snap.profiles }
        val keep = snap.profiles.count { it !in names }
        val mergedN = (names + snap.profiles).distinct().size
        ImportConfirm(
            jsonText = text,
            version = "v2",
            title = if (same > 0) "将覆盖 $same 个同名存档，保留 $keep 个本地存档" else "将新增 ${names.size} 个存档",
            summary = buildList {
                add("导入 ${names.size} 个存档：${names.joinToString("、")}")
                add("备份中的活跃存档：$active")
                add("同名存档用备份数据覆盖；其余本地存档与进度不受影响。")
                if (mergedN > PrefsRepo.MAX_PROFILES) {
                    add("合并后共 $mergedN 个、超出 ${PrefsRepo.MAX_PROFILES} 个上限，超出的会被丢弃。")
                }
                add("导入前会自动备份当前状态到 APP 私有目录。")
            },
        )
    } else {
        val year = o.optInt("year", 1)
        val season = o.optInt("season", 0) + 1
        val day = o.optInt("day", 1)
        val nChecks = o.optJSONArray("checks")?.length() ?: 0
        ImportConfirm(
            jsonText = text,
            version = "v1",
            title = "将覆盖你当前激活的存档「${snap.activeProfile}」",
            summary = listOf(
                "旧版单存档备份：年 $year / 季 $season / 日 $day",
                "$nChecks 个打勾",
                "其他存档不变。",
                "导入前会自动备份当前状态。",
            ),
        )
    }
}

/**
 * c237：Android 8/9 那条「写公共目录要走权限」的老路径上，**是谁在等权限**。
 *
 * ⚠ 为什么要有这个：脚本（send_save.bat）与模组包共用同一个
 * `rememberLauncherForActivityResult`（它们都要写同一个「下载」目录）。
 * 权限回调回来时如果不知道刚才是谁点的，就会给模组弹出**脚本的**失败文案
 * （「脚本存不进下载目录」）——用户看着完全对不上号。
 */
private enum class LegacyTarget { Script, Mod }

private val CHANGELOG = listOf(
    "v2.152.1 指引清理" to listOf(
        "使用指引删去打赏与群号",
        "数据说明指向随包协议全文",
    ),
    "v2.152.0 隐私收紧" to listOf(
        "移除致谢与赞助名单",
        "说明改为更新频率放缓",
    ),
    "v2.151.0 项目开源" to listOf(
        "源码公开，欢迎自行修改",
        "去掉联系方式与收款码",
    ),
    "v2.150.0 接收中断重连" to listOf(
        "接收意外中断会自动重连",
        "广播不再谎报端口已开",
    ),
    "v2.149.0 电脑直链下载" to listOf(
        "模组可复制链接到电脑直接下",
        "手机下载那条路照旧可用",
    ),
    "v2.148.0 接收页精简" to listOf(
        "从电脑接收页面文字收紧",
        "同一件事只说一次，不再说两遍",
    ),
    "v2.147.0 电脑端模组优先" to listOf(
        "推荐装模组，保存就自动推存档",
        "电脑脚本降为备选，照旧可用",
    ),
    "v2.146.0 协议归属写实" to listOf(
        "同步协议未参考外部项目",
        "局域网说明按查证结论改写",
    ),
    "v2.145.0 局域网协议说明" to listOf(
        "注明同步协议参考自电脑脚本",
        "关于页口径随自动接收改正",
    ),
    "v2.144.0 后台接收修复" to listOf(
        "息屏退后台也能收到存档",
        "补一步授权，系统不再冻结接收",
    ),
    "v2.143.0 后台常驻接收" to listOf(
        "息屏切后台也能收存档",
        "接收挂在常驻通知里，可随时停",
    ),
    "v2.142.0 存档自动同步" to listOf(
        "电脑保存后自动导入存档",
        "App 开着就能接收，信任设备可撤销",
    ),
    "v2.141.0 跳板下滑修复" to listOf(
        "跳到页面里往下滑不再误关",
        "下滑关闭只留在顶部标题条",
    ),
    "v2.140.0 液态玻璃升级" to listOf(
        "玻璃浓度可调，跳板改贴边样式",
        "卡顿自动降低模糊保流畅",
    ),
    "v2.139.0 设置页改三栏" to listOf(
        "外观、存档与其他分栏更好找",
        "使用指引挪到顶栏一键直达",
    ),
    "v2.138.0 瘦身：文字更短，包更小" to listOf(
        "设置页与各页说明精简约九百字",
        "安装包缩小约五百五十千字节",
    ),
    "v2.137.0 小组件换纯白新装" to listOf(
        "小组件木框移除更清爽",
        "衬底改纯白四季文字更清晰",
    ),
    "v2.136.0 小组件明天按钮更好按" to listOf(
        "小组件「明天」热区放大一倍多",
        "空列表时分享按钮不再消失",
    ),
    "v2.135.0 对比度收尾：裸字上纸" to listOf(
        "十八处低对比文字落米黄纸底",
        "压槽底的字统一改墨字保金边",
    ),
    "v2.134.0 修好加工分类滑动闪退" to listOf(
        "同名成品的格子各自独立",
    ),
    "v2.133.0 图鉴翻页修复 + 打包翻页更稳" to listOf(
        "抽屉点同包名字不会再关不掉",
        "加工同名成品不再点错条目",
    ),
    "v2.132.0 收集包翻页提示改口" to listOf(
        "图鉴抽屉点名字就地翻页",
    ),
    "v2.131.0 收集包翻页不丢语境" to listOf(
        "抽屉点同包名字就地翻页",
        "段标题与空态加米黄纸底",
    ),
    "v2.130.0 图鉴详情翻页不再越出筛选" to listOf(
        "图鉴列表与详情翻页同一套筛选",
        "小组件待办数不再残留昨天",
    ),
    "v2.129.0 修复多存档农场数据串档" to listOf(
        "各存档农场图鉴数据互不串档",
    ),
    "v2.128.0 设置里可一键关闭音效" to listOf(
        "设置页新增加效开关",
    ),
    "v2.127.0 图鉴勾选也有震动反馈" to listOf(
        "图鉴勾选补上震动",
    ),
    "v2.126.0 修复勾选和按钮震动无反应" to listOf(
        "震动改走系统触觉通道",
    ),
    "v2.125.0 操作手感优化，震动更明显" to listOf(
        "勾选和按钮震动走系统触觉",
    ),
    "v2.124.0 操作更有手感，图鉴进度可一键清空" to listOf(
        "勾选按键新增震动反馈",
        "今日页可手动刷新",
    ),
    "v2.123.0 图片鉴补官方贴图，新增加工分类" to listOf(
        "制作大件补上官方图标",
        "新增加工图鉴分类",
    ),
    "v2.122.0 鱼塘扩容补全，制作图鉴补到 169 条" to listOf(
        "每条鱼塘标注官方扩容任务",
        "制作补七十六条官方配方",
    ),
    "v2.121.0 图鉴版面重排，条目区占满全屏" to listOf(
        "特别关心与筛选区随列表滚动",
        "勾选分段常驻顶部随时切换",
    ),
    "v2.120.0 送礼避雷按官方重校，档位错位全纠正" to listOf(
        "村民礼物五档按游戏内判定重算",
        "黄水仙等八处重复条目归位",
    ),
    "v2.119.0 图鉴勾选可锁定，数据按官方 1.6.15 全面校正" to listOf(
        "图鉴勾选三段旁加锁定钮防手滑误勾",
        "商店鱼价作物等数据按官方逐项校正",
    ),
    "v2.118.0 出货图鉴标出三档价，礼物表按官方重校" to listOf(
        "银金铱三档价钱直接写出来",
        "村民礼物表按官方数据逐条校正",
    ),
    "v2.117.0 出货图鉴标出售价" to listOf(
        "每样物品标出能卖多少钱",
        "九张图换回原样，三位村民补上头像",
    ),
    "v2.116.0 背包物品认全，三位村民写明不收礼" to listOf(
        "存档物品补上中文名，图鉴能给准数",
        "三位村民写明不收礼",
    ),
    "v2.115.0 设置页补两个出口" to listOf(
        "外观可一键恢复默认，引导可重看",
        "图标与指引不再占住主线程",
    ),
    "v2.114.0 删除确认改成居中弹窗" to listOf(
        "删除确认改成屏幕中间的小框",
        "点遮罩或按返回都是取消",
    ),
    "v2.113.0 修按钮手感与档位对应" to listOf(
        "搜索框与星标按钮变大，长名字不再顶行",
        "删扩展包先确认，主题档位不再错位",
    ),
    "v2.112.0 修四处体验问题" to listOf(
        "农场页不再先闪「未导入」，进度页统计不再重算",
        "数据失败可重试，搜索结果可展开全部",
    ),
    "v2.111.0 新增切页横滑转场" to listOf(
        "底栏切换页签时内容左右滑入",
    ),
    "v2.110.0 修复冷启动闪屏" to listOf(
        "冷启动切页时不再整屏闪烁",
    ),
    "v2.109.0 修复弹窗交互问题" to listOf(
        "点击空白处可关闭弹窗，弹窗从底部滑出，与背景动画同步",
    ),
    "v2.108.0 修复弹窗显示异常" to listOf(
        "弹窗恢复清晰显示，点击空白处可关闭",
    ),
    "v2.107.0 详情弹窗细节打磨" to listOf(
        "移除下滑关闭，点空白处即可关闭，过渡更顺滑",
    ),
    "v2.106.0 详情弹窗更顺手" to listOf(
        "下滑即可关闭弹窗，弹出动画更从容",
    ),
    "v2.105.0 详情弹窗体验优化" to listOf(
        "下滑不再误关详情弹窗，打开时背景加深、模糊、轻微推后",
    ),
    "v2.104.0 补齐缺失的物品图标" to listOf(
        "礼物与商店商品共 32 个条目换上官方图标",
    ),
    "v2.103.0 优化列表滚动与搜索的流畅度" to listOf(
        "村民反查输入更跟手，列表滑动更顺滑",
    ),
    "v2.102.0 修正一处不存在的加工配方" to listOf(
        "加工利润列表移除查无此物的错误条目",
    ),
    "v2.101.0 补齐图标，送礼避雷更清楚" to listOf(
        "图鉴与收集包补齐图标，小鸡代码订正",
        "村民反查避雷显示头像，可点进详情",
    ),
    "v2.100.0 图鉴与存档打通，今日页给建议" to listOf(
        "图鉴条目显示你的状态，缺件能查去哪拿",
        "今日页按存档算今天该做什么",
    ),
    "v2.99.0 使用指引 + 图鉴与跳板更顺手" to listOf(
        "新增使用指引：逐条列出功能位置，可直接跳过去",
        "收集簿可跳图鉴、分类标题更清晰、跳板可跟手下滑、法师塔材料入库",
    ),
    // ⚠ v2.96.0：v2.93.0 / v2.94.0 这两版**从未对外发布过**（线上一直是 v2.95.0），
    //   按既定口径「没发布过的版本不留版本行」并进 v2.95.0 —— 与 v2.76.0→v2.77.0、
    //   v2.91.0→v2.92.0 同款。已发布的 v2.95.0 发布说明里本来就把「字体三档」
    //   与「NPC 行程表」两条都写进去了，这里只是让 App 内日志跟实际发出去的一致。
    "v2.95.0 导入自定义字体 + NPC 行程表" to listOf(
        "新增导入字体：可自选TTF字体文件替换界面字体",
        "新增NPC行程表：34位原版村民逐时段去向",
    ),
    "v2.92.0 今日页加「跳到下一日」+ 计算页块数按存档分开" to listOf(
        "通用日历新增「下一日」按钮，点一下日期往后推一天",
        "计算页地块块数按存档分开记，切换存档不再串数",
    ),
    "v2.90.0 补齐 Steam 与 Valve 的归属声明" to listOf(
        "协议页新增「Steam 云存档」归属说明",
        "引导页顶部即可看到非官方声明",
    ),
    "v2.89.0 小组件可切下一天 + 计算页日期跟今日页同步" to listOf(
        "小组件上点「明天」把日期往后推一天",
        "计算页的日期跟今日页是同一个",
    ),
    "v2.88.0 农场年度报告移到「我的农场」" to listOf(
        "今日页「我的农场」那一栏直接看报告",
        "设置页不再放这张卡",
    ),
    "v2.87.0 修：报告入口与多存档选择" to listOf(
        "点「查看农场报告」直接看报告长图",
        "下到多份存档时可自选一份导入",
    ),
    "v2.86.0 新增从 Steam 云获取存档" to listOf(
        "不用电脑，在 Steam 官网下载后自动导入",
        "账号密码由你在官网自己输入",
    ),
    "v2.85.0 修：更新弹窗一直闪" to listOf(
        "检测到新版后弹窗不再反复闪",
        "关掉之后不会又冒出来",
    ),
    "v2.84.0 修正四个作物的译名与数据" to listOf(
        "四个作物名改回游戏里的叫法",
        "修正售价、生长天数与季节",
    ),
    "v2.83.0 今日页加农场小地图" to listOf(
        "看清每块地在哪、今天能收多少",
        "点格子能看那块地种的是什么",
    ),
    "v2.82.0 电脑脚本改用一行命令下载" to listOf(
        "浏览器下不来脚本，改成复制一行命令",
        "不装这个 App 也能拿到 send_save.bat",
    ),
    "v2.81.0 今日页加「我的农场」" to listOf(
        "导入存档后显示今天能收什么、收完多少钱",
        "还有马上要熟的、枯死待清理的、该摸的牲畜",
    ),
    "v2.80.0 鱼价排行加鱼图，点鱼不跳页" to listOf(
        "排行每行直接显示鱼图",
        "点鱼在本页弹详情，不再跳图鉴页",
    ),
    "v2.79.0 小鸡代码补齐到 162 条" to listOf(
        "纯数字取名可用，带前缀要走作弊菜单",
        "编号按官方物品 ID 逐条核对",
    ),
    "v2.78.0 计算页加鱼价排行" to listOf(
        "74 种鱼按卖出价排序，附银金铱品质价",
        "点鱼名直达图鉴钓点与四档售价",
    ),
    "v2.77.0 底栏液态玻璃，许可页改名" to listOf(
        "选中标签跟手滑动，「跳到…」换玻璃弹层",
        "许可页改为「数据来源与协议」，不再写开源",
    ),
    "v2.75.1 SVE 鱼图鉴按新数据渲染" to listOf(
        "SVE 鱼显示难度、季节、天气和钓点",
        "季节天气筛选也能筛到 SVE 鱼",
    ),
    "v2.75.0 从电脑接收加验证码" to listOf(
        "同一 WiFi 下别人不能乱塞文件",
        "电脑显示 6 位码，手机上核对",
    ),
    "v2.74.3 修下载脚本存不进下载目录" to listOf(
        "电脑脚本能存进「下载」目录",
        "重复下载不会多出副本",
    ),
    "v2.74.2 年度报告改报纸版式" to listOf(
        "长图改成鹈鹕镇新闻特刊",
        "字号按手机实际大小重排",
    ),
    "v2.74.1 报告长图改版式" to listOf(
        "长图改成像素卡片墙",
        "四色分区 + 硬阴影卡片",
    ),
    "v2.74.0 农场年度报告" to listOf(
        "导入存档后可生成农场报告长图",
        "自动算称号与彩蛋，可存相册",
    ),
    "v2.73.14 扩展包入口换位置" to listOf(
        "扩展包入口移到进度页",
    ),
    "v2.73.13 扩展包可分开装" to listOf(
        "扩展包拆成村民/作物/料理/鱼/商店/地点",
        "按需下载，不用下整个大包",
    ),
    "v2.73.12 搜索与图标细化" to listOf(
        "扩展包内容也能全局搜到",
        "SVE 作物图标与原版同清晰度",
    ),
    "v2.73.11 SVE 地点图鉴" to listOf(
        "扩展包地点进图鉴，关联对应 NPC",
    ),
    "v2.73.10 SVE 红心事件" to listOf(
        "可结婚村民详情页加红心事件链",
        "列出 2/4/6/8/10 心节点与触发条件",
    ),
    "v2.73.9 SVE 鱼与商店" to listOf(
        "扩展包新鱼进钓鱼图鉴",
        "图鉴新增商店分类，查谁在哪开店",
    ),
    "v2.73.8 SVE 料理图标" to listOf(
        "SVE 料理显示包内图标",
        "料理图标与原版同密度",
    ),
    "v2.73.7 SVE 料理图鉴" to listOf(
        "扩展包料理进图鉴，标注解锁条件",
    ),
    "v2.73.6 计算页作物图标" to listOf(
        "计算页 SVE 作物显示包内图标",
    ),
    "v2.73.5 SVE 作物图标与角标" to listOf(
        "SVE 作物显示包内图标",
        "图鉴作物区改角标，去掉分区头",
    ),
    "v2.73.4 SVE 作物图鉴" to listOf(
        "扩展包作物进图鉴",
        "计算页按季节算能种到 SVE 作物",
    ),
    "v2.73.3 每次打开都查更新" to listOf(
        "打开 App 即检查一次新版本",
        "更新频率口径同步改诚实",
    ),
    "v2.73.2 扩展包更新修复" to listOf(
        "更新扩展包后新内容立即生效",
        "不再需要重启查看新数据",
    ),
    "v2.73.1 SVE 行程" to listOf(
        "扩展包新增概要行程，详情页可查",
        "当季去处按存档季节高亮",
    ),
    "v2.73.0 SVE 五段礼物" to listOf(
        "扩展包补齐喜欢讨厌与中立",
        "反查认出所有鱼类等类别",
    ),
    "v2.72.2 计算与攻略" to listOf(
        "计算页的季节日期地块不再重开就丢",
        "攻略正文底部不再被胶囊遮住",
    ),
    "v2.72.1 三项修复" to listOf(
        "夜间模式下底部栏选中项不再看不见",
        "三种地块数分开记，图鉴关掉不再弹回",
    ),
    "v2.72.0 Mod 扩展包" to listOf(
        "村民页可下载扩展包并带头像",
        "开关关掉即恢复原版列表",
    ),
    "v2.71.1 更新说明更易读" to listOf(
        "更新说明垫米色衬底，不再随季节看不清",
        "更新说明字号由最小号提升为正文号",
    ),
    "v2.71.0 村民特别关心" to listOf(
        "村民页可给村民点特别关心",
        "图鉴页集中显示其最爱物品",
    ),
    "v2.70.2 底栏选中标签圆角对齐" to listOf(
        "选中标签的圆角与底栏对齐",
        "圆角不再被裁掉一截",
    ),
    "v2.70.1 底栏玻璃效果" to listOf(
        "底部导航栏加入玻璃质感，设置里可切换",
        "生日卡点击直达村民页，反查支持礼物名部分匹配",
    ),
    "v2.69.0 更新提示更醒目、商店目录不再刷屏" to listOf(
        "更新提示不再因检查间隔而漏掉",
        "商店目录按店折叠并支持店内搜索",
    ),
    "v2.68.3 七处最爱礼物名归位" to listOf(
        "修正 7 处最爱礼物名与图鉴译名不一致",
        "统一为游戏内正式名称",
    ),
    "v2.68.2 补怪物掉落物图鉴" to listOf(
        "新增怪物掉落物图鉴分组",
        "修正法师与阿比盖尔的最爱礼物名",
    ),
    "v2.68.1 脚本地址点按复制" to listOf(
        "脚本下载地址点一下即复制",
        "接收页新增脚本使用教程",
    ),
    "v2.68.0 电脑脚本可在本机下载" to listOf(
        "接收窗口内可直接下载电脑端脚本",
    ),
    "v2.67.3 修接收窗口假状态" to listOf(
        "接收窗口超时或收包后界面同步显示已停止",
        "无人连接自动关闭的时长放宽到 5 分钟",
    ),
    "v2.67.1 电脑端双击即用" to listOf(
        "从电脑接收不再需要装 Python",
    ),
    "v2.67.0 存档可从电脑直传" to listOf(
        "同一 WiFi 下从电脑直传存档，免去压缩与传文件",
        "修正存档目录提示（StardewValley\\Saves）",
    ),
    "v2.66.1 补两条古物映射" to listOf(
        "导入存档可勾选精灵珠宝与手部骨骼",
    ),
    "v2.66.0 修博物馆导入口径" to listOf(
        "博物馆改按已捐赠计算，补全矿物图鉴映射",
    ),
    "v2.65.1 修中文存档导入" to listOf(
        "修正真机导入存档失败，支持中文压缩包",
    ),
    "v2.65.0 导入游戏存档" to listOf(
        "设置页新增导入游戏存档，自动补勾图鉴进度",
    ),
    "v2.63.0 支持开发者" to listOf(
        "关于页新增支持开发者，附微信与支付宝收款码",
    ),
    "v2.62.0 心事件不再剧透" to listOf(
        "村民页心事件默认收起，点标题才展开",
    ),
    "v2.61.0 新版本会提示了" to listOf(
        "每天最多联网检查一次，有新版在今日页顶部提示",
        "更新说明分「新增/优化」分层显示，可在设置里关掉提示",
    ),
    "v2.60.1 开源协议地址修正" to listOf(
        "开源协议页的仓库地址改为发布仓 data/ 目录",
    ),
    "v2.60.0 开源与数据来源" to listOf(
        "关于页新增开源与数据来源，附协议全文",
    ),
    "v2.59.0 字号可调" to listOf(
        "新增字号：标准 / 大 / 特大，设置页即点即生效",
    ),
    "v2.58.0 电影院与失踪的收集包" to listOf(
        "进度页收集包新增电影院，写明献祭流与 Joja 流两条解锁路线",
        "六件材料逐条标注获取途径，可打勾记录",
    ),
    "v2.57.0 今日待办可自定义" to listOf(
        "待办支持添加、改名、删除与恢复默认",
        "旧存档的今日勾选已自动迁移不受影响",
    ),
    "v2.56.0 商店目录册" to listOf(
        "攻略新增商店目录：15 家店 486 件",
        "含金币价、第 2 年调价与换购货币",
    ),
    "v2.55.0 在线更新检查" to listOf(
        "设置页新增检查更新，一键获取新版",
        "仅点击按钮时联网，不上传任何数据",
    ),
    "v2.54.0 三位村民心事件" to listOf(
        "潘姆、文森特、贾斯的心事件已收录",
        "两小孩八心共享事件解锁大葱大师",
    ),
    "v2.53.0 村民补齐三位小朋友" to listOf(
        "新增潘姆、文森特、贾斯的生日与礼物偏好",
        "三位头像已收录，村民页正常显示",
    ),
    "v2.52.0 节日与季节配色可读性" to listOf(
        "夏28 与冬12-13 两天正文不再看不清",
        "四季与日夜配色的次要文字对比度提高",
    ),
    "v2.51.0 修正鱼缸收集包鱼名" to listOf(
        "鱼缸「特色鱼类」包的幽灵鱼更正为鬼鱼",
        "这两个名字对应的是两种不同的鱼",
    ),
    "v2.45.0 物品图标补齐" to listOf(
        "菜谱、觅食、怪物、小鸡代码等处物品图标现已齐全",
        "移除一条游戏中并不存在的怪物条目",
    ),
    "v2.44.0 图标补全与错名修正" to listOf(
        "菜谱、小鸡代码、村民最爱等处不再显示首字占位块",
        "婚姻册艾利奥特头像与收集包黏液图标现已正常",
    ),
    "v2.43.0 浣熊商店补齐图标" to listOf(
        "12 项兑换物全部有图，不再显示首字占位块",
        "霜瓜种子沿用同页其他种子的图标样式",
    ),
    "v2.42.0 图鉴配方速查" to listOf(
        "图鉴详情可看材料与获取途径，不用翻分类",
        "菜谱与制作条目内不重复展示",
    ),
    "v2.41.0 浣熊商店与建造材料" to listOf(
        "总览新增浣熊兑换 12 项，材料与解锁条件逐条可查",
        "建筑的材料改为直接平铺，不再藏在「详细属性」里",
    ),
    "v2.40.0 临时查看原路返回" to listOf(
        "村民页最爱物品点一下跳图鉴，看完回到原抽屉",
        "攻略页点物品名跳图鉴，返回键送回原来那一页",
    ),
    "v2.39.0 群友三条" to listOf(
        "新手教程的字更清楚",
        "三个收集包按原版「选几样」判定，金皮西葫芦改回游戏里的名字",
    ),
    "v2.38.0 生日会挑人" to listOf(
        "第一年不再提前喊肯特的生日——他那时还在部队，第二年春天才回村",
    ),
    "v2.36.0 图像归位" to listOf(
        "蚌 · 绿宝石 · 黄水晶，三处归位原貌",
    ),
    "v2.35.0 每一层都有归途" to listOf(
        "二级页左上角，一律有「← 返回」",
        "钓鱼地图并入图鉴，攻略页不再设卡",
    ),
    "v2.34.0 转场有了方向" to listOf(
        "进入自右，退场向右，与手势同向",
    ),
    "v2.33.0 图鉴改为网格" to listOf(
        "列表让位网格，一屏翻倍",
        "细节默认折叠，相关一跳即达",
    ),
    "v2.32.0 打勾的版图" to listOf(
        "作物 · 钓具 · 菜谱 · 动物等八类，并入勾选",
        "旧记录一个不少，进度两处合一",
    ),
    "v2.31.0 名词皆可抵达" to listOf(
        "攻略正文中的名词，皆为入口",
        "长按底栏，「跳到…」一框收尽",
    ),
    "v2.30.0 记得你停在哪" to listOf(
        "重开即回到原处",
        "「最近看过」常驻侧栏，换存档不清",
    ),
    "v2.29.0 搜索越出分类" to listOf(
        "搜索框越过当前分类，覆盖全部 27 类，结果按类分组",
    ),
    "v2.28.0 攻略自立门户" to listOf(
        "顶部「数据 / 攻略」平分",
        "分类四色成组，一眼可循",
    ),
    "v2.27.0 收集包回归规矩" to listOf(
        "冒险家四选二，蟹笼十选五",
        "史莱姆泥更名黏液，旧勾随之迁移",
    ),
    "v2.26.2 卡片复校" to listOf(
        "比例复原，不再被挤成细长条",
        "图标与日期整体居中，署名贴住下沿",
    ),
    "v2.26.1 卡片定形" to listOf(
        "竖版归位 3:4",
        "下半幅留白收平，图标与日期居中",
    ),
    "v2.26 卡片重制" to listOf(
        "去木框，改卡片版式；今日概览以大日期当主角",
        "入口收于两处：今日页 📤，条目详情 📤",
        "发图前先预览；发出的图不可撤回",
        "进度页分享与截屏一并撤去",
    ),
    "v2.25 年历与卡片" to listOf(
        "攻略第十册「📅 年历」：全年 112 天，节日与生日一屏尽览",
        "分享卡：今日概览 / 进度成就，发前可预览；游戏随机结果不入卡",
    ),
    "v2.24 隐形文字" to listOf(
        "攻略 23 处浅色字落于浅底，今全部归位",
    ),
    "v2.23 流畅度" to listOf(
        "R8 瘦身 15MB → 7MB，测试签名；页签常驻，不重建",
        "计算页种植 / 加工列表回归木框卡片",
    ),
    "v2.18 群友所求" to listOf(
        "今日页全局搜索，横扫图鉴 27 类",
        "攻略第九册「⚔ 刷级路线」；🎲 小鸡代码 28 条一键复制",
    ),
    "v2.17 全面审查" to listOf(
        "修十二处：闭店星期错位、送礼避雷不显、钓鱼地图失灵等",
        "导入备份不再清空其余存档；数据损坏降级，不闪退",
        "订正远古水果 / 菠萝价格、加工耗时、博物馆古物 42 件",
    ),
    "v2.16 攻略四册" to listOf(
        "⭐ 技能 · 🍄 采集 42 种 · 🐱 宠物 · 天气与幸运，攻略至八册",
    ),
    "v2.15 图标补齐" to listOf(
        "武器 / 怪物 / 钓鱼补入 113 张官方图标",
    ),
    "v2.14.2 攻略中文化" to listOf(
        "姜岛 / 火山 / 结婚三册的 key 与原始 JSON 一并中文化",
    ),
    "v2.14.1 姜岛闪退" to listOf(
        "点开「金核桃」必崩，今已修复",
    ),
    "v2.14 攻略与利润排行" to listOf(
        "攻略三册：姜岛 · 结婚 · 武器；钓鱼加钓点筛选与四档售价",
        "加工页「📈 利润排行」15 设备 62 配方；菜谱订正 24 道",
    ),
    "v2.13.1 菜谱订正" to listOf(
        "补全至 81 道：改名 11 · 材料订正 4 · 补漏 4 · 去伪 2",
    ),
    "v2.13 音效与手感" to listOf(
        "音效体系落定；勾选弹跳与像素星迸发；详情左右滑翻条目",
    ),
    "v2.12 字体与开屏" to listOf(
        "字体层级重排，冷启动加载动画，节日彩蛋横幅",
    ),
    "v2.11 钓鱼筛选" to listOf(
        "钓鱼图鉴五套筛选：季节 / 地点 / 类型 / 天气 / 难度，可折叠",
    ),
    "v2.10.1 节日色板扩充" to listOf(
        "复活节 · 夏威夷宴会 · 月光水母 · 鱿鱼节 · 夜市，各有配色",
    ),
    "v2.10 节日氛围" to listOf(
        "花舞节 · 展览会 · 万灵节 · 冰雪节 · 冬日星盛宴，当天换色",
        "待办长按复制为 Markdown 清单",
        "首启引导增设「熟手技巧」",
    ),
    "v2.9 数据真源" to listOf(
        "博物馆矿物 51 → 53，补入五彩碎片与透闪石",
        "沙拉回归 wiki 真源：韭葱 · 蒲公英 · 香醋",
        "导入前弹确认，自动备份至私有目录",
    ),
    "v2.8 图鉴分组" to listOf(
        "分类面板分组直达",
        "补图 30 张，别名映射 19 条",
        "新增「怪物图鉴」：掉落 · 出没",
        "作物图鉴增 1.6 三种：远古水果 · 大米 · 菠萝",
    ),
    "v2.7 钓获记录" to listOf(
        "逐鱼打勾，三段筛选",
        "详情补「所需材料」，字段名中文化",
        "致谢列入二级菜单",
    ),
    "v2.6 桌面小组件" to listOf(
        "木框 · 季节配色 · 真实数据",
        "新增 2×2 与 4×2 两种规格",
    ),
    "v2.5 本周与解锁" to listOf(
        "本周预览 · 钓鱼排查 · 收集包来源 · 送礼规划",
        "解锁进度卡 · 桌面小组件 · 季节配色",
        "另含 14 项操作与视觉调整",
    ),
    "v2.3 图标" to listOf(
        "补齐 69 枚缺失图标",
    ),
    "v2.2 村民详情" to listOf(
        "改底部抽屉，查完浣熊不再弹回顶部",
    ),
    "v2.1 抽屉" to listOf(
        "反馈渠道改为 QQ 群",
        "图鉴详情改底部抽屉",
        "致谢首批两位社区纠错",
    ),
    "v2.0 首个完整版本" to listOf(
        "多存档 · 计算器升级 · 图鉴用途速查",
        "一键报错 · 首启引导 · 锁字号",
    ),
    "v1.6.x 抽屉 · 震动 · 图标" to listOf(
        "详情改底部抽屉，滚轮震动，全新图标",
        "性能优化",
    ),
    "v1.5 日期与分段" to listOf(
        "日期滚轮，进度页分段",
    ),
    "v1.1 – v1.4 质感" to listOf(
        "质感补齐 · 进度页大改版 · AI 图标",
    ),
    "v1.0 首个版本" to listOf(
        "五大板块，完全离线",
    ),
)
