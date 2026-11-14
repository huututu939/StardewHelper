package com.stardew.assistant.ui.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
// v2.33：条目列表改图标网格（决策 1 列数用 GridCells.Adaptive 自适应，不硬编码）
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.CodexGroup
import com.stardew.assistant.data.CodexItem
import com.stardew.assistant.data.FishSpotTable
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.CodexState
import com.stardew.assistant.data.CodexStateCache
import com.stardew.assistant.data.codexCheckKey
import com.stardew.assistant.data.groupCheckedCount
import com.stardew.assistant.data.isCheckable
import com.stardew.assistant.data.isItemChecked
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.vibrateTap
import com.stardew.assistant.ui.mods.ModCropIcon
import com.stardew.assistant.ui.mods.ModItemIcon
import com.stardew.assistant.ui.mods.VillagerAvatar
import com.stardew.assistant.ui.search.SearchHit
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PageBar
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.ParchmentStrip
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelSearchClear
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.ReportButton
import com.stardew.assistant.ui.nav.NavRequest
import com.stardew.assistant.ui.nav.navBus
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.pressFeedback
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 图鉴条目详情：抽屉打开目标（groupId + 下标） */
private data class CodexTarget(val groupId: String, val idx: Int)

/** 把 location 原文归并到 8 大类（v2.11）：与钓鱼图鉴 PixelSegmented 段下标对应——
 *  1 海洋 / 2 河流 / 3 湖泊 / 4 矿洞 / 5 山林 / 6 蟹笼 / 7 下水道 / 8 沙漠；0 = 不归任何类（理论上不会有） */
private fun locToBucket(loc: String): Int = when {
    loc.contains("蟹笼") -> 6
    loc.contains("海洋") || loc.contains("沙滩") || loc.contains("海盗湾") || loc.contains("夜市") -> 1
    loc.startsWith("河流") || loc.contains("/ 河流") || loc.contains("河流 /") || loc.contains("淡水") -> 2
    loc.startsWith("湖泊") || loc.contains("/ 湖泊") || loc.contains("湖泊 /") || loc.startsWith("深山湖泊") -> 3
    loc.contains("矿井") || loc.contains("矿洞") || loc.contains("火山口") -> 4
    loc.contains("秘密森林") || loc.contains("女巫") || loc.contains("突变虫穴") || loc.contains("瀑布") || loc.contains("森林箭形") -> 5
    loc.contains("下水道") -> 7
    loc.contains("沙漠") -> 8
    else -> 0
}

/** 分类面板分组（展示顺序 + 色调）已搬到 CodexPanelSections.kt（v2.31）：
 *  「跳到…」面板要复用同一套四组上色，两处各留一份 id 硬编码必然漂移。 */

/**
 * 把某分类的条目按**当前筛选**过滤成「实际显示的列表」。
 *
 * ## c218：网格与抽屉**必须共用同一份**
 * ⚠ 详情抽屉的翻页原来直接用 `group.items`（整组），绕过筛选/搜索 ——
 *   用户在「鱼塘」里筛「珊瑚」看到 8 条，点开一条，抽屉里左右滑却会
 *   滑到整个鱼塘分类（上百条）的无关条目，页码也按整组算。
 *   现在网格的展示列表与抽屉的翻页范围都从这**同一个** `filterItems` 出来 ——
 *   筛出几条，抽屉就在那几条里翻。
 *
 * 与旧逻辑等价：还记得的判定字段一个不少（query / checkFilter / 钓鱼 6 段筛选）。
 */
