package com.stardew.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.stardew.assistant.data.GameData
import com.stardew.assistant.BuildConfig
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.UpdateRepo
import com.stardew.assistant.ui.AppFont
import com.stardew.assistant.ui.FontScale
import com.stardew.assistant.ui.IconCache
import com.stardew.assistant.ui.IntroOverlay
import com.stardew.assistant.ui.LoadingSplash
import com.stardew.assistant.ui.ThemeMode
import com.stardew.assistant.ui.glass.GlassBackdropState
import com.stardew.assistant.ui.glass.GlassDragAnimation
import com.stardew.assistant.ui.glass.GlassLadder
import com.stardew.assistant.ui.glass.GlassPressHighlight
import com.stardew.assistant.ui.glass.LocalGlassState
import com.stardew.assistant.ui.glass.recordBackdrop
import com.stardew.assistant.ui.glass.rememberGlassBackdrop
import com.stardew.assistant.ui.glass.rememberGlassState
import com.stardew.assistant.ui.glass.trackPosition
import com.stardew.assistant.ui.LocalGuideLinkify
import com.stardew.assistant.ui.LocalAppFont
import com.stardew.assistant.ui.LocalDrawerOpen
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.ConfirmOverlayHost
import com.stardew.assistant.ui.DrawerOverlayHost
import com.stardew.assistant.ui.PixelFont
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.createSounds
import com.stardew.assistant.ui.PixelAppTheme
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.TabIcon
import com.stardew.assistant.ui.UpdateDialog
import com.stardew.assistant.ui.woodGrain
import kotlin.math.abs
import kotlin.math.roundToInt
import com.stardew.assistant.ui.today.TodayPage
import com.stardew.assistant.ui.calc.CalcPage
import com.stardew.assistant.ui.codex.CodexPage
import com.stardew.assistant.ui.guide.GUIDE_BOOK_BY_KEY
import com.stardew.assistant.ui.guide.GUIDE_SCREENS
import com.stardew.assistant.ui.guide.GuideHomeBody
import com.stardew.assistant.ui.guide.GuideSubPage
import com.stardew.assistant.ui.jump.JumpPanel
import com.stardew.assistant.ui.nav.NavRequest
import com.stardew.assistant.ui.nav.navBus
import com.stardew.assistant.ui.nav.villagerTarget
import com.stardew.assistant.ui.progress.ProgressPage
import com.stardew.assistant.ui.settings.SettingsPage
import com.stardew.assistant.ui.villagers.VillagersPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Today : Screen
    data object Calc : Screen
    data object Codex : Screen
    data object Villagers : Screen
    data object Progress : Screen
    data object Settings : Screen
    // v2.14 攻略二级页：页签不变，压栈进入，与设置页同范式
    data object Guide : Screen
    data object GuideIsland : Screen
    data object GuideMarriage : Screen
    data object GuideWeapons : Screen
    // v2.15：接自 v9 数据包的四册
    data object GuideSkills : Screen
    data object GuideForaging : Screen
    data object GuidePets : Screen
    data object GuideWeather : Screen
    // v2.18：战斗刷级路线册
    data object GuideCombat : Screen
    // v2.25：年历（游戏内 112 天历法）
    data object Calendar : Screen
    // v2.56：商店目录（群友反馈「各个卖东西的具体信息」）
    data object GuideShops : Screen
    // v2.72：Mod 扩展包（下载/启用外部 NPC 数据）。挂村民页顶栏进入，与设置页同范式
    data object Mods : Screen
    // v2.94.0：NPC 行程表（群友反馈）。挂村民页顶栏进入，与设置页同范式
    data object Schedule : Screen
    // v2.99.0：使用指引（「功能在哪儿」）。设置页 + 跳到面板双入口，与设置页同范式
    data object FeatureGuide : Screen
}

/** v2.20：keep-alive 范围——五个主 tab 访问过后常驻组合，二级页不含 */
private val MAIN_TABS = setOf(Screen.Today, Screen.Calc, Screen.Codex, Screen.Villagers, Screen.Progress)

/**
 * v2.40 **临时查看锚点**：跳去图鉴之前，把「我原来在哪」整份记下来。
 *
 * ## 为什么需要它
 * 图鉴是五个**主 tab** 之一，不在压栈里。以前的跳图鉴一律
 * `stack.clear() + tab = Codex`——动作做完了，出处被抹掉：
 * 用户在攻略册里点一个物品名跳到图鉴，按返回只能留在图鉴（或直接退 App），
 * 原来的攻略页再也回不去（群友反馈「返回之后却是在图鉴页面」）。
 *
 * ## 为什么记的是「页签 + 整条栈」而不是单存一个页签
 * 攻略册本身是压栈页（栈深可以好几层：攻略首页 → 姜岛 → ……），只记页签
 * 会把用户丢在图鉴的攻略段里，等于没回；只记栈又会把主 tab 弄丢。
 * 两个一起快照，返回时一次还原到出发前的**完整位置**。
 *
 * 快照而不是引用：`stack` 是可变列表，出栈入栈都会改它，返回时必须还原成
 * 离开那一刻的形状（v2.27「判定逻辑散落多处必然漂移」的同型：状态要复制，不要引用）。
 */
private data class PeekOrigin(val tab: Screen, val stack: List<Screen>)

/** 跨页跳转指令（今日页全局搜索 / 跳板「跳到…」面板共用一条通道）。
 *  v2.18：targetIndex 非空时图鉴会直接弹出该条目详情抽屉。
 *  v2.31 加两个字段：
 *  - [openSearch]：重名物品名不硬跳，退化成落到该词的跨分类搜索结果
 *    （实测 392 个名字跨分类重复，「木材」重复 22 次）。
 *  - [openGuide]：与 [groupId] 配合表示「切到**攻略段**且不改分类」——
 *    「跳到某本册子」时，关掉书页回来的落点才对。 */
