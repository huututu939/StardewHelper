package com.stardew.assistant.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import com.stardew.assistant.ui.glass.LocalGlassInset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.Calc
import com.stardew.assistant.data.CodexState
import com.stardew.assistant.data.CodexStateCache
import com.stardew.assistant.data.FarmToday
import com.stardew.assistant.data.FarmTodayCache
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.Season
import com.stardew.assistant.data.TodayAdviceBuilder
import com.stardew.assistant.data.birthdaysOnDate
import com.stardew.assistant.data.calcProgress
import com.stardew.assistant.data.myStatusSummary
import com.stardew.assistant.data.villagerPresent
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.FestivalBanner
import com.stardew.assistant.ui.UpdateBanner
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.WheelDrawer
import com.stardew.assistant.ui.WheelSpec
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PageBar
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelSearchClear
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.pressFeedback
import com.stardew.assistant.ui.share.TodayShareEntry
import com.stardew.assistant.ui.slotBevel
import com.stardew.assistant.ui.villagers.VillagerDetailBody
import com.stardew.assistant.widget.WidgetRender
import kotlinx.coroutines.launch
import com.stardew.assistant.ui.ParchmentStrip

// v2.57：默认清单移到 PrefsRepo.TODO_DEFAULT（数据层单源）；待办从写死 6 条变成可自定义。

/** 播种死线一档的展示数据（v2.21 提到文件级：数据计算挪进 remember 后 lambda 内不能声明类） */
private data class Tier(
    val mark: String,
    val color: androidx.compose.ui.graphics.Color,
    val label: String,
    val crops: List<Pair<String, String>>,
)

/**
 * 统计某快照今日待办完成数。
 * v2.57：键从下标改为**物品文本**——自定义列表让下标失去意义（删一行会让后面全部错位继承）。
 * 旧下标键由 PrefsRepo.migrateTodoKeys 一次性转换。
 */
