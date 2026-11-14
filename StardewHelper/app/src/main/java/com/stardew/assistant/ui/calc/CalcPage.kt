package com.stardew.assistant.ui.calc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.Calc
import com.stardew.assistant.data.CalcSetup
import com.stardew.assistant.data.FishPriceRow
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.Season
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PageBar
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.ReportButton
import com.stardew.assistant.ui.ValueBadge
import com.stardew.assistant.ui.WheelDrawer
import com.stardew.assistant.ui.WheelSpec
import com.stardew.assistant.ui.codex.CodexItemDetailBody
import com.stardew.assistant.ui.numberOptions
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.launch

@Composable
/**
 * @param onRetryData c200 A2：数据加载失败时的「重新加载」动作。
 *   加工利润表缺失原来只能杀进程重开（全项目没有第二处出路）。
 */
fun CalcPage(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    visible: Boolean,
    onRetryData: () -> Unit = {},
) {
    var section by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PageBar("计算")
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            PixelSegmented(
                // v2.78.0：加第四段「🐟 鱼价」—— 种植 / 加工 / 职业 算的都是钱，
                //   鱼价排行是同一件事的第四种问法（哪种鱼最值钱）。
                options = listOf("🌱 种植", "🍶 加工", "🎖 职业", "🐟 鱼价"),
                selected = section,
                onSelect = { section = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when (section) {
            0 -> PlantCalculator(snap, repo, visible)
            1 -> ProcessingPage(snap, visible, onRetryData)
            2 -> ProfessionPage(snap, repo)
            else -> FishPricePage(snap, visible)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlantCalculator(snap: PrefsRepo.Snapshot, repo: PrefsRepo, visible: Boolean) {
    val scope = rememberCoroutineScope()
    // v2.72.2：计算页输入条件落盘（用户反馈「设置好季节日期地块，中间退出重进就没了，
    // 还是得重新设置」）。三种地块各记各的由 v2.72.1 负责，这里管的是**跨进程不丢**。
    //
    // **本地状态 + 落盘**，不是直接拿 snap 当唯一数据源。两个原因：
    // ① DataStore 往返有一帧延迟，转盘确认后数字会晚一拍才变；
    // ② 纯 snap 驱动会**真的丢改动**——连点两下（先确认转盘、再切温室）时，第二下是基于
    //    上一份 snap 算的 `copy(mode = 1)`，会把第一下刚设的季节覆盖回旧值。
    //    先改本地再落盘就没有这个竞态。
    //
    // key 取 activeProfile：切存档时重新取种子（该存档自己的日期），而同一个存档内
    // 由写回触发的快照不会重新种子——否则刚设好的值会被自己刚写的快照冲掉。
    //
    // v2.92.0：**块数单独一份**（[plotsForm]）——块数是**这个存档的事实**
    // （A 存档 50 块地、B 存档 10 块），跟「用法」那份（mode / 品质）分开存。
    // 两份都按 activeProfile 重新种子：切存档时块数必须跟着换，
    // 否则切过去算出来的净利是上一个存档的块数算的（用户 2026-10-25 报）。
    var form by remember(snap.activeProfile) { mutableStateOf(snap.calc) }
    var plotsForm by remember(snap.activeProfile) { mutableStateOf(snap.calcPlots) }
    fun pick(f: (CalcSetup) -> CalcSetup) {
        val next = f(form)
        form = next
        scope.launch { repo.setCalcSetup(next) }
    }
    val mode = form.mode                    // 0 露天 · 1 温室 · 2 姜岛
    // v2.89.0：季节与日期**跟今日页是同一个**。
    //   今日页那份日期 = 导入存档的日期 + 用户在日期转盘里调过的值；
    //   计算页以前自己另存一份 season/day —— 两份日期迟早走散（改了一处忘了另一处，
    //   用户看着今日页是春天、计算页算的是夏天，谁也说不清哪个对）。
    //   现在只有一个真相：**今日页那个日期**。
    val season = snap.season
    val day = snap.day
    val plots = plotsForm.plots(mode)       // 三种地各记各的，mode 带在读写两端
    val quality = form.quality              // Calc.QUALITY_OPTIONS 下标

    /** v2.92.0：只改当前 mode 的块数，写**当前存档**那份（`cp:<存档名>`）。 */
    fun pickPlots(n: Int) {
        val next = plotsForm.withPlots(mode, n)
        plotsForm = next
        scope.launch { repo.setCalcPlots(next) }
    }
    var sortByDay by remember { mutableStateOf(false) }
    var showWheel by remember { mutableStateOf(false) }
    var openCrop by remember { mutableStateOf<Pair<String, Int>?>(null) }   // (作物名, crops 组下标)
    // ⚠ 读运行时视图（codexGroupsWithMods，含已启用 Mod 包的 SVE 作物）：
    //   读静态 codexGroups 的话，season in crop.seasons 会把 SVE 作物全筛掉
    //   （它们的季节在包里是英文 summer/fall，映射在 ModCrop.toCrop 里做）。
    // ⚠ 缓存键必须含 Mod 状态：GameData.crops 现在是**计算属性**，装/删包后
    //   同一个 key 会命中旧值，表现为「装完 SVE 进计算页还是看不到新作物」。
    //   与 ModsPage 那两处无条件 applyEnabled 是同一个病根的两张脸。
    val modCropsKey = GameData.modCropsKey
    val cropItems = remember(modCropsKey) {
        GameData.codexGroupsWithMods.firstOrNull { it.id == "crops" }?.items ?: emptyList()
    }
    val c = LocalPixel.current
    val outdoor = mode == 0
    val qMult = Calc.QUALITY_OPTIONS[quality].second
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.stardew.assistant.ui.ScrollToTopEffect(com.stardew.assistant.Screen.Calc, listState)

    // v2.17 A6：缓存键补 mode——此前切温室/姜岛再切回露天时结果仍是 emptyList，
    // 必现「这个日期已经种不下任何作物了」（cycles 的键含 mode，系漏写非有意）
    // ⚠ v2.73.4 补 modCropsKey：Calc.plantResults 读 GameData.crops（现在是含
    //   Mod 包的计算属性），装包后同一组键会命中旧值 → 看不到 SVE 作物。
    val results = remember(mode, season, day, plots, snap.prof5, snap.prof10, qMult, modCropsKey) {
        if (outdoor) Calc.plantResults(Season.names[season], day, plots, snap.prof5, snap.prof10, qMult) else emptyList()
    }.let { list -> if (sortByDay) list.sortedByDescending { it.perDay } else list.sortedByDescending { it.totalProfit } }

    val cycles = remember(mode, plots, snap.prof5, snap.prof10, qMult, modCropsKey) {
        if (outdoor) emptyList() else Calc.cycleResults(mode == 2, snap.prof5, snap.prof10, qMult)
    }.let { list -> if (sortByDay) list.sortedByDescending { it.perDay } else list.sortedByDescending { it.profitPerPlot } }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PixelCard {
                // 种植地点：露天按季末死线，温室/姜岛按持续周转
                PixelSegmented(
                    options = listOf("🌤 露天", "🏠 温室", "🌋 姜岛"),
                    selected = mode,
                    onSelect = { i -> pick { it.copy(mode = i) } },
                )
                Spacer(Modifier.height(8.dp))
                if (outdoor) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ValueBadge("季节：${Season.names[season]}", onClick = { showWheel = true }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        ValueBadge("第 $day 日", onClick = { showWheel = true }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        ValueBadge("地块 $plots ▾", onClick = { showWheel = true }, modifier = Modifier.weight(1f))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ValueBadge("地块 $plots ▾", onClick = { showWheel = true })
                        Spacer(Modifier.width(8.dp))
                        PixelText(if (mode == 1) "全年可种 · 不受季末限制" else "姜岛全年夏季 · 只列夏季作物", color = c.inkDim)
                    }
                }
                // v2.89.0：季节与日期**跟今日页是同一个**。
                // ⚠ v2.91.0 补回这句提示 —— 之前改计算页的日期只改了一半
                //   （季节/日期改了，今日页没跟着走），用户看得见两页日期打架。
                if (outdoor) {
                    Spacer(Modifier.height(6.dp))
                    PixelText(
                        "日期跟今日页是同一个：这里改了，今日页也跟着变",
                        style = Px.caption,
                        color = c.inkDim,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 品质 + 排序合并一行，职业 chip 收行尾，让结果首屏尽早露出
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelSegmented(
                        options = Calc.QUALITY_OPTIONS.map { it.first },
                        selected = quality,
                        onSelect = { i -> pick { it.copy(quality = i) } },
                        modifier = Modifier.weight(1.2f),
                    )
                    Spacer(Modifier.width(6.dp))
                    PixelSegmented(
                        options = if (outdoor) listOf("总净利", "每天") else listOf("每株", "每天"),
                        selected = if (sortByDay) 1 else 0,
                        onSelect = { sortByDay = it == 1 },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (snap.prof5.isNotEmpty() || snap.prof10.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Chip("职业：${snap.prof5.ifEmpty { "无" }}${if (snap.prof10.isNotEmpty()) "+" + snap.prof10 else ""}", border = c.green, content = c.green)
                }
            }
        }
        // v2.23：按用户要求还原「整卡」样式（v2.20 拆散行、v2.22 分壳拼框，均与原版有视觉差）。
        // 性能由 R8 构建（组合成本降 2~4 倍）+ keep-alive（每页只付一次首组合）兜底。
        item {
            val title = when {
                outdoor -> "🍀 可种作物（${Season.names[season]}季 · 剩 ${28 - day + 1} 天）"
                mode == 1 -> "🍀 温室作物（持续周转 · 按 $plots 块地）"
                else -> "🍀 姜岛作物（持续周转 · 按 $plots 块地）"
            }
            PixelCard(title = title) {
                if (outdoor) {
                    if (results.isEmpty()) PixelText("这个日期已经种不下任何作物了", color = c.red)
                    results.forEachIndexed { i, r ->
                        if (i > 0) com.stardew.assistant.ui.RowSep()
                        CropRow(r, cropItems) { openCrop = it }
                    }
                    Spacer(Modifier.padding(2.dp))
                    PixelText(
                        if (quality == 0) "基础售价口径 · 未计肥料 / 巨型作物 · 品质价目见图鉴作物详情"
                        else "已按「${Calc.QUALITY_OPTIONS[quality].first}」品质系数 ×${Calc.QUALITY_OPTIONS[quality].second} 计售价 · 巨型作物不计",
                        color = c.inkDim,
                    )
                } else {
                    cycles.forEachIndexed { i, r ->
                        if (i > 0) com.stardew.assistant.ui.RowSep()
                        val cidx = cropItems.indexOfFirst { it.name == r.crop.name }
                        val rg = r.crop.regrow
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .then(if (cidx >= 0) Modifier.clickable { openCrop = r.crop.name to cidx } else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // v2.73.5：温室循环行也走包内作物图（判据在 CropIcon 里单源）
                            com.stardew.assistant.ui.mods.CropIcon(r.crop, 30, pad = 1)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    PixelText(r.crop.name)
                                    if (rg != null) Chip("再生 $rg 天一茬")
                                    if (r.crop.seedPrice == null) Chip("种子不可买", border = c.blue, content = c.blue)
                                }
                                PixelText(
                                    (r.crop.growth?.let { "$it 天成熟" } ?: "") +
                                        if (r.crop.trellis) " · 需搭架" else "",
                                    color = c.inkDim,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                PixelText("${fmt((r.profitPerPlot * plots).coerceAtLeast(0))}g", style = Px.big, color = c.gold)
                                PixelText(
                                    "${fmt(r.profitPerPlot)}g/株 · ${"%.1f".format(r.perDay)}g/天",
                                    color = c.inkDim,
                                )
                            }
                            if (cidx >= 0) {
                                Spacer(Modifier.width(6.dp))
                                PixelText("▸", color = c.inkDim)
                            }
                        }
                    }
                    Spacer(Modifier.padding(2.dp))
                    PixelText(
                        // c226 瘦身：推导过程砍半；「巨型作物只能露天」在温室/姜岛页签里费解，删。
                        "口径：非再生 = 单轮净利 ÷ 生长天数；再生 = 售价 ÷ 再生天数 · 品质系数 ×${qMult}",
                        color = c.inkDim,
                    )
                }
            }
        }
    }

    WheelDrawer(
        visible = visible && showWheel,
        title = if (outdoor) "种植计算 · 选择条件" else "种植计算 · 地块",
        wheels = if (outdoor) listOf(
            WheelSpec("季节", listOf("春", "夏", "秋", "冬"), season),
            WheelSpec("日期", numberOptions(1, 28, "日"), day - 1),
            WheelSpec("地块", numberOptions(1, 999, "块"), plots - 1),
        ) else listOf(
            WheelSpec("地块", numberOptions(1, 999, "块"), plots - 1),
        ),
        onDismiss = { showWheel = false },
        onConfirm = { list ->
            showWheel = false
            // v2.72.2：整份回写并落盘。露天那次同时动季节+日期+块数，温室/姜岛只动块数——
            // 两条路径都经 pick，所以「改了没存」这件事在类型上就发生不了。
            // v2.89.0：改季节日期 = 改**今日页那个日期**（同一个游戏日期，两处一起走）。
            if (outdoor) {
                scope.launch { repo.setDate(snap.year, list[0], list[1] + 1) }
                pick { it.copy(season = list[0], day = list[1] + 1) }
                // v2.92.0：块数走**当前存档**那份，不再塞进全局的 calc_setup
                pickPlots(list[2] + 1)
            } else {
                pickPlots(list[0] + 1)
            }
        },
    )

    DetailDrawer(
        visible = visible && openCrop != null,
        title = "${openCrop?.first ?: ""} · 作物图鉴",
        onDismiss = { openCrop = null },
    ) {
        val oc = openCrop
        if (oc != null) CodexItemDetailBody("crops", oc.second, snap, Modifier.fillMaxSize())
    }
}

/** 露天生种植结果行（点击跳图鉴详情） */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CropRow(r: Calc.CropResult, cropItems: List<com.stardew.assistant.data.CodexItem>, onOpen: (Pair<String, Int>) -> Unit) {
    val c = LocalPixel.current
    val cidx = cropItems.indexOfFirst { it.name == r.crop.name }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(if (cidx >= 0) Modifier.clickable { onOpen(r.crop.name to cidx) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v2.73.5：露天结果行也走包内作物图（判据在 CropIcon 里单源）
        com.stardew.assistant.ui.mods.CropIcon(r.crop, 30, pad = 1)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PixelText(r.crop.name)
                if (r.crop.regrow != null) Chip("再生 ${r.crop.regrow} 天一茬")
                if (r.crop.giant) Chip("可巨型")
                if (r.crop.seedPrice == null) Chip("种子不可买", border = c.blue, content = c.blue)
            }
            PixelText(
                "${r.crop.growth} 天成熟 · 首收 ${r.firstHarvest} 日 · 共 ${r.harvests} 茬" +
                    if (r.crop.trellis) " · 需搭架" else "",
                color = c.inkDim,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            PixelText("${fmt(r.totalProfit)}g", style = Px.big, color = c.gold)
            PixelText(
                "${fmt(r.profitPerPlot)}g/株 · ${"%.1f".format(r.perDay)}g/天",
                color = c.inkDim,
            )
        }
        if (cidx >= 0) {
            Spacer(Modifier.width(6.dp))
            PixelText("▸", color = c.inkDim)
        }
    }
}

/** v2.14 加工利润排行的一行（来自 processingProfit.json） */
private data class ProfitRow(
    val machine: String,
    val input: String,
    val output: String,
    val inputPrice: Int,
    val outputPrice: Int,
    val hours: Double,
    val profit: Int,
    val perHour: Double,
    val note: String = "",
) {
    /** 工匠：成品价 ×1.4 后重算单次利润与时薪 */
    fun withArtisan(): ProfitRow {
        val p = (outputPrice * 1.4).toInt() - inputPrice
        return copy(profit = p, perHour = if (hours > 0) p / hours else 0.0)
    }
}

@Composable
private fun ProcessingPage(snap: PrefsRepo.Snapshot, visible: Boolean, onRetryData: () -> Unit) {
    val artisan = snap.prof10 == "工匠"
    val c = LocalPixel.current
    val results = remember(artisan) { Calc.processResults(artisan) }
    var openProc by remember { mutableStateOf<Calc.ProcessResult?>(null) }
    // v2.14：0 = 配方收益（旧口径，按日均） / 1 = 利润排行（新数据源，三种排序）
    var view by remember { mutableIntStateOf(0) }
    var sortBy by remember { mutableIntStateOf(0) }
    // v2.17 D8：删掉旧版 profitRows 死计算——无任何消费方（排行页 ProfitRankingPage 自带 rows），
    // 且其 remember 键缺 sortBy（一旦被复用即陈旧闭包），白解析 62 条 JSON

    Column(Modifier.fillMaxSize()) {
        PixelSegmented(
            options = listOf("🫖 配方收益", "📈 利润排行"),
            selected = view,
            onSelect = { view = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (view == 0) {
            RecipeProfitList(artisan, results) { openProc = it }
        } else {
            ProfitRankingPage(artisan, sortBy, onRetryData) { sortBy = it }
        }
    }

    DetailDrawer(
        visible = visible && openProc != null,
        title = "${openProc?.row?.product ?: ""} · 加工详情",
        onDismiss = { openProc = null },
    ) {
        val op = openProc
        if (op != null) ProcessDetailBody(op)
    }
}

/** v2.14 前的原有口径：按日均增益排序的配方收益 */
@Composable
private fun RecipeProfitList(
    artisan: Boolean,
    results: List<Calc.ProcessResult>,
    onOpen: (Calc.ProcessResult) -> Unit,
) {
    val c = LocalPixel.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // v2.23：还原整卡样式（同种植页，理由见 PlantCalculator 注释）
        item {
            PixelCard(title = "🫖 加工收益（按日均增益排序）") {
                PixelText(
                    if (artisan) "当前职业：工匠 → 手工品售价 ×1.4"
                    else "未勾选「工匠」职业（10 级），可在「职业」页设置",
                    color = if (artisan) c.green else c.inkDim,
                )
                Spacer(Modifier.padding(2.dp))
                results.forEachIndexed { i, r ->
                    if (i > 0) com.stardew.assistant.ui.RowSep()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable { onOpen(r) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FramedIcon(r.row.product, 30, pad = 1)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            PixelText("${r.row.raw} → ${r.row.product}")
                            PixelText("${r.row.machine} · ${r.row.timeText}", color = c.inkDim)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (r.profit != null) PixelText("+${fmt(r.profit)}g", style = Px.big, color = c.gold)
                            else PixelText("${fmt(r.productPrice)}g", style = Px.big, color = c.gold)
                            PixelText(
                                if (r.perDay != null) "日均 +${"%.0f".format(r.perDay)}g"
                                else "售价 ${fmt(r.productPrice)}g",
                                color = c.inkDim,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        PixelText("▸", color = c.inkDim)
                    }
                }
                PixelText(
                    "提示：腌菜罐 / 果酱一律 = 原料价 ×2 + 50g，耗时 2 天，对照本页日均决定酿酒还是腌菜",
                    color = c.inkDim,
                )
            }
        }
    }
}

/** v2.14 利润排行：processingProfit.json 的 15 台设备 / 62 条配方，三种排序 */
@Composable
private fun ProfitRankingPage(
    artisan: Boolean,
    sortBy: Int,
    onRetryData: () -> Unit,
    onSort: (Int) -> Unit,
) {
    val c = LocalPixel.current
    val root = com.stardew.assistant.data.GameData.processingProfit
    if (root == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp + LocalGlassInset.current)) {
            item { com.stardew.assistant.ui.guide.GuideLoadFail("加工利润", onRetryData) }
        }
        return
    }
    val machines = remember(root) { root.optJSONArray("machines") }
    val rows = remember(machines, artisan, sortBy) {
        val out = mutableListOf<ProfitRow>()
        if (machines != null) {
            for (i in 0 until machines.length()) {
                val m = machines.getJSONObject(i)
                val mName = m.optString("name")
                val rs = m.optJSONArray("recipes") ?: continue
                for (j in 0 until rs.length()) {
                    val r = rs.getJSONObject(j)
                    val row = ProfitRow(
                        machine = mName,
                        input = r.optString("input"),
                        output = r.optString("output"),
                        inputPrice = r.optInt("input_base_price"),
                        outputPrice = r.optInt("output_price"),
                        hours = r.optDouble("processing_time_hours", 0.0),
                        profit = r.optInt("profit"),
                        perHour = r.optDouble("profit_per_hour", 0.0),
                        note = r.optString("note"),
                    )
                    out.add(if (artisan) row.withArtisan() else row)
                }
            }
        }
        when (sortBy) {
            1 -> out.sortedByDescending { it.profit }
            2 -> out.sortedBy { it.profit }
            else -> out.sortedByDescending { it.perHour }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PixelSegmented(
            options = listOf("每小时利润", "总利润", "单次利润"),
            selected = sortBy,
            onSelect = onSort,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        PixelText(
            "共 ${rows.size} 条配方 · " + if (artisan) "工匠：成品价 ×1.4 已计入" else "未计工匠加成",
            style = Px.caption,
            color = if (artisan) c.green else c.inkDim,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp + LocalGlassInset.current),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // c191 性能：稳定 key——机器+输入+输出三元组在数据中零重名（脚本扫描证明），
            // 排序键切换重排时行按身份复用。
            items(rows.size, key = { val r = rows[it]; "${r.machine}/${r.input}/${r.output}" }) { i ->
                val r = rows[i]
                Row(
                    Modifier.fillMaxWidth().background(c.parchment)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FramedIcon(r.output, 26, pad = 1)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        PixelText("${r.input} → ${r.output}")
                        PixelText(
                            "${r.machine} · ${"%.1f".format(r.hours)} 小时",
                            style = Px.caption, color = c.inkDim,
                        )
                        if (r.note.isNotBlank()) {
                            PixelText(r.note, style = Px.caption, color = c.inkDim)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        PixelText(
                            (if (r.profit >= 0) "+" else "") + "${fmt(r.profit)}g",
                            style = Px.big,
                            color = if (r.profit >= 0) c.gold else c.red,
                        )
                        PixelText(
                            "${"%.1f".format(r.perHour)}g/时",
                            style = Px.caption, color = c.inkDim,
                        )
                    }
                }
            }
        }
    }
}


/** 加工品详情主体：加工链 + 收益 + 哪些村民最爱收（出货图鉴条目太单薄，这里吃计算页现成数据） */
@Composable
private fun ProcessDetailBody(r: Calc.ProcessResult) {
    val c = LocalPixel.current
    val product = r.row.product
    // c191 性能：product 变化才重扫礼物表（详情抽屉重组不再重复线性扫）
    val lovers = remember(product) { GameData.gifts.filter { product in it.loves }.map { it.name } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PixelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FramedIcon(product, 48)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    PixelText(product)
                    PixelText("${r.row.raw} → $product", color = c.inkDim)
                }
                ReportButton("计算·加工", product, "${r.row.raw}→$product · ${r.row.machine} · ${r.row.timeText}")
            }
        }
        PixelCard(title = "🍶 加工") {
            KVRow("原料", r.row.raw)
            KVRow("机器", r.row.machine)
            KVRow("耗时", r.row.timeText)
            val base = r.row.price ?: 0
            KVRow("成品基础价", "${fmt(base)} g")
            KVRow("工匠价 ×1.4", "${fmt((base * 1.4).toInt())} g")
        }
        PixelCard(title = "💰 收益") {
            KVRow("原料成本", if (r.rawPrice != null) "${fmt(r.rawPrice)} g" else "—")
            KVRow("单件净利", if (r.profit != null) "+${fmt(r.profit)} g" else "—")
            KVRow("日均增益", if (r.perDay != null) "+${"%.0f".format(r.perDay)} g" else "—")
        }
        PixelCard(title = "❤ 谁最爱收") {
            if (lovers.isEmpty()) {
                PixelText("没有村民把它列为最爱（当普通好感礼物送即可）", color = c.inkDim)
            } else {
                lovers.chunked(4).forEachIndexed { ri, rowItems ->
                    if (ri > 0) Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowItems.forEach { Chip(it) }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ProfessionPage(snap: PrefsRepo.Snapshot, repo: PrefsRepo) {
    val scope = rememberCoroutineScope()
    val c = LocalPixel.current
    val t5 = listOf("" to "无", "农耕人" to "农耕人（农产品 +10%）", "畜牧人" to "畜牧人（畜产品 +20%）")
    val t10 = when (snap.prof5) {
        "农耕人" -> listOf("" to "无", "工匠" to "工匠（手工品 +40%）", "农业学家" to "农业学家（作物生长 -10%）")
        "畜牧人" -> listOf("" to "无", "鸡舍大师" to "鸡舍大师（孵化减半）", "牧羊人" to "牧羊人（羊毛更快）")
        else -> listOf("" to "（先选 5 级职业）")
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PixelCard(title = "🎖 耕种 5 级职业") {
                t5.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { scope.launch { repo.setProfession(v, "") } }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(if (snap.prof5 == v) c.green else c.slot)
                                .slotBevel(inset = 2.dp, width = 1.dp)
                                .border(2.dp, c.slotEdge),
                        )
                        Spacer(Modifier.width(8.dp))
                        PixelText(label)
                    }
                }
            }
        }
        item {
            PixelCard(title = "🎖 耕种 10 级专精") {
                if (snap.prof5.isEmpty()) PixelText("先选择 5 级职业，10 级专精才会解锁", color = c.inkDim)
                t10.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { scope.launch { repo.setProfession(snap.prof5, v) } }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(if (snap.prof10 == v) c.green else c.slot)
                                .slotBevel(inset = 2.dp, width = 1.dp)
                                .border(2.dp, c.slotEdge),
                        )
                        Spacer(Modifier.width(8.dp))
                        PixelText(label)
                    }
                }
                PixelText("职业全局生效：种植计算用 农耕人/农业学家，加工收益用 工匠", color = c.inkDim)
            }
        }
    }
}