data class CodexJumpRequest(
    val groupId: String,
    val openMore: Boolean = false,
    val targetIndex: Int? = null,
    val openSearch: String = "",
    val openGuide: Boolean = false,
)

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * v2.86.0：Steam 云存档导入结果的深链。
         *
         * 通知点进来时带着它 → App 切到该存档位的进度/报告页。
         * 空串 = 只开 App 首页。
         */
        const val EXTRA_STEAM_DEEP_LINK = "steam_deep_link"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // c232：有信任设备 → App 启动即常驻自动监听（没有则什么都不发生，
        // 手动流程照旧）。幂等；配对成功发生在任何页面也能弹码框（全局宿主）。
        com.stardew.assistant.data.LanReceiveController.ensureAuto(applicationContext)
        setContent { App() }
    }

    /**
     * c234：回到前台时重查**电池优化白名单**状态。
     *
     * ⚠ 用户是去系统设置页授权的，回来时 App 进程可能还活着（onResume 而不是
     *   onCreate）——所以状态必须在这里重查，不能只在启动时查一次。
     */
    override fun onResume() {
        super.onResume()
        com.stardew.assistant.data.LanReceiveController.refreshWhitelist(applicationContext)
    }

    /**
     * v2.86.0：通知点进来时 App 可能**已经在跑**（用户在浏览器里下载完，
     * 直接点通知回到 App）——这时系统走 [onNewIntent] 而不是重新 onCreate。
     *
     * ⚠ `setIntent` 是必须的：不覆盖的话 `getIntent()` 永远返回**第一次**那个
     *   intent，第二次通知带来的新参数就丢了。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { PrefsRepo(ctx) }
    // c232：局域网接收的验证码弹窗是**全局**宿主——自动监听起来后，配对请求
    // 可能出现在任何页面，码框必须哪都能弹（不能只挂在设置页里）。
    val lanCodePrompt by com.stardew.assistant.data.LanReceiveController.codePrompt.collectAsState()
    lanCodePrompt?.let { prompt ->
        com.stardew.assistant.ui.settings.CodePromptSheet(
            attemptsLeft = prompt.attemptsLeft,
            wrong = prompt.wrong,
            onSubmit = { input ->
                com.stardew.assistant.data.LanReceiveController.clearCodePrompt()
                prompt.submit(com.stardew.assistant.data.SaveReceiver.CodeReply.Typed(input))
            },
            onCancel = {
                com.stardew.assistant.data.LanReceiveController.clearCodePrompt()
                prompt.submit(com.stardew.assistant.data.SaveReceiver.CodeReply.Cancel)
            },
        )
    }
    var snap by remember { mutableStateOf<PrefsRepo.Snapshot?>(null) }
    var dataReady by remember { mutableStateOf(false) }
    // c200 A2：数据重载闸门。0 = 首次加载；>0 = 用户在失败态点了「重新加载」。
    // GameData.load 开头是 `if (ready) return`（一次加载终身有效），
    // 所以重试必须显式走 force 分支，否则点了按钮什么也不会发生。
    // ⚠ 用显式的 state 引用（而不是 by 委托）：回调要写进 remember 的 lambda 里，
    //   捕获委托变量不如捕获 state 对象稳。
    val dataEpochState = remember { mutableIntStateOf(0) }
    val dataEpoch by dataEpochState
    val onRetryData = remember { { dataEpochState.intValue = dataEpochState.intValue + 1 } }
    LaunchedEffect(dataEpoch) {
        // 数据解析 + 页签/装饰图标解码全部离开主线程，消掉冷启动冻结
        val app = ctx.applicationContext
        // 快照流订阅：首次加载与重试重载**共用这一份**。
        // 写成两处必然漂移（v2.27「判定口径散落多处必然漂移」的同型），
        // 而且漏掉重试路径的订阅 ⇒ snap 不再更新 ⇒ 用户点了「重新加载」
        // 却看不到任何变化（比没有这个按钮更让人火大）。
        suspend fun collectSnaps() {
            repo.snapshot.collect {
                snap = it
                // c215：音效开关同步到进程级标志（PixelSounds.play 读不了 DataStore 流）。
                //   放在快照收集里 = 冷启动恢复 + 设置页改动两条路都走这里，单源不漂。
                com.stardew.assistant.ui.PixelSounds.enabled = it.soundEnabled
                // v2.72：Mod 包启用集合是**唯一**的合并触发点。
                //   装包/删包/开关三种操作最后都表现为「mod_enabled 变了」，
                //   所以重算放在这里而不是三处各调一次——三份就意味着迟早有一份漏。
                com.stardew.assistant.data.ModRepo.applyEnabled(it.modEnabled)
                // 小组件存档镜像：同步写入（小组件读不了 DataStore），并即时刷新
                com.stardew.assistant.widget.WidgetPrefs.sync(
                    app, it.season, it.day, it.year,
                    done = com.stardew.assistant.ui.today.todoDoneCount(it),
                    total = it.todoList.size,
                )
                com.stardew.assistant.widget.WidgetUpdater.refresh(app)
            }
        }
        if (dataEpoch > 0) {
            // 重试路径：**只重载数据表**，不重跑那串一次性迁移
            // （迁移带一次性标记，重跑既无意义又可能重复动作）。
            withContext(Dispatchers.IO) {
                runCatching { GameData.load(app, force = true) }
                    .onFailure { android.util.Log.e("StardewApp", "GameData.load 重试失败", it) }
                // 图标表也跟着重读一次：图鉴失败往往连带图标映射缺失
                runCatching { IconCache.warmBasics(app) }
                    .onFailure { android.util.Log.e("StardewApp", "IconCache.warmBasics 重试", it) }
            }
            com.stardew.assistant.widget.WidgetUpdater.refresh(app)
            collectSnaps()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            // v2.17 C4 外层兜底：GameData.load 内部已逐文件 runCatching，此处再包一层，
            // 任何漏网异常也只降级为空数据（页面显示加载失败），进程不崩
            runCatching { GameData.load(app) }
                .onFailure { android.util.Log.e("StardewApp", "GameData.load 兜底捕获", it) }
            runCatching { IconCache.warmBasics(app) }
                .onFailure { android.util.Log.e("StardewApp", "IconCache.warmBasics", it) }
            runCatching { repo.ensureMigrated() }
                .onFailure { android.util.Log.e("StardewApp", "ensureMigrated", it) }
            // v2.27：必须在 ensureMigrated 之后（依赖 PROFILES 已建好才能遍历所有存档）
            runCatching { repo.migrateCheckKeys() }
                .onFailure { android.util.Log.e("StardewApp", "migrateCheckKeys", it) }
            // v2.39：作物改名（金皮西葫芦）后的勾选键重写，同样必须在数据就绪后
            runCatching { repo.migrateCropRename() }
                .onFailure { android.util.Log.e("StardewApp", "migrateCropRename", it) }
            // v2.92.0：计算页**块数**从「全局一份」拆到「按存档」——
            //   升级前拨好的块数在全局 calc_setup 里，不迁的话用户得重新拨三下。
            //   同样必须在数据就绪后（要遍历所有存档）。
            runCatching { repo.migrateCalcPlots() }
                .onFailure { android.util.Log.e("StardewApp", "migrateCalcPlots", it) }
            // v2.82：第二轮作物改名（四个直译名 → 官方译名）后的勾选键重写。
            // ⚠ **必须另起一个闸门**：v2.39 那次的标记已经写进老用户数据里，不会再跑第二次。
            runCatching { repo.migrateCropRenameV282() }
                .onFailure { android.util.Log.e("StardewApp", "migrateCropRenameV282", it) }
            // v2.57：待办勾选键 下标->文本 的一次性迁移（自定义待办的前置）
            runCatching { repo.migrateTodoKeys() }
                .onFailure { android.util.Log.e("StardewApp", "migrateTodoKeys", it) }
            // v2.72：扫一次已装的 Mod 包。必须在偏好流开始 collect **之前**——
            //   下面 collect 里每发一次快照就 applyEnabled 一次，若此时盘上列表还是空的，
            //   用户装过包但重启后开 App 会看到「一个 Mod 村民都没有」。
            runCatching { com.stardew.assistant.data.ModRepo.refreshInstalled(app) }
                .onFailure { android.util.Log.e("StardewApp", "ModRepo.refreshInstalled", it) }
        }
        dataReady = true
        // 桌面小组件顺带刷新（装了组件的用户点开 app 立即看到当天信息）
        com.stardew.assistant.widget.WidgetUpdater.refresh(app)
        // v2.19：预热提早到 300ms（原 1500ms）——对照实验显示延后预热冷窗口滑动反而更差
        //（29% vs 15%）：缓存越早填满，滑动中「占位→图标」的重组抖动越少。
        // 配合下方后台优先级，提早跑不会抢 UI 的核。
        launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                runCatching { IconCache.warmItems(app) }
                    .onFailure { android.util.Log.e("StardewApp", "IconCache.warmItems", it) }
            } finally {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            }
        }
        // 订阅走共用的那份（首次加载与重试重载同一份口径，见上）
        collectSnaps()
    }
    val s = snap    // 字体缩放：v2.59 起设置页可调 3 档（标准 1× / 大 1.15 / 特大 1.3，单源 FontScale.SCALES），
    // 默认仍锁 1×。系统字号**始终不跟随**——像素字体非整数倍会插值发糊，且大屏系统档可到 2.0。
    // 桌面小组件（RemoteViews）与分享卡（ShareEntry 自带 Density(scale)）各有独立渲染路径，不随此档位变。
    val sysDensity = LocalDensity.current
    val fs = FontScale.SCALES[(s?.fontScaleIdx ?: 0).coerceIn(0, FontScale.SCALES.lastIndex)]
    // v2.93.0 字体档（像素 / 系统）/ v2.95.0 加第三档 custom。三值守卫在 PrefsRepo
    // 的 resolveFontFamily 里（读处与写处同一把尺），这里只管**拿哪套字体**。
    // 取 FontFamily.Default（跟系统走，含厂商定制字体），这才是「系统字体」的字面含义。
    // ⚠ 系统字体档**同样不跟随系统字号**：上面已把 Density.fontScale 覆盖成 App 自己的 3 档，
    //   换字体 ≠ 换字号，大小仍由设置页「🔤 字号」控制（跟随连续值会让图鉴网格大面积截字）。
    //
    // v2.95.0：自定义字体（用户导入的 TTF）**必须离开主线程加载**——
    // Typeface.createFromFile 读 7 MB 字体要几十到几百毫秒，主线程上做会掉帧。
    // 加载未完成时先显示像素字体，加载完再切过去（key 用 id ⇒ 换字体自动重新加载）。
    val customId = s?.fontCustomId
    val customFont by produceState<FontFamily?>(null, customId, s?.fontFamily) {
        if ((s?.fontFamily ?: AppFont.PIXEL) == AppFont.CUSTOM && !customId.isNullOrBlank()) {
            value = withContext(Dispatchers.IO) {
                com.stardew.assistant.data.CustomFont.loadFontFamily(ctx, customId)
            }
        }
    }
    val appFont = when ((s?.fontFamily ?: AppFont.PIXEL)) {
        AppFont.SYSTEM -> FontFamily.Default
        // 取不到（文件没了 / 加载失败）⇒ 回落像素字体，绝不留空字体
        AppFont.CUSTOM -> customFont ?: PixelFont
        else -> PixelFont
    }
    CompositionLocalProvider(
        LocalDensity provides Density(sysDensity.density, fontScale = fs),
        LocalAppFont provides appFont,
    ) {
        PixelAppTheme(
            themeMode = s?.theme ?: ThemeMode.SYSTEM,
            gameSeason = s?.season ?: -1,
            festival = if (s != null) com.stardew.assistant.data.GameData.festivalToday(s.season, s.day) else null,
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = LocalPixel.current.screen) {
                if (s == null || !dataReady) {
                    Box(Modifier.fillMaxSize().padding(WindowInsets.statusBars.asPaddingValues())) {
                        LoadingSplash()
                    }
                } else {
                    // c193 背景推后：DetailDrawer 打开时主内容加深+模糊+轻微缩小（iOS 模态推后感）。
                    // progress 0→1 动画；blur 仅 SDK31+（RenderEffect 门槛），低版本退回 scale+dim。
                    val drawerOpen by LocalDrawerOpen.current
                    val pushProgress by animateFloatAsState(
                        targetValue = if (drawerOpen) 1f else 0f,
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        label = "drawerPush",
                    )
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1f - 0.04f * pushProgress
                                    scaleY = 1f - 0.04f * pushProgress
                                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                                }
                                .let { m ->
                                    if (pushProgress > 0.01f && android.os.Build.VERSION.SDK_INT >= 31) {
                                        m.blur((10f * pushProgress).dp)
                                    } else m
                                },
                        ) {
                            AppRoot(repo, s, onRetryData)
                        }
                        // 深色罩（含 scale 露边的兜底染色，与 screen 底色叠加不穿帮）
                        if (pushProgress > 0.01f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.25f * pushProgress)),
                            )
                        }
                        // c196：抽屉覆盖层画在 dim 罩之后 = 在推后层之上——抽屉恢复清晰
                        // （c195 抽屉在 AppRoot 内，被 blur/scale/dim 一起打中），
                        // 空白判定线不再被 0.96 缩放拖偏，点空白关闭归位。
                        DrawerOverlayHost()
                        // c202：居中确认框（比底部抽屉轻，不做背景推后）
                        ConfirmOverlayHost()
                        if (!s.introSeen) {
                            IntroOverlay(onDone = { scope.launch { repo.setIntroSeen() } })
                        }
                    }
                }
            }
        }
    }
}

/** 双击底栏 tab → 当前长列表滚回顶部（图鉴 / 村民等通用习惯）。
 *  发射时订阅方（已在前台的列表页）早已 collect，SharedFlow 不丢事件，保持原样。 */