fun todoDoneCount(snap: PrefsRepo.Snapshot): Int =
    snap.todoList.count { "todo:${snap.year}:${snap.season}:${snap.day}:$it" in snap.checks }

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
fun TodayPage(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    // v2.40：本页当前是否可见。本页是 keep-alive 常驻页签，而三个弹层
    // （日期滚轮 / 村民详情 / 全局搜索）都是独立 Dialog 窗口，外层 alpha 管不到。
    visible: Boolean,
    openSettings: () -> Unit,
    // v2.18 全局搜索需要真正切页签：只有 AppRoot 持有 tab 状态，
    // 只设 codexJump 不切页的话，图鉴页没被组合、collect 没订阅，点击看起来「没反应」。
    openCodex: (groupId: String, targetIndex: Int?) -> Unit = { _, _ -> },
    // v2.61 更新提示横幅。状态由 MainActivity 持有（那里才有网络与节流的活儿），
    // 今日页只负责显示 —— 保持「一个地方发请求，所有地方只读状态」。
    latestUpdate: com.stardew.assistant.data.UpdateRepo.Latest? = null,
    // v2.69：横幅里「点这里下载」直接拉起浏览器，不再要求用户先进设置页找按钮
    onDownloadUpdate: () -> Unit = {},
    dismissUpdate: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val seasonName = Season.names[snap.season]
    val c = LocalPixel.current
    var showDateDrawer by remember { mutableStateOf(false) }
    // v2.70.0：生日卡点击改为跳村民页（NavRequest.Villager），页内抽屉拆除——
    // 用户原话「今日页里点生日的村民直接跳转到村民页」。跳转走全局 navBus 单点消费
    // （AppRoot 清栈 + 切页签 + villagerTarget），与长按底栏跳板同一条通道。

    // v2.57 自定义今日待办：管理抽屉 + 行内改名状态 + 新增输入
    var showTodoEditor by remember { mutableStateOf(false) }
    var todoEditing by remember { mutableStateOf(-1) }
    var todoEditBuf by remember { mutableStateOf("") }
    var todoNew by remember { mutableStateOf("") }
    // v2.18 群友反馈「全局搜索」：今日页 PageBar → 🔍 → 跨全部分类一次搜完
    var showGlobalSearch by remember { mutableStateOf(false) }
    // v2.20 性能：播种死线 chip 折叠的展开状态（页面级——卡片 item 滚出视口回收后不丢）
    var openTiers by remember { mutableStateOf(setOf<Int>()) }

    // ── v2.81.0：「我的农场」分段（今日页第二个视图）────────────────────
    // 通用日历那一侧**一个字都不改**；这里只是在其上方加一个分段控件，
    // 切到「我的农场」时换成存档驱动的今日清单。
    var farmTab by rememberSaveable { mutableStateOf(0) }   // 0=通用日历 1=我的农场
    // c214 刷新信号：点击「↻ 刷新」递增，作为 farm/codex 缓存重读的 key 触发点。
    //   （农场数据来自导入时落好的缓存，进设置页再回来才会重读；这里给一个不离开今日页的刷新入口。）
    var farmRefreshTick by remember { mutableIntStateOf(0) }
    // ⚠ 通用日历的滚动状态提到分段之外：切走再切回来时**滚动位置不丢**
    //   （LazyColumn 被重建，但 state 对象还在 → 位置还原）。
    val calendarListState = rememberLazyListState()
    // 「我的农场」的数据来自**导入时落好的缓存**，今日页不重复解析 35MB 存档。
    //
    // ⚠⚠ **key 必须是 visible**：本页是 keep-alive 常驻页签 —— 组合一次就一直在，
    //   只在首次组合时读一次缓存的话，**导入完回到今日页读到的还是旧值**
    //   （用户看到的是「导入了却没反应，重启 App 才有」）。
    //   `visible` 在「进设置页 → 回今日页」时 false→true，正好是重新读缓存的时机。
    //
    // ⚠ **c217：key 里还必须有 snap.activeProfile**。缓存**按存档分文件**了
    //   （farm_today_<token>.json），所以切存档必须重读 —— 否则切到存档2
    //   「我的农场」显示的还是存档1的农场（哪块地种了什么全是错的）。
    //   ⚠ 顺序不能变：activeProfile 要在 activeProfile 变化时**立刻**换掉缓存内容，
    //     不能等下一次 visible 翻转。
    //
    // ⚠ **c200 A1：三态而不是两态**。原来初值是 null，而 null 同时意味着
    //   「还在读缓存」与「真的没导入存档」—— FarmTodayView 分不清，只能一律画成
    //   「还没有导入存档」，于是导入过存档的用户切到这一侧会**先闪一下
    //   「你没导入」**（看着像导入没成功，容易重复导入）。
    //   现在把「读到了」和「读到什么」拆开：Loading / Loaded(有模型) / Loaded(无模型)。
    val farmLoad by produceState<FarmLoad>(
        FarmLoad.Loading,
        visible to farmRefreshTick to snap.activeProfile,
    ) {
        value = FarmLoad.Loaded(
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FarmTodayCache.read(ctx, snap.activeProfile)
            },
        )
    }
    val farmModel = (farmLoad as? FarmLoad.Loaded)?.model
    // 读取中：建议区与农场卡都不该出现（内容还没到，画出来是假信息）
    val farmLoading = farmLoad is FarmLoad.Loading

    // ── v2.100.0 / c181：「我今天该做什么」建议区 ──────────────────────
    // 数据来源与前两层**同一个解析出口**：codex_state.json（与 farm_today.json
    // 一起在 FarmTodayCache.refresh 里折出来、一起被清）。
    val codexModel by produceState<CodexState?>(
        null,
        visible to farmRefreshTick to snap.activeProfile,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CodexStateCache.read(ctx, snap.activeProfile)
        }
    }
    // 建议本体由数据层算（data/TodayAdvice.kt），UI 只渲染 —— 数字与
    // 「我的农场」那一屏同源（同一份 FarmToday / CodexState），不在这里另算一遍。
    //
    // ⚠ v2.100.0 / c182：建议的「今天」= **存档里的那天**（FarmToday.year/season/day，
    //   与下面「今日农场」卡头部那个日期**同一个字段**）。
    //   原来按**转盘**日期算 ⇒ 同屏两张卡给出两个「今天」（转盘=春 15、存档=第3年夏6日
    //   ⇒ 建议说「春 15 日是最后一天」，照做就是在夏 6 日种春作物）。
    //   生日名单也改按存档日期算（birthdaysOnDate），**不是**通用日历那份
    //   （TodayFacts.todayBirthdays 按转盘日期）—— 两者可以不是同一天，
    //   那正是这条建议不与「🎂 生日」卡重复的原因。
    val advice = remember(farmModel, codexModel, snap.prof10, GameData.modCropsKey) {
        val f = farmModel
        val y = f?.year
        val d = f?.day
        val s = f?.season
        val bdays = if (f != null && y != null && d != null && s != null && s in Season.names) {
            birthdaysOnDate(s, d, y)
        } else {
            emptyList()
        }
        TodayAdviceBuilder.build(
            farm = f,
            state = codexModel,
            prof10 = snap.prof10,
            birthdays = bdays,
            crops = GameData.crops,
        )
    }
    // 建议卡的**日期来源标签**（显式标注「今天」是哪天）：
    // · 转盘日期 = 存档日期 ⇒ 只标日期，不写「按存档」（它就是今天页那个日期）；
    // · 不一样 ⇒ 明写「按存档 第3年 夏6日」（否则同屏两个「今天」又说不清）。
    val adviceDateLabel = remember(farmModel, snap.season, snap.day, snap.year) {
        val f = farmModel
        val y = f?.year
        val d = f?.day
        val s = f?.season
        when {
            f == null || y == null || d == null || s == null || s !in Season.names -> ""
            s == Season.names[snap.season] && d == snap.day -> "$s $d · 第 $y 年"
            else -> "按存档 第${y}年 ${s}${d}日"
        }
    }
    // 建议区的折叠态放页面级（滚出视口再回来不丢）
    var adviceOpen by remember { mutableStateOf(true) }

    // ── v2.100.0 / c184：「🎒 你的状态」总览 ────────────────────────
    // 数据同源：codex_state.json（与建议区同一份）、snap.checks（用户勾选）、
    // GameData.codexGroupsWithMods（图鉴分类，与图鉴页看到的一致）。
    // 收集包那两行**直接用 calcProgress 的结果**，不另算一遍（两处各算一次必然漂移）。
    //
    // ⚠ calcProgress 会遍历所有收集包条目 —— 放在 remember 里，checks 不变就不重算。
    val myStatus = remember(codexModel, snap.checks, GameData.codexViewVersion) {
        myStatusSummary(
            state = codexModel,
            checks = snap.checks,
            groups = GameData.codexGroupsWithMods,
            bundle = calcProgress(snap),
        )
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("今日") {
            // v2.26 分享：📤 → 预览今日概览卡 → 分享
            TodayShareEntry(snap)
            Spacer(Modifier.width(6.dp))
            // v2.18 全局搜索入口（放在日期徽章前，与 ⚙ 同款木槽样式）
            Box(
                Modifier
                    .background(c.slot)
                    .slotBevel(inset = 2.dp, width = 1.dp)
                    .border(2.dp, c.slotEdge)
                    .clickable { showGlobalSearch = true }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) { PixelText("🔍", color = c.ink) }
            Spacer(Modifier.width(6.dp))
            // 日期徽章：点击弹出滚轮抽屉
            Box(
                Modifier
                    .background(c.slot)
                    .slotBevel(inset = 2.dp, width = 1.dp)
                    .border(2.dp, c.slotEdge)
                    .clickable { showDateDrawer = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { PixelText("${seasonName} ${snap.day} · 第 ${snap.year} 年 ▾", color = c.ink) }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .background(c.slot)
                    .slotBevel(inset = 2.dp, width = 1.dp)
                    .border(2.dp, c.slotEdge)
                    .clickable { openSettings() }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) { PixelText("⚙", color = c.ink) }
        }
        // ── v2.81.0：今日页两个视图的分段控件 ──────────────────────────
        // 放在 PageBar 下面（与预览稿一致）：通用日历 / 我的农场。
        // ⚠ 通用日历那一侧的内容**一个字都没改**，只是被包在 farmTab == 0 里。
        PixelSegmented(
            options = listOf("通用日历", "我的农场"),
            selected = farmTab,
            onSelect = { farmTab = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (farmTab == 0) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = calendarListState,
                contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // v2.69：节日彩蛋与更新横幅**并存**，节日在上。
            // 原来是 `if (festivalToday(...) == null)` 二选一 —— 节日存在的那天更新提示
            // 整条不渲染。节日一年就那么几天，而漏掉一次更新提示的代价（用户停在旧版
            // 几个月）远大于今天多占一屏，所以「让位」只能让顺序，不能让消失。
            item(key = "banner") { FestivalBanner(GameData.festivalToday(snap.season, snap.day)) }
            item(key = "updateBanner") {
                UpdateBanner(
                    latest = latestUpdate,
                    onOpen = openSettings,
                    onDownload = onDownloadUpdate,
                    onDismiss = dismissUpdate,
                )
            }
            item(key = "nextDay") {
                // v2.92.0：通用日历侧的「跳到下一日」快捷入口（用户 2026-10-25 要的）。
                //   推进的是**今日页那个日期**——与小组件的「▸ 明天」是同一份数据源
                //   （都用 [WidgetRender.nextDay] 这一个算法），所以两个入口改的
                //   永远是同一个游戏日期，不会出现「小组件走到明天、App 还在今天」。
                //   顺带：日期一变，计算页（v2.89.0 起与今日页共享）也跟着变。
                //
                // ⚠ v2.92.0 改：用户反馈「界面太大、占视觉空间」⇒ 从
                //   「标题行 + 说明行 + 按钮」三行压成**一行**（左边日期、右边按钮），
                //   高度从约 90dp 降到约 44dp。日期信息一个不丢，只是并进同一行。
                val next = WidgetRender.nextDay(snap.year, snap.season, snap.day)
                if (next == null) {
                    // 第 10 年是游戏里的上限，再往后没有「下一天」
                    // c223：inkDim 裸压屏幕底最低 1.09 → 上米黄纸条
                    ParchmentStrip {
                        PixelText("已到第 ${WidgetRender.MAX_YEAR} 年，游戏里不再往后推了", color = c.inkDim)
                    }
                } else {
                    val (ny, ns, nd) = next
                    PixelCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelText(
                                "📅 明天 ${Season.names[ns]} $nd · 第 $ny 年",
                                color = c.ink,
                                modifier = Modifier.weight(1f),
                            )
                            PixelButton(
                                text = "▸ 下一日",
                                active = true,
                                onClick = { scope.launch { repo.setDate(ny, ns, nd) } },
                            )
                        }
                    }
                }
            }
            item(key = "birthday") {
                PixelCard(title = "🎂 生日", accent = c.blue) {
                    // v2.21：数据计算按日期 remember——旧写法每次重组（勾选/切日期）
                    // 都重扫 34 村民 × daysUntil；keep-alive 后勾选也会触发本页重组
                    // v2.38：生日判定必须带「年份」+「是否在场」两个维度。
                    // ⚠ remember key 里的 snap.year 不能省——肯特第 1 年冬 28 与第 2 年冬 28
                    // 的 seasonName/day 完全相同，但落在不同年份，可见性结论相反。
                    val (rows, todayCount) = remember(seasonName, snap.day, snap.year) {
                        val si = Season.names.indexOf(seasonName)
                        val today = GameData.gifts
                            .filter { it.birthdaySeason == seasonName && it.birthdayDay == snap.day }
                            .filter { villagerPresent(it.name, snap.year, si, snap.day) }
                        val upcoming = GameData.gifts
                            .filter { g -> g.birthdaySeason != null && g.birthdayDay != null }
                            .map { g ->
                                val (oy, d) = Calc.daysUntilWithYear(
                                    snap.year, seasonName, snap.day, g.birthdaySeason!!, g.birthdayDay!!,
                                )
                                Triple(g, d, oy)
                            }
                            // 生日落在哪一年，就用哪一年判「他在不在」——第 1 年冬 28 预告的
                            // 是第 2 年春 4，那时肯特已回村，说得通。
                            .filter { (g, d, oy) ->
                                d in 1..7 && villagerPresent(
                                    g.name, oy, Season.names.indexOf(g.birthdaySeason!!), g.birthdayDay!!,
                                )
                            }
                            .sortedBy { it.second }
                            .take(3)
                        Pair(
                            today.map { g -> (g.name to "今天 · ${g.name}") to "生日送礼效果 ×8，记得备最爱礼物" } +
                                upcoming.map { (g, d, _) ->
                                    (g.name to "${if (d == 1) "明天" else "${d} 天后"} · ${g.name}（${g.birthdaySeason} ${g.birthdayDay}）") to "提前备好 TA 最爱的礼物"
                                },
                            today.size,
                        )
                    }
                    if (rows.isEmpty()) {
                        PixelText("☀ 今天没有人过生日", color = c.inkDim)
                    }
                    rows.forEachIndexed { i, (main, sub) ->
                        if (i > 0) RowSep()
                        val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .pressFeedback(isrc)
                                .padding(vertical = 10.dp)
                                .clickable(interactionSource = isrc, indication = null) {
                                    // v2.70.0：跳村民页并打开此人（AppRoot 消费：清栈+切签）
                                    com.stardew.assistant.ui.nav.navBus.value =
                                        com.stardew.assistant.ui.nav.NavRequest.Villager(main.first)
                                },
                        ) {
                            com.stardew.assistant.ui.mods.VillagerAvatar(main.first, 34)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                PixelText(main.second)
                                PixelText(sub, color = c.inkDim)
                            }
                            if (i >= todayCount) Chip("×8", border = c.red, content = c.red)
                            Spacer(Modifier.width(6.dp))
                            PixelText("▸", color = c.inkDim)
                        }
                    }
                    if (rows.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        PixelText("点击生日村民 → 查看 TA 的礼物喜好档案", color = c.inkDim)
                    }
                }
            }
            item(key = "festival") {
                PixelCard(title = "🎪 节日", accent = c.gold) {
                    // v2.21：按日期 remember，勾选/切页引发的重组不再重扫节日表
                    val (todays, next) = remember(seasonName, snap.day) {
                        val t = GameData.festivals.filter { f ->
                            f.season == seasonName && f.startDay != null && snap.day in f.startDay!!..(f.endDay ?: f.startDay!!)
                        }
                        val n = if (t.isNotEmpty()) null else GameData.festivals.map { f ->
                            f to (f.startDay?.let { sd -> Calc.daysUntil(seasonName, snap.day, f.season ?: "春", sd) } ?: 999)
                        }.filter { it.second in 0..7 }.minByOrNull { it.second }
                        Pair(t, n)
                    }
                    if (todays.isEmpty()) {
                        if (next == null) PixelText("🌙 7 天内没有节日", color = c.inkDim)
                        else {
                            val f = next.first
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FramedIcon(f.name, 34)
                                Spacer(Modifier.width(8.dp))
                                PixelText("${f.date} · ${f.name}", color = c.inkDim)
                            }
                        }
                    } else {
                        todays.forEachIndexed { fi, f ->
                            if (fi > 0) RowSep()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                                // v2.72：过生日的人可能来自 Mod 包，换成 VillagerAvatar 才有头像
                                com.stardew.assistant.ui.mods.VillagerAvatar(f.name, 34)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    PixelText("${f.name} · 就是今天！")
                                    if (f.rewards.isNotBlank()) PixelText(f.rewards, color = c.inkDim)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "week") {
                // 本周预览：营业时间 + 周循环（数据来自 shopHours/weekLoop；游戏第 1 天是周一）
                PixelCard(title = "🗓 本周预览") {
                    val dow = (snap.day - 1) % 7 // 0=周一 … 6=周日
                    val today = GameData.weekLoop.getOrNull(dow)
                    if (today != null) {
                        PixelText("今天（${today.day}）：", color = c.ink)
                        Spacer(Modifier.height(3.dp))
                        today.items.forEach {
                            PixelText("· $it", color = c.inkDim)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    RowSep()
                    Spacer(Modifier.height(6.dp))
                    GameData.weekLoop.forEachIndexed { wi, wd ->
                        val isToday = wi == dow
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            PixelText(
                                if (isToday) "▸ ${wd.day}" else "　 ${wd.day}",
                                color = if (isToday) c.gold else c.inkDim,
                            )
                            Spacer(Modifier.width(8.dp))
                            PixelText(wd.items.take(2).joinToString("、") + if (wd.items.size > 2) "…" else "", color = c.inkDim)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // v2.17 A2：按结构化 closedDays 精确判定（旧 when(dow) 映射 0→"周三" 整体错位：
                    // 周一误报皮埃尔、真闭店日周三永不报、旅行货车周五营业日反而报闭店）
                    val dayName = GameData.weekLoop.getOrNull(dow)?.day.orEmpty()
                    // c191 性能：dayName 变化才重算（此前每次重组重扫商店表）
                    val closedToday = remember(dayName) { GameData.shopHours.filter { it.closedDays.contains(dayName) } }
                    if (closedToday.isNotEmpty()) {
                        PixelText("闭店提醒：${closedToday.joinToString("、") { it.name }}", color = c.red)
                    }
                }
            }
            item(key = "deadline") {
                PixelCard(title = "🌱 播种死线", accent = c.red) {
                    val modCropsKey = GameData.modCropsKey
                    // v2.21：五趟全作物扫描按日期+职业 remember（旧写法每次重组全重算）
                    // ⚠ v2.73.4 补 Mod 键：GameData.crops 现在含已启用包的 SVE 作物
                    //   （计算属性），装包后同一组键命中旧值 → 播种死线漏掉新作物。
                    val (tiers, inTime) = remember(seasonName, snap.day, snap.prof10, modCropsKey) {
                        val must = GameData.crops.filter { Calc.deadline(it, seasonName, snap.day, snap.prof10) == Calc.Deadline.MUST_PLANT }
                        val tooLate = GameData.crops.filter { Calc.deadline(it, seasonName, snap.day, snap.prof10) == Calc.Deadline.TOO_LATE }
                        val cross = GameData.crops.filter { Calc.deadline(it, seasonName, snap.day, snap.prof10) == Calc.Deadline.CROSS_SEASON }
                        val inTimeCount = GameData.crops.count { Calc.deadline(it, seasonName, snap.day, snap.prof10) == Calc.Deadline.IN_TIME }
                        val soon = GameData.crops.filter {
                            val lp = Calc.lastPlantDay(it, seasonName, snap.prof10)
                            seasonName in it.seasons && lp > snap.day && lp - snap.day <= 3
                        }
                        Pair(
                            listOf(
                                Tier("⚠", c.red, "今天必种", must.map { it.name to "${Calc.lastPlantDay(it, seasonName, snap.prof10)} 日止" }),
                                Tier("✔", c.green, "最近 3 天截止", soon.map { it.name to "${Calc.lastPlantDay(it, seasonName, snap.prof10)} 日前" }),
                                Tier("☀", c.inkDim, "跨季不死", cross.map { it.name to "${it.seasons.first()}→${it.seasons.last()}" }),
                                Tier("✕", c.inkDim, "已来不及", tooLate.map { it.name to "" }),
                            ),
                            inTimeCount,
                        )
                    }
                    PixelText("还来得及：共 $inTime 种 · 点各档展开明细", color = c.inkDim)
                    Spacer(Modifier.height(6.dp))
                    // v2.20 性能：各档默认收起只留摘要行——旧实现 4 档 × 最多 15 个 chip
                    // 全挤一个 item，FlowRow 多轮测量让这张卡进视口那帧 30~50ms（今日页滑动掉帧主因）。
                    // 点档名展开该档 chips，状态记页面级 remember，滑出视口不丢。
                    tiers.forEachIndexed { ti, t ->
                        if (ti > 0) RowSep()
                        val open = ti in openTiers
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    openTiers = if (open) openTiers - ti else openTiers + ti
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PixelText(if (t.crops.isEmpty()) "　" else if (open) "▾" else "▸", color = c.inkDim)
                            Spacer(Modifier.width(4.dp))
                            PixelText(t.mark, color = t.color)
                            Spacer(Modifier.width(4.dp))
                            PixelText(t.label, color = if (ti == 0) c.red else c.ink)
                            Spacer(Modifier.width(6.dp))
                            PixelText("${t.crops.size} 种", color = c.inkDim)
                        }
                        if (open && t.crops.isNotEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.padding(start = 30.dp, bottom = 5.dp),
                            ) {
                                t.crops.forEach { (name, note) ->
                                    Chip(if (note.isEmpty()) name else "$name $note")
                                }
                            }
                        }
                    }
                }
            }
            item(key = "todo") {
                val items = snap.todoList
                val total = items.size
                val doneCount = todoDoneCount(snap)
                // v2.13 全完成小确幸：勾满全部条目瞬间播完成琶音（只在该次 false→true 播一次）
                val sounds = com.stardew.assistant.ui.rememberSounds()
                var wasAllDone by remember { mutableStateOf(total > 0 && doneCount == total) }
                androidx.compose.runtime.LaunchedEffect(doneCount, total) {
                    val all = total > 0 && doneCount == total
                    if (all && !wasAllDone) sounds.complete()
                    wasAllDone = all
                }
                PixelCard(title = "✅ 今日待办 $doneCount/$total", accent = c.green) {
                    if (items.isEmpty()) {
                        PixelText("还没有待办事项，点下方「管理待办」添加几条。", color = c.inkDim)
                    }
                    items.forEachIndexed { i, text ->
                        if (i > 0) RowSep()
                        // v2.57：勾选键用**文本**不用下标——自定义列表让下标失去意义，
                        //       删一行会让后面所有行的勾选错位继承。旧下标键由
                        //       PrefsRepo.migrateTodoKeys 一次性转换。键含年份（v2.17）。
                        val key = "todo:${snap.year}:${snap.season}:${snap.day}:$text"
                        val checked = key in snap.checks
                        // 外层 combinedClickable 接 onLongClick 复制为 Markdown；onClick 留空让内层 PixelCheckbox 自己处理打勾（不冲突，长按与点击分别走不同事件）
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        com.stardew.assistant.ui.copyToClipboard(
                                            ctx,
                                            "stardew_todo",
                                            "- [${if (checked) "x" else " "}] $text",
                                        )
                                    },
                                ),
                        ) {
                            com.stardew.assistant.ui.PixelCheckbox(
                                checked = checked,
                                text = text,
                                onChange = { scope.launch { repo.toggleCheck(key) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    PixelText("打勾按游戏日期记录，换天自动刷新；长按待办可复制为 Markdown。", color = c.inkDim, style = Px.caption)
                    // v2.57 管理入口
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        com.stardew.assistant.ui.PixelButton("⚙ 管理待办", onClick = { showTodoEditor = true })
                    }
                    // v2.13 全完成 banner：勾满时滑入一条金色庆祝条（清单为空不显示）
                    // c186：底色按 gold 亮度切换 —— 夜间 gold 是浅色（0xFFF2C069），cream
                    // 与 ink 同为 #F0E2C0，压上去只有 1.31:1（群友「看不清」）。
                    // 底色才是决定项（v2.72.1 底栏同判）：gold 浅（夜间）→ 底切 woodDark，
                    // cream 压深底恢复可读；gold 深（日间/四季/节日）→ 逐像素保持原样。
                    // 判据复用 GlassLadder.isLightSurface（与玻璃 veilFor 同一套 sRGB 亮度，
                    // 单源），将来任何色板把 gold 调浅都自动兜住。
                    if (total > 0 && doneCount == total) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (com.stardew.assistant.ui.glass.GlassLadder.isLightSurface(c.gold)) c.woodDark
                                    else c.gold
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            PixelText("🎉 今日待办全部完成！去做点喜欢的事吧～", color = c.cream, shadow = true)
                        }
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(4.dp)) }
        }
        } else {
            // ── v2.81.0：「我的农场」这一侧 ──────────────────────────
            // 数据来自导入时落好的缓存（不重复解析 35MB 存档）。
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // c214：农场侧顶部「↻ 刷新」——不离开今日页重读缓存。
                //   原来只能在「进设置页再回来」（visible false→true）时刷新，这里是显式入口。
                item(key = "farmRefresh") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PixelButton("↻ 刷新", onClick = { farmRefreshTick++ })
                    }
                }
                // v2.100.0 / c181：「我今天该做什么」建议区（存档驱动的今日清单之前）。
                // 一条都没有 ⇒ 整卡不出现（没导入存档 / 没算得出来的事）。
                item(key = "advice") {
                    TodayAdviceCard(advice, adviceOpen, adviceDateLabel) { adviceOpen = !adviceOpen }
                }
                // v2.100.0 / c184：「🎒 你的状态」总览卡（用户 2026-10-28 拍板）。
                // 位置：建议卡之后、「🌾 今日农场」之前 —— 行动 → 现状总览 → 田里明细。
                // 图鉴条目详情里那张「🎒 你的状态」**保留**（单条状态仍有用）；
                // 这张是**总览**：背包 / 村民好感 / 图鉴进度 / 收集包，
                // 补的是今日页与进度页的盲区（背包与好感原本只能进图鉴条目才看得到）。
                // ⚠ 没导入存档 ⇒ 整卡不出现（不显示「0 类 0 件」骗人）。
                item(key = "myStatus") {
                    MyStatusCard(myStatus)
                }
                item(key = "farm") {
                    // 「导入存档」按钮指向**现有的**存档导入入口（设置页），
                    // 不新建第二个入口（红线）。
                    FarmTodayView(farmModel, farmLoading, { openSettings() }, snap.activeProfile)
                }
                // v2.88.0：农场年度报告的入口从设置页搬到这里。
                // 报告说的是「这份农场这一年干了什么」，跟上面的今日清单是同一件事，
                // 放在设置页（跟导入存档挤在一起）反而更难找。
                item(key = "farm_report") {
                    com.stardew.assistant.ui.report.FarmReportCard(
                        report = snap.report,
                        profileName = snap.activeProfile,
                    )
                }
            }
        }
    }

    WheelDrawer(
        visible = visible && showDateDrawer,
        title = "调整游戏日期",
        wheels = listOf(
            WheelSpec("年", (1..10).map { "第 $it 年" }, snap.year - 1),
            WheelSpec("季", listOf("春", "夏", "秋", "冬"), snap.season),
            WheelSpec("日", (1..28).map { "$it 日" }, snap.day - 1),
        ),
        onDismiss = { showDateDrawer = false },
        onConfirm = { list ->
            showDateDrawer = false
            scope.launch { repo.setDate(list[0] + 1, list[1], list[2] + 1) }
        },
    )
    // v2.70.0：原「生日村民页内抽屉」已拆——点击改跳村民页（navBus 单点消费），
    // 村民档案只在村民页一处存在，避免两套抽屉行为漂移。

    // v2.57 今日待办管理抽屉：增 / 改名 / 删 / 恢复默认。清单是**全局**的（所有存档共用一份），
    // 勾选状态跟「日期+文本」走——改文本或删行只会让当天旧勾选失效，不会错标到新文本上。
    DetailDrawer(
        visible = visible && showTodoEditor,
        title = "管理今日待办",
        onDismiss = { showTodoEditor = false; todoEditing = -1 },
    ) {
        val todos = snap.todoList
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // c223：inkDim 裸压木壳抽屉底（inkDim/wood 展览会 1.03、冬季 1.06）→ 上米黄纸条
            ParchmentStrip {
                PixelText(
                    "清单所有存档共用；改名或删除条目后，当天已打的勾不迁移。",
                    style = Px.caption, color = c.inkDim,
                )
            }
            todos.forEachIndexed { i, text ->
                PixelCard {
                    if (todoEditing == i) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.weight(1f).background(c.parchment)
                                    .border(1.dp, c.slotEdge).padding(horizontal = 8.dp, vertical = 10.dp),
                            ) {
                                BasicTextField(
                                    value = todoEditBuf,
                                    onValueChange = { todoEditBuf = it.take(20) },
                                    textStyle = Px.body.copy(color = c.ink),
                                    cursorBrush = SolidColor(c.ink),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            com.stardew.assistant.ui.PixelButton(
                                text = "完成", active = true,
                                onClick = {
                                    val t = todoEditBuf.trim()
                                    when {
                                        t.isEmpty() ->
                                            android.widget.Toast.makeText(ctx, "不能为空", android.widget.Toast.LENGTH_SHORT).show()
                                        t != text && todos.filterIndexed { k, _ -> k != i }.contains(t) ->
                                            android.widget.Toast.makeText(ctx, "清单里已有同名条目", android.widget.Toast.LENGTH_SHORT).show()
                                        else -> {
                                            scope.launch { repo.setTodoList(todos.mapIndexed { k, o -> if (k == i) t else o }) }
                                            todoEditing = -1
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelText(text, modifier = Modifier.weight(1f))
                            com.stardew.assistant.ui.PixelButton("改名", onClick = { todoEditing = i; todoEditBuf = text })
                            Spacer(Modifier.width(6.dp))
                            com.stardew.assistant.ui.PixelButton("删除", onClick = {
                                scope.launch { repo.setTodoList(todos - text) }
                            })
                        }
                    }
                }
            }
            PixelCard(title = "➕ 添加待办") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).background(c.parchment)
                            .border(1.dp, c.slotEdge).padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        if (todoNew.isEmpty()) PixelText("比如：收黑莓", color = c.inkDim)
                        BasicTextField(
                            value = todoNew,
                            onValueChange = { todoNew = it.take(20) },
                            textStyle = Px.body.copy(color = c.ink),
                            cursorBrush = SolidColor(c.ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    com.stardew.assistant.ui.PixelButton(
                        text = "添加",
                        onClick = {
                            val t = todoNew.trim()
                            when {
                                t.isEmpty() ->
                                    android.widget.Toast.makeText(ctx, "先写点什么", android.widget.Toast.LENGTH_SHORT).show()
                                todos.contains(t) ->
                                    android.widget.Toast.makeText(ctx, "清单里已有同名条目", android.widget.Toast.LENGTH_SHORT).show()
                                else -> {
                                    scope.launch { repo.setTodoList(todos + t) }
                                    todoNew = ""
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                PixelText("最多 20 字；同名不能重复添加。", style = Px.caption, color = c.inkDim)
            }
            if (todos != com.stardew.assistant.data.TODO_DEFAULT) {
                com.stardew.assistant.ui.PixelButton("↺ 恢复默认清单", onClick = {
                    scope.launch { repo.setTodoList(com.stardew.assistant.data.TODO_DEFAULT) }
                    todoEditing = -1
                })
            }
        }
    }

    // v2.18 全局搜索抽屉：跨图鉴全部分类一次搜完，点结果跳图鉴并打开该条目
    var searchQuery by remember { mutableStateOf("") }
    // v2.31 改走共享索引单例：今天这里原本自己 buildSearchIndex 了一份，
    // 与图鉴页、跳到面板各建一次 = 823 条重建三遍，且三处口径可能漂。
    val searchIndex = com.stardew.assistant.ui.codex.rememberCodexIndex()
    val searchHits = remember(searchIndex, searchQuery) {
        com.stardew.assistant.ui.search.searchGlobal(searchIndex, searchQuery)
    }
    DetailDrawer(
        visible = visible && showGlobalSearch,
        title = "🔍 全局搜索",
        onDismiss = { showGlobalSearch = false },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(c.slot)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (searchQuery.isEmpty()) {
                    // c223：占位符 inkDim→ink —— inkDim/slot 最低 3.54，ink/slot 16 套 ≥4.5（137.11）
                    PixelText("🔍 搜全部分类：物品 / 菜谱 / 鱼 / 作物…", color = c.ink)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = Px.body.copy(color = c.ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searchQuery.isNotEmpty()) {
                    PixelSearchClear(
                        onClear = { searchQuery = "" },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            if (searchQuery.isBlank()) {
                // c223：inkDim 裸压木壳抽屉底 1.03 → 上米黄纸条
                ParchmentStrip(Modifier.padding(horizontal = 12.dp)) {
                    PixelText(
                        "输入关键词，一次搜完 ${searchIndex.size} 个条目（覆盖全部 ${searchIndex.map { it.groupId }.distinct().size} 个分类）",
                        style = Px.caption, color = c.inkDim,
                    )
                }
            } else if (searchHits.isEmpty()) {
                ParchmentStrip(Modifier.padding(horizontal = 12.dp)) {
                    PixelText(
                        "没有匹配「$searchQuery」的结果",
                        color = c.inkDim,
                    )
                }
            } else {
                // 按分类分组渲染（保持图鉴分类顺序）
                val grouped = searchHits.groupBy { it.groupId }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    grouped.forEach { (gid, hits) ->
                        item {
                            // c223：金字裸压木壳抽屉底（gold/wood 冬季 1.02，全场最差）→ 上米黄纸条
                            ParchmentStrip(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                                PixelText(
                                    "📂 ${hits.first().groupTitle}（${hits.size}）",
                                    color = c.gold,
                                )
                            }
                        }
                        val shown = hits.take(com.stardew.assistant.ui.search.SEARCH_GROUP_LIMIT)
                        // c191 性能：稳定 key——SearchHit 的 groupId+itemName 复合唯一
                        // c221：加工组有同名成品（蛋黄酱×2），同组同名会撞 key → 补整组下标
                        items(shown.size, key = { "${shown[it].groupId}/${shown[it].itemName}/${shown[it].index}" }) { i ->
                            val hit = shown[i]
                            val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(c.parchment)
                                    .pressFeedback(isrc)
                                    .combinedClickable(
                                        interactionSource = isrc,
                                        indication = null,
                                        onClick = {
                                            // 跳图鉴 → 定位分类 → 直接打开该条目详情
                                            showGlobalSearch = false
                                            searchQuery = ""
                                            // 由 AppRoot 切页签 + 发跳转指令（两者缺一不可）
                                            openCodex(hit.groupId, hit.index)
                                        },
                                        onLongClick = {
                                            com.stardew.assistant.ui.copyToClipboard(
                                                ctx, "stardew_search", "${hit.itemName}（${hit.groupTitle}）",
                                            )
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                com.stardew.assistant.ui.FramedIcon(hit.itemName, 26)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    PixelText(hit.itemName)
                                    if (hit.summary.isNotBlank()) {
                                        PixelText(hit.summary, style = Px.caption, color = c.inkDim)
                                    }
                                }
                            }
                        }
                        if (hits.size > shown.size) {
                            item {
                                // c223：inkDim 裸压木壳抽屉底 1.03 → 上米黄纸条
                                ParchmentStrip(Modifier.padding(top = 2.dp)) {
                                    PixelText(
                                        "…还有 ${hits.size - shown.size} 条",
                                        style = Px.caption, color = c.inkDim,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