private fun fmt(n: Int): String = java.text.DecimalFormat("#,###").format(n.toLong())

/**
 * v2.78.0 鱼价排行：所有鱼按**卖出价**从高到低，附银/金/铱品质价与钓取难度。
 *
 * ## 口径（只用一套，不并排摆两个价）
 * · **卖出价** = 钓上来卖给村民能拿到的钱。普档是基准，银 ×1.25 / 金 ×1.5 / 铱 ×2。
 *   数据源 = `fishSpots.json` 的 prices，与钓鱼图鉴详情里的「四档售价」**同一个源**。
 * · **鱼店买价**（fishing.json 的 price）是**另一套口径**（鱼店加价卖），不在本表里 ——
 *   74 条鱼里有 7 条两个价对不上（幽灵鱼鱼店 220 / 普档 60），混着摆只会让人
 *   以为 App 数据错了。鱼店买价去图鉴的「基础价」一栏看。
 * · **难度** = fishing.json 的难度值；蟹笼鱼不是钓的（难度 0），标「蟹笼」不标难度。
 *
 * ⚠ **没有的档位不补零**：蟹笼鱼只有「普」一档，采集物一档都没有 —— 缺就是缺，
 *   排在末尾、价格位显示「—」。
 *
 * v2.80 改两处（都是「够不着」类毛病，数据一个没动）：
 * 1. **每行直接带鱼图**（FramedIcon）—— 以前整张排行是纯文字，想看鱼长什么样
 *    只能跳去图鉴，而图鉴里那条还得自己再找一遍。
 * 2. **点鱼不再跳图鉴页** —— 改成在本页浮出 [DetailDrawer] 抽屉（钓点 / 经验 /
 *    四档售价 / 排查清单），关掉抽屉还在鱼价页。跳页会把「算鱼价」这件事打断，
 *    用户得自己再点回计算页；抽屉是浮层，不动底下的页签。
 */