val scrollToTop = MutableSharedFlow<Screen>(extraBufferCapacity = 4)

/** v2.18 今日页全局搜索 → 让图鉴切到指定分类。
 *  data 见 CodexJumpRequest。用 MutableStateFlow——旧 SharedFlow(replay=0) 在「切页帧发射、
 *  下一帧才订阅」时事件被直接丢弃（点击无反应）；StateFlow 保留最新值，订阅方收到后置 null 消费。 */
val codexJump = MutableStateFlow<CodexJumpRequest?>(null)

/**
 * @param onRetryData c200 A2：数据加载失败时的「重新加载」出口。
 *   GameData.load 是「一次加载终身有效」的（开头 `if (ready) return`），
 *   所以失败后必须有一条**显式重载**的通道，否则用户只能杀进程重开。
 */
@Composable
private fun AppRoot(
    repo: PrefsRepo,
    snap: PrefsRepo.Snapshot,
    onRetryData: () -> Unit,
) {
    // 小组件深链：点小组件的生日区→村民页，其余→今日页
    val ctx = LocalContext.current
    // c199：起始页签抽成独立 remember —— 横滑转场的镜头初值要与它同源，
    // 否则深链进村民页时镜头会先停在今日页（首帧闪一下错页再弹过去）。
    val initialTab = remember {
        when ((ctx as? MainActivity)?.intent?.getStringExtra("open_page")) {
            "villagers" -> Screen.Villagers
            else -> Screen.Today
        }
    }
    var tab by remember { mutableStateOf(initialTab) }
    val stack = remember { mutableStateListOf<Screen>() }
    val current = stack.lastOrNull() ?: tab
    val updateScope = rememberCoroutineScope()
    // c227：使用指引页的「重新看引导」入口（设置页那张卡已并入顶栏入口）
    val replayIntroScope = rememberCoroutineScope()

    // ── v2.86.0：从 Steam 云获取存档（无需电脑）────────────────────────
    //
    // 「自动」的最后一环：**回 App 时立刻扫一次下载目录**。
    //
    // 为什么 observer 之外还要这一层：observer 只在 App 活着时有效，
    // 而用户从浏览器回来时可能已经过了很久（甚至进程被系统杀过重启）。
    // 回 App 的那一刻扫一次，是「用户能看见结果」的最后一道保证。
    //
    // ⚠ 只有**已经授权过下载目录**时才扫（没授权就是空扫，白费 IO）。
    val steamScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // v2.86.0：通知深链 → 切到设置页（农场报告卡在那里）。
    //
    // ⚠ **必须每次都读** intent 而不是只在首次组合读：通知点进来时 App 可能已经在跑
    //   （onNewIntent），此时首次组合早已过去，只读一次会永远收不到。
    //   onNewIntent 走 setIntent 覆盖 intent，所以每次 RESUME 读都是新的。
    var steamDeepLink by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner) {
        // ⚠ **首次组合时 lifecycle 已经是 RESUMED**，ON_RESUME 事件**不会再发一遍** ——
        //   只靠 observer 的话，**冷启动**（用户从浏览器下载完、App 被杀过再进）
        //   就永远扫不到下载目录。
        //   所以这里**先主动扫一次**，observer 负责之后的「切回前台」。
        if (com.stardew.assistant.data.SteamDownloadDir.canAutoDetect(ctx)) {
            com.stardew.assistant.data.SteamImportCoordinator.scanAndImport(ctx, steamScope)
        }
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val act = ctx as? MainActivity
                val link = act?.intent?.getStringExtra(MainActivity.EXTRA_STEAM_DEEP_LINK)
                if (!link.isNullOrBlank()) {
                    steamDeepLink = link
                    // 消费掉：不清的话每次回 App 都会重新跳
                    act.intent.removeExtra(MainActivity.EXTRA_STEAM_DEEP_LINK)
                    stack.clear()
                    stack.add(Screen.Settings)
                    tab = Screen.Today
                }
                if (com.stardew.assistant.data.SteamDownloadDir.canAutoDetect(ctx)) {
                    com.stardew.assistant.data.SteamImportCoordinator.scanAndImport(ctx, steamScope)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ── v2.61 更新提示横幅（今日页顶部） ──────────────────────────────
    var bannerUpdate by remember { mutableStateOf<UpdateRepo.Latest?>(null) }

    /**
     * v2.69：把「已发现的新版本」恢复出来 —— **这一步零网络请求**。
     *
     * 原来横幅状态只在内存里（下面的 `remember`），于是「查到了新版但当时不在今日页、
     * 随后进程被杀」的用户，横幅一次都没出现过。现在从 `update_pending_json` 读回——
     * v2.73.3 起每次进 App 都会再查一次，但**提示不等网络**：断网冷启动也要能把
     * 上次发现的版本摆出来。
     */
    var dialogPending by remember { mutableStateOf<UpdateRepo.Latest?>(null) }
    LaunchedEffect(snap.updatePendingJson) {
        val pending = UpdateRepo.decodeLatest(snap.updatePendingJson) ?: return@LaunchedEffect
        when {
            // 自愈：用户已经装上这个版本了，清掉快照，别再提示
            pending.versionCode <= BuildConfig.VERSION_CODE ->
                repo.setUpdatePending("")
            // 用户已划掉过这一版 / 关掉了提示开关：快照留着但什么都不显示
            !snap.updatePromptEnabled || pending.versionCode <= snap.updateSeenVersion -> Unit
            else -> {
                bannerUpdate = pending
                // 弹窗只在这一刻弹——即"本次启动从落盘恢复时"，
                // 不在下面 2 秒后那次网络检查的结果上弹（那会让每次开 App 都被拦一下）
                if (snap.updateDialogVersion < pending.versionCode && snap.introSeen) {
                    dialogPending = pending
                }
            }
        }
    }

    // 每次进 App 查一次（v2.73.3，用户要求）。延迟 2 秒再查：让首页先渲染出来，
    // 别让网络请求挡在启动路径上。
    // 去重统一走 UpdateRepo.shouldCheck（进程级）—— 设置页的静默检查也调它，
    // 所以「启动查一次 + 进设置页又查一次」这种重复请求不会发生。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        val cur = snap ?: return@LaunchedEffect
        if (!UpdateRepo.shouldCheck(cur.updatePromptEnabled)) return@LaunchedEffect
        val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
            UpdateRepo.check(BuildConfig.VERSION_CODE)
        }
        // 记账（v2.73.3 起 updateCheckedAt 只作「上次检查时间」的记录，不参与判定——
        // 判定去重由进程级 sessionChecked 负责，同一次进 App 不会重发）
        repo.markUpdateChecked(System.currentTimeMillis())
        // versionCode > 已划掉的那版 → 说明是更新的版本，横幅再出现（这正是要的）
        if (r is UpdateRepo.Check.NewVersion && r.latest.versionCode > cur.updateSeenVersion) {
            bannerUpdate = r.latest
            // 顺手落盘，下次冷启动横幅立刻在（不用等这一次网络检查跑完）
            updateScope.launch {
                repo.setUpdatePending(UpdateRepo.encodeLatest(r.latest))
            }
        }
    }

    /**
     * v2.69：一键下载。与设置页「获取更新」同一套机制（浏览器 ACTION_VIEW），
     * 不引入第二条下载通道——`send_save.bat` 那轮踩过"浏览器可能改名文件"的坑，
     * 但 APK 走浏览器本来就在用，本轮不碰下载机制本身。
     */
    val openApk: (UpdateRepo.Latest) -> Unit = { latest ->
        if (latest.apkUrl.isBlank()) {
            android.widget.Toast.makeText(ctx, "这一版没给出下载地址", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(latest.apkUrl),
                    ),
                )
            }.onFailure {
                android.widget.Toast.makeText(
                    ctx, "没有可用的浏览器应用", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // v2.40：临时查看锚点。跳去图鉴前记下出处，返回键 / 关闭图鉴抽屉都原路送回。
    var peek by remember { mutableStateOf<PeekOrigin?>(null) }
    // 返回锚点只有一个出口。系统返回键、图鉴抽屉的 ✕ / 下滑 / 返回手势
    // 全部汇到这里——写成两处就必然漂移（v2.27 同型教训）。
    val returnFromPeek: () -> Unit = {
        val o = peek
        peek = null
        if (o != null) {
            stack.clear()
            stack.addAll(o.stack)
            tab = o.tab
        }
    }
    BackHandler(enabled = stack.isNotEmpty() || peek != null) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else returnFromPeek()
    }
    // v2.20 切页卡顿修复：五个主 tab keep-alive。
    // 实测新页签首次组合 81~121ms（一屏节点 initial composition + debug JIT），
    // 旧 when 切换每次离开即销毁 → 每次切回都重新付全款。
    // 现在访问过的 tab 常驻组合：隐藏的 Offscreen 不绘制、手势黑洞不吃点击，
    // 切 tab 只剩 recomposition（数据没变时几乎零成本）。
    // 二级页（设置/攻略册）不 keep-alive——压栈时盖 opaque 背景，出栈即销毁。
    val keptTabs = remember { mutableStateListOf<Screen>() }
    if (current in MAIN_TABS && current !in keptTabs) keptTabs.add(current)
    // v2.20 预热：启动 3 秒后把其余主 tab 逐个后台组合（Offscreen 不可见）——
    // 用户首次切页也吃不到 81~121ms 的 initial composition。每 700ms 加一个，
    // 把单次 ~100ms 的组合 hitch 摊开，避开与用户操作的同帧竞争。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        for (t in MAIN_TABS) {
            if (t !in keptTabs) keptTabs.add(t)
            kotlinx.coroutines.delay(700)
        }
    }

    // ── c199 主页签横滑转场（镜头模型）────────────────────────────────
    // 五个主页签按底栏槽位排开，每页位移 = (自身槽位 - 镜头) × 屏宽；切页签 =
    // 镜头滑到新槽位：旧页滑出一侧、新页从另一侧滑入，像左右翻页，与底栏
    // 药丸的滑动同步。用单个 Animatable 驱动镜头（而不是 from/to 两页各自补间）
    // 的原因：转场途中再点别的页签，animateTo 会从镜头当前值平滑改道，任何时刻
    // 都不闪跳。镜头值只在页签层的 graphicsLayer lambda（draw 阶段）里被读——
    // 动画全程零重组、零重排，每帧只是挪层，是老手机上最便宜的整屏动画。
    val tabCam = remember {
        Animatable(
            BAR_TABS.indexOfFirst { it.third == initialTab }.coerceAtLeast(0).toFloat(),
        )
    }
    // 二级页（设置/攻略册）打开时不播转场：出栈回到主 tab 直接就位，
    // 保持「压栈盖住、出栈揭开」的原有手感——转场只管页签之间的切换。
    var backFromStack by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        val idx = BAR_TABS.indexOfFirst { it.third == current }
        if (idx >= 0) {
            if (backFromStack) tabCam.snapTo(idx.toFloat())
            else tabCam.animateTo(idx.toFloat(), tween(240, easing = FastOutSlowInEasing))
        }
        backFromStack = idx < 0
    }

    // v2.17 E1：整 App 唯一音效池建在这里并下发（旧实现每个 PixelCheckbox 各 new 一个）
    val sounds = createSounds()

    // v2.28 攻略正文：图鉴的「📖 攻略」分段与下面的兜底路由共用同一份——
    // 十本书的跳转回调只写这一处，两边不会漂移。
    // 声明必须在 keptTabs 的 Box 与二级页 Box 的**共同父级**（下面的 Column）里，
    // 放窄了任一消费点就看不到（第一次就踩了：放在 when (sc) 前，Screen.Guide 编译不过）。
    // v2.31：目标改从册子表取（不再各写一个字面量），并在本作用域开启正文词典链接。
    val guideBody: @Composable () -> Unit = {
        CompositionLocalProvider(LocalGuideLinkify provides true) {
            GuideHomeBody(
                onOpenIsland = { navBus.value = guideNav("island") },
                onOpenMarriage = { navBus.value = guideNav("marriage") },
                onOpenWeapons = { navBus.value = guideNav("weapons") },
                onOpenSkills = { navBus.value = guideNav("skills") },
                onOpenForaging = { navBus.value = guideNav("foraging") },
                onOpenPets = { navBus.value = guideNav("pets") },
                onOpenWeather = { navBus.value = guideNav("weather") },
                onOpenCombat = { navBus.value = guideNav("combat") },
                onOpenCalendar = { navBus.value = guideNav("calendar") },
                onOpenShops = { navBus.value = guideNav("shops") },
            )
        }
    }

    // v2.31 全局导航总线的**唯一**消费点。
    // 攻略正文点物品名、跳到面板点分类/册子/村民，全走这一条；栈与页签只在这里动一次
    // （v2.27「判定口径散落多处必然漂移」的同型预防）。verify 钉死 collect 只有这一处。
    var jumpOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        navBus.collect { req ->
            when (req) {
                null -> {}
                is NavRequest.Codex -> {
                    sounds.click()
                    // v2.40：只有「本来就不在图鉴页」才记锚点。用户站在图鉴里连点好几个
                    // 物品名时，每次都记会把锚点刷成「图鉴 → 图鉴」，返回键就回不去了。
                    if (current != Screen.Codex) peek = PeekOrigin(tab, stack.toList())
                    stack.clear()
                    tab = Screen.Codex
                    codexJump.value = CodexJumpRequest(
                        groupId = req.groupId,
                        openMore = req.openMore,
                        targetIndex = req.targetIndex,
                        openSearch = req.openSearch,
                        openGuide = req.openGuide,
                    )
                }
                is NavRequest.Guide -> {
                    sounds.click()
                    // 压栈页 = 一次完整的新导航，锚点作废
                    peek = null
                    stack.clear()
                    tab = Screen.Codex
                    // 先发指令再压栈：关掉书页回到图鉴时落在攻略段，而不是数据段
                    codexJump.value = CodexJumpRequest(groupId = "", openGuide = true)
                    stack.add(req.screen)
                }
                is NavRequest.Villager -> {
                    sounds.click()
                    peek = null
                    stack.clear()
                    tab = Screen.Villagers
                    villagerTarget.value = req.name
                }
                // v2.99.0 使用指引：切主页签 / 压栈到二级页。
                // Tab 语义等同手点底栏 ⇒ 清栈、锚点作废；
                // Stack 只压栈、页签不动 ⇒ 从「跳到…」面板进来时退出后仍回原页签。
                is NavRequest.Tab -> {
                    sounds.click()
                    peek = null
                    stack.clear()
                    tab = req.screen
                }
                is NavRequest.Stack -> {
                    sounds.click()
                    peek = null
                    stack.add(req.screen)
                }
            }
            navBus.value = null
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(com.stardew.assistant.ui.LocalPixelSounds provides sounds) {
        // v2.70.0 底栏玻璃（B 方案，逐项对齐 AndroidLiquidGlass 官方 Glass Bottom Bar 教程）：
        // 内容全屏录进 backdrop；胶囊底栏**悬浮**在内容上方（align BottomCenter），
        // 从胶囊脚下采样 —— 内容从胶囊后面与两侧穿过，玻璃才看得出来。
        // v2.70.0 加开关：设置里可切回经典木纹栏（snap.glassBar）。
        // 关开关或 <33 设备：glassState = null → 走原木纹布局（内容在上、栏在下，老样子），
        // 且 recordBackdrop(null) 直通——关掉后**不再有任何录制开销**。
        val glassOn = snap.glassBar && com.stardew.assistant.ui.glass.GlassLadder.enabled
        // c228 性能护栏：玻璃开着才挂帧观测（false = 零开销）；护栏判「扛不住」
        //   会自己升档（减模糊 → 本会话停用），停用时给一条提示（见 degradedToOff）。
        com.stardew.assistant.ui.glass.GlassPerfGuard.active = glassOn && !com.stardew.assistant.ui.glass.GlassPerfGuard.disabled()
        val glassEffective = glassOn && !com.stardew.assistant.ui.glass.GlassPerfGuard.disabled()
        val glassBackdrop = rememberGlassBackdrop(glassEffective)
        val glassState = rememberGlassState(glassBackdrop)
        // c228：护栏降到「减模糊」档时，提示一次（Toast；level 2 的停用提示单独发）
        androidx.compose.runtime.LaunchedEffect(
            com.stardew.assistant.ui.glass.GlassPerfGuard.level
        ) {
            val lvl = com.stardew.assistant.ui.glass.GlassPerfGuard.level
            if (lvl >= 1) {
                android.widget.Toast.makeText(
                    ctx,
                    if (lvl >= 2) "设备发热或卡顿，玻璃效果本已暂停，重启应用可恢复"
                    else "检测到卡顿，已自动调低玻璃模糊，正在尽量保持效果",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        // 方案 A：玻璃模式下给主 tab 列表垫出胶囊占位（胶囊高 + 上下留白×2 + 导航条），
        // 滚到底时最后一条内容完整停在胶囊上方；中途穿过玻璃的效果不受影响。
        // 木纹/低版本 = 0.dp，各页 padding 与 v2.69 逐像素一致。
        val glassInset = if (glassState != null) {
            val gl = com.stardew.assistant.ui.glass.GlassLadder
            (gl.heightDp + gl.BAR_V_PAD_DP * 2).dp +
                WindowInsets.navigationBars.getBottom(androidx.compose.ui.platform.LocalDensity.current)
                    .dp
        } else 0.dp
        androidx.compose.runtime.CompositionLocalProvider(
            com.stardew.assistant.ui.glass.LocalGlassInset provides glassInset,
            // v2.76.0：玻璃弹层（GlassSheet）要采样同一份背景录制，
            // 所以玻璃状态也得往下传 —— 弹层不在 MainActivity 的子树里直接拿得到。
            LocalGlassState provides glassState,
        ) {

        // 内容区：两种布局共用一份定义（recordBackdrop 对 null 是直通，木纹模式零开销）
        val contentArea: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxSize()
                .recordBackdrop(glassBackdrop)
                .trackPosition { glassState?.contentOrigin = it },
        ) {
            keptTabs.forEach { sc ->
                val visible = sc == current
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .graphicsLayer {
                            // c199 横滑转场：页签按底栏槽位排开，位移与透明度都由
                            // 「自身槽位到镜头的距离」决定。相邻槽位全程不透明（贴着滑），
                            // 更远的页签淡出——跨多格跳转时中途页签一闪而过，也不会叠出
                            // 一堆半透明层。镜头静止时距离恒为整数，恰好只有目标页可见，
                            // 与旧版逐帧观感一致。
                            val slot = BAR_TABS.indexOfFirst { it.third == sc }.coerceAtLeast(0)
                            val dist = slot - tabCam.value
                            translationX = dist * size.width
                            val near = (1f - abs(dist)) * 2f
                            alpha = if (current in MAIN_TABS) near.coerceIn(0f, 1f) else 0f
                            compositingStrategy =
                                if (alpha > 0.001f) androidx.compose.ui.graphics.CompositingStrategy.Auto
                                else androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        }
                        .pointerInput(visible) {
                            // 隐藏页手势黑洞：Initial pass 先于子节点消费，
                            // 防止穿透触达不可见页面的按钮/列表
                            if (!visible) awaitEachGesture {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    ev.changes.forEach { it.consume() }
                                    if (ev.changes.none { it.pressed }) break
                                }
                            }
                        },
                ) {
                    when (sc) {
                        Screen.Today -> TodayPage(
                            snap, repo,
                            // v2.40：把「本页现在可见吗」交给页面。keep-alive 页签常驻组合，
                            // 而详情抽屉是独立 Dialog 窗口，父层 alpha 管不到它——
                            // 不显式门控的话，跳去别的页签后抽屉会浮在新页面上。
                            visible = visible,
                            openSettings = { stack.add(Screen.Settings) },
                            // v2.18 全局搜索：先发跳转指令再切页签，CodexPage 组合后立即消费到
                            openCodex = { gid, idx ->
                                codexJump.value = CodexJumpRequest(gid, targetIndex = idx)
                                // v2.40：今日页同样是「跳出去看一眼」，返回键得送得回来
                                if (tab != Screen.Codex) peek = PeekOrigin(tab, stack.toList())
                                if (tab != Screen.Codex) sounds.click()
                                stack.clear()
                                tab = Screen.Codex
                            },
                            latestUpdate = bannerUpdate,
                            onDownloadUpdate = { bannerUpdate?.let(openApk) },
                            // 划掉：横幅立刻没（本地态），并把「关掉了哪一版」落盘。
                            // 存的是版本号不是布尔 —— 下个版本发布后横幅会重新出现。
                            // v2.69：两件事都要做 —— updateSeenVersion 是「版本门槛」，
                            // pending 是「数据」。只记门槛不清数据的话，下次冷启动
                            // 读回 pending 时会靠版本门槛挡住（不至于出错），但那份
                            // 快照会一直躺在盘上；反过来只清数据不记门槛则每次都弹。
                            dismissUpdate = {
                                bannerUpdate?.let { v ->
                                    updateScope.launch {
                                        repo.markUpdateSeen(v.versionCode)
                                        repo.setUpdatePending("")
                                    }
                                }
                                bannerUpdate = null
                                dialogPending = null
                            },
                        )
                        Screen.Calc -> CalcPage(snap, repo, visible, onRetryData)
                        Screen.Codex -> CodexPage(
                            snap, repo,
                            guideContent = guideBody,
                            visible = visible,
                            // v2.72.1：关抽屉**不再**触发返回锚点。
                            // 病根是 v2.40 把「关抽屉 = 看完了，回去」当成了同一件事，
                            // 但用户的实际动线是「从进度页去勾博物馆/金核桃 → 点开一条看 →
                            // ✕ 关掉 → 继续在图鉴里勾下一条」；旧行为一关就被弹回进度页，
                            // 一次只能看一条，等于把「去勾」这个入口废掉。
                            // 现在分工明确：✕ / 下滑 = 只关抽屉，留在图鉴；
                            // 「回我来的地方」交给系统返回键/手势（下方 BackHandler
                            // 里 peek 仍在，照样原路送回）。锚点也**不清**——
                            // 清了就再也回不去了，等于把 v2.40 那次修正整个推翻。
                            onPeekDismiss = {},
                            onRetryData = onRetryData,
                        )
                        Screen.Villagers -> VillagersPage(
                            snap, repo, visible,
                            // v2.94.0：顶栏「🗺 行程」入口 → 压栈进独立行程表页
                            onOpenSchedule = { stack.add(Screen.Schedule) },
                        )
                        // v2.73.14：「🧩 扩展」入口从村民页挪到**进度页**总览段末尾
                        //   （那边有实测约 1000px 空白，且「总进度 X/279」的分母会随扩展包变化）。
                        Screen.Progress -> ProgressPage(
                            snap, repo, visible,
                            onOpenMods = { stack.add(Screen.Mods) },
                        )
                        else -> {}
                    }
                }
            }

            // v2.69 更新弹窗：放在 AnimatedContent 之外、**keptTabs 循环之外**，
            // 作为全屏 Dialog 独立于页签。
            //
            // ⚠ v2.85 修：这段原来在 `keptTabs.forEach` **循环体内**（v2.69 引入时就放错了，
            //   当时的注释写的是「放在 AnimatedContent 之外」—— 注释与代码位置从一开始就对不上）。
            //   keptTabs 是 keep-alive 页签列表：启动 3s 后每 700ms 预热加一个，最多 5 个。
            //   每加一个页签就多渲染一个 UpdateDialog，而 Compose 的 Dialog 是**独立窗口**——
            //   多个实例叠在一起，用户看到的现象就是「检测到更新后弹窗一直闪」（群友反馈）。
            //   挪到循环外 = 全 App 永远只有这一个实例。
            //
            // 只有「本次启动从落盘恢复出待提示版本」时 dialogPending 才非 null，
            // 且要求 updateDialogVersion < 该版本号（点过「稍后」就不再打扰）。
            UpdateDialog(
                latest = dialogPending,
                onDownload = {
                    dialogPending?.let { openApk(it) }
                    dialogPending = null
                },
                // ⚠「稍后」只记 dialog 版本，**不写 markUpdateSeen** ——
                //   写了等于永久屏蔽这一版，横幅也跟着没，提示就白做了。
                onLater = {
                    dialogPending?.let { v ->
                        updateScope.launch { repo.markUpdateDialogShown(v.versionCode) }
                    }
                    dialogPending = null
                },
            )
            // 二级页：不透明盖在全部主 tab 上（主 tab 此时 visible=false 不吃手势）
            // v2.34 转场：栈 push 从右滑入、pop 向右滑出，方向跟返回键/返回手势一致；
            // 淡入淡出只为让整屏平移不那么像「一张图在挪」。240ms 比 Material 默认 300ms
            // 稍快，贴合本项目按下去立刻有反应的手感。
            // 目标状态带上栈深 stack.size：连推两次同一个 Screen 也照样走动画
            // （只拿 Screen 当 key 时两次相等，AnimatedContent 会当成没变化而不播）。
            // 空态也画一个满屏空 Box，让前后两个状态等尺寸，SizeTransform 就不会
            // 把正在退出的那页按收缩矩形裁掉。
            val sec = stack.lastOrNull()
            AnimatedContent(
                targetState = sec to stack.size,
                transitionSpec = {
                    if (targetState.second > initialState.second) {
                        // 深入：新页整屏从右侧推入，旧页向左退 1/4 当视差
                        (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it } +
                            fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 4 } +
                                fadeOut(tween(240)))
                    } else {
                        // 退回：旧页整屏向右退出，下层那页从左侧 1/4 处迎上来
                        (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 4 } +
                            fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { it } +
                                fadeOut(tween(240)))
                    }
                },
                modifier = Modifier.fillMaxSize().zIndex(2f),
            ) { (s, _) ->
                Box(Modifier.fillMaxSize()) {
                    if (s != null) {
                        Box(Modifier.fillMaxSize().background(LocalPixel.current.screen)) {
                            when (s) {
                                Screen.Settings -> SettingsPage(
                                    snap, repo,
                                    onBack = { stack.removeAt(stack.lastIndex) },
                                    // v2.99.0：使用指引页压在这一层之上，退栈后仍回设置页
                                    onOpenGuide = { stack.add(Screen.FeatureGuide) },
                                )
                                // v2.72：Mod 扩展包页。压栈页 + BackBar「← 返回」，与设置页同一套退栈
                                Screen.Mods -> com.stardew.assistant.ui.mods.ModsPage(
                                    snap, repo,
                                ) { stack.removeAt(stack.lastIndex) }
                                // v2.94.0：NPC 行程表。与设置页同一套退栈范式
                                Screen.Schedule -> com.stardew.assistant.ui.schedule.SchedulePage(
                                    snap, repo,
                                ) { stack.removeAt(stack.lastIndex) }
                                // v2.99.0：使用指引页（功能在哪儿）。与设置页同一套退栈范式
                                // c227：「重新看引导」从设置页挪到这里（帮助语境内的次级入口）
                                Screen.FeatureGuide -> com.stardew.assistant.ui.guide.FeatureGuidePage(
                                    onBack = { stack.removeAt(stack.lastIndex) },
                                    onReplayIntro = { replayIntroScope.launch { repo.setIntroSeen(false) } },
                                )
                                in GUIDE_SCREENS -> GuideSubPage(
                                    screen = s,
                                    snap = snap,
                                    repo = repo,
                                    onBack = { stack.removeAt(stack.lastIndex) },
                                    guideHome = guideBody,
                                )
                                Screen.Guide -> GuideSubPage(
                                    screen = Screen.Guide,
                                    snap = snap,
                                    repo = repo,
                                    onBack = { stack.removeAt(stack.lastIndex) },
                                    guideHome = guideBody,
                                )
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        }

        val onTabSelect: (Screen) -> Unit = { s0 ->
            if (s0 != tab) {
                sounds.click()
                // v2.40：手点别的页签 = 用户自己选了新落点，锚点作废。
                // 点当前这个（图鉴）只回列表顶部，锚点留着，返回键还得能用。
                if (s0 != Screen.Codex) peek = null
            } else {
                // 双击当前 tab：回列表顶部
                scrollToTop.tryEmit(s0)
            }
            stack.clear()
            tab = s0
        }
        if (glassState != null) {
            // 玻璃模式：教程布局 —— 内容全屏 + 胶囊悬浮 BottomCenter。
            // 二级页（设置/攻略册，stack 非空）打开时**隐藏底栏**——用户点名：
            // 「点开设置时，底部的 tab 栏应该是消失的」。返回栈空了自然回来。
            Box(Modifier.fillMaxSize().padding(WindowInsets.statusBars.asPaddingValues())) {
                contentArea()
                if (stack.isEmpty()) {
                    GlassBottomBar(
                        current, glassState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onLongPress = { jumpOpen = true },
                        onSelect = onTabSelect,
                        // c228 玻璃浓度档（设置页「玻璃浓度」选的档位）
                        glassLevel = snap.glassLevel,
                    )
                }
            }
        } else {
            // 木纹模式：布局与 v2.69 及更早逐像素一致（<31 老设备）
            Column(Modifier.fillMaxSize().padding(WindowInsets.statusBars.asPaddingValues())) {
                Box(Modifier.weight(1f)) { contentArea() }
                WoodBottomBar(current, onLongPress = { jumpOpen = true }, onSelect = onTabSelect)
            }
        }
        // v2.31「跳到…」面板：画在最外层。ModalBottomSheet 自带 Dialog 窗口，
        // 所以在二级页（设置 / 攻略册）打开时也能盖在最上面。
        JumpPanel(visible = jumpOpen, onDismiss = { jumpOpen = false })
        } // CompositionLocalProvider(LocalGlassInset)
    }
}

/** 攻略册跳转目标查表。少一个 key 立刻炸出来，而不是静默跳到别处。 */
private fun guideNav(key: String): NavRequest = GUIDE_BOOK_BY_KEY.getValue(key).nav

/** 底栏五个页签的共用数据（玻璃/木纹两套栏共用，别处不许再写一份） */
private val BAR_TABS = listOf(
    Quadruple("today", "今日", Screen.Today),
    Quadruple("calc", "计算", Screen.Calc),
    Quadruple("codex", "图鉴", Screen.Codex),
    Quadruple("villagers", "村民", Screen.Villagers),
    Quadruple("progress", "进度", Screen.Progress),
)

/**
 * v2.99.0：底栏页签的 **键 → Screen** 映射，公开给使用指引页复用。
 *
 * ## 为什么要公开而不是在指引侧另写一份
 * [BAR_TABS] 的第一个字段（"today" / "calc" / …）是页签的**稳定键**，
 * 使用指引页的「去看今日页」要跳到同一个 [Screen]。
 * 若指引侧自己维护一张键→页签表，就是 v2.27「判定口径散落多处必然漂移」的
 * 同型复发：以后加页签只改一边，指引就会跳错地方。
 * ⇒ 这里从 [BAR_TABS] 直接派生，**只有一份真相**。
 */
val BAR_TAB_SCREENS: Map<String, Screen> = BAR_TABS.associate { it.first to it.third }

/**
 * v2.70.0 玻璃胶囊底栏 —— 对齐 AndroidLiquidGlass 官方《Glass Bottom Bar》教程：
 * 胶囊（CircleShape 口径：半径 = 高/2）悬浮在内容之上，效果链 vibrancy → blur(4dp)
 * → lens(16dp, 32dp)，表面压白色 50% 白纱。内容从胶囊后面与两侧穿过。
 *
 * v2.76.0 接上游《Liquid Bottom Tabs》的**交互层**（只借交互，不借视觉）：
 *   ① 选中药丸从「每个 tab 各自染底色」改成**一块独立药丸**，随动画在 tab 之间滑动；
 *   ② 药丸可**横向拖动**（上游同款：拖到哪松手就切到哪）；
 *   ③ 按下时胶囊按官方公式放大（maxScale = (宽 + 16dp) / 宽），玻璃里的背景
 *      **反向补偿**、钉在屏幕上不动（官方 inverse layer 的等价实现，见
 *      GlassSurface.sampleScale 的推导）；
 *   ④ 药丸按下缩放 + 按速度方向挤压（上游 layerBlock 公式逐字照抄）；
 *   ⑤ 按压处泛起柔光（上游 InteractiveHighlight），高光中心**重映射到药丸中心**
 *      而不是手指位置 —— 与上游 LiquidBottomTabs 的 position lambda 同款。
 *
 * 玻璃本体仍是**我们的**（v2.70.0 那套 RenderNode 宿主），不引入上游 drawBackdrop。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GlassBottomBar(
    current: Screen,
    glassState: GlassBackdropState,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onSelect: (Screen) -> Unit,
    // c228 玻璃浓度档（0=轻/1=标准/2=浓）。档位翻译成系数只在 GlassLadder 单源做。
    glassLevel: Int = 1,
) {
    val c = LocalPixel.current
    val density = LocalDensity.current
    val radius = GlassLadder.heightDp.dp / 2
    val itemH = GlassLadder.itemHeightDp.dp
    val itemR = GlassLadder.itemRadiusDp.dp
    // v2.76.0：白纱随主题走（白天白纱 / 夜间暗纱），见 GlassLadder.veilFor。
    // c228：再乘浓度档倍率（轻/标准/浓），档位 1 = 1f 与既有观感逐像素一致。
    val veil = GlassLadder.veilFor(c.screen, alphaScale = GlassLadder.veilScale(glassLevel))
    // c228：最终模糊半径 = 基准 × 浓度档 × 护栏档（护栏减模糊时 ×0.5；档 2 根本进不来这里）。
    //   ⚠ blurDp 参与 surface() 参数 → blurDp 变化会让 effectKey 变化重编效果链——
    //   这正是「降档生效」的路径，但必须把值 remember 住，重组不能抖动它。
    val barBlurDp = remember(glassLevel) {
        GlassLadder.defaultBlurDp * GlassLadder.blurScale(glassLevel) *
            com.stardew.assistant.ui.glass.GlassPerfGuard.blurFactor()
    }
    val scope = rememberCoroutineScope()

    // 每个 tab 的宽度（px）：拖动换算 + 药丸定位都用它
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    // 药丸高度（px）：跟着内容走（字号放到「特大」时内容会高于 52dp）
    var contentHeightPx by remember { mutableFloatStateOf(0f) }
    // 拖动起点（用于区分「拖动」与「轻点」）
    var dragStart by remember { mutableFloatStateOf(0f) }

    val currentIndex = remember(current) {
        BAR_TABS.indexOfFirst { it.third == current }.coerceAtLeast(0)
    }

    // 跟手动画：上游 DampedDragAnimation 的移植件（ui/glass/GlassDrag.kt）。
    // 弹簧参数与上游一致；pressedScale 是我们自己的取值（见下面注释）。
    val drag = remember {
        GlassDragAnimation(
            animationScope = scope,
            initialValue = currentIndex.toFloat(),
            valueRange = 0f..(BAR_TABS.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // 上游是 78f/56f（给 56dp 高的药丸用）；我们的药丸 52dp、外层胶囊 64dp，
            // 1.39× 会涨到 72dp 直接溢出胶囊 —— 故取 1.06。
            pressedScale = 1.06f,
            onDragStarted = { dragStart = value },
            onDragStopped = {
                val target = targetValue.roundToInt().coerceIn(0, BAR_TABS.size - 1)
                // 只有真的拖动过才切页签：轻点由 tab 自己的 clickable 处理，
                // 两者都发的话会把「点当前页签回顶部」执行两遍。
                if (abs(value - dragStart) > 0.01f && target != dragStart.roundToInt()) {
                    onSelect(BAR_TABS[target].third)
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx)
                            .coerceIn(0f, (BAR_TABS.size - 1).toFloat())
                    )
                }
            },
        )
    }
    // 外部（点 tab / 拖动结束）改了当前页 → 药丸滑过去
    LaunchedEffect(currentIndex) { drag.animateToValue(currentIndex.toFloat()) }

    // 按压柔光：高光中心锁在**药丸中心**（上游 position lambda 的重映射）
    val press = remember {
        GlassPressHighlight(
            animationScope = scope,
            position = { size, _ ->
                Offset((drag.value + 0.5f) * tabWidthPx, size.height / 2f)
            },
            // ⚠ 底栏是**白纱浅色玻璃面** → 不能用上游那套白色柔光（会把像素图标
            // 全洗白：用户真机录屏实测按下时整条底栏糊成一块白）。浅色面改深色压暗。
            onLightSurface = GlassLadder.isLightSurface(c.screen),
            // 半径只盖住「被按的那一格」：上游 1.5 倍是给深色底栏用的，
            // 浅色面上盖太多就成了「整条底栏发灰」。
            radiusScale = 1.1f,
        )
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
            .padding(vertical = GlassLadder.BAR_V_PAD_DP.dp)
            .height(GlassLadder.heightDp.dp)
            .clip(RoundedCornerShape(radius))
            .trackPosition { glassState.barOrigin = it }
            .graphicsLayer {
                // 官方《Interactive Glass Bottom Bar》教程的按压放大：
                //   maxScale = (size.width + 16dp) / size.width（宽度长 16dp）
                val progress = drag.pressProgress
                val maxScale = (size.width + 16.dp.toPx()) / size.width
                val s = 1f + (maxScale - 1f) * progress
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                // 玻璃里的背景要**反向补偿**，否则按下时背景跟着胶囊一起缩
                // （官方教程特意警告的那个坑，见 GlassSurface.sampleScale）。
                val progress = drag.pressProgress
                val maxScale = (size.width + 16.dp.toPx()) / size.width
                glassState.barSurface(glassLevel, barBlurDp).sampleScale = 1f + (maxScale - 1f) * progress
                // c228 动效：按压时白纱随按压力度变淡（最多褪三成）——「按下去」的
                //   时刻玻璃更透，松手弹回。官方教程没有这条，属我们自己的手感微调：
                //   浅色面上按压已有深色压暗（GlassPressHighlight），白纱再淡一点
                //   内容更「浮上来」，两路反馈叠加但不打架（压暗盖边缘、白纱管整体）。
                //   幅度 0.3：淡过头（>0.5）玻璃感会瞬间消失，看起来像渲染 bug。
                val veilNow = veil.copy(alpha = veil.alpha * (1f - 0.3f * progress))
                glassState.drawGlass(this, veilNow)
            }
            // 拖动换页 + 按压柔光的手势挂在整条栏上
            .then(drag.modifier)
            .then(press.gestureModifier),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = GlassLadder.BAR_ITEM_INSET_DP.dp),
        ) {
            // 一个 tab 的宽度
            val tabW = with(density) { maxWidth.toPx() } / BAR_TABS.size
            LaunchedEffect(tabW) { tabWidthPx = tabW }

            // ── 选中药丸（先画：在文字底下）──
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((drag.value * tabW).roundToInt(), 0) }
                    .width(with(density) { tabW.toDp() })
                    .height(
                        with(density) {
                            maxOf(contentHeightPx, itemH.toPx()).toDp()
                        }
                    )
                    .clip(RoundedCornerShape(itemR))
                    .background(c.cream.copy(alpha = 0.9f))
                    .graphicsLayer {
                        // 上游 LiquidBottomTabs 的 layerBlock 公式逐字照抄：
                        // 按下缩放 + 按速度方向挤压（快的时候横向拉长、纵向压扁）
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val v = drag.velocity / 10f
                        scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
            )

            // ── 五个页签 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .align(Alignment.Center)
                    .onSizeChanged { contentHeightPx = it.height.toFloat() }
                    // 按压柔光画在文字底下（上游同款：drawWithContent 先画柔光再画内容）
                    .then(press.modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BAR_TABS.forEachIndexed { i, (icon, label, sc) ->
                    // 药丸靠近时文字转深（与药丸同款过渡，避免滑动中途文字颜色突变）
                    val near = 1f - abs(drag.value - i).coerceAtMost(1f)
                    Column(
                        Modifier
                            .weight(1f)
                            // 药丸高度随内容长（min 而不是固定 height：字体档位放到
                            // 「特大」时宁可让药丸跟着长，也不裁字）。
                            .heightIn(min = itemH)
                            .clip(RoundedCornerShape(itemR))
                            // ⚠ 关掉 Material 默认涟漪：它在浅色玻璃底栏上是一坨
                            //   **深灰圆斑**（原生 Material 样式，跟像素画风完全不搭，
                            //   用户真机录屏里被误认成"渲染错误"）。上游也是
                            //   `clickable(interactionSource = null, indication = null)`。
                            //   按压反馈由我们自己的玻璃柔光/压暗 + 药丸缩放承担。
                            .combinedClickable(
                                interactionSource = null,
                                indication = null,
                                onClick = { onSelect(sc) },
                                onLongClick = onLongPress,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TabIcon(icon, 24.dp)
                        Spacer(Modifier.height(2.dp))
                        PixelText(
                            label,
                            // v2.72.1：选中态文字**固定用深色**，不跟主题走。
                            // 病根：选中药丸的底是 `c.cream`，而夜间模式里
                            // `NightColors.ink` 与 `NightColors.cream` **是同一个色**
                            // （都是 #F0E2C0）——于是「浅字压浅底」，选中的那个标签
                            // 直接看不见（用户夜间截图：「底部栏的字看不清」）。
                            // 底色才是决定项：cream 在日/夜/四季/各节日里**全是浅色**，
                            // 所以这里的字也必须是固定的深色。取 [DayColors.ink] 而不是
                            // 另一个字面量：日间模式下它就等于 c.ink，像素与改前完全一致。
                            // 非选中态仍用 c.ink —— 玻璃面在日间偏亮、夜间偏暗，
                            // 正好各自对上 ink（深）/ink（浅），两套主题都读得清。
                            // v2.76.0：药丸会滑动，所以按「离药丸多近」在两种颜色之间过渡，
                            // 而不是在切换瞬间硬切。
                            color = lerp(
                                c.ink.copy(alpha = 0.72f),
                                com.stardew.assistant.ui.DayColors.ink,
                                near,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** v2.69 及更早的木纹底栏：<31 老设备的**唯一**渲染分支，布局逐像素保留，不许删。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WoodBottomBar(
    current: Screen,
    onLongPress: () -> Unit,
    onSelect: (Screen) -> Unit,
) {
    val c = LocalPixel.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.woodDark))
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.wood)
                .woodGrain()
                .padding(WindowInsets.navigationBars.asPaddingValues()),
        ) {
            BAR_TABS.forEach { (icon, label, sc) ->
                val active = sc == current
                Column(
                    Modifier
                        .weight(1f)
                        // v2.31 长按 = 弹「跳到…」全局跳板。单击语义一字未改：
                        // 点别的 tab 切页、点当前 tab 回列表顶部（沿用 v2.20 的老行为）。
                        .combinedClickable(onClick = { onSelect(sc) }, onLongClick = onLongPress)
                        .background(if (active) c.parchment else Color.Transparent),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (active) c.gold else Color.Transparent))
                    Column(Modifier.padding(top = 8.dp, bottom = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        TabIcon(icon, 26.dp)
                        Spacer(Modifier.height(3.dp))
                        PixelText(
                            label,
                            color = if (active) c.ink else c.cream.copy(alpha = 0.85f),
                            shadow = !active,
                        )
                    }
                }
            }
        }
    }
}

private data class Quadruple<A, B, C>(val first: A, val second: B, val third: C)