internal fun filterItems(
    group: CodexGroup,
    query: String,
    checkFilter: Int,
    checks: Set<String>,
    fishSeasonFilter: Int,
    fishLocFilter: Int,
    fishTypeFilter: Int,
    fishWeatherFilter: Int,
    fishDifficultyFilter: Int,
    fishSpotFilter: String,
    fishTable: FishSpotTable?,
): List<CodexItem> {
    // isFishing 由 group.id 就地判定（钓鱼专属的 6 段筛选 + 词汇才需要本分支；
    // 勾选筛选是 13 个分类通用、不依赖它）。
    val isFishing = group.id == "fishing"
    var items = if (query.isBlank()) group.items else {
        group.items.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    // c211：鱼塘扩容门「按材料反查」——搜材料名时把「需要该材料扩容」的鱼也列出来。
    if (group.id == "fishPonds" && query.isNotBlank() && GameData.pondFishByMaterial.isNotEmpty()) {
        val q = query.trim()
        val hitFish = GameData.pondFishByMaterial.entries
            .filter { it.key.contains(q, ignoreCase = true) }
            .flatMapTo(mutableSetOf()) { it.value }
        if (hitFish.isNotEmpty()) {
            val byName = items.associateBy { it.name }
            val extra = hitFish.mapNotNull { byName[it] }
            items = items + extra.filter { it !in items }
        }
    }
    // v2.32：勾选筛选与钓鱼解耦，**13 个可打勾分类通用**（不只钓鱼）。
    if (isCheckable(group.id) && checkFilter != 0) {
        items = items.filter { isItemChecked(group.id, it.name, checks) == (checkFilter == 2) }
    }
    if (isFishing) {
        val fishByName = GameData.fishing.associateBy { it.name }
        val modFishByName = GameData.modFishes.associateBy { it.name }
        if (fishSeasonFilter != 0) {
            val sn = listOf("春", "夏", "秋", "冬", "全年")[fishSeasonFilter - 1]
            items = items.filter { fish ->
                val fs = fishByName[fish.name]
                if (fs != null) return@filter ("全年" in fs.seasons || sn in fs.seasons)
                val mf = modFishByName[fish.name] ?: return@filter true
                mf.seasons.isEmpty() || "全年" in mf.seasons || sn in mf.seasons
            }
        }
        if (fishLocFilter != 0) {
            items = items.filter { fish ->
                val fs = fishByName[fish.name] ?: return@filter true
                locToBucket(fs.location) == fishLocFilter
            }
        }
        if (fishTypeFilter != 0) {
            val tn = listOf("普通", "传说", "二代传说", "蟹笼")[fishTypeFilter - 1]
            items = items.filter { fish ->
                val fs = fishByName[fish.name] ?: return@filter true
                fs.type == tn
            }
        }
        if (fishWeatherFilter != 0) {
            val wn = listOf("任意", "晴天", "雨天")[fishWeatherFilter - 1]
            items = items.filter { fish ->
                val fs = fishByName[fish.name]
                if (fs != null) return@filter fs.weather == wn
                val mf = modFishByName[fish.name] ?: return@filter true
                mf.weather.isEmpty() || mf.weather == wn
            }
        }
        if (fishDifficultyFilter != 0) {
            val (lo, hi) = when (fishDifficultyFilter) {
                1 -> 0 to 40
                2 -> 41 to 60
                3 -> 61 to 999
                else -> 0 to 999
            }
            items = items.filter { fish ->
                val fs = fishByName[fish.name]
                if (fs != null) {
                    return@filter fs.difficulty != null && fs.difficulty in lo..hi
                }
                val mf = modFishByName[fish.name] ?: return@filter true
                mf.difficulty?.let { it in lo..hi } ?: true
            }
        }
        // v2.14 钓点：只保留「有钓点数据且包含该钓点」的鱼。
        if (fishSpotFilter.isNotEmpty()) {
            items = items.filter { fish ->
                val info = fishTable?.of(fish.name) ?: return@filter true
                info.spots.contains(fishSpotFilter)
            }
        }
    }
    return items
}

/**
 * c221：和 [filterItems] 同口径的筛选，但返回**整组下标**而非条目列表。
 *
 * ## 为什么必须有它
 * [filterItems] 返回 `List<CodexItem>`，抽屉拿到下标是靠
 * `group.items.indexOfFirst { it.name == itm.name }` **按名反查**。图鉴里「加工」
 * 组有大量同名成品：蛋黄酱×2、奶酪×2、布料×2、油×3、铜/铁/金/铱锭各×2、
 * 精炼石英×3、煤炭×3（processing.json 实测）。按名反查把第二次/第三次出现
 * 的下标**折叠成第一次** → 点「鸵鸟蛋蛋黄酱」开出来的是组里第一条（啤酒），
 * 且 pager 同一行占两页、页码虚高。这里直接保留 `mapIndexedNotNull` 的真实下标。
 *
 * ⚠ 与 [filterItems] 共用同一份底层计算 [filterIndexed] 的最后一步表，
 *   口径永远一致，不各算一遍。
 */
internal fun filterItemIndices(
    group: CodexGroup,
    query: String,
    checkFilter: Int,
    checks: Set<String>,
    fishSeasonFilter: Int,
    fishLocFilter: Int,
    fishTypeFilter: Int,
    fishWeatherFilter: Int,
    fishDifficultyFilter: Int,
    fishSpotFilter: String,
    fishTable: FishSpotTable?,
): List<Int> = filterIndexed(
    group, query, checkFilter, checks,
    fishSeasonFilter, fishLocFilter, fishTypeFilter, fishWeatherFilter,
    fishDifficultyFilter, fishSpotFilter, fishTable,
).map { it.first }

/** c221：筛选一次的**底层真源**，返回 `(整组下标, 条目名)`，两个出口共同复用。 */
private fun filterIndexed(
    group: CodexGroup,
    query: String,
    checkFilter: Int,
    checks: Set<String>,
    fishSeasonFilter: Int,
    fishLocFilter: Int,
    fishTypeFilter: Int,
    fishWeatherFilter: Int,
    fishDifficultyFilter: Int,
    fishSpotFilter: String,
    fishTable: FishSpotTable?,
): List<Pair<Int, String>> {
    val liveNameSet = filterItems(
        group, query, checkFilter, checks,
        fishSeasonFilter, fishLocFilter, fishTypeFilter, fishWeatherFilter,
        fishDifficultyFilter, fishSpotFilter, fishTable,
    ).mapTo(mutableSetOf()) { it.name }
    // 保留**每一个**出现下标：加工组同名成品（蛋黄酱×2、铜锭×2…）第二条也要进 pager，
    // 不能像 c218 的「按名 indexOfFirst 反查」那样折叠成第一条。
    return group.items.mapIndexedNotNull { i, itm -> if (itm.name in liveNameSet) i to itm.name else null }
}

/**
 * c219：收集包「同包条目」语境里的一页。
 * `name` 用于点名字后在同包列表里定位。
 * c221：唯一性判定不再只看「按名命中唯一」——图鉴把同一物品同时收在多个分类
 *（作物在 crops + shipping、鱼在 fishing + shipping），**跨全部分类** `nameToHits`
 * 会把绝大多数本可直达的名字算成「重名」⇒ 就地翻页对 13/27 个包完全不生效
 *（两轮审查独立确认）。因此换成 `targetVisibleRefs` 的复合判定（见 buildBundleRefs）。
 */
internal data class BundleRef(val groupId: String, val idx: Int, val name: String)

/**
 * c219：把「当前条目 + 同包其他名字」编成抽屉里可翻的页列表。
 *
 * ## 为什么需要它
 * 抽屉里点收集包 mate 名字，原来走 [resolveItemJump] 跳一个**新抽屉**，
 * 落地时按新条目的分类/筛选重新定位——用户在「这个包还缺啥」的语境里翻到一半，
 * 一跳就丢了上下文（还要自己滑回来）。现在改成：抽屉不关，内容就地换成被点的条目，
 * pager 只在**同一个收集包**的条目里翻。
 *
 * c221 修正「唯一命中」的判据：
 * · 按名跨全部分类命中**恰 1 条且在可翻分类里**才进包内列表；
 * · 名命中多条，但**全部落在出货/加工这类聚合展示分类**（它们不是条目本体，
 *   只是展示聚合）→ 视同唯一、可进包内——否则「防风草」这种作物在院校分类
 *   里都跨 2 个分类，包内翻页形同虚设；
 * · 按名零命中 → 复用 [resolveJumpRequest] 的**别名/品质归一回落**（金星南瓜→南瓜、
 *   大壶牛奶→大牛奶），回落后唯一且可翻也进——12 个别名侧名字从「只能跳走」变成可就地翻。
 * · 其余一律不进包内（点它们时走 resolveItemJump 让用户自己挑，不替用户猜）。
 *
 * 纯函数：`hitOf` / `titleOf` 注入，JVM 单测不需要真实图鉴数据。
 */
internal fun buildBundleRefs(
    anchorGroupId: String,
    anchorIdx: Int,
    anchorName: String,
    mateNames: List<String>,
    hitOf: (String) -> List<SearchHit>,
    resolveOf: (String) -> List<SearchHit>,
): List<BundleRef> {
    val names = (listOf(anchorName) + mateNames).distinct().sorted()
    return names.mapNotNull { n ->
        if (n == anchorName) {
            BundleRef(anchorGroupId, anchorIdx, n)
        } else {
            // 排除「聚合展示分类」：它们不是条目本体，算重名会把本该唯一的名字误判掉。
            // 判据：**全部**命中都在聚合分类（如 shipping）→ 视同唯一、可进包内；
            //     只要有 ≥1 条落在真实分类，就看真实分类的候选——1 条才进，多条（重名）不进。
            fun isDisplay(g: String) = g == "shipping"
            val hits = hitOf(n)
            val real = hits.filter { !isDisplay(it.groupId) }
            when {
                real.size == 1 -> BundleRef(real[0].groupId, real[0].index, n)
                // 没有任何真实分类命中 ⇒ 全在聚合展示 → 视同唯一，取其中一条
                real.isEmpty() && hits.isNotEmpty() ->
                    BundleRef(hits[0].groupId, hits[0].index, n)
                // 零命中 → 别名/品质归一回落，回落后唯一才进
                hits.isEmpty() -> {
                    val via = resolveOf(n).filter { !isDisplay(it.groupId) }
                    if (via.size == 1) BundleRef(via[0].groupId, via[0].index, n)
                    else if (via.isEmpty() && resolveOf(n).isNotEmpty()) BundleRef(resolveOf(n)[0].groupId, resolveOf(n)[0].index, n)
                    else null
                }
                // ≥2 条真实分类命中 = 重名 → 不进（让用户自己挑）
                else -> null
            }
        }
    }
}

/**
 * c221：收集包 mate 点击的纯判定 —— 就地翻页 or 跳搜索，抽出来可单测。
 *
 * [buildBundleRefs] 只负责「编哪些进包内」；点下去那一刻决定「这次点击怎么走」：
 * · 已在包内（inBundle != null）：按名字定位到目标页；定位不到 → 走搜索兜底。
 * · 首次（inBundle == null）：冻结名单并在名单里定位；名字不在名单 → 走搜索兜底。
 *
 * 返回：可不就地翻时返回 null（调用方走 resolveItemJump 的别名/重名退化）；
 * 就地翻时返回「应定位到的名字」—— 名单里用原样名 / 归一别名都能命中。
 *
 * 纯函数：注入，JVM 单测不需真实数据。
 */
internal fun bundleTapTarget(
    name: String,
    builtBundle: List<BundleRef>,
    inBundle: List<BundleRef>?,
): String? {
    val list = inBundle ?: builtBundle
    val hit = list.firstOrNull { it.name == name }
    return if (hit != null) name else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodexPage(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    // v2.28：攻略由 MainActivity 作为内容槽传进来。图鉴与攻略现在是**页内平级分段**，
    // 不再是「图鉴 → 攻略」的压栈二级页（那让图鉴同时背上查数据 + 10 本册子导航两件事）。
    guideContent: @Composable () -> Unit,
    // v2.40：本页当前是否可见。抽屉是独立 Dialog 窗口，keep-alive 页签的
    // alpha = 0 管不到它——不门控就会浮在别的页签上面。
    visible: Boolean,
    // v2.72.1：关抽屉时的附带回调。**调用方默认传空实现**——
    // v2.40 曾经把「关抽屉」直接接到「返回锚点」上，于是从进度页「去勾 ›」
    // 进图鉴、点开一条、✕ 关掉，就被弹回进度页，一次只能看一条（用户反馈）。
    // 现在 ✕ / 下滑 / 返回手势只负责关抽屉；「回我来的地方」交给系统返回键
    // （peek 锚点仍保留）。保留这个参数而不是删掉：将来若某个入口确实需要
    // 「关抽屉即返回」，接线方自己传，别再在 MainActivity 里默认接上。
    onPeekDismiss: () -> Unit = {},
    // c200 A2：图鉴数据加载失败时的「重新加载」动作。
    //   原来这个失败态只列错误清单，**没有出口**——用户只能杀进程重开
    //   （GameData.load 开头 `if (ready) return`，失败后也永远不再试）。
    onRetryData: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var segment by remember { mutableIntStateOf(0) }   // 0 = 📕 数据，1 = 📖 攻略
    // v2.29：冷启动落在上次看的分类，而不是永远从钓鱼图鉴开始。
    // coerceAtLeast(0) 是 IndexOutOfBounds 防线——数据包更新后分类可能改名/删除，
    // indexOfFirst 返回 -1 时必须兜回 0（v2.18 已因 groups[groupIdx] 崩过一次）。
    //
    // ⚠ 这个 remember **不带 key**：CodexPage 是 keep-alive 页，切 tab 回来 composition 还在，
    // groupIdx 应当原样保持。若写成 remember(snap.codexLastGroup)，用户勾个鱼/切个存档
    // 导致 snap 变化时，正在看的列表会被硬拉回旧分类。
    val lastIdx = GameData.codexGroupsWithMods.indexOfFirst { it.id == snap.codexLastGroup }.coerceAtLeast(0)
    var groupIdx by remember { mutableIntStateOf(lastIdx) }
    var query by remember { mutableStateOf("") }
    // v2.29：搜索作用域开关。true = 跨全部分类搜（默认），false = 只搜当前分类。
    // 占位符一直写着「物品 / 菜谱 / 成就…」，实际却只搜当前分类——言不由衷。
    var searchAll by remember { mutableStateOf(true) }
    // v2.32：勾选状态筛选泛化到全部 13 个可打勾分类（原 fishFilter，钓鱼只是第一个有勾的分类）。
    // 钓鱼专属的季节/地点/类型/天气/难度 5 段仍只在 isFishing 下出现。
    var checkFilter by remember { mutableIntStateOf(0) } // 0 全部 / 1 未勾 / 2 已勾
    // 钓鱼图鉴更多筛选（v2.11）：季节 / 地点 / 类型 / 天气 / 难度 5 段，0 = 全部
    var fishSeasonFilter by remember { mutableIntStateOf(0) }      // 1 春 / 2 夏 / 3 秋 / 4 冬 / 5 全年
    var fishLocFilter by remember { mutableIntStateOf(0) }         // 1 海洋 / 2 河流 / 3 湖泊 / 4 矿洞 / 5 山林 / 6 蟹笼 / 7 下水道 / 8 沙漠
    var fishTypeFilter by remember { mutableIntStateOf(0) }        // 1 普通 / 2 传说 / 3 二代传说 / 4 蟹笼
    var fishWeatherFilter by remember { mutableIntStateOf(0) }     // 1 任意 / 2 晴天 / 3 雨天
    var fishDifficultyFilter by remember { mutableIntStateOf(0) }   // 1 简单(≤40) / 2 中等(41-60) / 3 困难(≥61)
    // v2.14 钓点：20 个具体钓点（比旧的 8 大类地点细），空串 = 不限
    var fishSpotFilter by remember { mutableStateOf("") }
    var showMoreFilter by remember { mutableStateOf(false) }        // 高级筛选折叠
    var showPanel by remember { mutableStateOf(false) } // 全部分类面板
    var showRecent by remember { mutableStateOf(false) } // v2.29 最近查看折叠态
    var showFollowed by remember { mutableStateOf(true) } // v2.71 特别关心折叠态（c209 提到页级：头区进懒网格后行内 remember 会被懒回收重置）
    var target by remember { mutableStateOf<CodexTarget?>(null) }
    val c = LocalPixel.current
    // c207：锁定提示用的 Toast 上下文（勾选角标 / 锁定开关两处共用）
    val ctx = LocalContext.current
    // ⚠ 必须读运行时视图（含已启用 Mod 包的作物），不能读静态 codexGroups：
    //   否则列表里点开一个 SVE 作物时，`(groupId, idx)` 在静态组里指向别的条目——
    //   表现是「点古代蕨打开的是古代纤维」，比不显示更难排查。
    val groups = GameData.codexGroupsWithMods
    // v2.18 闪退修复：任何一份图鉴数据损坏都会让 codexGroups 为空，
    // 而下面 groups[groupIdx] 会直接 IndexOutOfBounds 崩掉整个 App。
    // 这里显式空态 + 失败清单，绝不让图鉴页把进程带崩。
    if (groups.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            PageBar("图鉴")
            PixelCard(Modifier.padding(12.dp), accent = c.red) {
                PixelText("⚠ 图鉴数据加载失败", color = c.red)
                Spacer(Modifier.height(6.dp))
                PixelText(
                    "没能加载的数据：" + GameData.loadFailures.joinToString("、"),
                    style = Px.caption, color = c.inkDim,
                )
                Spacer(Modifier.height(4.dp))
                PixelText("其余页签不受影响，数据修复后会自行恢复。", style = Px.caption, color = c.inkDim)
                Spacer(Modifier.height(8.dp))
                PixelButton("🔄 重新加载数据", onClick = onRetryData)
            }
        }
        return
    }
    val fishTable = GameData.fishSpots
    // v2.29 跨分类搜索索引：~823 条，必须 remember 一次（v2.17 E3 教训：
    // 在重组里全量扫必卡）。CodexPage 已被 keep-alive，索引随页面活到退出，只建一次。
    // v2.31：改走共享单例，保证与「跳到面板」「正文词典」是同一份数据。
    val searchIndex = rememberCodexIndex()
    val groupsSearchable = searchIndex.map { it.groupId }.distinct().size
    // v2.29：当前分类（越界兜底）。搜索框占位符与结果分支都要用，故提到搜索框之前。
    val group = groups[groupIdx.coerceIn(0, groups.lastIndex)]
    // v2.29：是否处于搜索态。「最近查看」行与跨分类结果分支都拿它做条件，
    // 故提到两者之前只声明一次。
    val searching = query.isNotBlank()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // v2.33：条目列表改图标网格后滚动状态是 LazyGridState。它与 listState 并存
    //（跨分类搜索结果仍是 LazyColumn），两者互斥显示，各挂一份 ScrollToTopEffect 互不干扰。
    val gridState = rememberLazyGridState()
    com.stardew.assistant.ui.ScrollToTopEffect(com.stardew.assistant.Screen.Codex, listState)
    com.stardew.assistant.ui.ScrollToTopEffect(com.stardew.assistant.Screen.Codex, gridState)
    androidx.compose.runtime.LaunchedEffect(groupIdx) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }
    // 钓点筛选是钓鱼分类专属：切走再切回来必须清掉，否则会串到别的分类上
    androidx.compose.runtime.LaunchedEffect(groupIdx) {
        if (groups.getOrNull(groupIdx)?.id != "fishing") fishSpotFilter = ""
    }
    // v2.29 写盘**集中在这里**，而不是散在各切换点。
    // groupIdx 有 5 个写入点（面板 chip ×2、codexJump、跨分类搜索结果、初始化），
    // 每处各写一次必然漏新增的路径——与 v2.27「判定口径散落多处必然漂移」同型。
    androidx.compose.runtime.LaunchedEffect(groupIdx) {
        groups.getOrNull(groupIdx)?.id?.takeIf { it.isNotBlank() }
            ?.let { scope.launch { repo.setLastCodexGroup(it) } }
    }
    // v2.29 最近查看：只挂 target（= 打开详情那一刻），**不挂 pager 翻页**。
    // 翻页是探索行为，记每一页会让「最近查看」变成翻页流水账，失去语义。
    androidx.compose.runtime.LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        val nm = groups.firstOrNull { it.id == t.groupId }
            ?.items?.getOrNull(t.idx)?.name
            ?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        scope.launch { repo.recordRecent(t.groupId, nm) }
    }
    // 攻略页「钓鱼地图」/ v2.18 今日页全局搜索：切分类（+ 可选：直接打开某条目详情）
    // codexJump 改 StateFlow 后，订阅即收到最新跳转指令；消费完置 null 防重复触发
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.stardew.assistant.codexJump.collect { req ->
            if (req != null) {
                // v2.28：跳图鉴 = 落在数据段。今日页全局搜索、攻略「钓鱼地图」都会发这个指令，
                // 不切回的话用户点完会停在攻略段上，看着像没跳。
                // v2.31：openGuide 反过来——「跳到某本册子」时指挥官鉴停在**攻略段**，
                // 关掉书页回来的落点才对（groupId 为空串，本就不改分类）。
                segment = if (req.openGuide) 1 else 0
                // v2.31：重名物品名不硬跳（392 个名字跨分类重复），退化成落到该词的
                // 跨分类搜索结果，让用户自己挑那条。此时**不要**动 groupIdx/抽屉。
                if (req.openSearch.isNotBlank()) {
                    query = req.openSearch
                    searchAll = true
                    // CodexPage 是 keep-alive 页，target 是跨切页保留的状态：
                    // 不显式清掉的话，用户跳走前开着抽屉，跳回来会看到
                    // 「搜索结果 + 一个不相干的旧抽屉」盖在上面。
                    target = null
                }
                val i = groups.indexOfFirst { it.id == req.groupId }
                if (i >= 0) {
                    groupIdx = i
                    if (req.openMore) showMoreFilter = true
                    // v2.18 全局搜索：带 targetIndex 时直接弹出该条目详情抽屉
                    req.targetIndex?.let { ti ->
                        val g = groups[i]
                        if (ti in g.items.indices) target = CodexTarget(g.id, ti)
                    }
                }
                com.stardew.assistant.codexJump.value = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("图鉴")
        // v2.28 顶部分段：图鉴与攻略平级。放 PageBar 下方单独一行，不挤 PageBar 右侧槽位
        // （今日页那里已被 🔍/⚙/📤 占满三格）。
        PixelSegmented(
            options = listOf("📕 数据", "📖 攻略"),
            selected = segment,
            onSelect = { segment = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        // 数据段（下面整块保持原缩进：diff 最小，回退时一眼能看出只加了一层分支）
        if (segment == 0) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(c.slot)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // v2.29：输入框与作用域开关并排（不另占一行，钓鱼分类已经有 6 段筛选了）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        PixelText(
                            if (searchAll) "🔍 搜索全部 $groupsSearchable 个分类…" else "🔍 搜索：${group.title}…",
                            // c223：占位符 inkDim→ink —— inkDim/slot 日间最低 3.54（冰雪节），
                            //   ink/slot 16 套 ≥4.5（verify 137.11 钉死）。槽底凹槽语义保留。
                            color = c.ink,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = Px.body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (query.isNotEmpty()) {
                        PixelSearchClear(
                            onClear = { query = "" },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 作用域开关：跨分类 / 仅本分类。c223：金字压槽底只有 3.27（gold/slot），
                //   inkDim 更低（3.54）—— 文字统一墨字，激活态改用金边框表达（金色语义
                //   从字挪到边，与本轮标签 Chip 同口径）；c201 B1 的 6dp 垂直内边距保留。
                Box(
                    Modifier
                        .clickable { searchAll = !searchAll }
                        .then(if (searchAll) Modifier.border(1.dp, c.gold) else Modifier)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    PixelText(
                        if (searchAll) "🌐 全部分类" else "📁 仅本分类",
                        style = Px.caption,
                        color = c.ink,
                    )
                }
            }
        }
        // 分类入口栏：只显示当前分类 + 「全部分类」，点任一都弹面板（替代 25 个 chip 的长横滑）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable { showPanel = true }) {
                // v2.32：可打勾的分类都显示进度「钓鱼图鉴 12/74」，纯参考表只显示总数。
                // 计数 remember 住——每次重组全量扫 42~93 条目是白付的（v2.17 E3 同型）。
                val label = if (isCheckable(group.id)) {
                    val got = remember(group.id, group.items, snap.checks) {
                        groupCheckedCount(group.id, group.items, snap.checks)
                    }
                    "${group.title} $got/${group.items.size}"
                } else "${group.title} ${group.items.size}"
                // c223：夜间 gold 是浅色（F2C069），白字压上去 1.68:1 —— 文字按底亮度切：
                //   浅金底换 woodDark 深字（≈11:1），日间深金底白字保留（4.93+）。
                //   判据与 c186 庆祝条同一单源（GlassLadder.isLightSurface）。
                Chip(
                    label,
                    border = c.gold,
                    content = if (com.stardew.assistant.ui.glass.GlassLadder.isLightSurface(c.gold)) c.woodDark
                    else androidx.compose.ui.graphics.Color.White,
                    fill = c.gold,
                )
            }
            Spacer(Modifier.weight(1f))
            // v2.28：原先这里还有个「📖 攻略」链接，现在由顶部「数据 / 攻略」分段承担，
            // 这一行从 4 样挤成 2 样（当前分类 chip + 全部分类）。
            Box(Modifier.clickable { showPanel = true }.padding(4.dp)) {
                // c223：inkDim 裸压屏幕底最低 1.09（冬日星盛宴）→ 行内米黄纸块
                //（Row 里不能用全宽 ParchmentStrip，用 c221 mates 名字同款行内小块，底换纸）
                Box(
                    Modifier
                        .background(c.parchment)
                        .border(1.dp, c.slotEdge)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    PixelText("▾ 全部分类 ${groups.size}", color = c.inkDim)
                }
            }
        }
        // ── v2.71 特别关心 ──
        // 村民页点 ★ 标记的人，这里集中列出他们的最爱，点物品名直接跳图鉴详情。
        // 与「最近查看」同款纪律：存名字不存下标，数据包改名/删人时反查失败即丢弃，
        // 宁可少显示也不显示错的人。
        // 默认展开：这块就是标★的全部意义（「优先看到TA的最爱」），收起来等于白标。
        val followedLive = remember(snap.followed, groups) {
            GameData.gifts.filter { it.name in snap.followed }
        }
        // ── v2.29 最近查看 ──
        // 反查：存的是名字不是下标（见 PrefsRepo.CodexRecentRef），
        // 这里用 indexOfFirst 按名字找下标；找不到（改名/数据包删除）就丢弃这条，
        // 宁可不显示，也不要点开一条错的。
        val recentLive = remember(snap.codexRecent, groups) {
            snap.codexRecent.mapNotNull { r ->
                val g = groups.firstOrNull { it.id == r.groupId } ?: return@mapNotNull null
                val i = g.items.indexOfFirst { it.name == r.itemName }
                if (i < 0) null else r to i
            }
        }
        // query 非空时隐藏：搜索结果已经是跨分类的，再叠一层最近是噪音
        val isFishing = group.id == "fishing"
        // c209：SVE 提示的两个计算提到**页级**——它们原先在固定区的 Column 里，是页级 remember；
        //   搬进懒网格后若留在 item lambda 内，网格回收/重建 item 时 remember 跟着丢，每次滑动都重算一遍。
        val modFishNote = remember(GameData.modFishesKey) { GameData.modFishNoLegendary }
        val hardestModFish = remember(GameData.modFishesKey) {
            GameData.modFishes.filter { it.difficulty != null && !it.isForage }
                .maxByOrNull { it.difficulty ?: -1 }
        }
        // v2.32：勾选三段（全部/未勾/已勾）泛化到全部可打勾分类；
        // 钓鱼专属的季节/地点/类型/天气/难度 5 段仍只在 isFishing 下出现。
        val checkable = isCheckable(group.id)
        // c209：勾选三段 + 锁定 提为**常驻吸顶行**（用户拍板保留）——
        //   头区（特别关心/最近看过/钓鱼筛选）滚进网格后，这是唯一常驻操作件：
        //   滑到哪里都能切「未勾/已勾」。跨分类搜索时隐藏（三段计数属于当前分类）。
        if (checkable && !(searching && searchAll)) {
            val got = groupCheckedCount(group.id, group.items, snap.checks)
            // c207：勾选三段右边挂「勾选锁定」开关（防查图鉴时手滑误勾）。
            //   锁上后格子右上角的勾选角标只显示、点了不记录；筛选（未勾/已勾）不受影响。
            //   锁定态是全局设备偏好（codexMarkLocked），重启保留，不进备份。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PixelSegmented(
                    options = listOf(
                        "全部 ${group.items.size}",
                        "${if (isFishing) "未钓" else "未勾"} ${group.items.size - got}",
                        "${if (isFishing) "已钓" else "已勾"} $got",
                    ),
                    selected = checkFilter,
                    onSelect = { checkFilter = it },
                    modifier = Modifier.weight(1f),
                )
                val locked = snap.codexMarkLocked
                Box(
                    Modifier
                        // 点可区补 6dp 垂直内边距（续 c201 B1：贴文字行太扁不好点）
                        .clickable {
                            val to = !locked
                            scope.launch { repo.setCodexMarkLocked(to) }
                            android.widget.Toast.makeText(
                                ctx,
                                if (to) "已锁定勾选，点格子角标不再记录" else "已解锁勾选",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Chip(
                        if (locked) "🔒" else "🔓",
                        border = if (locked) c.gold else c.slotEdge,
                        fill = if (locked) c.slot else c.parchment2,
                    )
                }
            }
        }
        // v2.17 E3 + A7：筛选结果按全部筛选键 remember 缓存——此前每次重组（如抽屉翻页引发）
        // 都对 ~130 条目全量重算；季节筛选补「全年」放行（36 条全年鱼此前筛任何季节都消失）。
        // c218：计算抽成 [filterItems]（页级共用），抽屉翻页也用这一份，不再脱筛选语境。
        val items = remember(
            group, query, snap.checks, checkFilter, fishSeasonFilter, fishLocFilter,
            fishTypeFilter, fishWeatherFilter, fishDifficultyFilter, fishSpotFilter, fishTable,
        ) {
            filterItems(
                group, query, checkFilter, snap.checks,
                fishSeasonFilter, fishLocFilter, fishTypeFilter, fishWeatherFilter,
                fishDifficultyFilter, fishSpotFilter, fishTable,
            )
        }
        // ── v2.29 跨分类搜索结果（独立分支，绝不复用上面的 items）──
        // 复用 items 会把钓鱼那 6 组筛选套到全部分类结果上：locToBucket 对非鱼条目
        // 一律归 0 类，钓点筛选还会让没收录钓点的条目凭空消失。
        // 跨分类结果与钓鱼筛选互不相干，这里只认 query + searchAll 两个键。
        val crossHits = remember(searchIndex, query, searchAll) {            if (searching && searchAll) {
                com.stardew.assistant.ui.search.searchGlobal(searchIndex, query)
            } else {
                emptyList()
            }
        }
        if (searching && searchAll) {
            // 顺序由 codexGroups 驱动，不用 groupBy 的命中顺序
            val grouped = remember(crossHits, groups) {
                com.stardew.assistant.ui.search.groupHits(crossHits, groups)
            }
            // c200 A5：被截断的分类可以就地展开。
            // 原来「…还有 N 条」是**死文本**——搜到了却打不开剩下的，用户只能
            // 自己记住分类名、再去那个分类里翻一遍。这里点了就展开该分类的全部命中。
            val expandedHitGroups = remember { androidx.compose.runtime.mutableStateListOf<String>() }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (grouped.isEmpty()) {
                    item {
                        // c219：空态裸压屏幕底在冬季/展览会等配色下只有 1.09~1.33:1；
                        //   套米黄纸底（verify 46.1 钉死 inkDim/parchment ≥4.5）。
                        PixelCard {
                            PixelText("没有匹配「$query」的条目", color = c.inkDim)
                            PixelText(
                                "已搜全部 $groupsSearchable 个分类；点上方「📁 仅本分类」可只搜当前分类",
                                color = c.inkDim,
                            )
                        }
                    }
                }
                grouped.forEach { (g, hits) ->
                    item(key = "g_${g.id}") {
                        // c219：分类标题原来是 gold 裸压屏幕底（秋季 1.16:1）；
                        //   改压米黄纸条，保留金色分组感。
                        ParchmentStrip(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                            PixelText(
                                "📂 ${g.title}（${hits.size}）",
                                color = c.gold,
                            )
                        }
                    }
                    // c200 A5：展开过的分类显示全部命中，其余仍按上限截断
                    val expanded = g.id in expandedHitGroups
                    val shown = if (expanded) hits else hits.take(com.stardew.assistant.ui.search.SEARCH_GROUP_LIMIT)
                    // c191 性能：稳定 key。⚠ 原键 groupId+itemName 在「同组同名」时不唯一——
                    //   加工组蛋黄酱×2，跨分类搜「蛋黄酱」滚到第二条 → key 撞车闪退（与网格
                    //   key 同一批雷，c221 真机实证）。补上整组下标 index：同组同名的两条
                    //   各有各的 index，永不撞车；其他场景键值不变。
                    items(shown.size, key = { "${shown[it].groupId}/${shown[it].itemName}/${shown[it].index}" }) { i ->
                        val hit = shown[i]
                        val hsrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(c.parchment)
                                .pressFeedback(hsrc)
                                .clickable(
                                    interactionSource = hsrc,
                                    indication = null,
                                ) {
                                    // 分类与详情必须同时设：只设 target 的话，
                                    // 关掉抽屉后列表还停在一个不相干的分类上
                                    val gi = groups.indexOfFirst { it.id == g.id }
                                    if (gi >= 0) groupIdx = gi
                                    target = CodexTarget(hit.groupId, hit.index)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FramedIcon(hit.itemName, 26)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                PixelText(hit.itemName)
                                if (hit.summary.isNotBlank()) {
                                    PixelText(hit.summary, style = Px.caption, color = c.inkDim)
                                }
                                if (hit.source.isNotBlank()) {
                                    PixelText(hit.source, style = Px.caption, color = c.green)
                                }
                            }
                        }
                    }
                    if (hits.size > shown.size) {
                        item(key = "more_${g.id}") {
                            // c223：金字裸压屏幕底（gold/screen 秋季 1.16）→ 上米黄纸条
                            ParchmentStrip(Modifier.padding(top = 2.dp)) {
                                PixelText(
                                    "…还有 ${hits.size - shown.size} 条 ▾",
                                    style = Px.caption, color = c.gold,
                                    modifier = Modifier
                                        .clickable { expandedHitGroups.add(g.id) }
                                        .padding(start = 6.dp),
                                )
                            }
                        }
                    } else if (expanded) {
                        // 已展开的给一个「收起」出口（否则用户不知道能再收回去）
                        item(key = "less_${g.id}") {
                            // c223：inkDim 裸压屏幕底最低 1.09（冬日星盛宴）→ 上米黄纸条
                            ParchmentStrip(Modifier.padding(top = 2.dp)) {
                                PixelText(
                                    "收起 ▴",
                                    style = Px.caption, color = c.inkDim,
                                    modifier = Modifier
                                        .clickable { expandedHitGroups.remove(g.id) }
                                        .padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
        // v2.33 批次 6 事 1：条目列表从文字行改成图标网格（图标 + 下方文字标签）。
        // 决策 1：列数用 GridCells.Adaptive 自适应（每列最小 62dp），**不硬编码列数**——
        // 屏宽 411dp 得 6 列、360dp 窄屏得 5 列。写死列数必然在某档屏上出问题，
        // 本项目已有两处 360dp 槽位溢出前科。
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 62.dp),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── c209：头区滚进网格（通栏 item，机制同下方说明卡）──
            //   原来特别关心/最近看过/钓鱼筛选是**固定区**，把网格挤到只剩一条缝；
            //   现在它们随网格一起滚走，条目区占满全屏，滑回顶部筛选又回来。
            // ── v2.71 特别关心 ──
            //   （纪律注释见页级 followedLive 处；此块已滚进网格，折叠态 showFollowed 也是页级）
            if (!searching && followedLive.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_followed") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // c223：金字裸压屏幕底（gold/screen 秋季 1.16）→ 折叠头上米黄纸条
                        ParchmentStrip {
                            PixelText(
                                if (showFollowed) "⭐ 特别关心 ${followedLive.size} 人 ▾" else "⭐ 特别关心 ${followedLive.size} 人 ▸",
                                style = Px.caption,
                                color = c.gold,
                                modifier = Modifier.clickable { showFollowed = !showFollowed },
                            )
                        }
                        if (showFollowed) {
                            followedLive.forEach { v ->
                                PixelCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // v2.72：特别关心的人可能来自 Mod 包，走 VillagerAvatar 才有头像
                                        VillagerAvatar(v.name, 30)
                                        Spacer(Modifier.width(8.dp))
                                        // 点人名跳村民页详情（NavRequest 由 AppRoot 单点消费）
                                        PixelText(
                                            v.name,
                                            modifier = Modifier.clickable {
                                                navBus.value = NavRequest.Villager(v.name)
                                            },
                                        )
                                        Spacer(Modifier.weight(1f))
                                        PixelText("最爱 ${v.loves.size}", style = Px.caption, color = c.inkDim)
                                    }
                                    Spacer(Modifier.padding(2.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        v.loves.forEach { itemName ->
                                            Box(Modifier.clickable { resolveItemJump(itemName) }) {
                                                Chip(itemName, border = c.slotEdge)
                                            }
                                        }
                                    }
                                }
                            }
                            // c223：inkDim 裸压屏幕底最低 1.09 → 上米黄纸条
                            ParchmentStrip {
                                PixelText("点物品跳图鉴详情，点名字看该村民完整档案", style = Px.caption, color = c.inkDim)
                            }
                        }
                    }
                }
            }
            // ── v2.29 最近查看 ──
            //   （纪律注释见页级 recentLive 处；点击行为：分类与详情同设，防关抽屉后落错分类）
            if (!searching && recentLive.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_recent") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // c223：inkDim 裸压屏幕底最低 1.09 → 折叠头上米黄纸条
                        ParchmentStrip {
                            PixelText(
                                if (showRecent) "🕘 最近看过的 ${recentLive.size} 条 ▾" else "🕘 最近看过的 ${recentLive.size} 条 ▸",
                                style = Px.caption,
                                color = c.inkDim,
                                modifier = Modifier.clickable { showRecent = !showRecent },
                            )
                        }
                        if (showRecent) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                recentLive.forEach { (r, i) ->
                                    Box(Modifier.clickable {
                                        // 与批次 2 的搜索结果同一条纪律：分类与详情同设
                                        val gi = groups.indexOfFirst { it.id == r.groupId }
                                        if (gi >= 0) groupIdx = gi
                                        target = CodexTarget(r.groupId, i)
                                    }) {
                                        Chip(r.itemName, border = c.slotEdge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // ── 钓鱼专属筛选：SVE 提示 / 季节 / 高级筛选（地点/钓点/类型/天气/难度）──
            if (isFishing) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_fishfilter") {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // v2.75.1：SVE 扩展鱼的顶部提示。
                        //   「没有传奇鱼王」是**包里自述的**（fish.json 顶层 noLegendary），
                        //   没有这句就不显示 —— 自己断言「确实没有」是编数据。
                        //   「最难的是哪条」**从数据里算**（取难度最高的那条），不是编的。
                        if (modFishNote.isNotBlank()) {
                            // c219：这块原来裸压屏幕底（ink/screen 秋季 2.87、万灵节 1.75），
                            //   套米黄纸底；金色「最难的是哪条」也一起保住（gold/parchment 已过 4.5）。
                            PixelCard {
                                PixelText("🐟 SVE 扩展鱼", style = Px.title, color = c.ink)
                                PixelText(modFishNote, color = c.inkDim)
                                hardestModFish?.let {
                                    PixelText(
                                        "这条包里最难的是「${it.name}」（难度 ${it.difficulty}）。",
                                        color = c.gold,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        PixelSegmented(
                            options = listOf("全部季节", "春", "夏", "秋", "冬", "全年"),
                            selected = fishSeasonFilter,
                            onSelect = { fishSeasonFilter = it },
                        )
                        // c223：inkDim 裸压屏幕底最低 1.09 → 整行上米黄纸条（可点区不变）
                        ParchmentStrip {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showMoreFilter = !showMoreFilter }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PixelText(
                                    if (showMoreFilter) "🎛 收起高级筛选" else "🎛 更多筛选（地点 / 类型 / 天气 / 难度）",
                                    color = c.inkDim,
                                )
                            }
                        }
                        if (showMoreFilter) {
                            PixelSegmented(
                                options = listOf("全部地点", "海洋", "河流", "湖泊", "矿洞", "山林", "蟹笼", "下水道", "沙漠"),
                                selected = fishLocFilter,
                                onSelect = { fishLocFilter = it },
                            )
                            // v2.14 钓点：20 个具体钓点用 chip 铺，比 PixelSegmented 塞 21 项更耐看
                            if (fishTable != null && fishTable.spots.isNotEmpty()) {
                                // c223：inkDim/gold 裸压屏幕底（1.09/1.16）→ 整行上米黄纸条
                                ParchmentStrip {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { fishSpotFilter = "" },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        PixelText(
                                            if (fishSpotFilter.isEmpty()) "🎯 钓点：全部（点一下重置）" else "🎯 钓点筛选中",
                                            color = if (fishSpotFilter.isEmpty()) c.inkDim else c.gold,
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    fishTable.spots.forEach { s ->
                                        Box(
                                            Modifier.clickable {
                                                fishSpotFilter = if (fishSpotFilter == s.name) "" else s.name
                                            }.padding(2.dp),
                                        ) {
                                            if (fishSpotFilter == s.name) {
                                                // c223：夜间浅金底白字 1.68 → 文字按底亮度切（同顶部 Chip 口径）
                                                Chip(s.name, border = c.gold,
                                                    content = if (com.stardew.assistant.ui.glass.GlassLadder.isLightSurface(c.gold)) c.woodDark
                                                    else androidx.compose.ui.graphics.Color.White, fill = c.gold)
                                            } else {
                                                Chip(s.name, border = c.slotEdge)
                                            }
                                        }
                                    }
                                }
                            }
                            PixelSegmented(
                                options = listOf("全部类型", "普通", "传说", "二代传说", "蟹笼"),
                                selected = fishTypeFilter,
                                onSelect = { fishTypeFilter = it },
                            )
                            PixelSegmented(
                                options = listOf("全部天气", "任意", "晴天", "雨天"),
                                selected = fishWeatherFilter,
                                onSelect = { fishWeatherFilter = it },
                            )
                            PixelSegmented(
                                options = listOf("全部难度", "简单 ≤40", "中等 41-60", "困难 ≥61"),
                                selected = fishDifficultyFilter,
                                onSelect = { fishDifficultyFilter = it },
                            )
                        }
                    }
                }
            }   // c209：if (isFishing) 到此为止（沿用 v2.32 注释位）
            // v2.79：小鸡代码分组顶部加一条用法说明卡。
            // 理由：这一组 162 条，编号分两种形态——纯数字（取名可用）与带前缀
            // （必须走作弊菜单）。不先讲清楚，玩家在 162 个格子里翻到一半才发现
            // 自己那批编号根本刷不出来。
            if (group.id == "cheatCodes") {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // ⚠ 计数取 **group.items 全量**，不是筛选后的 items：
                    //   搜索时 items 会变少，卡片却写着「共 3 条」，看着像这一分类只剩 3 条。
                    val namingN = group.items.count {
                        it.rows.firstOrNull { r -> r.first == "代码" }?.second?.all { ch -> ch.isDigit() } == true
                    }
                    PixelCard(accent = c.gold) {
                        PixelText("🎲 小鸡代码怎么用", color = c.gold)
                        Spacer(Modifier.height(4.dp))
                        // c226 瘦身：两条 bullet 与每条详情复制卡里的「取名可用/取名无效」
                        // 逐点重复，删；计数行保留（取名/作弊的分布有信息量）。
                        PixelText("共 ${group.items.size} 条：取名可用 $namingN 条 · 需作弊菜单 ${group.items.size - namingN} 条", color = c.ink)
                    }
                }
            }
            // v2.117：出货图鉴顶部加一条售价说明卡。
            // 理由：182 条里每格只显示一个价格数字，玩家第一个问题就是
            //「这是普通价吗、银品质能卖多少」——不先讲清楚，满屏数字会被误读。
            // ⚠ 说明只写一遍（不逐条重复）：品质倍率三条对 182 条完全相同，
            //   逐条写就是 182 份同样的废话。
            //
            // v2.118：倍率改成**绝对价**后，说明卡跟着改口径——
            //   ① 网格状态行只显示普通价，三档在详情里；
            //   ② 只有 89 条有品质资格（作物/水果/花/蘑菇/蛋/奶/鱼），
            //      矿物宝石/工匠品/加工品在游戏里就一个价，没有银金铱三档。
            if (group.id == "shipping") {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PixelCard(accent = c.gold) {
                        PixelText("💰 售价怎么看", color = c.gold)
                        Spacer(Modifier.height(4.dp))
                        PixelText("共 ${group.items.size} 项 · 格子里是普通品质的基础售价", color = c.ink)
                        Spacer(Modifier.height(3.dp))
                        PixelText("· 点开详情看银/金/铱三档的具体价钱", color = c.green)
                        Spacer(Modifier.height(2.dp))
                        // c226 瘦身：两条并一条（「拿不到手，也出不了货」同义反复）。
                        PixelText("· 矿物/工匠品只有一个价；标「不可出货」的拿不到手", color = c.inkDim)
                    }
                }
            }
            // c191 性能：稳定 key（组内条目名零重名，数据侧已脚本扫描证明）。
            // 筛选/搜索变化重排时，格子按条目身份复用，五个 remember(group.id, item.name, …)
            // 的 Mod 反查不再按位置错位重算。
            // c191 性能 + c221：稳定 key。⚠ 原 key 是纯条目名（「组内条目名零重名，数据侧已脚本
            //   扫描证明」）——那句话在加工组不成立：蛋黄酱×2、奶酪×2、布料×2、油×3、
            //   铜/铁/金/铱锭各×2、精炼石英×3、煤炭×3（processing.json 实测同名）。
            //   c221 让第二条蛋黄酱真的渲染出来后，往下滑 key 撞车 → IllegalArgumentException
            //   闪退（用户真机复现）。key 改成 组id/名字/整组下标 复合：
            //   · 同组同名的两条各有各的下标 → 永不撞车；
            //   · 其他组行为不变（同一名字在同组仍只有一个下标）。
            //   筛选/搜索重排时格子仍按条目身份复用，Mod 反查 remember 不按位置错位。
            items(items.size, key = { i -> "${group.id}/${items[i].name}/$i" }) { i ->
                val item = items[i]
                val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                // v2.32：打勾键从单源取；白名单外返回 null = 这格不出勾选角标
                val checkKey = remember(group.id, item) { codexCheckKey(group.id, item.name) }
                // 状态行：选了钓点时标这条鱼在该钓点有没有数据（v2.14 语义），
                // 否则显示难度/首字段（沿用原列表行尾那点信息，不让网格丢信息）
                val spotting = isFishing && fishSpotFilter.isNotEmpty()
                val spotInfo = if (spotting) fishTable?.of(item.name) else null
                // v2.73.9：Mod 扩展鱼（SVE）不在原版钓点表里，钓点筛选下会落到「未收录」。
                //   「未收录」说的是「App 收录的钓点表里没有这条」——对 Mod 鱼是**误导**
                //   （它不是没被收录，是钓点信息在游戏里）。改成明说以游戏内为准。
                val modFish = remember(group.id, item.name, GameData.modFishesKey) {
                    if (group.id == "fishing") GameData.modFishOf(item.name) else null
                }
                val isModFish = modFish != null
                val statusText: String
                val statusColor: Color
                if (spotting) {
                    statusText = when {
                        isModFish -> "钓点见游戏内"
                        spotInfo == null -> "未收录"
                        spotInfo.spots.contains(fishSpotFilter) -> "✓ 此处"
                        else -> "不在此处"
                    }
                    statusColor = if (!isModFish && spotInfo?.spots?.contains(fishSpotFilter) == true) c.green else c.inkDim
                } else {
                    statusText = when {
                        // v2.75.1：采集物（红藻之类，difficulty = null）不是钓的 → 标出来，
                        //   免得它在钓鱼图鉴里看着像一条能钓的鱼。
                        modFish?.isForage == true -> "采集物"
                        else -> item.rows.firstOrNull { it.first == "难度" }?.second?.let { "🐟$it" }
                            ?: item.rows.firstOrNull()?.second.orEmpty()
                    }
                    statusColor = c.inkDim
                }
                // 决策 2：格子等高（图标 34 + 两行 caption 32 + 内距 10 + 余量）。
                // 网格行高由该行最高的格子决定，不锁高会参差；secretNotes 的条目名
                // 是 "#num 文本" 天然偏长，靠 maxLines=1 + 省略号压住，**不改它的 name 格式**
                //（name 是打勾键，改了旧勾作废，verify 26.12 已钉死）。
                //
                // v2.73.5：Mod 作物的来源标签与包内图标。
                // v2.73.7：菜谱组同样标来源（SVE 的 20 道菜与原版 81 道同组）。
                // ⚠ 只在 crops / recipes 两组按 name 反查：图鉴其它分类的条目名
                //   与作物/菜谱表无关，硬查要么白查要么撞名。缓存键进 remember，
                //   装/删包后来源与图标都得跟着换（少了它 = 换包不换图，同「重启才好」那类）。
                val modCrop = remember(group.id, item.name, GameData.modCropsKey) {
                    if (group.id == "crops") GameData.modCropOf(item.name) else null
                }
                val cropModId = modCrop?.modId
                val cropIconRel = modCrop?.iconPath
                // 料理的来源包（料理没有包内图，只有来源标签）
                val recipeModId = remember(group.id, item.name, GameData.modRecipesKey) {
                    if (group.id == "recipes") GameData.modRecipeOf(item.name)?.modId else null
                }
                // v2.73.8：料理的包内图标（包里有就用包里的）
                val recipeIconRel = remember(group.id, item.name, GameData.modRecipesKey) {
                    if (group.id == "recipes") GameData.modRecipeOf(item.name)?.icon else null
                }
                // v2.73.9：SVE 鱼的来源包与包内图标
                val fishModId = remember(group.id, item.name, GameData.modFishesKey) {
                    if (group.id == "fishing") GameData.modFishOf(item.name)?.modId else null
                }
                val fishIconRel = remember(group.id, item.name, GameData.modFishesKey) {
                    if (group.id == "fishing") GameData.modFishOf(item.name)?.icon else null
                }
                // v2.73.11：Mod 地点的来源包（地点无图，只有来源标签）
                val locModId = remember(group.id, item.name, GameData.modLocationsKey) {
                    if (group.id == "locations") GameData.modLocationOf(item.name)?.modId else null
                }
                // 标签取**包自己的短名**（缺键退 modId 大写），不写死「SVE」
                val modTag = modCrop?.let { GameData.modTagOf(it) }
                    ?: recipeModId?.let { GameData.modTagOf(it) }
                    ?: fishModId?.let { GameData.modTagOf(it) }
                    ?: locModId?.let { GameData.modTagOf(it) }
                Box(
                    Modifier
                        .height(82.dp)
                        .fillMaxWidth()
                        .background(c.parchment)
                        .pressFeedback(isrc)
                        .clickable(interactionSource = isrc, indication = null) {
                            target = CodexTarget(group.id, group.items.indexOf(item))
                        },
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 包里有图就用包里的（ModCropIcon 失败时自己退回 FramedIcon）
                        when {
                            cropModId != null && cropIconRel != null ->
                                ModCropIcon(item.name, cropModId, cropIconRel, 26)
                            recipeModId != null && recipeIconRel != null ->
                                ModItemIcon(item.name, recipeModId, recipeIconRel, 26)
                            fishModId != null && fishIconRel != null ->
                                ModItemIcon(item.name, fishModId, fishIconRel, 26)
                            else -> FramedIcon(item.name, 26)
                        }
                        Spacer(Modifier.height(3.dp))
                        PixelText(
                            item.name,
                            style = Px.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(1.dp))
                        PixelText(
                            statusText,
                            style = Px.caption,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // v2.73.5：Mod 作物角标（取代 v2.73.4 的大分区头）。
                    // ⚠ 放**左上角**而不是右上角：右上角已经被下面的勾选框占着，
                    //   叠上去既挡字又会吃掉那 22dp 的点击区（点角标会误触勾选）。
                    //   配色沿用村民列表的来源 Chip（金字 + 槽底），但去掉描边并压到
                    //   caption 尺寸——角标是标记，不该跟格子里的图标抢视觉重量。
                    if (modTag != null) {
                        // c223：金字压槽底 3.27（gold/slot）→ 墨字；金色语义交给外层金边（若有）
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .background(c.slot)
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                        ) {
                            PixelText(modTag, style = Px.caption, color = c.ink, maxLines = 1)
                        }
                    }
                    // 勾选角标在格子右上角：点它只切换记录、不打开详情
                    //（沿用 v2.32「点方框不进详情」的语义，网格里没有 26dp 见方给方框，故缩小到 14dp）。
                    // 显隐仍走 checkKey 是否为 null，**不由 isFishing 决定**（v2.32 纪律）。
                    // c207：锁定时点角标只提示、不记录——勾选通路原样保留，解锁即恢复。
                    if (checkKey != null) {
                        val caught = checkKey in snap.checks
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(22.dp)
                                .clickable {
                                    if (snap.codexMarkLocked) {
                                        android.widget.Toast.makeText(
                                            ctx, "勾选已锁定", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        // c214 触觉：勾上瞬间震一下（与 PixelCheckbox 同口径：只勾上不取消）
                                        if (!caught) vibrateTap(ctx, 28)
                                        scope.launch { repo.toggleCheck(checkKey) }
                                    }
                                },
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(if (caught) c.green else c.slot)
                                    .slotBevel(inset = 1.dp, width = 1.dp)
                                    .border(1.dp, c.slotEdge),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (caught) PixelText("✔", style = Px.caption, color = Color.White)
                            }
                        }
                    }
                }
            }
            // 决策 6：空态说人话。⚠ span = maxLineSpan 让空态占满整行，
            // 否则会被网格挤进一格里，一行一个字地断开。
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (items.isEmpty()) {
                    // ⚠ v2.100.0 / c183：**LazyVerticalGrid 的 item 里不许直接写两个根级 Text**。
                    //   Grid 把 item 内容当 Box 测量，多个根级组件**叠在同一个位置** ——
                    //   搜索空态的两行字叠成一团花字（群友截图 + 模拟器复现，搜「1」仅本分类）。
                    //   跨分类那处（搜索结果的 LazyColumn）item 里多个根组件是竖排，没事；
                    //   但 Grid 这边每个多行分支都必须包 Column。verify 110 节钉住。
                    // c219：整块套 PixelCard，空态不再裸压屏幕底（inkDim/screen 最低 1.09:1）；
                    //   PixelCard 内容本来就是 Column，也顺带消掉「多根级 Text 叠一起」的风险。
                    PixelCard {
                        when {
                            query.isNotBlank() -> {
                                // v2.29：本分支只在「仅本分类」模式下可达（跨分类有独立分支）
                                Column {
                                    PixelText("「${group.title}」里没有匹配「${query}」的条目", color = c.inkDim)
                                    PixelText("点上方「🌐 全部分类」可搜其余 $groupsSearchable 个分类", color = c.inkDim)
                                }
                            }
                            // v2.32：钓鱼有自己的词汇（未钓/已钓），空态跟着它；其余走通用文案。
                            // 钓鱼那两条原文被 verify 13.17 钉住（意图：空态不能被搜索空态吞掉）
                            checkable && checkFilter == 1 -> PixelText(
                                if (isFishing) "全部鱼都标记钓上了，恭喜！" else "全部条目都标记完成了，恭喜！",
                                color = c.green,
                            )
                            // v2.33：勾选入口从「行尾方框」变成「格子右上角」，文案跟着改，否则用户找不到
                            checkable && checkFilter == 2 -> PixelText(
                                if (isFishing) "还没标记钓获——点格子右上角的方框记录"
                                else "还没标记——点格子右上角的方框记录",
                                color = c.inkDim,
                            )
                            else -> {
                                Column {
                                    PixelText("这个分类暂时没有内容", color = c.inkDim)
                                    PixelText("可能是数据包里还没有这项资料", color = c.inkDim)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.padding(4.dp))
            }
        }
        }   // v2.29：else —— 跨分类搜索结果分支到此结束
        } else {
            // v2.72.2：攻略正文的底部玻璃占位**不在这里加**。
            // 初稿曾包一层 `Box(Modifier.padding(bottom = LocalGlassInset.current))`，
            // 那是**压缩视口**而非预留可滚动空白——卡片被拦腰截断、滑不过去（用户截图实证）。
            // 现在由各册列表自己的 contentPadding.bottom 负责（收在 guideContentPadding）。
            guideContent()
        }
    }

    // 条目详情抽屉：图鉴页本体不销毁，分类条与选中分类原样保留
    // v2.13：详情包 HorizontalPager 左右滑翻条目 + 页码行 + flip 音效
    val t = target
    if (t != null && visible) {
        val pagerGroup = groups.firstOrNull { it.id == t.groupId }
        // c218：抽屉翻页**只在被筛选出来的条目里翻**，不再脱筛选语境。
        //   ⚠ 原来的洞：抽屉直接用 `pagerGroup.items`（整组几百条）——
        //   用户在鱼塘筛「珊瑚」看到 8 条，点开一条，左右滑却滑到整个分类
        //   （127 条）的无关条目，页码也按整组算。
        //   now：抽屉打开时按当前筛选（query / checkFilter / 钓鱼 6 段）算出
        //   这个分类实际显示哪些**整组下标**，pager 只在这几条里走。
        //   - 抽屉的目标分类 == 当前正在看的分类 → 用筛选后的下标列表；
        //   - 否则（跨分类搜索/最近查看/攻略跳过来的其他分类）→ 退回整组
        //     （那些入口本就不带当前筛选语境，翻整组是既定行为，不误伤）。
        //   ⚠ `filtered` 存的是**整组下标**，`CodexItemDetailBody` 仍按整组
        //     `group.items[idx]` 取内容，不会因换列表中位数而点错条目。
        val usePagerFilter = pagerGroup != null && t.groupId == group.id && !(searching && searchAll)
        val filteredIdx = if (usePagerFilter) {
            // c221：改用直接返回整组下标的 filterItemIndices（不再按名反查）——
            //   加工组同名成品（蛋黄酱×2/铜锭×2…）第二条不再被折叠成第一条。
            filterItemIndices(
                pagerGroup!!, query, checkFilter, snap.checks,
                fishSeasonFilter, fishLocFilter, fishTypeFilter, fishWeatherFilter,
                fishDifficultyFilter, fishSpotFilter, fishTable,
            )
        } else {
            pagerGroup?.items?.indices?.toList() ?: emptyList()
        }
        // c219：抽屉里的「一页」统一成 [BundleRef]。
        //   普通模式 = 当前分类（c218 的筛选后列表）；
        //   收集包模式 = 点 mate 名字后冻结的「同一个收集包」条目。
        val normalRefs = filteredIdx.mapNotNull { i ->
            pagerGroup?.items?.getOrNull(i)?.let { BundleRef(t.groupId, i, it.name) }
        }
        var bundleRefs by remember(t) { mutableStateOf<List<BundleRef>?>(null) }
        var bundleNames by remember(t) { mutableStateOf<List<String>?>(null) }
        val refs = bundleRefs ?: normalRefs
        // c221：包内指示挪进页码行，title **必须恒定**（DetailDrawer 用 title 当身份，
        //   中途变会导致抽屉关不掉 —— 阻断已由 DrawerSpec.id 兜底，但 title 仍不该变）。
        //   pager 的 identity / 状态 key 用这个——普通模式与包内模式是两套 pager。
        val modeKey = if (bundleRefs != null) "bundle:${bundleNames.hashCode()}" else "normal:$groupIdx"
        // v2.26：分享按钮跟随 pager 当前页（左右滑到哪一条，发的就是哪一条）
        // c218：detailIdx 是 **pager 槽位**，不是整组下标。
        // c219：切进收集包模式时由 onMateClick 直接设到被点条目。
        val startPos = if (usePagerFilter) {
            filteredIdx.indexOf(t.idx).coerceAtLeast(0)
        } else {
            t.idx.coerceIn(0, (refs.size - 1).coerceAtLeast(0))
        }
        var detailIdx by remember(t) { mutableIntStateOf(startPos) }
        // c219：包内再点另一条 mate 时，把 pager 滚过去（首次进包由 build 后的同一个值负责）。
        var jumpTo by remember(t) { mutableStateOf<Int?>(null) }
        // c224：refs 意外为空时回落到 target 本身（c218 的兜底语义）——否则空 refs 时
        //   分享按钮整个消失（c219 起的语义差）。target 指向的条目也被数据重载删掉时
        //   item 仍为 null，分享不渲染 = 合理降级。
        val curRef = refs.getOrNull(detailIdx) ?: refs.firstOrNull() ?: run {
            val nm = groups.firstOrNull { it.id == t.groupId }?.items?.getOrNull(t.idx)?.name ?: ""
            BundleRef(t.groupId, t.idx, nm)
        }
        val curItem = curRef.let { r -> groups.firstOrNull { it.id == r.groupId }?.items?.getOrNull(r.idx) }
        val curGroupTitle = curRef.let { r -> groups.firstOrNull { it.id == r.groupId }?.title } ?: "图鉴"
        val drawerId = remember(t) { Any() }   // c221：稳定身份，不与 title 绑定
        DetailDrawer(
            visible = true,
            title = pagerGroup?.title ?: "详情",
            id = drawerId,
            // v2.40 的注释写的是「看完了 = 关抽屉 = 回原页」，v2.72.1 已改：
            // ✕ / 下滑 / 返回手势三条路都只**关抽屉**，人留在图鉴里继续看下一条；
            // 回原页由系统返回键/手势负责（锚点仍在，见 MainActivity 的 BackHandler）。
            onDismiss = {
                target = null
                onPeekDismiss()
            },
            right = {
                if (curItem != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.stardew.assistant.ui.share.ItemShareEntry(curItem, curGroupTitle)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (refs.isNotEmpty()) {
                    // c221：pager 用 key(modeKey) 重建 —— 普通模式与包内模式各一套状态，
                    //   不会在切换瞬间读到旧的 currentPage（A 号审查的「目标切换不同步」）。
                    key(modeKey) {
                        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                            // c221：用 detailIdx 当 initialPage —— 进包内模式时 onMateClick 已把
                            //   detailIdx 设成被点条目的槽位，直接落位，不会先从 startPos（普通模式
                            //   的下标，可能越界被 clamp 到最后一页）闪一帧再被 scrollToPage 纠正。
                            initialPage = detailIdx.coerceIn(0, (refs.size - 1).coerceAtLeast(0)),
                            pageCount = { refs.size },
                        )
                        androidx.compose.runtime.LaunchedEffect(pagerState) {
                            androidx.compose.runtime.snapshotFlow { pagerState.settledPage }.collect { detailIdx = it }
                        }
                        // c219：包内点另一条 mate → 滚到目标页（不在包内则不触发）。
                        androidx.compose.runtime.LaunchedEffect(jumpTo) {
                            jumpTo?.let {
                                pagerState.scrollToPage(it.coerceIn(0, (refs.size - 1).coerceAtLeast(0)))
                                jumpTo = null
                            }
                        }
                        val sounds = com.stardew.assistant.ui.rememberSounds()
                        // 翻页音效：settledPage 变化时播 flip（首次落位跳过）
                        androidx.compose.runtime.LaunchedEffect(pagerState) {
                            var first = true
                            androidx.compose.runtime.snapshotFlow { pagerState.settledPage }
                                .distinctUntilChanged()
                                .collect {
                                    if (first) first = false
                                    else sounds.flip()
                                }
                        }
                        // c221：包内模式在页码行标「收集包」。
                        val caption = if (bundleRefs != null) "📦 收集包 · ${refs.size} 件" else "全部"
                        // 页码行：‹ n/N ›（c186 套 PixelCard：inkDim 裸压木壳日间 2.04:1，
                        //   羊皮纸内衬上日/夜都 ≈5:1 —— v2.97.0 注释点名的「组件层套卡片」）
                        // c218：总数用筛选后条数；c219：包内模式用包内可翻条数。
                        PixelCard(Modifier.padding(bottom = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PixelText(
                                    "$caption · ${pagerState.currentPage + 1}/${refs.size}",
                                    color = c.inkDim,
                                )
                                // c221：包内翻页模式的出口 —— 退出把 pager 还原到进来前的按分类/筛选翻页。
                                //   （用户拍板：加「↩ 退出包内翻页」按钮）
                                if (bundleRefs != null) {
                                    Spacer(Modifier.width(10.dp))
                                    Box(
                                        Modifier
                                            .background(c.slot)
                                            .border(1.dp, c.slotEdge)
                                            .clickable {
                                                // c221：退出包内 → 回到进来前的「按分类/筛选翻页」，
                                                //   pager 因 modeKey 变回 normal 重建时用 detailIdx 落位。
                                                bundleRefs = null
                                                bundleNames = null
                                                detailIdx = startPos
                                                jumpTo = null
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) { PixelText("↩ 退出包内翻页", style = Px.caption, color = c.ink) }
                                }
                            }
                        }
                        androidx.compose.foundation.pager.HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val r = refs.getOrNull(page) ?: return@HorizontalPager
                            CodexItemDetailBody(
                                r.groupId,
                                r.idx,
                                snap,
                                Modifier.fillMaxSize(),
                                matesOverride = bundleNames,
                                onMateClick = { name, mates ->
                                    val inB = bundleRefs
                                    val built = if (inB == null) {
                                        buildBundleRefs(
                                            r.groupId, r.idx, r.name, mates,
                                            hitOf = { nameToHits[it].orEmpty() },
                                            resolveOf = { codexFallbackHits(it) { n -> nameToHits[n].orEmpty() } },
                                        )
                                    } else inB
                                    val target = bundleTapTarget(name, built, inB)
                                    if (target == null) {
                                        // 不在包内名单：重名/查不到 → 交 resolveItemJump 的退化链
                                        resolveItemJump(name)
                                    } else {
                                        if (inB == null) {
                                            bundleRefs = built
                                            bundleNames = (listOf(r.name) + mates).distinct().sorted()
                                        }
                                        val pos = built.indexOfFirst { it.name == target }
                                        if (pos >= 0) { detailIdx = pos; jumpTo = pos } else { detailIdx = 0; jumpTo = 0 }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    CodexItemDetailBody(t.groupId, t.idx, snap, Modifier.fillMaxSize())
                }
            }
        }
    }

    // 全部分类面板：四大类分组网格，点分类即选中并收起（替代长横滑）
    DetailDrawer(visible = visible && showPanel, title = "全部分类 ${groups.size}", onDismiss = { showPanel = false }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PANEL_SECTIONS.forEach { sec ->
                val tone = toneColor(sec.tone)
                // v2.97.0：段落标题改用 ink，不再用分组色当文字色。
                //   病因：MAKE 段的 tone 是 c.wood，而这个面板的底就是木色壳
                //   （DetailDrawer 用 c.wood）—— 1.00:1，「🔨 制作装备」四个字直接隐形。
                //   分组色不丢：下面 chip 的边框仍按 tone 上色，一眼能分出四组。
                // c219：换成 ink 只是「看得见」，压木壳仍只有 1.2~4.2:1；
                //   标题落到米黄纸条上，ink/parchment 才是 16 套配色都 ≥4.5:1。
                ParchmentStrip {
                    PixelText(sec.label, color = c.ink)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sec.ids.forEach { id ->
                        val i = groups.indexOfFirst { it.id == id }
                        if (i < 0) return@forEach
                        val g = groups[i]
                        // v2.29：chip 与预览竖排（FlowRow 里每格 = chip 上 + 预览下）
                        Column(
                            Modifier
                                .clickable { groupIdx = i; showPanel = false }
                                .padding(2.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            if (i == groupIdx) {
                                // c223：夜间浅金底白字 1.68 → 文字按底亮度切（同顶部 Chip 口径）
                                Chip(g.title, border = c.gold,
                                    content = if (com.stardew.assistant.ui.glass.GlassLadder.isLightSurface(c.gold)) c.woodDark
                                    else androidx.compose.ui.graphics.Color.White, fill = c.gold)
                            } else {
                                // v2.28：未选中态描边跟分组色，27 个分类第一次有了视觉分组
                                Chip(g.title, border = tone)
                            }
                            Spacer(Modifier.height(1.dp))
                            PanelPreview(g)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                RowSep()
            }
            // v2.18 兜底：白名单外的分类也要能点到，否则新增分类会「凭空消失」
            val listed = PANEL_SECTIONS.flatMap { it.ids }.toSet()
            val extra = groups.filter { it.id !in listed }
            if (extra.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                // c219：同上面段落标题 —— 木壳底上的裸字换到米黄纸条。
                ParchmentStrip {
                    PixelText("🧩 其他", color = c.ink)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    extra.forEach { g ->
                        val i = groups.indexOfFirst { it.id == g.id }
                        Column(
                            Modifier
                                .clickable { groupIdx = i; showPanel = false }
                                .padding(2.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            if (i == groupIdx) {
                                // c223：夜间浅金底白字 1.68 → 文字按底亮度切（同顶部 Chip 口径）
                                Chip(g.title, border = c.gold,
                                    content = if (com.stardew.assistant.ui.glass.GlassLadder.isLightSurface(c.gold)) c.woodDark
                                    else androidx.compose.ui.graphics.Color.White, fill = c.gold)
                            } else {
                                Chip(g.title, border = toneColor(SectionTone.OTHER))
                            }
                            Spacer(Modifier.height(1.dp))
                            PanelPreview(g)
                        }
                    }
                }
            }
            // c219：面板最下方那句说明同样压在木壳上（inkDim/wood 日间 2.04:1）。
            ParchmentStrip {
                PixelText("分类下条目太多划不到头？用上方搜索框直接搜名字。", color = c.inkDim)
            }
        }
    }
}

/** 图鉴条目详情主体 —— 整屏页与底部抽屉共用；整体可滚动 */
@OptIn(ExperimentalLayoutApi::class) // v2.33：详情里「相关」用 FlowRow 排名字标签
@Composable
fun CodexItemDetailBody(
    groupId: String,
    idx: Int,
    snap: PrefsRepo.Snapshot,
    modifier: Modifier = Modifier,
    // c219：抽屉进入「收集包语境」后，每页都用同一份同包名单（见 BundleRef），
    //   保证左右滑到同包任一页时，底部「同一个收集包里还有」还是同一组人。
    matesOverride: List<String>? = null,
    // c219：抽屉里点 mate 名字的出口。null = 旧行为（跳到新抽屉/搜索结果）。
    onMateClick: ((String, List<String>) -> Unit)? = null,
) {
    val group = GameData.codexGroupsWithMods.firstOrNull { it.id == groupId } ?: return
    val item = group.items.getOrNull(idx) ?: return
    val c = LocalPixel.current
    val usages = remember(item.name) { GameData.usagesOf(item.name).filter { it.first != "📍 获取途径" } }
    // v2.99.0 / c180：缺件溯源（第二层）——「我要 X，去哪拿」。
    //   · clues：逐条「去哪拿」线索（由既有数据源派生，见 GameData.sourceCluesOf）
    //   · dupGroups：图鉴里的同名条目（重名时明说 N 条并给搜索页入口，不挑一个）
    //   · canon：规范名（别名归一后的名字）
    //   ⚠「📍 获取途径」那一行已从上面的 usages 里滤掉——它在下面「🔍 去哪拿」卡里逐条展开，
    //     两边都放就是同一句话说两遍。进度页仍走 usagesOf 那一行，不受影响。
    val clues = remember(item.name) { GameData.sourceCluesOf(item.name) }
    val dupGroups = remember(item.name) { GameData.codexDupGroups(item.name) }
    val canon = remember(item.name) { GameData.canonicalName(item.name) }
    // v2.33 批次 6 事 3：抽屉里的「相关」= 同一个收集包里的其他物品（玩家真需求：这包还缺啥）。
    // 关联关系**现算**，不建第二份静态关联表——那会是 v2.17「钓点数写死」的复刻。
    // v2.100.0 / c182：反查**两侧都过别名归一**（与 GameData.usagesOf 同一口径）——
    //   收集包里写的是别名侧（「罂粟」「动物毛」…），规范名条目原先匹配不到任何包
    //   ⇒「相关」整块空着（看着像这个条目没有关联）。
    // c219：抽屉进入收集包语境后，名单由抽屉统一注入（matesOverride），
    //   翻到同包任何一页都还是同一组名单；整屏详情/计算页仍按当前条目现算。
    val mates = remember(item.name, matesOverride) {
        val canon = GameData.canonicalName(item.name)
        val names = matesOverride ?: GameData.bundleRooms
            .flatMap { room -> room.bundles.filter { b -> b.items.any { GameData.canonicalName(it.name) == canon } } }
            .flatMap { it.items.map { x -> x.name } }
        names
            .distinct()
            // 排除「同一件东西的另一个叫法」（罂粟 ≡ 虞美人）—— 那是它自己，不是「相关」
            .filter { it != item.name && GameData.canonicalName(it) != canon }
            .sorted()
    }
    // v2.42：成品配方（群友 `:෴天劫:` 要「怎么制作」）。与 usages / mates 同一范式——**现算**。
    val recipe = remember(item.name) { GameData.recipeOf(item.name) }
    // v2.73.5：Mod 作物在详情里也用包内图 + 来源角标，与列表格保持同一眼图。
    // v2.73.7：菜谱组同理（料理无包内图，只有来源角标）。
    // 判据与格子那处同款（只在 crops / recipes 组按名反查），缓存键进键防换包不换图。
    val modCrop = remember(groupId, item.name, GameData.modCropsKey) {
        if (groupId == "crops") GameData.crops.firstOrNull { it.name == item.name } else null
    }
    val cropModId = modCrop?.modId
    val cropIconRel = modCrop?.iconPath
    val recipeModId = remember(groupId, item.name, GameData.modRecipesKey) {
        if (groupId == "recipes") GameData.modRecipeOf(item.name)?.modId else null
    }
    val recipeIconRel = remember(groupId, item.name, GameData.modRecipesKey) {
        if (groupId == "recipes") GameData.modRecipeOf(item.name)?.icon else null
    }
    // v2.73.9：SVE 鱼在详情里也用包内图 + 来源角标
    val fishModId = remember(groupId, item.name, GameData.modFishesKey) {
        if (groupId == "fishing") GameData.modFishOf(item.name)?.modId else null
    }
    val fishIconRel = remember(groupId, item.name, GameData.modFishesKey) {
        if (groupId == "fishing") GameData.modFishOf(item.name)?.icon else null
    }
    val modTag = modCrop?.let { GameData.modTagOf(it) }
        ?: recipeModId?.let { GameData.modTagOf(it) }
        ?: fishModId?.let { GameData.modTagOf(it) }
    // v2.73.11：Mod 地点 → 关联的 NPC（子串精确匹配包内 NPC 名单，命中不了就不关联）
    val modLocation = remember(groupId, item.name, GameData.modLocationsKey) {
        if (groupId == "locations") GameData.modLocationOf(item.name) else null
    }
    val locationNpcs = remember(modLocation) {
        modLocation?.let { GameData.locationsOf(it) } ?: emptyList()
    }
    // v2.99.0 / c179：「你的状态」数据准备。
    // · 村民判定：条目名按 gifts 数据命中（movies 组条目名 = 村民名；好感与生日都从这取）。
    //   v2.100.0 / c182：判定源改成 **礼物表 ∪ 存档侧译名表**（GameData.isVillagerName）——
    //   原来只认礼物表 ⇒ 名字对不上时（gifts 写「里奥」、图鉴写「雷欧」）
    //   isVillager 判成 false ⇒ **整张「🎒 你的状态」卡不渲染**（存档里有 TA 的好感也没用）。
    //   数据侧已统一（c182），但判定仍以两张表的并集为准（礼物表缺的人照样是村民）。
    // · 状态读缓存：未导入存档 = null = 整段不出现（绝不显示「0」骗人）。
    //   读缓存在 IO 线程（今日页同款），换条目（item.name 作 key）重读。
    val dctx = androidx.compose.ui.platform.LocalContext.current
    // ⚠ 缓存键带 modVillagerCount：装/删 Mod 包会改 gifts（多一位扩展包村民），
    //   不进键的话 remember 会抱着旧值（与 v2.73.7「crops 键要带 modCropsKey」同款纪律）。
    val giftV = remember(item.name, GameData.modVillagerCount) {
        GameData.gifts.firstOrNull { it.name == item.name }
    }
    val isVillager = remember(item.name, GameData.modVillagerCount) {
        GameData.isVillagerName(item.name)
    }
    // ⚠ c217：key 里加 **snap.activeProfile** —— 缓存按存档分文件了，
    //   切存档必须重读（否则图鉴详情里的「🎒 你的状态」显示的是上一个存档的
    //   背包 / 好感 / 田里状态）。
    val codexState = produceState<CodexState?>(null, item.name, snap.activeProfile) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CodexStateCache.read(dctx, snap.activeProfile)
        }
    }
    // v2.99.0 / c179：「你的状态」行组装（在 LazyColumn **之前**算好 —— LazyListScope
    // 里不能调 @Composable 的 remember，item{} 里只做渲染）。
    // ⚠ 没导入存档（st == null）或这条没有可显示的状态（行列表空）时整卡不出现。
    val st = codexState.value
    val status = remember(item.name, st, snap.checks, snap.year, snap.season, snap.day) {
        if (st == null) CodexStatusResult(emptyList())
        else codexStatusLines(
            CodexStatusInput(
                groupId = groupId,
                itemName = item.name,
                state = st,
                checks = snap.checks,
                snapYear = snap.year, snapSeason = snap.season, snapDay = snap.day,
                seedPrice = modCrop?.seedPrice,
                isVillager = isVillager,
                birthdaySeason = giftV?.birthdaySeason,
                birthdayDay = giftV?.birthdayDay,
                ingredients = recipe?.ingredients ?: emptyList(),
                rooms = GameData.bundleRooms,
            )
        )
    }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PixelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        cropModId != null && cropIconRel != null ->
                            ModCropIcon(item.name, cropModId, cropIconRel, 48)
                        recipeModId != null && recipeIconRel != null ->
                            ModItemIcon(item.name, recipeModId, recipeIconRel, 48)
                        fishModId != null && fishIconRel != null ->
                            ModItemIcon(item.name, fishModId, fishIconRel, 48)
                        else -> FramedIcon(item.name, 48)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        // 来源角标与村民详情同款（名字右侧挂金色 Chip），列表格那处用小角标是因为格子太窄
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelText(item.name)
                            if (modTag != null) {
                                Spacer(Modifier.width(7.dp))
                                Chip(modTag, border = c.gold)
                            }
                        }
                    }
                    ReportButton("图鉴·${group.title}", item.name, item.rows.joinToString(" ") { "${it.first}=${it.second}" }.ifBlank { item.name })
                }
            }
        }
        // c221：把「📦 同一个收集包里还有」从详情底部挪到首屏 —— 它是这个条目最相关的一卡
        //   （玩家真需求：这包还缺啥），沉在属性长表后面还要翻好几屏。放唯一一个能跨条目
        //   定位的位置：紧跟标题卡。名字唯一可点；重名/查不到走 resolveItemJump 退化。
        if (mates.isNotEmpty()) {
            item {
                PixelCard(title = "📦 同一个收集包里还有") {
                    PixelText(
                        if (onMateClick != null)
                            "点名字就地换到那一条，左右滑就在这个包里翻；重名的会列成搜索结果让你挑"
                        else
                            "点名字跳过去看；重名的会列成搜索结果让你挑",
                        style = Px.caption, color = c.inkDim,
                    )
                    Spacer(Modifier.height(5.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        mates.forEach { mn ->
                            val msrc = remember(mn) {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            }
                            PixelText(
                                mn,
                                color = c.gold,
                                modifier = Modifier
                                    .border(1.dp, c.slotEdge)
                                    .background(c.slot)
                                    .pressFeedback(msrc)
                                    .clickable(interactionSource = msrc, indication = null) {
                                        // c219：抽屉里由调用方接管——就地换内容、留在同包语境；
                                        // 其余入口（计算页）没有回调，保持原来的跳转行为。
                                        if (onMateClick != null) onMateClick(mn, mates)
                                        else resolveItemJump(mn)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        // v2.73.11：Mod 地点 → 相关 NPC（可点跳村民页）。
        // ⚠ 「相关」是**子串精确匹配**包内 NPC 名单得到的，命中不了就不显示这一卡——
        //   从「索菲娅家，酿酒/售酒」这种描述文字里**猜**谁相关是编造。
        if (locationNpcs.isNotEmpty()) {
            item(key = "locNpcs") {
                PixelCard(title = "👥 相关 NPC") {
                    locationNpcs.forEach { n ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { navBus.value = NavRequest.Villager(n) }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Mod NPC 走 VillagerAvatar 才有头像
                            VillagerAvatar(n, 26)
                            Spacer(Modifier.width(8.dp))
                            PixelText(n)
                        }
                    }
                }
            }
        }
        // v2.73.11：SVE 给原版节日加的新剧情（包级说明，整包一份）
        if (groupId == "locations" && GameData.modFestivals.isNotBlank()) {
            item(key = "modFestivals") {
                PixelCard(title = "🎉 节日") {
                    PixelText(GameData.modFestivals)
                }
            }
        }
        // v2.33 批次 6 事 2：抽屉分层。属性长表是详情里最长、最次要的一段（用途速查已覆盖
        // 「哪来的 / 给谁用」），默认收起，让首屏先回答「这是什么、哪来的、有什么用」。
        // ⚠ 只有行数够多才折：短表折叠反而多一次点击，那几行直接平铺。
        //
        // v2.41：阈值 3 → 5。**群友报障「建造需要什么材料」的真凶就在这一行**：
        // buildings 19 条的 rows 恰好都是 4 行（价格 / 材料 / 容量·效果 / 工期），
        // 卡在 3 与 4 之间被整整齐齐地折了起来——点开「鸡舍」首屏只有一行
        // 「📋 详细属性（价格、材料、容量 / 效果、工期…）」，**一个数都看不到**，
        // 想看材料要点两下。数据一直都在，是折叠把它藏了（典型的「功能在、够不着」）。
        //
        // 为什么放宽到 5 而不是给 buildings 开特例：特例判定一旦散开就是 v2.27
        // 「判定口径散落多处必然漂移」。5 行是「平铺后仍一眼扫得完」的分界——
        // 再长才真的压过首屏，折叠才有意义。
        //
        // ⚠ 折叠态按 item.name 记忆：换条目自动收起，否则看完一条的展开状态会带到下一条。
        if (item.rows.isNotEmpty()) {
            item {
                var expanded by remember(item.name) { mutableStateOf(false) }
                if (item.rows.size <= 5) {
                    PixelCard { item.rows.forEach { (k, v) -> KVRow(k, v) } }
                } else {
                    val keys = remember(item.name) { item.rows.joinToString("、") { it.first } }
                    PixelCard {
                        val esrc = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressFeedback(esrc)
                                .clickable(interactionSource = esrc, indication = null) {
                                    expanded = !expanded
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PixelText(
                                if (expanded) "📋 详细属性（点此收起）" else "📋 详细属性（$keys…）",
                                color = c.gold,
                                modifier = Modifier.weight(1f),
                            )
                            PixelText(if (expanded) "▲" else "▼", color = c.gold)
                        }
                        if (expanded) {
                            Spacer(Modifier.height(6.dp))
                            item.rows.forEach { (k, v) -> KVRow(k, v) }
                        }
                    }
                }
            }
        }
        // v2.99.0 / c179：「你的状态」—— 百科 × 存档第 1 层。
        // 位置：属性行之后、正文段落卡（item.text）之前；独立段落卡，不挤进 rows
        // （rows 超 5 行会折叠，v2.41 的教训）。正文行走 KVRow 默认色（c.ink 压羊皮纸
        // 内衬），不用 c.wood —— 那是 v2.97 踩过的隐形坑。
        if (status.lines.isNotEmpty()) {
            item(key = "myStatus") {
                PixelCard(title = "🎒 你的状态") {
                    status.lines.forEach { (k, v) -> KVRow(k, v) }
                    status.footnote?.let {
                        Spacer(Modifier.height(6.dp))
                        PixelText(it, style = Px.caption, color = c.inkDim)
                    }
                }
            }
        }
        // v2.42：怎么制作（群友 `:෴天劫:` ——「图鉴条目想一键跳怎么制作」）。
        //
        // ⚠ 做成**详情内现算展示**，不跳去「菜谱 / 制作」分类：跳过去要处理重名、要依赖
        //   返回锚点，而答案就「材料 + 出处」两行，直接摆在条目自己这一屏里最短。
        //   「菜谱」「制作」本来也是图鉴里的两个分类（GameData.buildCodexGroups 注册），
        //   但玩家记不住某个东西归哪一档，翻分类比看一屏卡片慢得多。
        // ⚠ 排除 recipes / crafting 分类**自身**：在「菜谱」里点开煎鸡蛋，它的 rows 早已有
        //   「材料 / 获取」两行（rowsGroup 的 ingredientKey 拼的），再摆一张是同屏重复。
        // ⚠ 查不到就整块不显示：174 条配方覆盖不到全部图鉴条目，不显示好过摆半张卡。
        //   两表材料都最多 4 个，一行放得下，所以不折叠。
        if (recipe != null && groupId != "recipes" && groupId != "crafting") {
            item {
                PixelCard(
                    title = "🔧 怎么制作" + if (recipe.category.isNotBlank()) " · ${recipe.category}" else "",
                    accent = c.gold,
                ) {
                    if (recipe.ingredients.isNotEmpty()) {
                        KVRow("材料", recipe.ingredients.joinToString("、") { "${it.first} ×${it.second}" })
                    }
                    if (recipe.source.isNotBlank()) KVRow("获取", recipe.source)
                }
            }
        }
        // v2.18 小鸡代码：一键复制 spawn code（图鉴详情原本没有复制能力，专为该分类补上）
        // v2.79：按**编号形态**给不同用法。纯数字是物品 object id，取名就能刷出；
        //   带 (W)/(B)/(BC) 前缀的取名无效，只能走 SMAPI/CJB 作弊菜单或聊天调试命令。
        //   卡片文案跟着编号走，玩家不用猜哪个能刷哪个不能。
        if (groupId == "cheatCodes") {
            item {
                val code = item.rows.firstOrNull { it.first == "代码" }?.second
                    ?: item.text.takeIf { it.isNotBlank() } ?: item.name
                val naming = code.all { it.isDigit() }
                val dctx = androidx.compose.ui.platform.LocalContext.current
                PixelCard(title = "📋 复制代码", accent = c.gold) {
                    PixelText(code, style = Px.big, color = c.ink)
                    Spacer(Modifier.height(6.dp))
                    PixelButton(
                        "复制 ${code}",
                        onClick = {
                            com.stardew.assistant.ui.copyToClipboard(dctx, "cheat_code", code)
                        },
                        active = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        if (naming) "取名可用：给动物取名时输入这个数字，就会出这个物品"
                        else "取名无效：带前缀的编号要用 SMAPI / CJB 作弊菜单或聊天命令刷",
                        style = Px.caption,
                        color = if (naming) c.green else c.red,
                    )
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        "作弊模式（GM 模式）：输入 /player_\"物品名\" \"数量\" \"代码\"（1.6 内置，无需 Mod）",
                        style = Px.caption, color = c.inkDim,
                    )
                    if (item.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        PixelText("⚠ ${item.note}", style = Px.caption, color = c.red)
                    }
                }
            }
        }
        // 钓鱼条目专属：钓不上来时的排查清单（数据全部来自 fishing.json 已有字段）
        if (groupId == "fishing") {
            item {
                val fish = GameData.fishing.firstOrNull { it.name == item.name }
                if (fish != null) {
                    PixelCard(title = "🎣 钓不上来时排查") {
                        PixelText("· 地点对不对：${fish.location.ifBlank { "见地点行" }}", color = c.ink)
                        Spacer(Modifier.height(4.dp))
                        PixelText("· 季节对不对：${fish.seasons.joinToString("、")}（可用计算页查当天日期）", color = c.ink)
                        Spacer(Modifier.height(4.dp))
                        PixelText("· 天气对不对：${fish.weather}（雨天要等下雨、晴天要避开雨天）", color = c.ink)
                        Spacer(Modifier.height(4.dp))
                        PixelText("· 时段对不对：${fish.time}", color = c.ink)
                        if (fish.type.contains("传说") || fish.difficulty != null && fish.difficulty >= 70) {
                            Spacer(Modifier.height(6.dp))
                            PixelText("⚠ 高难度条目，建议先用食物/装备提升钓鱼等级再试。", color = c.red)
                        }
                    }
                }
            }
            // v2.14 钓鱼地图：钓点 / 经验 / 四档售价
            GameData.fishSpots?.of(item.name)?.let { info ->
                item {
                    PixelCard(title = "🗺 钓点（钓鱼地图）", accent = c.blue) {
                        when {
                            info.crabPot -> {
                                PixelText("蟹笼获取，不是钓竿钓的", color = c.gold)
                                if (info.crabNote.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    PixelText(info.crabNote, style = com.stardew.assistant.ui.Px.caption, color = c.inkDim)
                                }
                            }
                            info.spots.isEmpty() -> {
                                PixelText("钓点数据未收录（数据包缺这一条）", color = c.inkDim)
                            }
                            else -> {
                                info.spots.forEach { KVRow("· $it", "") }
                            }
                        }
                        if (info.xp != null) {
                            Spacer(Modifier.height(6.dp))
                            KVRow("钓鱼经验", "${info.xp}")
                        }
                        if (info.timeText.isNotBlank()) KVRow("可钓时段", info.timeText)
                        if (info.priceText.isNotBlank()) KVRow("四档售价", info.priceText)
                        info.legendary?.let {
                            Spacer(Modifier.height(6.dp))
                            PixelText("🏆 $it", color = c.gold)
                        }
                    }
                }
            }
        }
        if (usages.isNotEmpty()) {
            item {
                PixelCard(title = "🧭 用途速查") {
                    usages.forEach { (k, v) -> KVRow(k, v) }
                }
            }
        }
        // v2.99.0 / c180：缺件溯源 ——「我要 X，去哪拿」。
        //   线索全部由既有数据源派生（GameData.sourceCluesOf），**不新建第二份来源表**。
        //   ⚠「📍 获取途径」那一行已从上面的「用途速查」里挪到这里（那边只留摘要行会重复），
        //     进度页的收集簿仍走 usagesOf 那一行，不受影响。
        //   ⚠ 重名不许挑一个：同名条目 ≥2 时**明说「有 N 个同名条目」**并给搜索页入口，
        //     入口复用 resolveItemJump 的重名退化行为（**不新建第二套跳转逻辑**）。
        if (clues.isNotEmpty()) {
            item {
                PixelCard(title = "🔍 去哪拿") {
                    clues.forEach { cl ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            PixelText(cl.kind, color = c.gold, style = Px.caption)
                            Spacer(Modifier.width(6.dp))
                            PixelText(cl.text, modifier = Modifier.weight(1f))
                        }
                    }
                    if (dupGroups.size >= 2) {
                        Spacer(Modifier.height(6.dp))
                        PixelText(
                            "图鉴里有 ${dupGroups.size} 个同名条目：${dupGroups.joinToString("、") { it.second }}",
                            style = Px.caption, color = c.inkDim,
                        )
                        Spacer(Modifier.height(4.dp))
                        val dsrc = remember(dupGroups) {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        }
                        PixelText(
                            // c223：金字压槽底 3.27（gold/slot）→ 去槽底直接压卡底（parchment），
                            //   gold/parchment 16 套 ≥4.6（verify 46.5 钉死），金字/动作感保留
                            "挑一个 ›",
                            color = c.gold,
                            modifier = Modifier
                                .border(1.dp, c.slotEdge)
                                .pressFeedback(dsrc)
                                .clickable(interactionSource = dsrc, indication = null) {
                                    resolveItemJump(canon)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        // v2.33 批次 6 事 3：抽屉里的「相关」。
        // c219 起分两种入口，**提示必须跟着行为走**（c220 修的正是这句）：
        //   · 图鉴抽屉：名字唯一 ⇒ **就地换页**，之后左右滑就在这个包里翻；
        //   · 计算页/整屏详情：仍是 resolveItemJump **跳过去**（名字唯一才直跳）。
        // 两边共同的退化：重名（实测 392 个名字跨分类重复）不替用户猜，列搜索结果让他自己挑。
        // c221：整卡已上移到首屏（紧跟标题卡），此处不再重复渲染。
        if (item.text.isNotBlank()) {
            item {
                PixelCard {
                    PixelText(item.text)
                }
            }
        }
        item {
            // c219：抽屉的底是木壳，这行 inkDim 裸压木壳日间只有 2.04:1、
            //   冬季 1.06、展览会 1.03 —— 与 c186 的 PanelPreview 同一个病因，同一张米黄纸底。
            ParchmentStrip {
                PixelText("数据有误？点右上「📋 报错」一键复制模板，粘贴发到社区群里即可。", color = c.inkDim)
            }
        }
    }
}