@Composable
private fun FishPricePage(snap: PrefsRepo.Snapshot, visible: Boolean) {
    val c = LocalPixel.current
    val rows = remember { Calc.fishPriceRanking() }
    // 折叠态只给前 20 名（74 条全摊开会把这一段拉到屏外）
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) rows else rows.take(20)
    // 图鉴「钓鱼」分类：点排行里的鱼 → 在本页浮出那条鱼的详情抽屉
    val fishGroup = remember(GameData.modFishesKey) {
        GameData.codexGroupsWithMods.firstOrNull { it.id == "fishing" }
    }
    // 当前打开的鱼在「钓鱼图鉴」里的下标（-1 = 没打开）。抽屉关掉就回 -1。
    var openFishIdx by remember { mutableStateOf(-1) }

    Column(Modifier.fillMaxSize()) {
        PixelText(
            "共 ${rows.size} 种 · 按卖出价排序（普档基准，右侧银金铱为各档实际售价）；鱼店买价见图鉴",
            style = Px.caption,
            color = c.inkDim,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        if (rows.isEmpty()) {
            PixelText("鱼的数据没加载出来", color = c.red, modifier = Modifier.padding(14.dp))
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // c191 性能：稳定 key——鱼名在钓鱼排行中唯一（数据侧同鱼单条）
            items(shown.size, key = { shown[it].name }) { i ->
                val r = shown[i]
                val idx = fishGroup?.items?.indexOfFirst { it.name == r.name } ?: -1
                Row(
                    Modifier.fillMaxWidth().background(c.parchment)
                        .clickable(enabled = idx >= 0) { openFishIdx = idx }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelText("${i + 1}", style = Px.caption, color = c.gold, modifier = Modifier.width(24.dp))
                    // v2.80：鱼图直接摆在行里，不用点进去才看得到
                    FramedIcon(r.name, 26, pad = 1)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        PixelText(r.name)
                        PixelText(fishSubLine(r), style = Px.caption, color = c.inkDim)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        PixelText(
                            r.sellPrice?.let { "${fmt(it)}g" } ?: "—",
                            style = Px.big,
                            color = if (r.sellPrice != null) c.gold else c.inkDim,
                        )
                        val q = fishQualityText(r)
                        if (q.isNotEmpty()) PixelText(q, style = Px.caption, color = c.inkDim)
                    }
                }
            }
            if (!showAll && rows.size > 20) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        PixelButton(text = "展开全部 ${rows.size} 种 ▾", onClick = { showAll = true })
                    }
                }
            }
        }
    }
    // v2.80：在鱼价页就地看鱼详情，**不跳图鉴页**。跟作物计算器 / 加工页同一个 DetailDrawer。
    DetailDrawer(
        visible = visible && openFishIdx >= 0,
        title = "${fishGroup?.items?.getOrNull(openFishIdx)?.name ?: ""} · 鱼详情",
        onDismiss = { openFishIdx = -1 },
    ) {
        CodexItemDetailBody("fishing", openFishIdx, snap, Modifier.fillMaxSize())
    }
}

/** 排行行的副行：钓取难度 + 季节；蟹笼鱼不是钓的，标「蟹笼」不标难度。 */
private fun fishSubLine(r: FishPriceRow): String {
    if (r.crabPot) return "蟹笼 · 拾取"
    val d = r.difficulty?.let { "难度 $it" } ?: "难度未知"
    val s = if (r.seasons.isEmpty()) "" else r.seasons.joinToString("、")
    return if (s.isEmpty()) d else "$d · $s"
}

/** 品质档位串（银/金/铱）。**缺档不补零** —— 没有的档位直接不出现。 */
private fun fishQualityText(r: FishPriceRow): String {
    val parts = listOf(
        r.silver?.let { "银$it" },
        r.gold?.let { "金$it" },
        r.iridium?.let { "铱$it" },
    ).filterNotNull()
    return parts.joinToString(" ")
}
